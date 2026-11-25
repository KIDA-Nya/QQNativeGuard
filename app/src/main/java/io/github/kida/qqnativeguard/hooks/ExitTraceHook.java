package io.github.kida.qqnativeguard.hooks;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.kida.qqnativeguard.XposedEntry;
import io.github.kida.qqnativeguard.utils.HookUtils;

/**
 * Exit 追踪：记录所有可能让 QQ 退出的调用点，含参数与完整调用栈。
 *
 * 背景（2026-11-26 静态分析，A/B 级）：
 *   扫全部 dex 得到 50 个 exit 调用点。按参数分组：
 *     code=-1  × 4    ← exit(-1) 在 Linux 下即进程 status=255
 *     code=0   × 14
 *     code=1   × 5
 *     code=2   × 1
 *     动态参数 × 26
 *
 *   **没有任何调用点直接写 255**，而实测自杀是 status=255，
 *   所以元凶必是 exit(-1) 的四个之一：
 *     1. NativeMonitorConfigHelper#initSoLoadConfig  （读 isBanIllegalSoLoad 开关）
 *     2. guardcallback.e#c                           （GuardManager 回调）
 *     3. KernelSetterImpl#<clinit>
 *     4. [Lcom.tencent.luggage.wxa.kj.e$a#clone
 *
 * 本类只观测、不改行为（硬约束：不许让 QQ 自杀退出）。
 * 结果落盘到 files/qqng_exit_trace.log。
 */
public final class ExitTraceHook {

    private static final String OUT = "qqng_exit_trace.log";
    private static volatile boolean installed = false;
    private static Context ctx;

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        if (installed) return;
        installed = true;

        // ---- 1. 退出原语：记录参数 + 堆栈 ----
        hookExitPrimitive("java.lang.System", "exit");
        hookExitPrimitive("java.lang.Runtime", "exit");
        hookExitPrimitive("java.lang.Runtime", "halt");
        hookExitPrimitive("android.os.Process", "killProcess");
        hookExitPrimitive("android.os.Process", "killProcessQuiet");

        // ---- 2. QQ 的退出封装 ----
        hookWrapper("mqq.app.MobileQQ", "realExit");
        hookWrapper("mqq.app.MobileQQ", "exit");
        hookWrapper("mqq.app.MobileQQ", "killProcess");
        hookWrapper("mqq.app.AppRuntime", "exit");
        hookWrapper("com.tencent.mobileqq.app.QQAppInterface", "exit");
        hookWrapper("com.tencent.mobileqq.app.guard.GuardManager", "exit");

        // ---- 3. 四个 exit(-1) 嫌疑点 ----
        hookWrapper("com.tencent.mobileqq.data.nativemonitor.NativeMonitorConfigHelper",
                "initSoLoadConfig");
        hookWrapper("com.tencent.mobileqq.app.guardcallback.e", "c");
        hookWrapper("com.tencent.qqnt.kernel.api.impl.KernelSetterImpl", "<clinit>");

        // ---- 4. SystemMethodProxy：57 个调用者的统一入口 ----
        hookWrapper("com.tencent.mobileqq.perf.block.SystemMethodProxy", "killProcess");

        XposedEntry.log("[EXIT-TRACE] installed");
    }

    private static void hookExitPrimitive(String cls, final String mth) {
        try {
            HookUtils.hookAllMethods(cls, mth, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    StringBuilder sb = new StringBuilder();
                    sb.append("=== EXIT PRIMITIVE ===\n");
                    sb.append("target: ").append(cls).append('#').append(mth).append('\n');
                    sb.append("args: ");
                    int status = -1;
                    boolean hasInt = false;
                    for (Object a : param.args) {
                        if (a instanceof Integer) {
                            int v = (Integer) a;
                            if (!hasInt) {
                                status = v & 0xFF;
                                hasInt = true;
                            }
                            sb.append(v).append(" (unsigned=").append(v & 0xFF).append(") ");
                        } else {
                            sb.append(a).append(' ');
                        }
                    }
                    sb.append('\n');
                    appendStack(sb);
                    dump(sb.toString());

                    if (hasInt) {
                        XposedEntry.log("[EXIT-TRACE] " + cls + "." + mth
                                + " -> status=" + status);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void hookWrapper(String cls, final String mth) {
        try {
            HookUtils.hookAllMethods(cls, mth, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    StringBuilder sb = new StringBuilder();
                    sb.append("=== EXIT WRAPPER ===\n");
                    sb.append("target: ").append(cls).append('#').append(mth).append('\n');
                    appendStack(sb);
                    dump(sb.toString());
                    XposedEntry.log("[EXIT-TRACE] wrapper: " + cls + "." + mth);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void appendStack(StringBuilder sb) {
        sb.append("stack:\n");
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        int n = 0;
        for (StackTraceElement e : st) {
            String s = e.toString();
            if (s.contains("de.robv.android.xposed")
                    || s.contains("io.github.kida.qqnativeguard")) continue;
            sb.append("  at ").append(s).append('\n');
            if (++n >= 20) break;
        }
    }

    private static synchronized void dump(String text) {
        Context c = appContext();
        if (c == null) return;
        try {
            File dir = c.getExternalFilesDir(null);
            File f = (dir != null) ? new File(dir, OUT) : new File(c.getFilesDir(), OUT);
            String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                    .format(new Date());
            FileOutputStream fos = new FileOutputStream(f, true);
            try {
                fos.write(("[" + stamp + "] " + text + "\n").getBytes("UTF-8"));
            } finally {
                fos.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static Context appContext() {
        if (ctx != null) return ctx;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = XposedHelpers.callStaticMethod(at, "currentActivityThread");
            if (thread == null) return null;
            Object app = XposedHelpers.callMethod(thread, "getApplication");
            ctx = (Context) app;
            return ctx;
        } catch (Throwable t) {
            return null;
        }
    }

    private ExitTraceHook() {
    }
}