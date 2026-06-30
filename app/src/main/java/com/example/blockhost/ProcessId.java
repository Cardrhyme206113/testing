package com.example.blockhost;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ProcessId {
    private ProcessId() {}
    public static long get(Process process) {
        if (process == null) return -1;
        try {
            Method method = process.getClass().getMethod("pid");
            Object value = method.invoke(process);
            if (value instanceof Number) return ((Number)value).longValue();
        } catch (Exception ignored) {}
        for (String name : new String[]{"pid","mPid"}) {
            try {
                Field field = process.getClass().getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(process);
                if (value instanceof Number) return ((Number)value).longValue();
            } catch (Exception ignored) {}
        }
        return -1;
    }
}
