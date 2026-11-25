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
import io.github.kida.qqnativeguard.config.HookConfig;
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

        // ---- 5d. isGuardState 的返回值（降级的前置条件）----
        try {
            HookUtils.hookAllMethods("com.tencent.mobileqq.app.guard.GuardManager",
                "isGuardState", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Object mgr = param.thisObject;
                            int sid = XposedHelpers.getIntField(mgr, "mStateId");
                            Object r = param.getResult();
                            String s = "[IS-GUARD] stateId=" + sid + " -> " + r;
                            XposedEntry.log(s);
                            dump("=== " + s + " ===\n");
                        } catch (Throwable ignored) {
                        }
                    }
                });
        } catch (Throwable ignored) {
        }

        // ---- 5c. 各 GuardState 子类的 onEnter（确定是否真的进入过某状态）----
        for (final String sc : new String[]{
                "LiteUnguard", "LiteGuard", "BackgroundUnguard", "BackgroundGuard",
                "ForgroundOther", "ForgroundMain", "PrestartGuard", "GuardState"}) {
            try {
                HookUtils.hookAllMethods("com.tencent.mobileqq.app.guard." + sc,
                    "onEnter", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Object a0 = param.args.length > 0 ? param.args[0] : null;
                            String s = "[STATE-ENTER] " + sc + " arg=" + a0;
                            XposedEntry.log(s);
                            dump("=== " + s + " ===\n");
                        }
                    });
            } catch (Throwable ignored) {
            }
        }

        // ---- 5. 状态机采样：算出距离自杀还有多少拍 ----
        hookGuardStateMachine();

        // ---- 5b. handleMessage 的 what 值（定位降级消息编号）----
        try {
            HookUtils.hookAllMethods("com.tencent.mobileqq.app.guard.GuardManager",
                "handleMessage", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Object a0 = param.args.length > 0 ? param.args[0] : null;
                            if (a0 == null) return;
                            int what = XposedHelpers.getIntField(a0, "what");
                            int arg1 = XposedHelpers.getIntField(a0, "arg1");
                            Object obj = XposedHelpers.getObjectField(a0, "obj");
                            // 只记录非 TICK(5) 的消息，避免每 12 秒刷一条
                            if (what == 5) return;
                            Object mgr = param.thisObject;
                            int sid = -1;
                            try {
                                sid = XposedHelpers.getIntField(mgr, "mStateId");
                            } catch (Throwable ignored) {
                            }
                            String s = "[HANDLE-MSG] what=" + what + " arg1=" + arg1
                                    + " obj=" + obj + " stateId=" + sid;
                            XposedEntry.log(s);
                            dump("=== " + s + " ===\n");
                        } catch (Throwable ignored) {
                        }
                    }
                });
        } catch (Throwable ignored) {
        }

        // ---- 6. 一次性 dump 状态 ID 映射 ----
        scheduleStateIdDump();

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

    /**
     * 状态机采样。
     *
     * 自杀判定（BackgroundUnguard.onTick）依赖：
     *   mClearTick == SUICIDE_FACTOR*50 (=250) ± mGuardTick
     *   且 GuardManager.mFgProcess == null
     *
     * 这里每拍读一次两个计数器与 mFgProcess，就能算出：
     *   - 当前在哪个状态
     *   - 距离阈值还差多少拍 / 多少分钟
     *   - 是否满足"无前台进程"这一必要条件
     */
    private static void hookGuardStateMachine() {
        // 5.1 状态转移
        try {
            HookUtils.hookAllMethods("com.tencent.mobileqq.app.guard.GuardManager",
                "nextState", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Object mgr = param.thisObject;
                        int from = -1;
                        try {
                            from = XposedHelpers.getIntField(mgr, "mStateId");
                        } catch (Throwable ignored) {
                        }
                        int to = -1;
                        if (param.args.length > 0 && param.args[0] instanceof Integer) {
                            to = (Integer) param.args[0];
                        }
                        Object ev = param.args.length > 1 ? param.args[1] : null;
                        String evs = String.valueOf(ev);
                        if (evs.length() > 120) evs = evs.substring(0, 120) + "...";

                        // 通过 getState 拿到目标状态的类名，确定 id -> 状态映射
                        String fromName = "?";
                        String toName = "?";
                        try {
                            Object f = XposedHelpers.callMethod(mgr, "getState", from);
                            if (f != null) fromName = f.getClass().getSimpleName();
                        } catch (Throwable ignored) {
                        }
                        try {
                            Object t = XposedHelpers.callMethod(mgr, "getState", to);
                            if (t != null) toName = t.getClass().getSimpleName();
                        } catch (Throwable ignored) {
                        }

                        StringBuilder sb = new StringBuilder();
                        sb.append("=== STATE TRANSITION ===\n");
                        sb.append("from=").append(from).append("(").append(fromName)
                          .append(") to=").append(to).append("(").append(toName)
                          .append(") event=").append(evs).append('\n');
                        appendStack(sb);
                        dump(sb.toString());
                        XposedEntry.log("[EXIT-TRACE] state " + from + "(" + fromName + ") -> " + to + "(" + toName + ") ev=" + evs);
                    }
                });
        } catch (Throwable ignored) {
        }

        // 5.2 每拍采样
        try {
            HookUtils.hookAllMethods("com.tencent.mobileqq.app.guard.GuardState",
                "onTick", new XC_MethodHook() {
                    private int count = 0;

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            count++;
                            Object st = param.thisObject;
                            if (st == null) return;
                            String stateName = st.getClass().getSimpleName();

                            long clearTick = XposedHelpers.getLongField(st, "mClearTick");
                            long guardTick = XposedHelpers.getLongField(st, "mGuardTick");

                            Object mgr = XposedHelpers.getObjectField(st, "mManager");
                            String fg = null;
                            int stateId = -1;
                            if (mgr != null) {
                                Object v = XposedHelpers.getObjectField(mgr, "mFgProcess");
                                fg = (v == null) ? null : String.valueOf(v);
                                try {
                                    stateId = XposedHelpers.getIntField(mgr, "mStateId");
                                } catch (Throwable ignored) {
                                }
                            }

                            // 阈值：SUICIDE_FACTOR(5) * 50 = 250
                            long T = 250;
                            // 三个候选命中点里，最接近的一个
                            long d1 = T - guardTick;
                            long d2 = T;
                            long d3 = T + guardTick;

                            // 距离最近命中点还差多少
                            long best = Long.MAX_VALUE;
                            for (long cand : new long[]{d1, d2, d3}) {
                                if (cand > clearTick) {
                                    long gap = cand - clearTick;
                                    if (gap < best) best = gap;
                                } else if (cand == clearTick) {
                                    best = 0;
                                }
                            }

                            // 采样策略：每 10 拍一条；接近阈值（<10 拍）每拍一条；命中立刻一条
                            boolean near = (best >= 0 && best <= 10);
                            boolean sample = (count % 10 == 0) || near || best == 0;
                            if (!sample) return;

                            StringBuilder sb = new StringBuilder();
                            sb.append("=== TICK SAMPLE ===\n");
                            sb.append("state=").append(stateName)
                              .append(" id=").append(stateId).append('\n');
                            sb.append("mClearTick=").append(clearTick)
                              .append(" mGuardTick=").append(guardTick).append('\n');
                            sb.append("thresholds: ").append(d1).append(" / ")
                              .append(d2).append(" / ").append(d3).append('\n');
                            sb.append("ticks_to_hit=").append(best < 0 ? -1 : best);
                            if (best > 0) {
                                sb.append(" (~").append(best * 12 / 60)
                                  .append(" min)");
                            }
                            sb.append('\n');
                            sb.append("mFgProcess=").append(fg == null ? "null" : fg)
                              .append(fg == null ? "   <<< 自杀必要条件已满足" : "")
                              .append('\n');
                            // 只在 Unguard 状态提示风险
                            if (stateName.contains("Unguard")) {
                                sb.append("!! IN UNGUARD STATE — suicide possible at hit\n");
                            }
                            dump(sb.toString());

                            if (best == 0 || near) {
                                XposedEntry.log("[EXIT-TRACE] tick " + stateName
                                        + " clear=" + clearTick + " guard=" + guardTick
                                        + " toHit=" + best + " fg=" + fg);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
        } catch (Throwable ignored) {
        }
    }

    /**
     * 一次性 dump 状态 ID -> 类名。
     *
     * GuardManager 有静态字段 INSTANCE，拿实例后调 getState(0..7)，
     * 把类名写进日志，从此 id 映射不再需要靠反汇编猜。
     */
    private static void scheduleStateIdDump() {
        // NAME_STATE / NAME_EVENT 是静态字段，不等实例，立即读
        try {
            Class<?> gsCls = Class.forName(
                    "com.tencent.mobileqq.app.guard.GuardState",
                    false, HookConfig.getClassLoader());
            Object so = XposedHelpers.getStaticObjectField(gsCls, "NAME_STATE");
            if (so instanceof String[]) {
                String[] a = (String[]) so;
                StringBuilder b = new StringBuilder("[STATE-NAME] ");
                for (int i = 0; i < a.length; i++) {
                    b.append(i).append('=').append(a[i]).append(' ');
                }
                XposedEntry.log(b.toString());
                dump("=== NAME_STATE ===\n" + b + "\n");
            } else {
                XposedEntry.log("[STATE-NAME] not a String[]: " + so);
            }
            Object eo = XposedHelpers.getStaticObjectField(gsCls, "NAME_EVENT");
            if (eo instanceof String[]) {
                String[] a = (String[]) eo;
                StringBuilder b = new StringBuilder("[EVENT-NAME] ");
                for (int i = 0; i < a.length; i++) {
                    b.append(i).append('=').append(a[i]).append(' ');
                }
                XposedEntry.log(b.toString());
                dump("=== NAME_EVENT ===\n" + b + "\n");
            } else {
                XposedEntry.log("[EVENT-NAME] not a String[]: " + eo);
            }
        } catch (Throwable t) {
            XposedEntry.log("[STATE-NAME] immediate read failed: " + t);
        }

        try {
            final android.os.Handler h =
                    new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        Class<?> gmCls = Class.forName(
                                "com.tencent.mobileqq.app.guard.GuardManager",
                                false, HookConfig.getClassLoader());
                        Object inst = XposedHelpers.getStaticObjectField(gmCls, "sInstance");
                        if (inst == null) {
                            XposedEntry.log("[STATE-ID] sInstance is null");
                            return;
                        }
                        StringBuilder sb = new StringBuilder("[STATE-ID] map:");
                        for (int id = 0; id <= 7; id++) {
                            String nm = "?";
                            try {
                                Object st = XposedHelpers.callMethod(inst, "getState", id);
                                if (st != null) nm = st.getClass().getSimpleName();
                            } catch (Throwable t) {
                                nm = "ERR:" + t.getClass().getSimpleName();
                            }
                            sb.append(' ').append(id).append('=').append(nm);
                        }
                        XposedEntry.log(sb.toString());
                        dump("=== STATE ID MAP ===\n" + sb.toString() + "\n");

                        // NAME_STATE / NAME_EVENT：状态名与事件名数组，
                        // 直接读出来就能把 id 映射和事件语义一次性搞清楚
                        try {
                            Class<?> gsCls = Class.forName(
                                    "com.tencent.mobileqq.app.guard.GuardState",
                                    false, HookConfig.getClassLoader());
                            String[] stNames = (String[]) XposedHelpers
                                    .getStaticObjectField(gsCls, "NAME_STATE");
                            String[] evNames = (String[]) XposedHelpers
                                    .getStaticObjectField(gsCls, "NAME_EVENT");
                            StringBuilder b2 = new StringBuilder("[STATE-NAME] ");
                            if (stNames != null) {
                                for (int i = 0; i < stNames.length; i++) {
                                    b2.append(i).append('=').append(stNames[i]).append(' ');
                                }
                            }
                            XposedEntry.log(b2.toString());
                            dump("=== NAME_STATE ===\n" + b2 + "\n");

                            StringBuilder b3 = new StringBuilder("[EVENT-NAME] ");
                            if (evNames != null) {
                                for (int i = 0; i < evNames.length; i++) {
                                    b3.append(i).append('=').append(evNames[i]).append(' ');
                                }
                            }
                            XposedEntry.log(b3.toString());
                            dump("=== NAME_EVENT ===\n" + b3 + "\n");
                        } catch (Throwable t) {
                            XposedEntry.log("[STATE-NAME] failed: " + t);
                        }
                    } catch (Throwable t) {
                        XposedEntry.log("[STATE-ID] dump failed: " + t);
                    }
                }
            }, 8000);
        } catch (Throwable t) {
            XposedEntry.log("[STATE-ID] schedule failed: " + t);
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