package com.cardrhyme.framegen;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 1001;
    private static final int REQUEST_NOTIFICATIONS = 1003;

    private EditText inputFpsEdit;
    private int pendingFps = 60;
    private boolean continueAfterAccessibility;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(16, 20, 22));
        window.setNavigationBarColor(Color.rgb(16, 20, 22));
        if (Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
        setContentView(buildUi());

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS
            );
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (continueAfterAccessibility && FrameLiftAccessibilityService.isRunning()) {
            continueAfterAccessibility = false;
            requestCapture();
        }
    }

    private View buildUi() {
        int pad = dp(22);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(16, 20, 22));

        TextView title = text("FrameLift", 30, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap(LinearLayout.LayoutParams.MATCH_PARENT));

        TextView subtitle = text(
                "Full-display GPU frame interpolation. Output targets 120 FPS.",
                16,
                Color.rgb(190, 201, 196)
        );
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(10), 0, dp(28));
        root.addView(subtitle, matchWrap(LinearLayout.LayoutParams.MATCH_PARENT));

        TextView label = text("Source FPS", 15, Color.rgb(220, 230, 225));
        root.addView(label, matchWrap(LinearLayout.LayoutParams.MATCH_PARENT));

        inputFpsEdit = new EditText(this);
        inputFpsEdit.setText("60");
        inputFpsEdit.setTextColor(Color.WHITE);
        inputFpsEdit.setTextSize(24);
        inputFpsEdit.setGravity(Gravity.CENTER);
        inputFpsEdit.setSingleLine(true);
        inputFpsEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        inputFpsEdit.setBackgroundColor(Color.rgb(34, 42, 45));
        LinearLayout.LayoutParams fpsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(62)
        );
        fpsParams.topMargin = dp(8);
        fpsParams.bottomMargin = dp(12);
        root.addView(inputFpsEdit, fpsParams);

        TextView output = text("Output: 120 FPS", 17, Color.rgb(87, 227, 137));
        output.setGravity(Gravity.CENTER);
        output.setPadding(0, 0, 0, dp(22));
        root.addView(output, matchWrap(LinearLayout.LayoutParams.MATCH_PARENT));

        Button start = new Button(this);
        start.setText("Share full display and start");
        start.setTextSize(18);
        start.setAllCaps(false);
        start.setTextColor(Color.rgb(8, 25, 15));
        start.setBackgroundColor(Color.rgb(87, 227, 137));
        start.setOnClickListener(v -> beginStart());
        root.addView(start, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58)
        ));

        TextView note = text(
                "FrameLift now captures the whole display instead of one app. This prevents "
                        + "YouTube fullscreen from changing the capture geometry. The generated "
                        + "surface and control panel are secure, so they are excluded from capture.",
                14,
                Color.rgb(150, 164, 157)
        );
        note.setPadding(0, dp(22), 0, 0);
        root.addView(note, matchWrap(LinearLayout.LayoutParams.MATCH_PARENT));

        return root;
    }

    private void beginStart() {
        pendingFps = parseFps();
        if (!FrameLiftAccessibilityService.isRunning()) {
            continueAfterAccessibility = true;
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(
                    this,
                    "Enable ‘FrameLift touch-through overlay’, then return.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }
        requestCapture();
    }

    private int parseFps() {
        try {
            int value = Integer.parseInt(inputFpsEdit.getText().toString().trim());
            return Math.max(1, Math.min(120, value));
        } catch (NumberFormatException ignored) {
            return 60;
        }
    }

    private void requestCapture() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Intent captureIntent;
        if (Build.VERSION.SDK_INT >= 34) {
            captureIntent = manager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
            );
        } else {
            captureIntent = manager.createScreenCaptureIntent();
        }
        startActivityForResult(captureIntent, REQUEST_CAPTURE);
        Toast.makeText(this, "Allow full-display sharing.", Toast.LENGTH_LONG).show();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode != RESULT_OK || data == null) {
                Toast.makeText(this, "Capture cancelled.", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent serviceIntent = new Intent(this, FrameGenService.class);
            serviceIntent.setAction(FrameGenService.ACTION_START);
            serviceIntent.putExtra(FrameGenService.EXTRA_RESULT_CODE, resultCode);
            serviceIntent.putExtra(FrameGenService.EXTRA_RESULT_DATA, data);
            serviceIntent.putExtra(FrameGenService.EXTRA_INPUT_FPS, pendingFps);
            startForegroundService(serviceIntent);
            moveTaskToBack(true);
        }
    }

    private TextView text(String value, float sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap(int width) {
        return new LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
