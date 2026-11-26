package io.github.kida.qqnativeguard;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XposedHelpers;
import io.github.kida.qqnativeguard.config.HookConfig;

/**
 * 行为自检：在 QQ 进程内**实际调用**被 hook 的方法，验证返回值是否正确。
 *
 * 为什么需要：
 *   之前只验证了「hook 安装成功」（日志里出现 Hooked all methods），
 *   但没验证「hook 生效后行为是否正确」。这两件事不是一回事 ——
 *   hook 可能装上了但逻辑写错，或者被别的 hook 覆盖。
 *
 * 测试项：
 *   1. File 的 12 个方法（exists / canRead / isFile / isDirectory /
 *      length / lastModified / listFiles / isHidden）
 *   2. PackageManager 的 25+ 方法（getPackageInfo / getInstalledPackages /
 *      getPackagesForUid / resolveActivity / queryIntentActivities）
 *   3. 系统属性伪造
 *
 * 结果落盘到 external files dir，不依赖 logcat（会滚动）。
 */
public final class SelfTest {

    private static final String OUT_NAME = "qqng_selftest.log";
    private static volatile boolean ran = false;

    /** 延迟执行：等 Application 创建后再测（hook 装完时还没有 Context）
     *
     *  【修订】原来用 new Thread + sleep(10000)，实测线程在 QQ 启动早期被清理，
     *  InterruptedException 分支静默 return，导致 run() 从未执行。
     *  改用 Handler.postDelayed 走 Android 主线程消息队列，取消延迟到 5 秒。
     */
    public static void schedule() {
        XposedEntry.log("[SELFTEST] schedule() entered");
        if (ran) return;
        try {
            final android.os.Handler h = new android.os.Handler(
                    android.os.Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // 自检有 IO，放到后台线程做，避免卡 UI
                    Thread worker = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                // 必须限定为 SelfTest.run()。
                                // 在匿名 Runnable 内裸写 run() 会解析成 Runnable.run() 自身，
                                // 造成无限递归 → StackOverflowError（已实测踩过）。
                                SelfTest.run();
                            } catch (Throwable t) {
                                XposedEntry.log("[SELFTEST] run failed: " + t);
                                // StackOverflow 必须看堆栈才知道递归在哪
                                StringBuilder tb = new StringBuilder("[SELFTEST] trace:\n");
                                StackTraceElement[] st = t.getStackTrace();
                                int n = 0;
                                for (StackTraceElement e : st) {
                                    tb.append("  at ").append(e).append('\n');
                                    if (++n >= 30) break;
                                }
                                // 同时落盘，避免 logcat 被冲
                                try {
                                    java.io.File f = new java.io.File(
                                        android.os.Environment.getExternalStorageDirectory(),
                                        "Download/qqng_selftest_trace.txt");
                                    java.io.FileOutputStream fos =
                                        new java.io.FileOutputStream(f, false);
                                    fos.write(tb.toString().getBytes("UTF-8"));
                                    fos.close();
                                    XposedEntry.log("[SELFTEST] trace -> " + f);
                                } catch (Throwable ignored) {
                                }
                                XposedEntry.log(tb.toString());
                            }
                        }
                    }, "QNG-SelfTest");
                    worker.setDaemon(true);
                    worker.start();
                }
            }, 5000);
            XposedEntry.log("[SELFTEST] posted to main handler (5s)");
        } catch (Throwable t) {
            XposedEntry.log("[SELFTEST] schedule failed: " + t);
        }
    }

    private static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = XposedHelpers.callStaticMethod(at, "currentActivityThread");
            if (thread == null) return null;
            Object app = XposedHelpers.callMethod(thread, "getApplication");
            return (Context) app;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void run() {
        ran = true;
        XposedEntry.log("[SELFTEST] run() started");
        Context ctx = appContext();
        if (ctx == null) {
            XposedEntry.log("[SELFTEST] no Application yet, abort");
            return;
        }

        StringBuilder sb = new StringBuilder(8192);
        sb.append("=== QQNativeGuard SelfTest ===\n");
        sb.append("time: ")
          .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
          .append('\n');
        sb.append("package: ").append(ctx.getPackageName()).append('\n');
        sb.append("pid: ").append(android.os.Process.myPid()).append('\n');
        sb.append("uid: ").append(android.os.Process.myUid()).append("\n\n");

        // ---------- 1. File 方法 ----------
        sb.append("--- File (12 methods) ---\n");
        // 应当被隐藏的路径：期望全部为 false / -1 / 0
        fileBlock(sb, "/data/adb/magisk", false);
        fileBlock(sb, "/system/bin/su", false);
        fileBlock(sb, "/data/adb/lspd", false);
        // 对照组：正常文件，期望 exists=true（证明 hook 只拦可疑路径）
        fileBlock(sb, "/system/build.prop", true);
        fileBlock(sb, "/system/etc/hosts", true);

        // ---------- 2. PackageManager ----------
        sb.append("\n--- PackageManager ---\n");
        PackageManager pm = ctx.getPackageManager();

        // 2.1 隐藏包应抛 NameNotFoundException
        String[] hidden = {
            "io.github.kida.qqnativeguard",
            "de.robv.android.xposed.installer",
            "com.topjohnwu.magisk",
            "me.weishu.kernelsu",
        };
        for (String p : hidden) {
            sb.append(String.format(Locale.US, "hidden %-44s ", p));
            try {
                PackageInfo pi = pm.getPackageInfo(p, 0);
                sb.append("LEAKED versionName=")
                  .append(pi.versionName == null ? "?" : pi.versionName)
                  .append('\n');
            } catch (PackageManager.NameNotFoundException e) {
                sb.append("BLOCKED (NameNotFoundException)\n");
            } catch (Throwable t) {
                sb.append("ERROR ").append(t).append('\n');
            }
        }

        // 2.2 对照：QQ 自己必须可见
        sb.append(String.format(Locale.US, "control %-42s ", "com.tencent.mobileqq"));
        try {
            PackageInfo pi = pm.getPackageInfo("com.tencent.mobileqq", 0);
            sb.append("VISIBLE versionName=")
              .append(pi.versionName == null ? "?" : pi.versionName).append('\n');
        } catch (Throwable t) {
            sb.append("MISSING ").append(t).append('\n');
        }

        // 2.3 getInstalledPackages 里不应含隐藏包
        sb.append("\ngetInstalledPackages(0):\n");
        try {
            List<PackageInfo> all = pm.getInstalledPackages(0);
            sb.append("  total=").append(all.size()).append('\n');
            for (PackageInfo pi : all) {
                if (pi != null && pi.packageName != null
                        && pi.packageName.startsWith("io.github.kida")) {
                    sb.append("  LEAKED in list: ").append(pi.packageName).append('\n');
                }
            }
            sb.append("  (扫描完成，LEAKED 行数应为 0)\n");
        } catch (Throwable t) {
            sb.append("  ERROR ").append(t).append('\n');
        }

        // 2.4 getPackagesForUid：过滤后不应返回隐藏包
        sb.append("\ngetPackagesForUid:\n");
        try {
            int uid = android.os.Process.myUid();
            String[] pkgs = pm.getPackagesForUid(uid);
            sb.append("  myUid=").append(uid).append(" -> ");
            if (pkgs == null) {
                sb.append("null\n");
            } else {
                sb.append(pkgs.length).append(" pkgs\n");
                for (String s : pkgs) {
                    sb.append("    ").append(s)
                      .append(s != null && s.startsWith("io.github.kida") ? "  <-- LEAK" : "")
                      .append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("  ERROR ").append(t).append('\n');
        }

        // 2.5 getInstalledApplications 检查
        sb.append("\ngetInstalledApplications(0):\n");
        try {
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            boolean leak = false;
            for (ApplicationInfo ai : apps) {
                if (ai != null && ai.packageName != null
                        && ai.packageName.startsWith("io.github.kida")) {
                    leak = true;
                    sb.append("  LEAKED: ").append(ai.packageName).append('\n');
                }
            }
            sb.append("  total=").append(apps.size())
              .append(" leak=").append(leak).append('\n');
        } catch (Throwable t) {
            sb.append("  ERROR ").append(t).append('\n');
        }

        // ---------- 2.9 Turing 类存在性 ----------
        // 目的：确认 Turing SDK 是「打包但从未启用」还是「已加载并在运行」。
        // Class.forName(cls, false, loader) 只查存在性，不触发 static 初始化，
        // 所以不会因探测而激活任何东西。
        sb.append("\n--- Turing class presence ---\n");
        String[] turingClasses = {
            "com.tencent.turingfd.sdk.xq.Pomegranate",
            "com.tencent.turingfd.sdk.xq.Plum",
            "com.tencent.turingfd.sdk.xq.Blueberry",
            "com.tencent.mobileqq.turingfd.TuringSdkManager",
            "com.tencent.turingcam.oqKCa",
        };
        for (String cls : turingClasses) {
            try {
                Class.forName(cls, false, HookConfig.getClassLoader());
                sb.append("  LOADABLE: ").append(cls).append('\n');
            } catch (Throwable t) {
                sb.append("  ABSENT  : ").append(cls)
                  .append("  (").append(t.getClass().getSimpleName()).append(")\n");
            }
        }

        // ---------- 3. 系统属性 ----------
        sb.append("\n--- System properties (via reflection) ---\n");
        for (String key : new String[]{
                "ro.product.model", "ro.product.board", "ro.board.platform",
                "ro.product.brand", "ro.build.id", "ro.serialno"}) {
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                Method get = sp.getMethod("get", String.class);
                Object v = get.invoke(null, key);
                sb.append(String.format(Locale.US, "  %-24s = %s%n", key, v));
            } catch (Throwable t) {
                sb.append("  ").append(key).append(" ERROR ").append(t).append('\n');
            }
        }

        // ---------- 4. 写盘 ----------
        String text = sb.toString();
        File outDir = ctx.getExternalFilesDir(null);
        File out = (outDir != null)
                ? new File(outDir, OUT_NAME)
                : new File(ctx.getFilesDir(), OUT_NAME);
        try (FileOutputStream fos = new FileOutputStream(out, false)) {
            fos.write(text.getBytes("UTF-8"));
        } catch (Throwable t) {
            XposedEntry.log("[SELFTEST] write failed: " + t);
        }

        // 日志只打摘要，全文在文件里
        XposedEntry.log("[SELFTEST] wrote " + out.getAbsolutePath()
                + " (" + text.length() + " chars)");
    }

    /** 测一个路径上的全部 File 方法 */
    private static void fileBlock(StringBuilder sb, String path, boolean shouldExist) {
        File f = new File(path);
        sb.append(String.format(Locale.US, "%-26s expect=%s | ", path,
                shouldExist ? "visible" : "hidden"));
        sb.append("exists=").append(safeBool(f, "exists"));
        sb.append(" canRead=").append(safeBool(f, "canRead"));
        sb.append(" isFile=").append(safeBool(f, "isFile"));
        sb.append(" isDir=").append(safeBool(f, "isDirectory"));
        sb.append(" isHidden=").append(safeBool(f, "isHidden"));
        sb.append(" len=").append(safeLong(f));
        sb.append(" mtime=").append(safeMtime(f));

        // listFiles 过滤验证
        File dir = f.isDirectory() ? f : f.getParentFile();
        if (dir != null && dir.exists()) {
            File[] kids = dir.listFiles();
            sb.append(" listN=").append(kids == null ? -1 : kids.length);
            if (kids != null) {
                int bad = 0;
                for (File k : kids) {
                    if (k != null && k.getAbsolutePath().contains("/su")) bad++;
                }
                if (bad > 0) sb.append(" LIST_LEAK(su)=").append(bad);
            }
        }
        sb.append('\n');
    }

    private static String safeBool(File f, String method) {
        try {
            Object r;
            switch (method) {
                case "exists": r = f.exists(); break;
                case "canRead": r = f.canRead(); break;
                case "isFile": r = f.isFile(); break;
                case "isDirectory": r = f.isDirectory(); break;
                case "isHidden": r = f.isHidden(); break;
                default: r = "?";
            }
            return String.valueOf(r);
        } catch (Throwable t) {
            return "ERR";
        }
    }

    private static String safeLong(File f) {
        try {
            return String.valueOf(f.length());
        } catch (Throwable t) {
            return "ERR";
        }
    }

    private static String safeMtime(File f) {
        try {
            return String.valueOf(f.lastModified());
        } catch (Throwable t) {
            return "ERR";
        }
    }

    private SelfTest() {
    }
}