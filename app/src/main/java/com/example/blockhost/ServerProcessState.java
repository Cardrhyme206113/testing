package com.example.blockhost;

import android.content.Context;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** File-backed state bridge between the UI process and the server JVM process. */
public final class ServerProcessState {
    private static final Object LOCK = new Object();
    private static final String FILE_NAME = "server-process-state.json";
    private static final long STALE_AFTER_MS = 12_000L;

    private ServerProcessState() {}

    public static void write(Context context, JSONObject state) {
        synchronized (LOCK) {
            try {
                JSONObject copy = new JSONObject(state.toString());
                copy.put("processPid", Process.myPid());
                copy.put("updatedAt", System.currentTimeMillis());
                File target = stateFile(context);
                File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
                FileIo.writeUtf8(tmp, copy.toString());
                try {
                    Files.move(tmp.toPath(), target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception atomicMoveFailed) {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception ignored) {}
        }
    }

    public static JSONObject read(Context context, String serverId) {
        synchronized (LOCK) {
            try {
                File file = stateFile(context);
                if (!file.isFile()) return idle(serverId);
                JSONObject state = new JSONObject(FileIo.readUtf8(file));
                if (!serverId.equals(state.optString("serverId"))) return idle(serverId);

                String status = state.optString("status", "stopped");
                long updatedAt = state.optLong("updatedAt", 0L);
                int pid = state.optInt("processPid", -1);
                boolean busy = "starting".equals(status)
                        || "running".equals(status)
                        || "installing".equals(status)
                        || "stopping".equals(status);
                if (busy && System.currentTimeMillis() - updatedAt > STALE_AFTER_MS
                        && !isProcessAlive(pid)) {
                    state.put("status", "error");
                    state.put("phase", "server-process-exit");
                    state.put("progress", 0);
                    state.put("message", "Server process exited unexpectedly. Check hs_err_pid*.log in Files or Android crash logs.");
                    state.put("ramBytes", 0L);
                    state.put("cpuPercent", 0.0);
                    state.put("players", new JSONArray());
                    write(context, state);
                }
                return state;
            } catch (Exception ignored) {
                return idle(serverId);
            }
        }
    }

    public static void clear(Context context, String serverId) {
        write(context, idle(serverId));
    }

    private static File stateFile(Context context) {
        File root = new File(context.getFilesDir(), "blockhost");
        root.mkdirs();
        return new File(root, FILE_NAME);
    }

    private static boolean isProcessAlive(int pid) {
        if (pid <= 0) return false;
        try { return new File("/proc/" + pid).exists(); }
        catch (Exception ignored) { return false; }
    }

    private static JSONObject idle(String serverId) {
        try {
            return new JSONObject()
                    .put("serverId", serverId == null ? "" : serverId)
                    .put("status", "stopped")
                    .put("phase", "idle")
                    .put("progress", 0)
                    .put("message", "Stopped")
                    .put("ramBytes", 0L)
                    .put("cpuPercent", 0.0)
                    .put("players", new JSONArray())
                    .put("startedAt", 0L)
                    .put("processPid", -1)
                    .put("updatedAt", System.currentTimeMillis());
        } catch (Exception impossible) {
            return new JSONObject();
        }
    }
}
