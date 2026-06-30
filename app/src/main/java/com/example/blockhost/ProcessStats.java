package com.example.blockhost;

import android.system.Os;
import android.system.OsConstants;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

public final class ProcessStats {
    private long previousTicks;
    private long previousAtMs;

    public synchronized JSONObject sample(long rootPid) {
        JSONObject result = new JSONObject();
        try {
            Set<Long> pids = new HashSet<>();
            collectTree(rootPid, pids);
            long rssKb = 0, ticks = 0;
            for (long pid : pids) { rssKb += readRssKb(pid); ticks += readCpuTicks(pid); }
            long now = System.currentTimeMillis();
            double cpu = 0;
            if (previousAtMs > 0 && ticks >= previousTicks && now > previousAtMs) {
                long hz;
                try { hz = Os.sysconf(OsConstants._SC_CLK_TCK); } catch (Exception e) { hz = 100; }
                cpu = ((ticks - previousTicks) / (double)hz) / ((now - previousAtMs) / 1000.0) * 100.0;
            }
            previousTicks = ticks;
            previousAtMs = now;
            result.put("ramBytes", rssKb * 1024L).put("cpuPercent", Math.max(0, Math.min(999, cpu))).put("processCount", pids.size());
        } catch (Exception e) {
            try { result.put("ramBytes",0L).put("cpuPercent",0.0).put("processCount",0); } catch (Exception ignored) {}
        }
        return result;
    }

    private static void collectTree(long pid,Set<Long> out) {
        if (pid <= 0 || out.contains(pid)) return;
        File proc = new File("/proc/" + pid);
        if (!proc.exists()) return;
        out.add(pid);
        try {
            String text = FileIo.readUtf8(new File(proc,"task/" + pid + "/children")).trim();
            if (!text.isEmpty()) for (String token : text.split("\\s+")) collectTree(Long.parseLong(token),out);
        } catch (Exception ignored) {}
    }

    private static long readRssKb(long pid) {
        try {
            for (String line : Files.readAllLines(new File("/proc/" + pid + "/status").toPath(), StandardCharsets.UTF_8)) {
                if (line.startsWith("VmRSS:")) {
                    String numeric = line.replaceAll("[^0-9]","");
                    return numeric.isEmpty() ? 0 : Long.parseLong(numeric);
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static long readCpuTicks(long pid) {
        try {
            String text = FileIo.readUtf8(new File("/proc/" + pid + "/stat"));
            int close = text.lastIndexOf(')');
            if (close < 0) return 0;
            String[] fields = text.substring(close + 2).split("\\s+");
            return Long.parseLong(fields[11]) + Long.parseLong(fields[12]);
        } catch (Exception ignored) { return 0; }
    }
}
