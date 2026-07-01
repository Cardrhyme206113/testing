package com.cardrhyme.framegen;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

final class OverlayController {
    interface Listener {
        void onPauseToggle();
        void onStop();
        void onInputFpsChanged(int fps);
    }

    private final Context context;
    private final WindowManager windowManager;
    private final Listener listener;

    private SurfaceView outputView;
    private WindowManager.LayoutParams outputParams;
    private LinearLayout controlRoot;
    private WindowManager.LayoutParams controlParams;
    private TextView statusView;
    private TextView statsView;
    private Button pauseButton;
    private LinearLayout expandedPanel;

    private int inputFps;
    private boolean expanded;
    private boolean paused;
    private boolean attached;

    OverlayController(Context context, int inputFps, Listener listener) {
        this.context = context;
        this.inputFps = inputFps;
        this.listener = listener;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    void attach(
            SurfaceHolder.Callback surfaceCallback,
            int outputX,
            int outputY,
            int bufferWidth,
            int bufferHeight
    ) {
        if (attached) return;
        attached = true;

        outputView = new SurfaceView(context);
        outputView.setSecure(true);
        outputView.getHolder().setFormat(PixelFormat.OPAQUE);
        outputView.getHolder().setFixedSize(bufferWidth, bufferHeight);
        outputView.getHolder().addCallback(surfaceCallback);
        outputView.setKeepScreenOn(true);

        outputParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.OPAQUE
        );
        outputParams.gravity = Gravity.TOP | Gravity.START;
        outputParams.x = 0;
        outputParams.y = 0;
        outputParams.alpha = 0f;
        outputParams.preferredRefreshRate = 120f;
        if (Build.VERSION.SDK_INT >= 28) {
            outputParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        select120HzMode(outputParams);
        windowManager.addView(outputView, outputParams);

        controlRoot = buildControls();
        controlParams = new WindowManager.LayoutParams(
                dp(318),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT
        );
        controlParams.gravity = Gravity.TOP | Gravity.START;
        positionControlInsideSystemBars();
        windowManager.addView(controlRoot, controlParams);
    }

    void resizeOutput(int ignoredX, int ignoredY, int bufferWidth, int bufferHeight) {
        if (!attached || outputView == null) return;
        outputView.getHolder().setFixedSize(
                Math.max(1, bufferWidth),
                Math.max(1, bufferHeight)
        );
        positionControlInsideSystemBars();
        if (controlRoot != null) windowManager.updateViewLayout(controlRoot, controlParams);
    }

    private void positionControlInsideSystemBars() {
        try {
            WindowMetrics metrics = windowManager.getMaximumWindowMetrics();
            Rect bounds = metrics.getBounds();
            Insets bars = metrics.getWindowInsets().getInsets(WindowInsets.Type.systemBars());
            int safeTop = bounds.top + bars.top;
            int safeRight = bounds.right - bars.right;
            controlParams.x = Math.max(bounds.left + bars.left,
                    safeRight - controlParams.width - dp(12));
            controlParams.y = safeTop + dp(12);
        } catch (RuntimeException ignored) {
            controlParams.x = Math.max(0,
                    context.getResources().getDisplayMetrics().widthPixels
                            - controlParams.width - dp(12));
            controlParams.y = dp(20);
        }
    }

    @SuppressWarnings("deprecation")
    private void select120HzMode(WindowManager.LayoutParams params) {
        try {
            android.view.Display display = windowManager.getDefaultDisplay();
            android.view.Display.Mode best = null;
            for (android.view.Display.Mode mode : display.getSupportedModes()) {
                if (Math.abs(mode.getRefreshRate() - 120f) < 1f
                        && (best == null || mode.getPhysicalWidth() > best.getPhysicalWidth())) {
                    best = mode;
                }
            }
            if (best != null) params.preferredDisplayModeId = best.getModeId();
        } catch (RuntimeException ignored) {
        }
    }

    private LinearLayout buildControls() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.setBackgroundColor(Color.argb(238, 18, 24, 25));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(4), dp(8), dp(4));

        TextView fg = label("FG", 17, Color.rgb(87, 227, 137));
        fg.setGravity(Gravity.CENTER);
        header.addView(fg, new LinearLayout.LayoutParams(dp(44), dp(48)));

        LinearLayout readout = new LinearLayout(context);
        readout.setOrientation(LinearLayout.VERTICAL);
        readout.setGravity(Gravity.CENTER_VERTICAL);
        statusView = label(statusText(), 15, Color.WHITE);
        statsView = label("SRC --.-  OUT --.-  GEN --.-", 11, Color.rgb(174, 190, 181));
        readout.addView(statusView);
        readout.addView(statsView);

        LinearLayout.LayoutParams readoutParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        readoutParams.leftMargin = dp(7);
        header.addView(readout, readoutParams);
        root.addView(header);

        expandedPanel = new LinearLayout(context);
        expandedPanel.setOrientation(LinearLayout.VERTICAL);
        expandedPanel.setVisibility(View.GONE);

        LinearLayout fpsRow = new LinearLayout(context);
        fpsRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView fpsLabel = label("Configured source FPS", 14, Color.rgb(205, 215, 210));
        fpsRow.addView(fpsLabel, new LinearLayout.LayoutParams(0, dp(46), 1f));

        Button minus = compactButton("−");
        minus.setOnClickListener(v -> changeFps(-1));
        fpsRow.addView(minus, new LinearLayout.LayoutParams(dp(54), dp(42)));

        Button plus = compactButton("+");
        plus.setOnClickListener(v -> changeFps(1));
        fpsRow.addView(plus, new LinearLayout.LayoutParams(dp(54), dp(42)));
        expandedPanel.addView(fpsRow);

        pauseButton = compactButton("Bypass");
        pauseButton.setOnClickListener(v -> listener.onPauseToggle());
        expandedPanel.addView(pauseButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
        ));

        Button stop = compactButton("STOP FRAME GEN");
        stop.setTextColor(Color.rgb(255, 180, 180));
        stop.setOnClickListener(v -> listener.onStop());
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
        );
        stopParams.topMargin = dp(6);
        expandedPanel.addView(stop, stopParams);
        root.addView(expandedPanel);

        installDragAndExpand(header);
        return root;
    }

    private void installDragAndExpand(View header) {
        header.setOnTouchListener(new View.OnTouchListener() {
            int startX;
            int startY;
            float downX;
            float downY;
            boolean moved;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = controlParams.x;
                        startY = controlParams.y;
                        downX = event.getRawX();
                        downY = event.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(event.getRawX() - downX);
                        int dy = Math.round(event.getRawY() - downY);
                        if (Math.abs(dx) + Math.abs(dy) > dp(8)) moved = true;
                        controlParams.x = Math.max(0, startX + dx);
                        controlParams.y = Math.max(0, startY + dy);
                        windowManager.updateViewLayout(controlRoot, controlParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) setExpanded(!expanded);
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void setExpanded(boolean value) {
        expanded = value;
        expandedPanel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        controlRoot.requestLayout();
    }

    private void changeFps(int delta) {
        inputFps = Math.max(1, Math.min(120, inputFps + delta));
        updateStatus();
        listener.onInputFpsChanged(inputFps);
    }

    void setOutputVisible(boolean visible) {
        if (!attached || outputView == null) return;
        outputParams.alpha = visible ? 1f : 0f;
        windowManager.updateViewLayout(outputView, outputParams);
    }

    void setPaused(boolean value) {
        paused = value;
        if (pauseButton != null) pauseButton.setText(paused ? "Resume" : "Bypass");
        updateStatus();
    }

    void setInputFps(int fps) {
        inputFps = Math.max(1, Math.min(120, fps));
        updateStatus();
    }

    void setStats(
            float sourceFps,
            float outputFps,
            float generatedFps,
            float captureFps,
            boolean flowActive
    ) {
        if (statsView == null) return;
        statsView.setText(String.format(
                Locale.US,
                "SRC %.1f  OUT %.1f  GEN %.1f  CAP %.0f  %s",
                sourceFps,
                outputFps,
                generatedFps,
                captureFps,
                flowActive ? "FLOW" : "WAIT"
        ));
        statsView.setTextColor(flowActive
                ? Color.rgb(137, 223, 166)
                : Color.rgb(208, 186, 126));
    }

    void setError(String message) {
        if (statusView != null) {
            statusView.setText("ERROR · " + message);
            statusView.setTextColor(Color.rgb(255, 170, 170));
        }
    }

    private void updateStatus() {
        if (statusView == null) return;
        statusView.setText(statusText());
        statusView.setTextColor(paused ? Color.rgb(255, 220, 140) : Color.WHITE);
    }

    private String statusText() {
        return paused ? "Bypassed · tap to open" : inputFps + " → 120 FPS";
    }

    void detach() {
        if (!attached) return;
        attached = false;
        try {
            if (controlRoot != null) windowManager.removeViewImmediate(controlRoot);
        } catch (RuntimeException ignored) {
        }
        try {
            if (outputView != null) windowManager.removeViewImmediate(outputView);
        } catch (RuntimeException ignored) {
        }
        controlRoot = null;
        outputView = null;
    }

    private Button compactButton(String text) {
        Button button = new Button(context);
        button.setText(text);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.rgb(39, 50, 51));
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
    }

    private TextView label(String text, float sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
