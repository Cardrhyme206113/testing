package com.example.blockhost;

import android.app.Application;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;

import java.io.File;

public final class BlockHostApplication extends Application {
    private FileObserver observer;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        File runtime = new File(getFilesDir(), "blockhost/runtime");
        runtime.mkdirs();
        repair(runtime);
        observer = new FileObserver(runtime, FileObserver.CREATE | FileObserver.MOVED_TO | FileObserver.ATTRIB) {
            @Override public void onEvent(int event, String path) {
                repair(runtime);
            }
        };
        observer.startWatching();
        handler.post(new Runnable() {
            int checks = 240;
            @Override public void run() {
                repair(runtime);
                if (--checks > 0) handler.postDelayed(this, 250);
            }
        });
    }

    private static void repair(File runtime) {
        File root = new File(runtime, "alpine");
        if (!root.isDirectory()) return;
        root.setReadable(true, false);
        root.setExecutable(true, false);
    }
}
