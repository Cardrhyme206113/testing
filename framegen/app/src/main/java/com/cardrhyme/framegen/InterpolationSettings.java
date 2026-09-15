package com.cardrhyme.framegen;

import java.util.concurrent.atomic.AtomicInteger;

final class InterpolationSettings {
    private static final AtomicInteger STRENGTH_PERCENT = new AtomicInteger(90);

    private InterpolationSettings() {
    }

    static int getStrengthPercent() {
        return STRENGTH_PERCENT.get();
    }

    static void setStrengthPercent(int value) {
        STRENGTH_PERCENT.set(Math.max(0, Math.min(100, value)));
    }
}
