package com.cardrhyme.framegen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
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
import android.view.WindowInsets;
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

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private OverlayController overlay;
    private GpuFrameGenerator renderer;
    private Surface captureSurface;

    private int usableX;
    private int usableY;
    private int usableWidth;
    private int usableHeight;
    private int outputX;
    private int outputY;
    private int width;
    private int height;
    private int densityDpi;
    private int rendererWidth;
    private int rendererHeight;
    private int inputFps = 60;
    private boolean paused;
    private boolean outputReady;
    private boolean shuttingDown;

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
                mainHandler.post(() -> applyCapturedSize(newWidth, newHeight));
            }
        }, mainHandler);

        readUsableDisplayArea(accessibility);
        overlay = new OverlayController(accessibility, inputFps, this);
        overlay.attach(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                startRenderer(holder.getSurface(), width, height);
            }

            @Override
            public void surfaceChanged(
                    SurfaceHolder holder,
                    int format,
                    int newWidth,
                    int newHeight
            ) {
                if (newWidth <= 0 || newHeight <= 0) return;
                if (newWidth == rendererWidth && newHeight == rendererHeight) return;
                width = newWidth;
                height = newHeight;
                startRenderer(holder.getSurface(), newWidth, newHeight);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (renderer != null) renderer.stop();
                renderer = null;
                rendererWidth = 0;
                rendererHeight = 0;
            }
        }, outputX, outputY, width, height);

        return START_NOT_STICKY;
    }

    private void readUsableDisplayArea(FrameLiftAccessibilityService accessibility) {
        WindowManager windowManager = accessibility.getSystemService(WindowManager.class);
        WindowMetrics metrics = windowManager.getMaximumWindowMetrics();
        Rect bounds = metrics.getBounds();
        Insets systemBars = metrics.getWindowInsets().getInsets(WindowInsets.Type.systemBars());

        usableX = bounds.left + systemBars.left;
        usableY = bounds.top + systemBars.top;
        usableWidth = Math.max(1, bounds.width() - systemBars.left - systemBars.right);
        usableHeight = Math.max(1, bounds.height() - systemBars.top - systemBars.bottom);
        densityDpi = accessibility.getResources().getDisplayMetrics().densityDpi;

        outputX = usableX;
        outputY = usableY;
        width = usableWidth;
        height = usableHeight;
    }

    private void applyCapturedSize(int newWidth, int newHeight) {
        if (newWidth <= 0 || newHeight <= 0 || overlay == null) return;

        int fittedWidth = Math.min(newWidth, usableWidth);
        int fittedHeight = Math.min(newHeight, usableHeight);
        if (fittedWidth <= 0 || fittedHeight <= 0) return;

        outputX = usableX + Math.max(0, (usableWidth - fittedWidth) / 2);
        outputY = usableY + Math.max(0, (usableHeight - fittedHeight) / 2);

        boolean changed = fittedWidth != width || fittedHeight != height;
        width = fittedWidth;
        height = fittedHeight;
        overlay.resizeOutput(outputX, outputY, width, height);

        if (changed && virtualDisplay != null) {
            virtualDisplay.resize(width, height, densityDpi);
        }
    }

    private void startRenderer(Surface outputSurface, int renderWidth, int renderHeight) {
        if (renderWidth <= 0 || renderHeight <= 0) return;
        if (renderer != null) renderer.stop();
        rendererWidth = renderWidth;
        rendererHeight = renderHeight;
        outputReady = false;
        if (overlay != null) overlay.setOutputVisible(false);

        renderer = new GpuFrameGenerator(inputFps, new GpuFrameGenerator.Callback() {
            @Override
            public void onCaptureSurfaceReady(Surface surface) {
                mainHandler.post(() -> connectVirtualDisplay(surface));
            }

            @Override
            public void onFirstOutputFrame() {
                mainHandler.post(() -> {
                    outputReady = true;
                    if (!paused && overlay != null) overlay.setOutputVisible(true);
                });
            }

            @Override
            public void onFatalError(String message) {
                mainHandler.post(() -> {
                    paused = true;
                    if (overlay != null) {
                        overlay.setOutputVisible(false);
                        overlay.setPaused(true);
                        overlay.setError(message);
                    }
                    updateNotification();
                });
            }
        });
        renderer.start(outputSurface, renderWidth, renderHeight);
    }

    private void connectVirtualDisplay(Surface surface) {
        if (mediaProjection == null) {
            surface.release();
            return;
        }

        if (captureSurface != null && captureSurface != surface) {
            captureSurface.release();
        }
        captureSurface = surface;

        if (virtualDisplay == null) {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "FrameLiftCapture",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    captureSurface,
                    null,
                    mainHandler
            );
        } else {
            virtualDisplay.resize(width, height, densityDpi);
            virtualDisplay.setSurface(captureSurface);
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
        updateNotification();
    }

    private void shutdown() {
        if (shuttingDown) return;
        shuttingDown = true;
        if (renderer != null) {
            renderer.stop();
            renderer = null;
        }
        rendererWidth = 0;
        rendererHeight = 0;
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
                .setContentText(inputFps + " → 120 FPS")
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
