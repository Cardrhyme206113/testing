package com.cardrhyme.framegen;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
    private EditText outputFpsEdit;
    private int pendingInputFps = 60;
    private int pendingOutputFps = 120;
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
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(16, 20, 22));

        TextView title = text("FrameLift", 28, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap(LinearLayout.LayoutParams.MATCH_PARENT));

        TextView subtitle = text(
                "Quality GPU interpolation with separate source and target rates.",
                14,
                Color.rgb(190, 201, 196)
        );
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(7), 0, dp(20));
        root.addView(subtitle, matchWrap(LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout fpsRow = new LinearLayout(this);
        fpsRow.setOrientation(LinearLayout.HORIZONTAL);
        fpsRow.setGravity(Gravity.CENTER);

        LinearLayout sourceColumn = fpsColumn("Source FPS", "60", true);
        LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        columnParams.rightMargin = dp(5);
        fpsRow.addView(sourceColumn, columnParams);

        LinearLayout targetColumn = fpsColumn("Target FPS", "120", false);
        LinearLayout.LayoutParams targetParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        targetParams.leftMargin = dp(5);
        fpsRow.addView(targetColumn, targetParams);
        root.addView(fpsRow, matchWrap(LinearLayout.LayoutParams.MATCH_PARENT));

        Button start = new Button(this);
        start.setText("Select app and start");
        start.setTextSize(16);
        start.setAllCaps(false);
        start.setTextColor(Color.rgb(8, 25, 15));
        start.setBackgroundColor(Color.rgb(87, 227, 137));
        start.setOnClickListener(v -> beginStart());
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
        );
        startParams.topMargin = dp(16);
        root.addView(start, startParams);

        TextView note = text(
                "Choose one app in the capture picker. Target FPS can also be changed from the overlay.",
                13,
                Color.rgb(150, 164, 157)
        );
        note.setPadding(0, dp(16), 0, 0);
        root.addView(note, matchWrap(LinearLayout.LayoutParams.MATCH_PARENT));
        return root;
    }

    private LinearLayout fpsColumn(String labelText, String initialValue, boolean source) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        TextView label = text(labelText, 13, Color.rgb(220, 230, 225));
        label.setGravity(Gravity.CENTER);
        column.addView(label, matchWrap(LinearLayout.LayoutParams.MATCH_PARENT));

        EditText edit = new EditText(this);
        edit.setText(initialValue);
        edit.setTextColor(Color.WHITE);
        edit.setTextSize(22);
        edit.setGravity(Gravity.CENTER);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        edit.setBackgroundColor(Color.rgb(34, 42, 45));
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
        );
        editParams.topMargin = dp(6);
        column.addView(edit, editParams);

        if (source) inputFpsEdit = edit;
        else outputFpsEdit = edit;
        return column;
    }

    private void beginStart() {
        pendingInputFps = parseFps(inputFpsEdit, 60);
        pendingOutputFps = Math.max(
                pendingInputFps,
                parseFps(outputFpsEdit, 120)
        );
        outputFpsEdit.setText(Integer.toString(pendingOutputFps));

        if (!FrameLiftAccessibilityService.isRunning()) {
            continueAfterAccessibility = true;
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(
                    this,
                    "Enable FrameLift touch-through overlay, then return.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }
        requestCapture();
    }

    private int parseFps(EditText editText, int fallback) {
        try {
            int value = Integer.parseInt(editText.getText().toString().trim());
            return Math.max(1, Math.min(120, value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void requestCapture() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
        Toast.makeText(this, "Select one app.", Toast.LENGTH_LONG).show();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;

        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Capture cancelled.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent serviceIntent = new Intent(this, FrameGenService.class);
        serviceIntent.setAction(FrameGenService.ACTION_START);
        serviceIntent.putExtra(FrameGenService.EXTRA_RESULT_CODE, resultCode);
        serviceIntent.putExtra(FrameGenService.EXTRA_RESULT_DATA, data);
        serviceIntent.putExtra(FrameGenService.EXTRA_INPUT_FPS, pendingInputFps);
        serviceIntent.putExtra(FrameGenService.EXTRA_OUTPUT_FPS, pendingOutputFps);
        startForegroundService(serviceIntent);
        moveTaskToBack(true);
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
