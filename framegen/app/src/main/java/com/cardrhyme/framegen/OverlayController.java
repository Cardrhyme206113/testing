package com.cardrhyme.framegen;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
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
        void onOutputFpsChanged(int fps);
        void onInterpolationStrengthChanged(int percent);
    }

    private final Context context;
    private final WindowManager windowManager;
    private final Listener listener;

    private SurfaceView outputView;
    private WindowManager.LayoutParams outputParams;
    private LinearLayout controlRoot;
    private WindowManager.LayoutParams controlParams;
    private LinearLayout header;
    private LinearLayout readout;
    private TextView collapseButton;
    private TextView statusView;
    private TextView statsView;
    private TextView sourceLabel;
    private TextView targetLabel;
    private TextView strengthLabel;
    private Button pauseButton;
    private LinearLayout expandedPanel;

    private int inputFps;
    private int outputFps;
    private int interpolationStrength;
    private boolean mini;
    private boolean expanded;
    private boolean paused;
    private boolean attached;

    OverlayController(
            Context context,
            int inputFps,
            int outputFps,
            int interpolationStrength,
            Listener listener
    ) {
        this.context = context;
        this.inputFps = clampFps(inputFps);
        this.outputFps = Math.max(this.inputFps, clampFps(outputFps));
        this.interpolationStrength = clampPercent(interpolationStrength);
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
        outputParams.preferredRefreshRate = outputFps;
        if (Build.VERSION.SDK_INT >= 28) {
            outputParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        selectExactDisplayMode(outputParams, outputFps);
        windowManager.addView(outputView, outputParams);

        controlRoot = buildControls();
        controlParams = new WindowManager.LayoutParams(
                dp(270),
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
        applySurfaceFrameRate();
    }

    void resizeOutput(int ignoredX, int ignoredY, int bufferWidth, int bufferHeight) {
        if (!attached || outputView == null) return;
        outputView.getHolder().setFixedSize(
                Math.max(1, bufferWidth),
                Math.max(1, bufferHeight)
        );
        positionControlInsideSystemBars();
        if (controlRoot != null) windowManager.updateViewLayout(controlRoot, controlParams);
        applySurfaceFrameRate();
    }

    private void positionControlInsideSystemBars() {
        try {
            WindowMetrics metrics = windowManager.getMaximumWindowMetrics();
            Rect bounds = metrics.getBounds();
            Insets bars = metrics.getWindowInsets().getInsets(WindowInsets.Type.systemBars());
            int safeTop = bounds.top + bars.top;
            int safeRight = bounds.right - bars.right;
            controlParams.x = Math.max(bounds.left + bars.left,
                    safeRight - controlParams.width - dp(10));
            controlParams.y = safeTop + dp(10);
        } catch (RuntimeException ignored) {
            controlParams.x = Math.max(0,
                    context.getResources().getDisplayMetrics().widthPixels
                            - controlParams.width - dp(10));
            controlParams.y = dp(18);
        }
    }

    @SuppressWarnings("deprecation")
    private void selectExactDisplayMode(WindowManager.LayoutParams params, int requestedFps) {
        params.preferredDisplayModeId = 0;
        try {
            android.view.Display display = windowManager.getDefaultDisplay();
            android.view.Display.Mode current = display.getMode();
            android.view.Display.Mode best = null;
            float bestDifference = Float.MAX_VALUE;
            for (android.view.Display.Mode mode : display.getSupportedModes()) {
                if (mode.getPhysicalWidth() != current.getPhysicalWidth()
                        || mode.getPhysicalHeight() != current.getPhysicalHeight()) {
                    continue;
                }
                float difference = Math.abs(mode.getRefreshRate() - requestedFps);
                if (difference < bestDifference) {
                    bestDifference = difference;
                    best = mode;
                }
            }
            if (best != null && bestDifference < 1.5f) {
                params.preferredDisplayModeId = best.getModeId();
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void applySurfaceFrameRate() {
        if (outputView == null) return;
        try {
            Surface surface = outputView.getHolder().getSurface();
            if (surface == null || !surface.isValid()) return;
            if (Build.VERSION.SDK_INT >= 31) {
                surface.setFrameRate(
                        outputFps,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        Surface.CHANGE_FRAME_RATE_ALWAYS
                );
            } else {
                surface.setFrameRate(
                        outputFps,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                );
            }
        } catch (RuntimeException ignored) {
        }
    }

    private LinearLayout buildControls() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(5), dp(5), dp(5), dp(5));
        root.setBackgroundColor(Color.argb(238, 18, 24, 25));

        header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(3), dp(2), dp(3), dp(2));

        TextView fg = label("FG", 15, Color.rgb(87, 227, 137));
        fg.setGravity(Gravity.CENTER);
        header.addView(fg, new LinearLayout.LayoutParams(dp(40), dp(38)));

        readout = new LinearLayout(context);
        readout.setOrientation(LinearLayout.VERTICAL);
        readout.setGravity(Gravity.CENTER_VERTICAL);
        statusView = label(statusText(), 13, Color.WHITE);
        statsView = label("SRC --  OUT --  GEN --  CAP --", 9, Color.rgb(174, 190, 181));
        readout.addView(statusView);
        readout.addView(statsView);

        LinearLayout.LayoutParams readoutParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        readoutParams.leftMargin = dp(4);
        header.addView(readout, readoutParams);

        collapseButton = label("‹", 19, Color.rgb(190, 205, 197));
        collapseButton.setGravity(Gravity.CENTER);
        collapseButton.setOnClickListener(v -> setMini(true));
        header.addView(collapseButton, new LinearLayout.LayoutParams(dp(28), dp(36)));
        root.addView(header);

        expandedPanel = new LinearLayout(context);
        expandedPanel.setOrientation(LinearLayout.VERTICAL);
        expandedPanel.setVisibility(View.GONE);

        expandedPanel.addView(makeFpsRow(true));
        expandedPanel.addView(makeFpsRow(false));
        expandedPanel.addView(makeStrengthRow());

        pauseButton = compactButton("Bypass");
        pauseButton.setOnClickListener(v -> listener.onPauseToggle());
        LinearLayout.LayoutParams pauseParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(38)
        );
        pauseParams.topMargin = dp(3);
        expandedPanel.addView(pauseButton, pauseParams);

        Button stop = compactButton("STOP FRAME GEN");
        stop.setTextColor(Color.rgb(255, 180, 180));
        stop.setOnClickListener(v -> listener.onStop());
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(38)
        );
        stopParams.topMargin = dp(4);
        expandedPanel.addView(stop, stopParams);
        root.addView(expandedPanel);

        installDragAndExpand(header);
        updateControlLabels();
        return root;
    }

    private LinearLayout makeFpsRow(boolean source) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView value = label("", 12, Color.rgb(205, 215, 210));
        if (source) sourceLabel = value;
        else targetLabel = value;
        row.addView(value, new LinearLayout.LayoutParams(0, dp(36), 1f));

        Button minus = compactButton("−");
        minus.setOnClickListener(v -> {
            if (source) changeInputFps(-1);
            else changeOutputFps(-5);
        });
        row.addView(minus, new LinearLayout.LayoutParams(dp(42), dp(34)));

        Button plus = compactButton("+");
        plus.setOnClickListener(v -> {
            if (source) changeInputFps(1);
            else changeOutputFps(5);
        });
        row.addView(plus, new LinearLayout.LayoutParams(dp(42), dp(34)));
        return row;
    }

    private LinearLayout makeStrengthRow() {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);

        strengthLabel = label("", 12, Color.rgb(205, 215, 210));
        row.addView(strengthLabel, new LinearLayout.LayoutParams(0, dp(36), 1f));

        Button minus = compactButton("−");
        minus.setOnClickListener(v -> changeInterpolationStrength(-5));
        row.addView(minus, new LinearLayout.LayoutParams(dp(42), dp(34)));

        Button plus = compactButton("+");
        plus.setOnClickListener(v -> changeInterpolationStrength(5));
        row.addView(plus, new LinearLayout.LayoutParams(dp(42), dp(34)));
        return row;
    }

    private void installDragAndExpand(View dragView) {
        dragView.setOnTouchListener(new View.OnTouchListener() {
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
                        if (Math.abs(dx) + Math.abs(dy) > dp(7)) moved = true;
                        controlParams.x = Math.max(0, startX + dx);
                        controlParams.y = Math.max(0, startY + dy);
                        windowManager.updateViewLayout(controlRoot, controlParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            if (mini) setMini(false);
                            else setExpanded(!expanded);
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void setMini(boolean value) {
        if (mini == value || controlParams == null) return;
        int oldWidth = controlParams.width;
        int oldRight = controlParams.x + oldWidth;
        mini = value;
        expanded = false;

        readout.setVisibility(mini ? View.GONE : View.VISIBLE);
        collapseButton.setVisibility(mini ? View.GONE : View.VISIBLE);
        expandedPanel.setVisibility(View.GONE);
        controlParams.width = dp(mini ? 50 : 270);
        controlParams.x = Math.max(0, oldRight - controlParams.width);
        controlRoot.setPadding(
                dp(mini ? 2 : 5),
                dp(mini ? 2 : 5),
                dp(mini ? 2 : 5),
                dp(mini ? 2 : 5)
        );
        controlRoot.requestLayout();
        windowManager.updateViewLayout(controlRoot, controlParams);
    }

    private void setExpanded(boolean value) {
        if (mini) {
            setMini(false);
            return;
        }
        expanded = value;
        expandedPanel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        controlRoot.requestLayout();
        windowManager.updateViewLayout(controlRoot, controlParams);
    }

    private void changeInputFps(int delta) {
        inputFps = clampFps(inputFps + delta);
        listener.onInputFpsChanged(inputFps);
        if (outputFps < inputFps) {
            outputFps = inputFps;
            listener.onOutputFpsChanged(outputFps);
            applyOutputRatePreference();
        }
        updateControlLabels();
        updateStatus();
    }

    private void changeOutputFps(int delta) {
        outputFps = Math.max(inputFps, clampFps(outputFps + delta));
        listener.onOutputFpsChanged(outputFps);
        applyOutputRatePreference();
        updateControlLabels();
        updateStatus();
    }

    private void changeInterpolationStrength(int delta) {
        interpolationStrength = clampPercent(interpolationStrength + delta);
        listener.onInterpolationStrengthChanged(interpolationStrength);
        updateControlLabels();
    }

    private void applyOutputRatePreference() {
        if (outputParams == null) return;
        outputParams.preferredRefreshRate = outputFps;
        selectExactDisplayMode(outputParams, outputFps);
        if (attached && outputView != null) {
            windowManager.updateViewLayout(outputView, outputParams);
            applySurfaceFrameRate();
        }
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
        inputFps = clampFps(fps);
        if (outputFps < inputFps) outputFps = inputFps;
        updateControlLabels();
        updateStatus();
    }

    void setOutputFps(int fps) {
        outputFps = Math.max(inputFps, clampFps(fps));
        updateControlLabels();
        updateStatus();
        applyOutputRatePreference();
    }

    void setInterpolationStrength(int percent) {
        interpolationStrength = clampPercent(percent);
        updateControlLabels();
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
                "S %.1f  O %.1f  G %.1f  C %.0f  %s",
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

    private void updateControlLabels() {
        if (sourceLabel != null) sourceLabel.setText("Source  " + inputFps + " FPS");
        if (targetLabel != null) targetLabel.setText("Target  " + outputFps + " FPS");
        if (strengthLabel != null) {
            strengthLabel.setText("Interpolation  " + interpolationStrength + "%");
        }
    }

    private void updateStatus() {
        if (statusView == null) return;
        statusView.setText(statusText());
        statusView.setTextColor(paused ? Color.rgb(255, 220, 140) : Color.WHITE);
    }

    private String statusText() {
        return paused ? "Bypassed" : inputFps + " → " + outputFps + " FPS";
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
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.rgb(39, 50, 51));
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
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

    private static int clampFps(int value) {
        return Math.max(1, Math.min(120, value));
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
