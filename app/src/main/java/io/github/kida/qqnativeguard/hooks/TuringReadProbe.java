package io.github.kida.qqnativeguard.hooks;

import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.kida.qqnativeguard.XposedEntry;
import io.github.kida.qqnativeguard.utils.HookUtils;

/**
 * Turing 读取探针：反查 Turing 风控 SDK 到底读了哪些设备字段。
 *
 * 为什么用反查法：
 *   Turing 的 646 个类全是混淆名（Pomegranate / Plum / Blueberry / 水果系列），
 *   静态读不出语义。但它要采集设备信息，就必然调用标准 Android API。
 *   我们在这些 API 上挂探针，检查调用栈里有没有 "turing"，
 *   有就说明这个字段被 Turing 读了 —— 一次就能列出全部采集点。
 *
 * 为什么只记录不改：
 *   实测阻断 Turing 上报会让会话存活从 ~120 分钟骤降到 7 分钟，
 *   说明服务端依赖它的数据。所以必须先看清它读什么，再决定怎么对齐。
 */
public final class TuringReadProbe {

    private static final String OUT = "qqng_turing_reads.log";
    private static final Set<String> seen = new HashSet<>();
    private static volatile boolean installed = false;
    private static android.content.Context ctx;

    /**
     * 采样限流：SystemProperties.get / File.exists 这类是高频繁调用，
     * 每次遍历栈会明显拖慢 QQ。这里每 N 次才真正查一次栈。
     */
    private static final java.util.concurrent.atomic.AtomicInteger tick =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final int SAMPLE_MASK = 0x3F;   // 约 1/64 采样

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        if (installed) return;
        installed = true;

        // Build 静态字段：直接在类初始化后读不到，改用 SystemProperties
        String[] props = {
            "ro.product.model", "ro.product.brand", "ro.product.device",
            "ro.product.board", "ro.product.manufacturer", "ro.product.name",
            "ro.build.id", "ro.build.display.id", "ro.build.fingerprint",
            "ro.build.version.release", "ro.build.version.sdk",
            "ro.build.type", "ro.build.tags", "ro.build.host", "ro.build.user",
            "ro.serialno", "ro.hardware", "ro.board.platform",
            "ro.boot.serialno", "ro.boot.hardware", "ro.boot.verifiedbootstate",
            "gsm.version.baseband", "ro.product.cpu.abi", "ro.product.cpu.abilist",
        };
        for (String p : props) {
            probeSystemProperty(p);
        }

        // Settings.Secure / Global（ANDROID_ID 等）
        probeSettings("android.provider.Settings$Secure", "getString");
        probeSettings("android.provider.Settings$Global", "getString");

        // TelephonyManager（低频、高价值）
        probeAll("android.telephony.TelephonyManager", new String[]{
            "getDeviceId", "getImei", "getMeid", "getSubscriberId",
            "getSimSerialNumber", "getSimOperator", "getSimOperatorName",
            "getNetworkOperator", "getNetworkOperatorName", "getLine1Number",
            "getSimCountryIso", "getNetworkCountryIso",
        });

        // WiFi（低频、高价值）
        probeAll("android.net.wifi.WifiInfo", new String[]{
            "getMacAddress", "getSSID", "getBSSID", "getIpAddress",
        });
        probeAll("android.net.wifi.WifiManager", new String[]{
            "getConnectionInfo", "getScanResults", "getDhcpInfo",
        });

        // 包列表（中频，Turing 会查 root 管理器）
        probeAll("android.app.ApplicationPackageManager", new String[]{
            "getPackageInfo", "getInstalledPackages", "getInstalledApplications",
        });

        // 文件（高频，靠采样限流）
        probeAll("java.io.File", new String[]{
            "exists", "canRead", "isFile", "isDirectory",
        });

        // 进程 / 调试
        probeAll("java.lang.Runtime", new String[]{"exec"});
        probeAll("android.os.Debug", new String[]{"isDebuggerConnected"});

        // ---- 上报通道定位 ----
        // libturingxq.so 里没有任何域名/IP，native sendto 也零命中，
        // 说明 Turing 的网络走 Java 层（OkHttp / HttpURLConnection），
        // 最终落到 Socket，而不是 raw send。
        probeSocketChannel();
        probeHttpTargets();

        XposedEntry.log("[TURING-PROBE] installed");
    }

    /** Socket 建立：记录目标地址（按调用栈过滤 turing） */
    private static void probeSocketChannel() {
        try {
            HookUtils.hookAllMethods("java.net.Socket", "connect",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Object a0 = param.args.length > 0 ? param.args[0] : null;
                        report("Socket.connect", String.valueOf(a0));
                    }
                });
        } catch (Throwable ignored) {
        }
        // InetSocketAddress 构造（很多 HTTP 栈会先建这个）
        try {
            HookUtils.hookAllMethods("java.net.InetSocketAddress", "<init>",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length < 2) return;
                        Object h = param.args[0];
                        Object p = param.args[1];
                        if (h instanceof String) {
                            report("InetSocketAddress", h + ":" + p);
                        }
                    }
                });
        } catch (Throwable ignored) {
        }
    }

    /** HTTP 目标：OkHttp 与 HttpURLConnection 两条路径 */
    private static void probeHttpTargets() {
        // HttpURLConnection.openConnection
        try {
            HookUtils.hookAllMethods("java.net.URL", "openConnection",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Object t = param.thisObject;
                        report("URL.openConnection", t == null ? "?" : String.valueOf(t));
                    }
                });
        } catch (Throwable ignored) {
        }
        // OkHttp Request 构造（拿到 URL）
        try {
            HookUtils.hookAllMethods("okhttp3.Request$Builder", "url",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Object a0 = param.args.length > 0 ? param.args[0] : null;
                        report("OkHttp.url", String.valueOf(a0));
                    }
                });
        } catch (Throwable ignored) {
        }
        // 底层写（HTTPS 是密文，但能确认通道存在）
        try {
            HookUtils.hookAllMethods("java.net.SocketOutputStream", "socketWrite0",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        int len = -1;
                        if (param.args.length >= 4 && param.args[3] instanceof Integer) {
                            len = (Integer) param.args[3];
                        }
                        report("socketWrite0", "len=" + len);
                    }
                });
        } catch (Throwable ignored) {
        }
    }

    /** 探针：SystemProperties.get(key) */
    private static void probeSystemProperty(final String key) {
        try {
            HookUtils.hookAllMethods("android.os.SystemProperties", "get",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length == 0) return;
                        Object a0 = param.args[0];
                        if (!(a0 instanceof String)) return;
                        String k = (String) a0;
                        if (k == null || !k.equals(key)) return;
                        report("SystemProperties.get", key);
                    }
                });
        } catch (Throwable ignored) {
        }
    }

    /** 探针：Settings.Secure/Global.getString(resolver, name) */
    private static void probeSettings(String cls, String mth) {
        try {
            HookUtils.hookAllMethods(cls, mth, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length < 2) return;
                    Object a1 = param.args[1];
                    if (a1 instanceof String) {
                        report(cls.substring(cls.lastIndexOf('$') + 1) + ".getString",
                               (String) a1);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 探针：对一批方法统一挂 */
    private static void probeAll(String cls, String[] methods) {
        for (final String m : methods) {
            try {
                HookUtils.hookAllMethods(cls, m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        report(cls.substring(cls.lastIndexOf('.') + 1), m);
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 检查调用栈里有没有 turing / turingfd / turingcam。
     * 命中则记录一次（去重，避免刷爆）。
     */
    private static void report(String api, String detail) {
        // 采样限流：高频 API 只有 1/64 的调用会进入真正的栈检查
        if ((tick.incrementAndGet() & SAMPLE_MASK) != 0) return;
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            String hit = null;
            String hitLine = null;
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (cn == null) continue;
                if (cn.contains("de.robv.android.xposed")
                        || cn.contains("io.github.kida.qqnativeguard")) continue;
                String low = cn.toLowerCase(Locale.US);
                if (low.contains("turing")) {
                    hit = cn;
                    hitLine = e.toString();
                    break;
                }
            }
            if (hit == null) return;

            String key = api + "|" + detail + "|" + hit;
            synchronized (seen) {
                if (seen.contains(key)) return;
                seen.add(key);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== TURING READ ===\n");
            sb.append("api:    ").append(api).append('(').append(detail).append(")\n");
            sb.append("caller: ").append(hitLine).append('\n');
            // 再往下几层，便于定位到具体方法
            sb.append("stack:\n");
            int n = 0;
            for (StackTraceElement e : st) {
                String s = e.toString();
                if (s.contains("de.robv.android.xposed")
                        || s.contains("io.github.kida.qqnativeguard")) continue;
                sb.append("  at ").append(s).append('\n');
                if (++n >= 12) break;
            }
            dump(sb.toString());
            XposedEntry.log("[TURING-READ] " + api + "(" + detail + ") <- " + hit);
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void dump(String text) {
        android.content.Context c = ctx != null ? ctx : appContext();
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

    private static android.content.Context appContext() {
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

    /** Turing 的类名特征，供其它模块复用 */
    public static boolean isTuringFrame(StackTraceElement[] st) {
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            if (cn != null && cn.toLowerCase(Locale.US).contains("turing")) return true;
        }
        return false;
    }

    private TuringReadProbe() {
    }
}