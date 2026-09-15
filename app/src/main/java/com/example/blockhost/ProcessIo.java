package com.example.blockhost;

import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public final class ProcessIo {
    private ProcessIo() {}

    public static String consume(Process process, Consumer<String> lineConsumer) throws IOException {
        StringBuilder all = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                all.append(line).append('\n');
                if (lineConsumer != null) lineConsumer.accept(line);
            }
        }
        return all.toString();
    }
}

final class NativeJvmRunner implements AutoCloseable {
    static { System.loadLibrary("blockhost_jvm"); }

    private final ParcelFileDescriptor stdinRead;
    private final ParcelFileDescriptor stdinWrite;
    private final ParcelFileDescriptor stdoutRead;
    private final ParcelFileDescriptor stdoutWrite;
    private final OutputStream commandStream;
    private final InputStream outputStream;

    NativeJvmRunner() throws IOException {
        ParcelFileDescriptor[] input = ParcelFileDescriptor.createPipe();
        stdinRead = input[0]; stdinWrite = input[1];
        ParcelFileDescriptor[] output = ParcelFileDescriptor.createPipe();
        stdoutRead = output[0]; stdoutWrite = output[1];
        commandStream = new FileOutputStream(stdinWrite.getFileDescriptor());
        outputStream = new FileInputStream(stdoutRead.getFileDescriptor());
    }

    int launch(LinuxRuntimeManager.Layout runtime, File workDir, String[] args) {
        String[] preload = new String[runtime.preloadLibraries.length];
        for (int i = 0; i < preload.length; i++) preload[i] = runtime.preloadLibraries[i].getAbsolutePath();
        return nativeLaunch(runtime.libjli.getAbsolutePath(), runtime.libraryPath,
                runtime.javaHome.getAbsolutePath(), workDir.getAbsolutePath(),
                preload, args, stdinRead.getFd(), stdoutWrite.getFd());
    }

    InputStream output() { return outputStream; }
    OutputStream commands() { return commandStream; }

    @Override public void close() {
        try { commandStream.close(); } catch (Exception ignored) {}
        try { outputStream.close(); } catch (Exception ignored) {}
        try { stdinRead.close(); } catch (Exception ignored) {}
        try { stdinWrite.close(); } catch (Exception ignored) {}
        try { stdoutRead.close(); } catch (Exception ignored) {}
        try { stdoutWrite.close(); } catch (Exception ignored) {}
    }

    private static native int nativeLaunch(String libjliPath, String libraryPath,
            String javaHome, String workDir, String[] preloadLibraries,
            String[] args, int stdinFd, int stdoutFd);
}
