package io.github.kida.qqnativeguard.hooks;

import android.content.Intent;
import android.os.Build;

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
 * 踢线瞬间快照。
 *
 * 背景：每次被 w21 踢下线后，logcat 都已经滚动，拿不到被踢时刻的状态。
 * 20:44 那次就是如此 —— 只知道被踢，不知道之前发生了什么。
 *
 * 做法：hook LoginActivity 的 onCreate。当 Intent action 是
 * mqq.intent.action.KICK_TO_LOGIN 时，立刻把关键状态落盘：
 *   - 触发时间
 *   - Intent 的 extras（可能含错误码）
 *   - 当前设备指纹（确认是否还处于伪造状态）
 *   - 关键 SP / MMKV 的值
 *
 * 这样下次被踢，证据一定拿得到。
 */
public final class KickSnapshot {

    private static final String OUT = "qqng_kick_snapshot.log";
    private static volatile boolean installed = false;
    private static android.content.Context ctx;

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        if (installed) return;
        installed = true;

        // LoginActivity 是踢线时跳转的目标。
        // 【2026-11-27 修正】只 hook onCreate 不够 —— 如果 LoginActivity 已在栈中，
        // 踢线走的是 setIntent/onNewIntent，onCreate 不会再次触发。
        // 实测 01:01 那次就是这样漏掉的。
        final String[] ACTS = {
            "com.tencent.mobileqq.activity.LoginActivity",
            "com.tencent.mobileqq.activity.LoginPublicFragmentActivity",
        };
        for (final String cls : ACTS) {
            try {
                HookUtils.hookAllMethods(cls, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        checkActivity(param);
                    }
                });
                HookUtils.hookAllMethods(cls, "onNewIntent", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        checkActivity(param);
                    }
                });
                // 兜底：有些实现直接 setIntent 而不回调 onNewIntent
                HookUtils.hookAllMethods(cls, "setIntent", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Object a0 = param.args.length > 0 ? param.args[0] : null;
                        if (a0 instanceof Intent) {
                            Intent it = (Intent) a0;
                            String act = it.getAction();
                            if (act != null && act.contains("KICK_TO_LOGIN")) {
                                snapshot(it);
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                XposedEntry.log("[KICK-SNAP] hook " + cls + " failed: " + t);
            }
        }

        XposedEntry.log("[KICK-SNAP] installed (onCreate + onNewIntent + setIntent)");
    }

    /** 从 Activity 当前 intent 里判断是否踢线 */
    private static void checkActivity(XC_MethodHook.MethodHookParam param) {
        try {
            Object a0 = param.args.length > 0 ? param.args[0] : null;
            // onCreate(Bundle) / onNewIntent(Intent)
            Intent it = null;
            if (a0 instanceof Intent) {
                it = (Intent) a0;
            } else if (param.thisObject instanceof android.app.Activity) {
                it = ((android.app.Activity) param.thisObject).getIntent();
            }
            if (it == null) return;
            String action = it.getAction();
            if (action == null || !action.contains("KICK_TO_LOGIN")) return;
            snapshot(it);
        } catch (Throwable ignored) {
        }
    }

    private static void snapshot(Intent it) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== KICK SNAPSHOT ==========\n");
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date());
        sb.append("time:   ").append(stamp).append('\n');
        sb.append("action: ").append(it.getAction()).append('\n');
        sb.append("data:   ").append(it.getDataString()).append('\n');

        // extras —— 错误码很可能在这里
        try {
            android.os.Bundle ex = it.getExtras();
            if (ex != null) {
                sb.append("extras:\n");
                for (String k : ex.keySet()) {
                    Object v;
                    try {
                        v = ex.get(k);
                    } catch (Throwable t) {
                        v = "<" + t.getClass().getSimpleName() + ">";
                    }
                    sb.append("  ").append(k).append(" = ").append(String.valueOf(v)).append('\n');
                }
            } else {
                sb.append("extras: (null)\n");
            }
        } catch (Throwable t) {
            sb.append("extras: error ").append(t).append('\n');
        }

        // 当前伪造值是否还在生效
        sb.append("\nfingerprint-in-process:\n");
        String[] props = {
            "ro.product.model", "ro.product.board", "ro.soc.model",
            "ro.product.first_api_level", "ro.serialno", "ro.build.fingerprint",
        };
        for (String p : props) {
            String v = "?";
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                v = String.valueOf(XposedHelpers.callStaticMethod(sp, "get", p));
            } catch (Throwable ignored) {
            }
            sb.append("  ").append(p).append(" = ").append(v).append('\n');
        }

        // 关键 SP
        sb.append("\nsp_login_auto:\n");
        try {
            android.content.Context c = appCtx();
            if (c != null) {
                android.content.SharedPreferences sp =
                        c.getSharedPreferences("sp_login_auto", 4);
                for (java.util.Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                    sb.append("  ").append(e.getKey()).append(" = ")
                      .append(String.valueOf(e.getValue())).append('\n');
                }
            }
        } catch (Throwable ignored) {
        }

        // wlogin_device.dat 现状
        sb.append("\nwlogin_device.dat: ");
        try {
            android.content.Context c = appCtx();
            if (c != null) {
                File f = new File(c.getFilesDir(), "wlogin_device.dat");
                if (f.exists()) {
                    byte[] b = new byte[(int) f.length()];
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    try {
                        int n = in.read(b);
                        StringBuilder hx = new StringBuilder();
                        for (int i = 0; i < n; i++) {
                            hx.append(String.format(Locale.US, "%02x", b[i]));
                        }
                        sb.append("exists, hex=").append(hx);
                    } finally {
                        in.close();
                    }
                } else {
                    sb.append("absent");
                }
            }
        } catch (Throwable t) {
            sb.append("error ").append(t);
        }
        sb.append('\n');
        sb.append("===================================\n");

        dump(sb.toString());
        XposedEntry.log("[KICK-SNAP] captured");
    }

    private static synchronized void dump(String text) {
        try {
            android.content.Context c = appCtx();
            if (c == null) return;
            File dir = c.getExternalFilesDir(null);
            File f = (dir != null) ? new File(dir, OUT) : new File(c.getFilesDir(), OUT);
            FileOutputStream fos = new FileOutputStream(f, true);
            try {
                fos.write(text.getBytes("UTF-8"));
            } finally {
                fos.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static android.content.Context appCtx() {
        if (ctx != null) return ctx;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object th = XposedHelpers.callStaticMethod(at, "currentActivityThread");
            if (th == null) return null;
            Object app = XposedHelpers.callMethod(th, "getApplication");
            ctx = (android.content.Context) app;
            return ctx;
        } catch (Throwable t) {
            return null;
        }
    }

    private KickSnapshot() {
    }
}
