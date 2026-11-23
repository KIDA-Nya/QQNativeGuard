package io.github.kida.qqnativeguard.utils;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 简单文件日志：把模块日志同时追加写入一个文件（配合外部配置 LOG_FILE）。
 *
 * 行为：
 * - LOG_FILE 未配置（空）→ 不写文件，只打 logcat。
 * - LOG_FILE 配置了但写失败 → 自动按顺序回退尝试两个已知 QQ 可写的路径，
 *   每个失败路径的真实错误都会打 logcat；全部失败才降级为仅 logcat。
 *
 * 回退顺序（QQ 进程可写的位置）：
 *   1. 配置里写的路径
 *   2. /data/user/0/com.tencent.mobileqq/qqbypass.log
 *   3. /sdcard/Android/data/com.tencent.mobileqq/files/qqbypass.log
 */
public class FileLog {

    private static final String TAG = "QQEnhancedBypass";
    private static volatile String logPath = null;
    private static final Object LOCK = new Object();
    private static final Map<String, Long> LAST_WRITTEN = new HashMap<>();
    private static final long DEDUPE_WINDOW_MS = 5000L;
    private static final long MAX_LOG_BYTES = 2L * 1024L * 1024L;
    private static long lastRateNoticeMs;

    /** LOG_FILE 配置的路径失败后的回退路径 */
    private static final String[] FALLBACK_PATHS = {
        "/storage/emulated/0/Android/data/com.tencent.mobileqq/qqbypass.log",
        "/data/user/0/com.tencent.mobileqq/qqbypass.log",
        "/sdcard/Android/data/com.tencent.mobileqq/files/qqbypass.log"
    };

    public static void init(String configPath) {
        logPath = null;
        boolean configured = configPath != null && !configPath.isEmpty();

        if (!configured) {
            // 未配置：不写文件
            return;
        }

        String first = configPath;
        if (tryEnable(first)) {
            return;
        }
        for (String fallback : FALLBACK_PATHS) {
            if (tryEnable(fallback)) {
                return;
            }
        }
        Log.w(TAG, "FileLog: all paths failed, 已降级为仅 logcat（见上方各路径的错误原因）");
    }

    /**
     * 尝试把 logPath 设为 path 并写入起始标记。
     * 成功返回 true；失败打 logcat（含真实异常）并返回 false，logPath 保持 null。
     */
    private static boolean tryEnable(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            Log.w(TAG, "FileLog: cannot create parent dir " + parent.getAbsolutePath());
            return false;
        }
        synchronized (LOCK) {
            logPath = path;
            try {
                rotateIfNeeded(file, 0);
                try (FileOutputStream fos = new FileOutputStream(file, true)) {
                    String line = timestamp() + " === QQ Enhanced Bypass log file started (pid="
                            + android.os.Process.myPid() + ") ===\n";
                    fos.write(line.getBytes("UTF-8"));
                }
                Log.i(TAG, "FileLog enabled: " + path);
                return true;
            } catch (Throwable t) {
                logPath = null;
                Log.w(TAG, "FileLog: cannot write " + path + " -> "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
                return false;
            }
        }
    }

    /**
     * 追加一行带时间戳的日志。文件未初始化或写入失败时静默忽略。
     */
    private static boolean shouldWrite(String message) {
        // Keep this list in sync with filter_logcat.sh (the on-device logcat
        // matcher): same event vocabulary on both sides so the merged
        // timeline contains the same classes from module log and logcat.
        String s = message.toLowerCase(java.util.Locale.US);
        return s.contains("account_kicked") || s.contains("account_expired")
            || s.contains("kick_to_login") || s.contains("device_risk")
            || s.contains("device risk") || s.contains("设备存在风险")
            || s.contains("devicetokenv3") || s.contains("手机号验证")
            || s.contains("身份验证失败") || s.contains("w21")
            || s.contains("fatal exception") || s.contains("fatal signal")
            || s.contains("sigsegv") || s.contains("sigabrt")
            || s.contains("initialization complete") || s.contains("external config loaded")
            || s.contains("failed") || s.contains("exception")
            || s.contains("crash") || s.contains("risk")
            || s.contains("kicked") || s.contains("expired")
            // login-rejection diagnostics (PROBE): a bare "登录失败" dialog has no
            // body text, so these lines are the only place the code survives.
            || s.contains("fromservicemsg") || s.contains("wtlogin")
            || s.contains("wlogin") || s.contains("login fail")
            || s.contains("setbusinessfail") || s.contains("ecdh")
            || s.contains("loadclass") || s.contains("loginrsp")
            || s.contains("body@") || s.contains("body cmd=") || s.contains("body cjk=") || s.contains("body ascii=")
            || s.contains("msfservlet") || s.contains("fail")
            || s.contains("blocked pkg") || s.contains("hidden package")
            || s.contains("pkgprobe") || s.contains("pandora.") || s.contains("probe open") || s.contains("faked ") || s.contains("turing") || s.contains("send cmd=") || s.contains("send@") || s.contains("send ascii=") || s.contains("send cjk=")
            // 调用者归因（观测）：native 层的 FKCALL 行（谁读了哪个路径）
            || s.contains("fkcall") || s.contains("attr module")
            // wlogin 上报观测（Java 层 log_report 明文）
            || s.contains("wlogin_report");
    }

    public static void write(String message) {
        String path = logPath;
        if (path == null || message == null || !shouldWrite(message)) {
            return;
        }
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            Long last = LAST_WRITTEN.get(message);
            if (last != null && now - last < DEDUPE_WINDOW_MS) {
                return;
            }
            LAST_WRITTEN.put(message, now);
            if (LAST_WRITTEN.size() > 1024) {
                LAST_WRITTEN.clear();
            }
            try {
                File file = new File(path);
                String line = timestamp() + " " + message + "\n";
                byte[] bytes = line.getBytes("UTF-8");
                rotateIfNeeded(file, bytes.length);
                try (FileOutputStream fos = new FileOutputStream(file, true)) {
                    fos.write(bytes);
                }
            } catch (Throwable t) {
                // File logging must never interfere with QQ execution.
                if (now - lastRateNoticeMs > 60000L) {
                    lastRateNoticeMs = now;
                    Log.w(TAG, "FileLog: write failed (suppressed): "
                        + t.getClass().getSimpleName());
                }
            }
        }
    }

    private static String timestamp() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(new Date());
    }

    private static void rotateIfNeeded(File file, int incomingBytes) {
        if (file.exists() && file.length() + incomingBytes > MAX_LOG_BYTES) {
            File old = new File(file.getAbsolutePath() + ".1");
            if (old.exists()) old.delete();
            if (!file.renameTo(old)) {
                // Truncate only the configured diagnostic file if rotation fails.
                try (FileOutputStream ignored = new FileOutputStream(file, false)) { }
                catch (Throwable ignored) { }
            }
        }
    }

    public static boolean isEnabled() {
        return logPath != null;
    }
}
