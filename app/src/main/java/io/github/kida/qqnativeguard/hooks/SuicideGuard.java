package io.github.kida.qqnativeguard.hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.kida.qqnativeguard.XposedEntry;
import io.github.kida.qqnativeguard.utils.HookUtils;

/**
 * 阻止 QQ 的后台「静默自杀」。
 *
 * ## 为什么要阻止
 *
 * 实测时间线（2026-11-26）：
 *   18:07:20  登录成功
 *   20:04:00  BackgroundUnguard.onTick -> System.exit(-1)   ← 自杀
 *   20:30:29  自动恢复登录（rst1=0）
 *   20:44:00  KICK_TO_LOGIN                                  ← 被服务端踢线
 *
 * 自杀会让会话突然异常中断，服务端记录为「非正常断开」，
 * 随后重新登录时被加严校验，14 分钟后就踢了。
 *
 * 所以自杀不是无害的 —— 它是被踢的诱因。
 *
 * ## 怎么做
 *
 * 反汇编表明判定逻辑是：
 *   BackgroundUnguard.onTick():
 *       super.onTick();                       // mGuardTick++, mClearTick++
 *       T = SUICIDE_FACTOR * 50 = 250
 *       if (mClearTick == T - mGuardTick
 *        || mClearTick == T
 *        || mClearTick == T + mGuardTick) {
 *           if (mManager.mFgProcess == null) System.exit(-1);
 *       }
 *
 * 最温和的干预：在 onTick 返回前把 mClearTick 归零，
 * 让倒计时永远到不了阈值。不改状态机、不影响内存回收的其他途径
 * （QQ 仍会在系统内存压力下被正常回收）。
 */
public final class SuicideGuard {

    private static volatile boolean installed = false;
    private static int blockCount = 0;

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        if (installed) return;
        installed = true;

        try {
            HookUtils.hookAllMethods(
                "com.tencent.mobileqq.app.guard.BackgroundUnguard", "onTick",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Object self = param.thisObject;
                            // 只在这个状态下归零，避免影响基类行为
                            XposedHelpers.setLongField(self, "mClearTick", 0L);
                            if (blockCount < 20) {
                                blockCount++;
                                long gt = XposedHelpers.getLongField(self, "mGuardTick");
                                XposedEntry.log("[SUICIDE-GUARD] mClearTick reset to 0"
                                        + " (mGuardTick=" + gt + ", blocked=" + blockCount + ")");
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
            XposedEntry.log("[SUICIDE-GUARD] installed on BackgroundUnguard.onTick");
        } catch (Throwable t) {
            XposedEntry.log("[SUICIDE-GUARD] hook failed: " + t);
        }

        // 兜底：如果上面没拦住，在 System.exit(-1) 且栈含 BackgroundUnguard 时拦截
        try {
            HookUtils.hookAllMethods("java.lang.System", "exit", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        if (param.args.length == 0) return;
                        Object a0 = param.args[0];
                        if (!(a0 instanceof Integer)) return;
                        if (((Integer) a0) != -1) return;   // 只关注 -1
                        StackTraceElement[] st = Thread.currentThread().getStackTrace();
                        for (StackTraceElement e : st) {
                            String cn = e.getClassName();
                            if (cn != null && cn.contains("BackgroundUnguard")
                                    && "onTick".equals(e.getMethodName())) {
                                param.setResult(null);   // 吞掉这次 exit
                                XposedEntry.log("[SUICIDE-GUARD] intercepted exit(-1)"
                                        + " from BackgroundUnguard.onTick");
                                return;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedEntry.log("[SUICIDE-GUARD] fallback exit interceptor installed");
        } catch (Throwable t) {
            XposedEntry.log("[SUICIDE-GUARD] fallback failed: " + t);
        }
    }

    private SuicideGuard() {
    }
}
