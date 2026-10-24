package dev.card.anima;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import java.io.File;

/** Each diagnostic runs in :npu_probe, never in the Anima Pocket UI process. */
public final class NpuPteService extends Service {
    public static final int MSG_RUN = 1;
    public static final int MSG_RESULT = 2;
    private boolean started;

    private final Messenger incoming = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        if (msg.what != MSG_RUN) return false;
        if (started) return true;
        started = true;
        final Messenger recipient = msg.replyTo;
        final Bundle data = msg.getData();
        final String path = data == null ? null : data.getString("model_path");
        final String mode = data == null ? null : data.getString("mode");
        new Thread(() -> {
            String report;
            try {
                if ("pte_lazy".equals(mode) || "pte_backend".equals(mode)) {
                    File allowed = new File(new File(getFilesDir(), "npu"), "anima-projection.pte");
                    File selected = path == null ? null : new File(path);
                    if (selected == null || !selected.getCanonicalFile().equals(allowed.getCanonicalFile())) {
                        throw new SecurityException("Invalid NPU projection file path");
                    }
                    report = NpuPteTest.run(selected, "pte_backend".equals(mode));
                } else {
                    report = NpuPteTest.probeLoader(mode);
                }
            } catch (Throwable t) {
                report = "ISOLATED_NPU_JAVA_ERROR mode=" + mode + "\n"
                    + android.util.Log.getStackTraceString(t);
            }
            if (recipient != null) {
                Message reply = Message.obtain(null, MSG_RESULT);
                Bundle body = new Bundle();
                body.putString("report", report);
                reply.setData(body);
                try { recipient.send(reply); }
                catch (RemoteException e) { android.util.Log.e("AnimaNpuWorker", "UI receiver unavailable", e); }
            }
            stopSelf();
        }, "Anima-Isolated-NPU").start();
        return true;
    }));

    @Override public IBinder onBind(Intent intent) { return incoming.getBinder(); }

    /** Fresh child process per attempt; no native loader state leaks between probes. */
    @Override public boolean onUnbind(Intent intent) {
        android.os.Process.killProcess(android.os.Process.myPid());
        return false;
    }
}
