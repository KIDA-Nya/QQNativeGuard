package io.github.kida.qqnativeguard.utils;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import io.github.kida.qqnativeguard.config.HookConfig;

/**
 * QIMEI 重置（实验功能）：
 * Qimei SDK 把 qimei16/qimei36 缓存在 files/qm、files/com.tencent.qimei.sdk.QimeiSDK
 * 和 shared_prefs 里。删除这些缓存后，SDK 会在本次进程启动时重新生成一个新的 qimei，
 * 服务器侧就会把它当成“另一台设备”。
 *
 * 用 RESET_QIMEI=<token> 控制：token 变化才执行一次，避免每次启动都重置。
 * 执行后把 token 写入 files/.qqbypass_qimei_token 作为标记。
 */
public class QimeiResetter {

    private static final String TAG = "QQEnhancedBypass";
    private static final String BASE = "/data/user/0/com.tencent.mobileqq";
    private static final String TOKEN_FILE = BASE + "/files/.qqbypass_qimei_token";

    public static void maybeReset() {
        String token = HookConfig.RESET_QIMEI;
        if (token == null || token.isEmpty()) {
            return;
        }
        try {
            String old = readToken();
            if (token.equals(old)) {
                Log.i(TAG, "QimeiReset: token unchanged, skip");
                return;
            }

            int deleted = 0;
            deleted += deleteRecursive(new File(BASE, "files/qm"));
            deleted += deleteRecursive(new File(BASE, "files/com.tencent.qimei.sdk.QimeiSDK"));
            // 其他可能的 qimei 缓存文件
            deleted += deleteMatching(new File(BASE, "files"), "qimei");
            deleted += deleteMatching(new File(BASE, "shared_prefs"), "qimei");
            deleted += deleteRecursive(new File(BASE, "shared_prefs/qm_global_sp.xml"));
            deleted += deleteMatching(new File(BASE, "cache"), "qimei");

            writeToken(token);
            String msg = "QimeiReset: token=" + token + ", deleted=" + deleted
                + " (old qimei cache removed, SDK will regenerate)";
            Log.i(TAG, msg);
            FileLog.write("I/" + TAG + ": " + msg);
        } catch (Throwable t) {
            Log.w(TAG, "QimeiReset failed: " + t);
            FileLog.write("E/" + TAG + ": QimeiReset failed: " + t);
        }
    }

    private static String readToken() {
        try (FileInputStream in = new FileInputStream(TOKEN_FILE)) {
            byte[] buf = new byte[256];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8).trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static void writeToken(String token) throws Throwable {
        File f = new File(TOKEN_FILE);
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.write(token.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static int deleteRecursive(File f) {
        if (f == null || !f.exists()) return 0;
        int n = 0;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) n += deleteRecursive(c);
            }
        }
        if (f.delete()) {
            n++;
            Log.i(TAG, "QimeiReset: deleted " + f.getAbsolutePath());
        }
        return n;
    }

    private static int deleteMatching(File dir, String lowerKeyword) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        int n = 0;
        for (File c : children) {
            if (c.getName().toLowerCase().contains(lowerKeyword)) {
                n += deleteRecursive(c);
            }
        }
        return n;
    }
}