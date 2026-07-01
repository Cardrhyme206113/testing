package com.cardrhyme.framegen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;

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
    private int width;
    private int height;
    private int densityDpi;
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

        inputFps = Math.max(1, Math.min(120, intent.getIntExtra(EXTRA_INPUT_FPS, 60)));
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = (Intent) intent.getParcelableExtra(EXTRA_RESULT_DATA);
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
        }, mainHandler);

        readDisplaySize();
        overlay = new OverlayController(this, inputFps, this);
        overlay.attach(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                startRenderer(holder.getSurface());
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int newWidth, int newHeight) {
                // The display-sized overlay is recreated on rotation; restart is safest for v0.1.
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (renderer != null) renderer.stop();
                renderer = null;
            }
        });

        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void readDisplaySize() {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(metrics);
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        densityDpi = metrics.densityDpi;
    }

    private void startRenderer(Surface outputSurface) {
        if (renderer != null) renderer.stop();
        renderer = new GpuFrameGenerator(inputFps, new GpuFrameGenerator.Callback() {
            @Override
            public void onCaptureSurfaceReady(Surface surface) {
                mainHandler.post(() -> createVirtualDisplay(surface));
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
        renderer.start(outputSurface, width, height);
    }

    private void createVirtualDisplay(Surface surface) {
        if (mediaProjection == null || virtualDisplay != null) return;
        captureSurface = surface;
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
