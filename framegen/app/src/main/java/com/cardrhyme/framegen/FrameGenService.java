package com.cardrhyme.framegen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.Toast;

public final class FrameGenService extends Service implements OverlayController.Listener {
    static final String ACTION_START = "com.cardrhyme.framegen.START";
    static final String ACTION_STOP = "com.cardrhyme.framegen.STOP";
    static final String ACTION_TOGGLE = "com.cardrhyme.framegen.TOGGLE";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";
    static final String EXTRA_INPUT_FPS = "input_fps";

    private static final int NOTIFICATION_ID = 4102;
    private static final String CHANNEL_ID = "framelift_running";
    private static final long RESIZE_DEBOUNCE_MS = 260L;
    private static final long PRODUCER_RESIZE_DELAY_MS = 70L;
    private static final long SURFACE_START_DELAY_MS = 150L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private OverlayController overlay;
    private GpuFrameGenerator renderer;
    private Surface captureSurface;
    private Surface outputSurface;

    private int displayWidth;
    private int displayHeight;
    private int captureWidth;
    private int captureHeight;
    private int pendingCaptureWidth;
    private int pendingCaptureHeight;
    private int densityDpi;
    private int inputFps = 60;
    private int surfaceEpoch;
    private int rendererEpoch;
    private int eglRetryCount;

    private boolean paused;
    private boolean outputReady;
    private boolean shuttingDown;

    private final Runnable applyPendingResize = () -> {
        if (shuttingDown) return;
        int newWidth = pendingCaptureWidth;
        int newHeight = pendingCaptureHeight;
        if (newWidth <= 0 || newHeight <= 0) return;
        if (newWidth == captureWidth && newHeight == captureHeight) return;

        captureWidth = newWidth;
        captureHeight = newHeight;
        GpuFrameGenerator activeRenderer = renderer;
        if (activeRenderer != null) {
            activeRenderer.resizeCaptureBuffer(captureWidth, captureHeight);
        }

        mainHandler.postDelayed(() -> {
            if (shuttingDown) return;
            if (newWidth != captureWidth || newHeight != captureHeight) return;
            if (virtualDisplay != null) {
                virtualDisplay.resize(captureWidth, captureHeight, densityDpi);
            }
        }, PRODUCER_RESIZE_DELAY_MS);
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    @SuppressWarnings("deprecation")
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(action)) {
            onPauseToggle();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;

        startForeground(NOTIFICATION_ID, buildNotification());
        if (mediaProjection != null) return START_NOT_STICKY;

        FrameLiftAccessibilityService accessibility =
                FrameLiftAccessibilityService.getInstance();
        if (accessibility == null) {
            Toast.makeText(
                    this,
                    "Enable FrameLift's accessibility overlay first.",
                    Toast.LENGTH_LONG
            ).show();
            shutdown();
            return START_NOT_STICKY;
        }

        inputFps = Math.max(1, Math.min(120, intent.getIntExtra(EXTRA_INPUT_FPS, 60)));
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            resultData = (Intent) intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        if (resultData == null) {
            shutdown();
            return START_NOT_STICKY;
        }

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = manager.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) {
            shutdown();
            return START_NOT_STICKY;
        }

        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                mainHandler.post(FrameGenService.this::shutdown);
            }

            @Override
            public void onCapturedContentResize(int newWidth, int newHeight) {
                mainHandler.post(() -> scheduleCaptureResize(newWidth, newHeight));
            }
        }, mainHandler);

        readPhysicalDisplayBounds(accessibility);
        captureWidth = displayWidth;
        captureHeight = displayHeight;
        pendingCaptureWidth = displayWidth;
        pendingCaptureHeight = displayHeight;

        overlay = new OverlayController(accessibility, inputFps, this);
        overlay.attach(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                outputSurface = holder.getSurface();
                scheduleRendererStart(SURFACE_START_DELAY_MS);
            }

            @Override
            public void surfaceChanged(
                    SurfaceHolder holder,
                    int format,
                    int newWidth,
                    int newHeight
            ) {
                outputSurface = holder.getSurface();
                scheduleRendererStart(SURFACE_START_DELAY_MS);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceEpoch++;
                outputSurface = null;
                outputReady = false;
                GpuFrameGenerator oldRenderer = renderer;
                renderer = null;
                rendererEpoch++;
                if (oldRenderer != null) {
                    oldRenderer.stopAndWait(700L);
                }
            }
        }, 0, 0, displayWidth, displayHeight);

        return START_NOT_STICKY;
    }

    private void readPhysicalDisplayBounds(FrameLiftAccessibilityService accessibility) {
        WindowManager windowManager = accessibility.getSystemService(WindowManager.class);
        WindowMetrics metrics = windowManager.getMaximumWindowMetrics();
        Rect bounds = metrics.getBounds();
        displayWidth = Math.max(1, bounds.width());
        displayHeight = Math.max(1, bounds.height());
        densityDpi = accessibility.getResources().getConfiguration().densityDpi;
    }

    private void scheduleCaptureResize(int newWidth, int newHeight) {
        if (newWidth <= 0 || newHeight <= 0 || shuttingDown) return;
        pendingCaptureWidth = newWidth;
        pendingCaptureHeight = newHeight;
        mainHandler.removeCallbacks(applyPendingResize);
        mainHandler.postDelayed(applyPendingResize, RESIZE_DEBOUNCE_MS);
    }

    private void scheduleRendererStart(long delayMs) {
        final int expectedSurfaceEpoch = surfaceEpoch;
        mainHandler.postDelayed(() -> {
            if (shuttingDown || expectedSurfaceEpoch != surfaceEpoch) return;
            startRendererIfNeeded();
        }, delayMs);
    }

    private void startRendererIfNeeded() {
        if (renderer != null) return;
        if (outputSurface == null || !outputSurface.isValid()) {
            scheduleRendererStart(120L);
            return;
        }

        outputReady = false;
        if (overlay != null) overlay.setOutputVisible(false);

        final int thisRendererEpoch = ++rendererEpoch;
        final GpuFrameGenerator candidate = new GpuFrameGenerator(
                inputFps,
                new GpuFrameGenerator.Callback() {
                    private boolean isCurrent() {
                        return !shuttingDown
                                && renderer == candidate
                                && rendererEpoch == thisRendererEpoch;
                    }

                    @Override
                    public void onCaptureSurfaceReady(Surface newCaptureSurface) {
                        mainHandler.post(() -> {
                            if (!isCurrent()) {
                                newCaptureSurface.release();
                                return;
                            }
                            connectVirtualDisplay(newCaptureSurface);
                        });
                    }

                    @Override
                    public void onFirstOutputFrame() {
                        mainHandler.post(() -> {
                            if (!isCurrent()) return;
                            eglRetryCount = 0;
                            outputReady = true;
                            if (!paused && overlay != null) overlay.setOutputVisible(true);
                        });
                    }

                    @Override
                    public void onStats(
                            float sourceFps,
                            float outputFps,
                            float generatedFps,
                            float captureFps,
                            boolean flowActive
                    ) {
                        mainHandler.post(() -> {
                            if (!isCurrent() || overlay == null) return;
                            overlay.setStats(
                                    sourceFps,
                                    outputFps,
                                    generatedFps,
                                    captureFps,
                                    flowActive
                            );
                        });
                    }

                    @Override
                    public void onFatalError(String message) {
                        mainHandler.post(() -> handleRendererFailure(
                                candidate,
                                thisRendererEpoch,
                                message
                        ));
                    }
                }
        );
        renderer = candidate;
        candidate.start(outputSurface, displayWidth, displayHeight);
        candidate.resizeCaptureBuffer(captureWidth, captureHeight);
    }

    private void handleRendererFailure(
            GpuFrameGenerator failedRenderer,
            int failedEpoch,
            String message
    ) {
        if (shuttingDown) return;
        if (renderer != failedRenderer || rendererEpoch != failedEpoch) return;

        renderer = null;
        rendererEpoch++;
        outputReady = false;
        if (overlay != null) overlay.setOutputVisible(false);
        failedRenderer.stopAndWait(700L);

        boolean transientEglFailure = message.contains("eglCreateWindowSurface")
                || message.contains("eglMakeCurrent")
                || message.contains("eglSwapBuffers");
        if (transientEglFailure && eglRetryCount < 4 && outputSurface != null) {
            eglRetryCount++;
            scheduleRendererStart(260L + eglRetryCount * 180L);
            return;
        }

        paused = true;
        if (overlay != null) {
            overlay.setPaused(true);
            overlay.setError(message);
        }
        updateNotification();
    }

    private void connectVirtualDisplay(Surface newSurface) {
        if (mediaProjection == null) {
            newSurface.release();
            return;
        }

        Surface oldSurface = captureSurface;
        captureSurface = newSurface;

        if (virtualDisplay == null) {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "FrameLiftCapture",
                    captureWidth,
                    captureHeight,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    captureSurface,
                    null,
                    mainHandler
            );
        } else {
            virtualDisplay.setSurface(captureSurface);
            virtualDisplay.resize(captureWidth, captureHeight, densityDpi);
        }

        if (oldSurface != null && oldSurface != captureSurface) {
            oldSurface.release();
        }
    }

    @Override
    public void onPauseToggle() {
        if (overlay == null) return;
        paused = !paused;
        if (renderer != null) renderer.setPaused(paused);
        overlay.setPaused(paused);
        overlay.setOutputVisible(!paused && outputReady);
        updateNotification();
    }

    @Override
    public void onStop() {
        shutdown();
    }

    @Override
    public void onInputFpsChanged(int fps) {
        inputFps = Math.max(1, Math.min(120, fps));
        if (renderer != null) renderer.setInputFps(inputFps);
        if (overlay != null) overlay.setInputFps(inputFps);
        updateNotification();
    }

    private void shutdown() {
        if (shuttingDown) return;
        shuttingDown = true;
        surfaceEpoch++;
        rendererEpoch++;
        mainHandler.removeCallbacks(applyPendingResize);

        GpuFrameGenerator oldRenderer = renderer;
        renderer = null;
        if (oldRenderer != null) oldRenderer.stopAndWait(800L);
        outputSurface = null;

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (captureSurface != null) {
            captureSurface.release();
            captureSurface = null;
        }
        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (RuntimeException ignored) {
            }
            mediaProjection = null;
        }
        if (overlay != null) {
            overlay.detach();
            overlay = null;
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "FrameLift running",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Controls the active frame-generation overlay");
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        PendingIntent toggle = PendingIntent.getService(
                this,
                1,
                new Intent(this, FrameGenService.class).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent stop = PendingIntent.getService(
                this,
                2,
                new Intent(this, FrameGenService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent open = PendingIntent.getActivity(
                this,
                3,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.cardrhyme.framegen.R.drawable.ic_stat_fg)
                .setContentTitle(paused ? "FrameLift bypassed" : "FrameLift active")
                .setContentText(inputFps + " → 120 FPS · selected app")
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(0, paused ? "Resume" : "Bypass", toggle)
                .addAction(0, "Stop", stop)
                .build();
    }

    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, buildNotification());
    }
}
