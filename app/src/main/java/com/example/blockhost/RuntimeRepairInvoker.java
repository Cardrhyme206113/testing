package com.example.blockhost;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.lang.reflect.Method;

public final class RuntimeRepairInvoker {
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private RuntimeRepairInvoker() {}

    public static void run(Context context) {
        File runtime = new File(context.getFilesDir(), "blockhost/runtime");
        HANDLER.post(new Runnable() {
            int checks = 240;
            @Override public void run() {
                repair(runtime);
                if (--checks > 0) HANDLER.postDelayed(this, 250);
            }
        });
    }

    private static void repair(File runtime) {
        try {
            Method method = BlockHostApplication.class.getDeclaredMethod("repair", File.class);
            method.setAccessible(true);
            method.invoke(null, runtime);
        } catch (Exception ignored) {}
    }
}
