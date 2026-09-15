package com.cardrhyme.framegen;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

public final class FrameLiftAccessibilityService extends AccessibilityService {
    private static volatile FrameLiftAccessibilityService instance;

    static FrameLiftAccessibilityService getInstance() {
        return instance;
    }

    static boolean isRunning() {
        return instance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // FrameLift does not inspect accessibility events or window contents.
    }

    @Override
    public void onInterrupt() {
        // No accessibility feedback is produced.
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
