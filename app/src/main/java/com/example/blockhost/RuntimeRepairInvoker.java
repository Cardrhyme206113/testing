package com.example.blockhost;

import android.content.Context;

import java.io.File;
import java.lang.reflect.Method;

public final class RuntimeRepairInvoker {
    private RuntimeRepairInvoker() {}

    public static void run(Context context) {
        try {
            Method repair = BlockHostApplication.class.getDeclaredMethod("repair", File.class);
            repair.setAccessible(true);
            repair.invoke(null, new File(context.getFilesDir(), "blockhost/runtime"));
        } catch (Exception ignored) {}
    }
}
