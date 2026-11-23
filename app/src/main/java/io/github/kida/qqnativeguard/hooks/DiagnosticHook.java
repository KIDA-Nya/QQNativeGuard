package io.github.kida.qqnativeguard.hooks;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.media.MediaDrm;
import android.os.Build;
import android.os.Bundle;

import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import io.github.kida.qqnativeguard.XposedEntry;
import io.github.kida.qqnativeguard.config.HookConfig;
import io.github.kida.qqnativeguard.utils.HookUtils;

/**
 * 诊断探针（PROBE=true 时启用，只读不拦）：
 *
 * 1. 记录 QQ 启动 Activity 的 Intent（重点：mqq.intent.action.KICK_TO_LOGIN 的 extras）
 * 2. 记录 MSFServlet 收到的推送（服务器下发踢人/风控指令时走这里）
 * 3. 记录 ChannelProxyExt / MsfCore 发送的风控相关 SSO 命令
 * 4. 记录 MediaDrm(Widevine deviceUniqueId) 读取 —— 应用可读的硬件级设备 ID
 * 5. 记录 wlogin 设备指纹构建调用
 *
 * 目的：确定“设备存在风险/被踢”到底由客户端哪个上报触发，还是纯服务端标记。
 */
public class DiagnosticHook {

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!HookConfig.PROBE) return;

        XposedEntry.log("Initializing Diagnostic Probe");
        logBuildIdentity();

        hookStartActivity();
        hookMSFServlet(lpparam);
        hookChannelSend();
        hookMsfSend();
        hookLoginResult();
        hookLoginBusiness();
        hookMediaDrm();
        hookWloginDeviceInfo();
        hookKickHandler(lpparam);
        hookQimeiAndOaid();
        hookTuringRisk();
        // hookTuringManagers();
        // hookTuringClassLoad();
        hookOsCalls();
    }

    // ------------------------------------------------------- 登录结果（拒绝原因）
    //
    // MSF 的响应对象 FromServiceMsg 携带 resultCode 与业务失败码。登录被服务端
    // 拒绝时 QQ 只弹一个没有正文的"登录失败"框，真正的码在这里：
    //   -10003 / -10004 / -10006 = wtlogin 层拒绝（token/密码/验证）
    //   1000x                    = 服务端安全策略拒绝
    // 只读记录，不做拦截。

    private static void hookLoginResult() {
        final String fsmName = "com.tencent.qphone.base.remote.FromServiceMsg";
        try {
            Class<?> fsm = XposedHelpers.findClass(fsmName, HookConfig.getClassLoader());

            XposedBridge.hookAllConstructors(fsm, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    StringBuilder sb = new StringBuilder();
                    for (Object a : param.args) {
                        if (a instanceof String) {
                            String s = (String) a;
                            if (s.contains("login") || s.contains("Login")
                                || s.contains("wtlogin") || s.contains("wlogin")) {
                                sb.append("cmd=").append(s).append(' ');
                            }
                        }
                    }
                    if (sb.length() > 0) {
                        XposedEntry.log("[PROBE] FromServiceMsg ctor " + sb);
                    }
                }
            });

            XposedBridge.hookAllMethods(fsm, "setBusinessFail", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    StringBuilder sb = new StringBuilder();
                    sb.append("FromServiceMsg.setBusinessFail");
                    for (Object a : param.args) {
                        sb.append(' ').append(stringify(a));
                    }
                    XposedEntry.log("[PROBE] " + limit(sb.toString(), 800));
                }
            });
        } catch (Throwable t) {
            XposedEntry.log("[PROBE] login result hook failed: " + t.getMessage());
        }

        // NTLogin reports its verdict through resultCode / businessFailCode rather
        // than setBusinessFail. Also: every *failed* response is recorded regardless
        // of command, because the session termination (ACCOUNT_EXPIRED / w21) is
        // preceded by whichever refresh/check the server refuses. 1000 is success.
        try {
            final Class<?> fsm = XposedHelpers.findClass(fsmName, HookConfig.getClassLoader());
            for (final String getter : new String[]{
                    "getResultCode", "getBusinessFailCode", "getBusinessFailMsg"}) {
                XposedBridge.hookAllMethods(fsm, getter, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object self = param.thisObject;
                        if (self == null) return;
                        Object cmdObj;
                        try {
                            cmdObj = XposedHelpers.callMethod(self, "getServiceCmd");
                        } catch (Throwable t) {
                            return;
                        }
                        if (!(cmdObj instanceof String)) return;
                        String cmd = (String) cmdObj;
                        Object res = param.getResult();

                        boolean isLoginCmd = cmd.toLowerCase(Locale.US).contains("login");
                        boolean failed = false;
                        if (res instanceof Integer) {
                            int code = (Integer) res;
                            if (code != 1000 && code != 0) failed = true;
                        } else if (res instanceof String) {
                            failed = !((String) res).isEmpty();
                        }

                        if (isLoginCmd || failed) {
                            XposedEntry.log("[PROBE] FromServiceMsg." + getter
                                + (failed ? " FAIL" : "") + " cmd=" + cmd
                                + " -> " + stringify(res));
                        }
                        if (isLoginCmd || failed) {
                            dumpLoginBodyIfNeeded(self, cmd);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedEntry.log("[PROBE] resultCode hook failed: " + t.getMessage());
        }

        // Login failure dialog: the class name drifts between QQ versions, so try the
        // known candidates and record whatever ints the dialog is built with.
        for (String clsName : new String[]{
                "com.tencent.mobileqq.activity.LoginFailedDialogWindow",
                "com.tencent.mobileqq.login.LoginFailedDialog",
                "com.tencent.mobileqq.activity.LoginFailedDialog"}) {
            Class<?> dialogCls;
            try {
                dialogCls = XposedHelpers.findClass(clsName, HookConfig.getClassLoader());
            } catch (Throwable ignored) {
                continue;
            }
            XposedBridge.hookAllConstructors(dialogCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    StringBuilder sb = new StringBuilder("LoginFailedDialog ctor");
                    for (Object a : param.args) {
                        if (a instanceof Integer || a instanceof Long || a instanceof String) {
                            sb.append(' ').append(stringify(a));
                        }
                    }
                    XposedEntry.log("[PROBE] " + limit(sb.toString(), 400));
                }
            });
            XposedEntry.log("[PROBE] hooked " + clsName);
        }

        // wtlogin 层的回调：登录响应最终在这里落地
        for (String cls : new String[]{
                "oicq.wlogin_sdk.request.WtloginHelper",
                "oicq.wlogin_sdk.request.w"}) {
            try {
                XposedHelpers.findClass(cls, HookConfig.getClassLoader());
            } catch (Throwable ignored) {
                continue;
            }
            HookUtils.hookAllMethods(cls, "OnLoginResp", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    StringBuilder sb = new StringBuilder("wtlogin.OnLoginResp");
                    for (Object a : param.args) {
                        sb.append(' ').append(stringify(a));
                    }
                    XposedEntry.log("[PROBE] " + limit(sb.toString(), 800));
                }
            });
        }
    }

    // ---------------------------------------------- 登录业务响应（trpc / LoginRsp）
    //
    // MSF 层已经确认 resultCode=1000，也就是说这一层没有拒绝。真正的"登录失败"
    // 来自 trpc 业务响应体（LoginRsp 的 errCode/errMsg）。先记录哪些类被加载，
    // 再看能不能直接钩到响应对象的读取点。

    private static final java.util.Set<String> CLS_SEEN =
        java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    // 每个响应对象只 dump 一次响应体
    private static final java.util.Set<Integer> BODY_DUMPED =
        java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());

    /**
     * trpc 业务响应的 protobuf 体里，string 字段是明文。登录被业务层拒绝时，
     * 错误文案（如"密码错误"/"登录环境异常"）就在这些字节里，不需要知道类名。
     */
    private static void dumpLoginBodyIfNeeded(Object fsm, String cmd) {
        int key = System.identityHashCode(fsm);
        if (!BODY_DUMPED.add(key)) return;
        if (BODY_DUMPED.size() > 512) BODY_DUMPED.clear();
        byte[] buf;
        try {
            Object b = XposedHelpers.callMethod(fsm, "getWupBuffer");
            if (!(b instanceof byte[])) return;
            buf = (byte[]) b;
        } catch (Throwable t) {
            return;
        }
        if (buf.length == 0) return;
        XposedEntry.log("[PROBE] body cmd=" + cmd + " len=" + buf.length);
        // Dump the whole buffer in 200-byte chunks: the login verdict (risk level,
        // errCode, retry hints) may sit anywhere in the payload, and the first
        // 160 bytes alone were not enough to tell encrypted from structured data.
        for (int off = 0; off < buf.length; off += 200) {
            int end = Math.min(off + 200, buf.length);
            StringBuilder hex = new StringBuilder();
            for (int i = off; i < end; i++) {
                hex.append(String.format(Locale.US, "%02x", buf[i]));
            }
            XposedEntry.log("[PROBE] body@" + off + " " + hex);
        }
        StringBuilder ascii = new StringBuilder();
        for (byte value : buf) {
            char ch = (char) (value & 0xFF);
            ascii.append(ch >= 0x20 && ch < 0x7F ? ch : '.');
        }
        XposedEntry.log("[PROBE] body ascii=" + limit(ascii.toString(), 700));
        // UTF-8 明文片段（中文错误文案）
        try {
            String utf = new String(buf, "UTF-8");
            StringBuilder cn = new StringBuilder();
            for (int i = 0; i < utf.length(); i++) {
                char ch = utf.charAt(i);
                if (ch >= 0x4E00 && ch <= 0x9FFF) cn.append(ch);
                else if (cn.length() > 0 && cn.charAt(cn.length() - 1) != ' ') cn.append(' ');
            }
            if (cn.length() > 0) {
                XposedEntry.log("[PROBE] body cjk=" + limit(cn.toString().trim(), 400));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void hookLoginBusiness() {
        // 1) 记录 ecdh/login 相关的类名，确定真实响应类型
        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length == 0 || !(param.args[0] instanceof String)) return;
                    String name = (String) param.args[0];
                    String lower = name.toLowerCase(Locale.US);
                    if (!(lower.contains("ecdh") || lower.contains("loginrsp")
                        || lower.contains("ntlogin") || lower.contains("wtlogin"))) {
                        return;
                    }
                    if (CLS_SEEN.size() < 200 && CLS_SEEN.add(name)) {
                        XposedEntry.log("[PROBE] loadClass " + name);
                    }
                }
            });
        } catch (Throwable t) {
            XposedEntry.log("[PROBE] loadClass hook failed: " + t.getMessage());
        }

        // 2) 若响应类已存在，直接读它的错误码字段
        for (final String clsName : new String[]{
                "trpc.login.ecdh.LoginRsp",
                "trpc.login.ecdh.EcdhService",
                "com.tencent.mobileqq.wtlogin.EcdhService"}) {
            Class<?> c;
            try {
                c = XposedHelpers.findClass(clsName, HookConfig.getClassLoader());
            } catch (Throwable ignored) {
                continue;
            }
            XposedEntry.log("[PROBE] found class " + clsName);
            for (final String getter : new String[]{
                    "getErrCode", "getErrMsg", "getErrType", "getErrInfo", "getRet"}) {
                try {
                    XposedBridge.hookAllMethods(c, getter, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            XposedEntry.log("[PROBE] " + clsName + "." + getter
                                + " -> " + stringify(param.getResult()));
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void hookKsRiskPlaceholder() {
        // reserved
    }

    private static final java.util.Set<String> OS_SEEN =
        java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static void hookOsCalls() {
        // Qimei 的 native 会通过 JNI 调 android.system.Os.stat/statvfs
        try {
            Class<?> os = XposedHelpers.findClass("android.system.Os",
                HookConfig.getClassLoader());
            for (String method : new String[]{"stat", "lstat", "statvfs", "readlink"}) {
                XposedBridge.hookAllMethods(os, method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length == 0 || !(param.args[0] instanceof String)) return;
                        String path = (String) param.args[0];
                        if (OS_SEEN.size() < 300 && OS_SEEN.add(path)) {
                            XposedEntry.log("[PROBE] Os." + method + ": " + path);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedEntry.log("[PROBE] Os hook failed: " + t.getMessage());
        }

        // java.lang.System.getProperty（Qimei 可能读 os.version 等）
        try {
            XposedBridge.hookAllMethods(System.class, "getProperty", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length == 0 || !(param.args[0] instanceof String)) return;
                    String key = (String) param.args[0];
                    if ((key.contains("os.") || key.contains("java.") || key.contains("user."))
                        && OS_SEEN.size() < 300 && OS_SEEN.add("prop:" + key)) {
                        XposedEntry.log("[PROBE] System.getProperty(" + key + ") -> "
                            + param.getResult());
                    }
                }
            });
        } catch (Throwable t) {
            XposedEntry.log("[PROBE] System.getProperty hook failed: " + t.getMessage());
        }
    }

    // ------------------------------------------------------ TuringFD 风险结果

    private static void hookTuringRisk() {
        try {
            Class<?> aa = XposedHelpers.findClass(
                "com.tencent.turingfd.sdk.xq.TNative$aa", HookConfig.getClassLoader());
            String[] methods = {
                "g209311_B9DBF55887B6AEA3",  // riskCheckWup：key0=风险码，key1=上报数据
                "h209311_B9DBF55887B6AEA3",
                "m209311_B9DBF55887B6AEA3",
                "n209311_B9DBF55887B6AEA3",  // DeviceTokenV3
            };
            for (String m : methods) {
                XposedBridge.hookAllMethods(aa, m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedEntry.log("[PROBE] Turing." + m + " -> "
                            + describeSparse(param.getResult()));
                    }
                });
            }
            XposedEntry.log("[PROBE] Hooked TuringFD native risk methods");
        } catch (Throwable t) {
            XposedEntry.log("[PROBE] TuringFD risk hook failed: " + t.getMessage());
        }
    }

    // ------------------------------------------------- 包内探测逻辑（运行时枚举）
    //
    // Turing 的采集数据不是自己读的：ITuringDeviceInfoProvider / ITuringPkgProvider
    // 由 QQ 侧实现后再喂给它。所以直接 hook QQ 的 turingfd 包，看它到底提供了什么。
    private static final java.util.Set<String> TURING_SEEN =
        java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static void hookTuringManagers() {
        String[] classes = {
            "com.tencent.mobileqq.turingfd.TuringSdkManager",
            "com.tencent.mobileqq.turingfd.a", "com.tencent.mobileqq.turingfd.b",
            "com.tencent.mobileqq.turingfd.c", "com.tencent.mobileqq.turingfd.d",
            "com.tencent.mobileqq.turingfd.e", "com.tencent.mobileqq.turingfd.f",
            "com.tencent.mobileqq.turingfd.g", "com.tencent.mobileqq.turingfd.h",
            "com.tencent.mobileqq.turingfd.i",
        };
        for (final String clsName : classes) {
            Class<?> k;
            try {
                k = XposedHelpers.findClass(clsName, HookConfig.getClassLoader());
            } catch (Throwable t) {
                continue;
            }
            int hooked = 0;
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                final String mName = m.getName();
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            String key = clsName + "#" + mName;
                            if (TURING_SEEN.size() < 400 && TURING_SEEN.add(key)) {
                                XposedEntry.log("[PROBE] TuringMgr " + clsName.substring(clsName.lastIndexOf('.') + 1)
                                    + "." + mName + " -> " + stringify(param.getResult()));
                            }
                        }
                    });
                    hooked++;
                } catch (Throwable ignored) {
                }
            }
            if (hooked > 0) {
                XposedEntry.log("[PROBE] hooked TuringMgr " + clsName + " (" + hooked + " methods)");
            }
        }
    }

    // 记录 Turing 相关类的加载：从「谁实现了 Provider 接口」倒推真正的采集入口
    private static final java.util.Set<String> TCLS =
        java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static void hookTuringClassLoad() {
        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length == 0 || !(param.args[0] instanceof String)) return;
                    String n = (String) param.args[0];
                    String low = n.toLowerCase(Locale.US);
                    if (!(low.contains("turing") || low.contains("tfd.sdk")
                        || low.contains("deviceinfoprovider") || low.contains("pkgprovider"))) {
                        return;
                    }
                    if (TCLS.size() < 400 && TCLS.add(n)) {
                        XposedEntry.log("[PROBE] TuringClass loaded: " + n);
                    }
                }
            });
            XposedEntry.log("[PROBE] Turing class-load watcher armed");
        } catch (Throwable t) {
            XposedEntry.log("[PROBE] Turing class-load hook failed: " + t.getMessage());
        }
    }

    private static String describeSparse(Object result) {
        if (result == null) return "null";
        if (!(result instanceof android.util.SparseArray)) {
            if (result instanceof byte[]) return "byte[" + ((byte[]) result).length + "]";
            return String.valueOf(result);
        }
        android.util.SparseArray<?> s = (android.util.SparseArray<?>) result;
        StringBuilder sb = new StringBuilder("SparseArray{");
        for (int i = 0; i < s.size(); i++) {
            int key = s.keyAt(i);
            Object v = s.valueAt(i);
            if (i > 0) sb.append(", ");
            sb.append(key).append('=');
            if (v instanceof byte[]) {
                sb.append("byte[").append(((byte[]) v).length).append(']');
            } else {
                sb.append(limit(String.valueOf(v), 120));
            }
        }
        sb.append('}');
        return sb.toString();
    }

    // ------------------------------------------------------------ QIMEI / OAID

    private static void hookQimeiAndOaid() {
        // Qimei 对象
        try {
            Class<?> qimei = XposedHelpers.findClass(
                "com.tencent.qimei.sdk.Qimei", HookConfig.getClassLoader());
            for (String m : new String[]{"getQimei16", "getQimei36"}) {
                XposedBridge.hookAllMethods(qimei, m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedEntry.log("[PROBE] Qimei." + m + " -> " + param.getResult());
                    }
                });
            }
        } catch (Throwable t) {
            XposedEntry.log("[PROBE] Qimei hook failed: " + t.getMessage());
        }

        // IMSFQimei 接口实现
        try {
            Class<?> iface = XposedHelpers.findClass(
                "com.tencent.qphone.base.util.IMSFQimei", HookConfig.getClassLoader());
            XposedBridge.hookAllMethods(iface, "getQimei36", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    XposedEntry.log("[PROBE] IMSFQimei.getQimei36 -> " + param.getResult());
                }
            });
        } catch (Throwable t) {
            XposedEntry.log("[PROBE] IMSFQimei hook failed: " + t.getMessage());
        }

        // OAID
        try {
            Class<?> oaid = XposedHelpers.findClass(
                "com.tencent.qmethod.pandoraex.monitor.OaidMonitor", HookConfig.getClassLoader());
            for (java.lang.reflect.Method m : oaid.getDeclaredMethods()) {
                final String name = m.getName();
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedEntry.log("[PROBE] OaidMonitor." + name + " -> " + param.getResult());
                    }
                });
            }
            XposedEntry.log("[PROBE] Hooked OaidMonitor (" + oaid.getDeclaredMethods().length + " methods)");
        } catch (Throwable t) {
            XposedEntry.log("[PROBE] OaidMonitor hook failed: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------- Kick 处理

    private static void hookKickHandler(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> c = XposedHelpers.findClass(
                "mqq.app.MainService$MyErrorHandler", lpparam.classLoader);

            XposedBridge.hookAllMethods(c, "onKicked", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object from = param.args.length > 1 ? param.args[1] : null;
                    Object sameDevice = param.args.length > 2 ? param.args[2] : null;
                    XposedEntry.log("[PROBE] onKicked isSameDevice=" + sameDevice
                        + " from=" + describeMsg(from));
                }
            });

            XposedBridge.hookAllMethods(c, "onInterceptKicked", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object from = param.args.length > 1 ? param.args[1] : null;
                    XposedEntry.log("[PROBE] onInterceptKicked bool1="
                        + (param.args.length > 2 ? param.args[2] : null)
                        + " bool2=" + (param.args.length > 3 ? param.args[3] : null)
                        + " from=" + describeMsg(from));
                }
            });

            XposedBridge.hookAllMethods(c, "popupNotification", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    StringBuilder sb = new StringBuilder("[PROBE] popupNotification");
                    for (Object arg : param.args) {
                        sb.append(" | ").append(arg instanceof String ? arg : String.valueOf(arg));
                    }
                    XposedEntry.log(limit(sb.toString(), 3000));
                }
            });

            XposedEntry.log("[PROBE] Hooked MainService$MyErrorHandler kick methods");
        } catch (Throwable t) {
            XposedEntry.logError("[PROBE] Kick handler hook failed", t);
        }
    }

    private static String describeMsg(Object msg) {
        if (msg == null) return "null";
        StringBuilder sb = new StringBuilder(msg.getClass().getSimpleName());
        try {
            Object cmd = XposedHelpers.callMethod(msg, "getServiceCmd");
            sb.append("{cmd=").append(cmd);
            Object rc = XposedHelpers.callMethod(msg, "getResultCode");
            sb.append(",rc=").append(rc);
            Object uin = XposedHelpers.callMethod(msg, "getUin");
            sb.append(",uin=").append(uin);
            Object attrs = XposedHelpers.callMethod(msg, "getAttributes");
            if (attrs instanceof java.util.Map) {
                for (Object e : ((java.util.Map<?, ?>) attrs).entrySet()) {
                    java.util.Map.Entry<?, ?> en = (java.util.Map.Entry<?, ?>) e;
                    sb.append(", ").append(en.getKey()).append('=').append(stringify(en.getValue()));
                }
            }
        } catch (Throwable t) {
            sb.append("{err=").append(t.getClass().getSimpleName()).append('}');
        }
        sb.append('}');
        return limit(sb.toString(), 2000);
    }

    // ---------------------------------------------------------------- Activity

    private static void hookStartActivity() {
        try {
            XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivity",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        for (Object arg : param.args) {
                            if (arg instanceof Intent) {
                                Intent intent = (Intent) arg;
                                if (isKickOrLoginIntent(intent)) {
                                    dumpIntent("startActivity", intent);
                                }
                                break;
                            }
                        }
                    }
                });
            XposedEntry.log("[PROBE] Hooked Instrumentation.execStartActivity");
        } catch (Throwable t) {
            XposedEntry.logError("[PROBE] execStartActivity hook failed", t);
        }
    }

    private static void logBuildIdentity() {
        XposedEntry.log("[PROBE] Build identity: FINGERPRINT=" + Build.FINGERPRINT);
        XposedEntry.log("[PROBE] Build identity: DISPLAY=" + Build.DISPLAY
            + " HOST=" + Build.HOST + " USER=" + Build.USER
            + " TAGS=" + Build.TAGS + " TYPE=" + Build.TYPE);
        XposedEntry.log("[PROBE] Build identity: MANUFACTURER=" + Build.MANUFACTURER
            + " BRAND=" + Build.BRAND + " MODEL=" + Build.MODEL
            + " DEVICE=" + Build.DEVICE + " PRODUCT=" + Build.PRODUCT
            + " BOOTLOADER=" + Build.BOOTLOADER + " HARDWARE=" + Build.HARDWARE);
        XposedEntry.log("[PROBE] Build identity: RELEASE=" + Build.VERSION.RELEASE
            + " SDK=" + Build.VERSION.SDK_INT
            + " INCREMENTAL=" + Build.VERSION.INCREMENTAL
            + " SECURITY_PATCH=" + Build.VERSION.SECURITY_PATCH);
    }

    private static boolean isKickOrLoginIntent(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        String a = action == null ? "" : action.toLowerCase(Locale.US);
        if (a.contains("kick") || a.contains("login") || a.contains("account")
            || a.contains("offline") || a.contains("logout")) {
            return true;
        }
        ComponentName cn = intent.getComponent();
        return cn != null && cn.getClassName() != null
            && cn.getClassName().toLowerCase(Locale.US).contains("login");
    }

    // ------------------------------------------------------------- MSF Servlet

    private static void hookMSFServlet(XC_LoadPackage.LoadPackageParam lpparam) {
        HookUtils.hookAllMethods("mqq.app.MSFServlet", "onReceive",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    for (Object arg : param.args) {
                        if (arg instanceof Intent) {
                            dumpIntent("MSFServlet.onReceive", (Intent) arg);
                            break;
                        }
                    }
                }
            });
    }

    // -------------------------------------------------------------- SSO 发送

    private static void hookChannelSend() {
        for (String method : new String[]{"sendMessageInner", "send"}) {
            HookUtils.hookAllMethods("com.tencent.mobileqq.channel.ChannelProxyExt", method,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length == 0 || !(param.args[0] instanceof String)) return;
                        String cmd = (String) param.args[0];
                        if (isRiskCommand(cmd)) {
                            XposedEntry.log("[PROBE] SSO send cmd=" + cmd
                                + " args=" + param.args.length
                                + " payloadLen=" + payloadLength(param.args));
                            scanPayload(cmd, param.args);
                        }
                    }
                });
        }
    }

    private static final java.util.Set<String> SENT_SEEN =
        java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static void hookMsfSend() {
        String[] classes = {
            "com.tencent.qphone.base.remote.MsfCore",
            "com.tencent.mobileqq.msf.core.MsfCore"
        };
        for (String className : classes) {
            HookUtils.hookAllMethods(className, "sendSsoMsg",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        // sendSsoMsg 收到的 payload 仍是业务层 protobuf 明文，加密在
                        // MSF native 的 WUP 层才发生 —— 这是唯一能看到"上报了什么
                        // 设备特征"的位置。
                        String cmd = null;
                        Object msgObj = null;
                        for (Object a : param.args) {
                            if (a == null) continue;
                            if (cmd == null && a instanceof String) cmd = (String) a;
                            if (a.getClass().getName().endsWith("ToServiceMsg")) msgObj = a;
                        }
                        byte[] payload = null;
                        if (msgObj != null) {
                            try {
                                Object b = XposedHelpers.callMethod(msgObj, "getWupBuffer");
                                if (b instanceof byte[]) payload = (byte[]) b;
                            } catch (Throwable ignored) {
                            }
                            if (cmd == null) {
                                try {
                                    Object c = XposedHelpers.callMethod(msgObj, "getServiceCmd");
                                    if (c instanceof String) cmd = (String) c;
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                        if (payload == null) {
                            for (Object a : param.args) {
                                if (a instanceof byte[]) {
                                    payload = (byte[]) a;
                                    break;
                                }
                            }
                        }
                        if (cmd == null) return;
                        final int len = payload == null ? 0 : payload.length;
                        String key = cmd + "/" + len;
                        if (SENT_SEEN.size() < 500 && SENT_SEEN.add(key)) {
                            XposedEntry.log("[PROBE] SEND cmd=" + cmd + " len=" + len);
                            if (payload != null && len > 0 && isDumpWorthy(cmd)) {
                                dumpSentPayload(cmd, payload);
                            }
                        }
                    }
                });
        }
    }

    private static boolean isDumpWorthy(String cmd) {
        String c = cmd.toLowerCase(Locale.US);
        String[] kw = {
            "device", "report", "risk", "turing", "qimei", "0xd79", "secure",
            "sec_", "auth", "login", "sig", "oaid", "androidid", "imei", "token"
        };
        for (String k : kw) {
            if (c.contains(k)) return true;
        }
        return false;
    }

    /** 打印发送 payload 的明文：hex 分段 + 可打印串 + CJK 片段 */
    private static void dumpSentPayload(String cmd, byte[] buf) {
        int limit = Math.min(buf.length, 1400);
        for (int off = 0; off < limit; off += 200) {
            int end = Math.min(off + 200, limit);
            StringBuilder hex = new StringBuilder();
            for (int i = off; i < end; i++) {
                hex.append(String.format(Locale.US, "%02x", buf[i]));
            }
            XposedEntry.log("[PROBE] SEND@" + off + " " + hex);
        }
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            char ch = (char) (buf[i] & 0xFF);
            ascii.append(ch >= 0x20 && ch < 0x7F ? ch : '.');
        }
        XposedEntry.log("[PROBE] SEND ascii=" + ascii);
        try {
            String utf = new String(buf, 0, limit, "UTF-8");
            StringBuilder cn = new StringBuilder();
            for (int i = 0; i < utf.length(); i++) {
                char ch = utf.charAt(i);
                if (ch >= 0x4E00 && ch <= 0x9FFF) cn.append(ch);
            }
            if (cn.length() > 0) {
                XposedEntry.log("[PROBE] SEND cjk=" + limit0(cn.toString(), 300));
            }
        } catch (Throwable ignored) {
        }
    }

    private static String limit0(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private static boolean isRiskCommand(String cmd) {
        if (cmd == null) return false;
        String c = cmd.toLowerCase(Locale.US);
        String[] kw = {
            "risk", "turing", "qimei", "device_lock", "device_token", "mobile_security",
            "o3.report", "0xd79", "kick", "punish", "lock_punish", "sec_check",
            "secevent", "login_safe", "safe_login",
            // session keep-alive path: the server refuses one of these right before
            // ACCOUNT_EXPIRED, so they must be visible in the probe log.
            "trpc.login", "wtlogin", "refresh", "token", "ticket", "a2",
            "online", "session", "verify", "heartbeat", "timestamp", "guide"
        };
        for (String k : kw) {
            if (c.contains(k)) return true;
        }
        return false;
    }

    private static int payloadLength(Object[] args) {
        int total = 0;
        for (int i = 1; i < args.length; i++) {
            if (args[i] instanceof byte[]) {
                total += ((byte[]) args[i]).length;
            }
        }
        return total;
    }

    private static final String[] PAYLOAD_KEYWORDS = {
        "magisk", "lsposed", "xposed", "frida", "kernelsu", "verifiedboot",
        "orange", "qimei", "imei", "android_id", "root", "hook", "turing",
        "emulator", "virtual", "debug", "risk", "proxy", "vpn"
    };

    /** 扫描上报包内容（可能是明文 protobuf，也可能 gzip 压缩） */
    private static void scanPayload(String cmd, Object[] args) {
        for (int i = 1; i < args.length; i++) {
            if (!(args[i] instanceof byte[])) continue;
            byte[] raw = (byte[]) args[i];
            if (raw.length == 0) continue;

            byte[] data = raw;
            String note = "";
            if (raw.length > 2 && (raw[0] & 0xff) == 0x1f && (raw[1] & 0xff) == 0x8b) {
                try {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    java.util.zip.GZIPInputStream in =
                        new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(raw));
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0 && bos.size() < 1024 * 1024) {
                        bos.write(buf, 0, n);
                    }
                    in.close();
                    data = bos.toByteArray();
                    note = " gzip->" + data.length;
                } catch (Throwable ignored) {
                }
            }

            String ascii = new String(data, java.nio.charset.StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.US);
            StringBuilder hits = new StringBuilder();
            for (String k : PAYLOAD_KEYWORDS) {
                if (ascii.contains(k)) hits.append(k).append(',');
            }
            XposedEntry.log("[PROBE] payload cmd=" + cmd + " len=" + raw.length + note
                + " head=" + hexHead(data, 24) + " hits=" + hits);
            dumpPayload(cmd, raw);
        }
    }

    /** 把上报包原样落盘，便于离线解析 protobuf */
    private static void dumpPayload(String cmd, byte[] raw) {
        if (raw.length > 2 * 1024 * 1024) return;
        try {
            java.io.File dir = new java.io.File(
                "/storage/emulated/0/Android/data/com.tencent.mobileqq/qqbypass_payload");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            String safe = cmd.replaceAll("[^A-Za-z0-9._-]", "_");
            java.io.File out = new java.io.File(dir,
                safe + "_" + System.currentTimeMillis() + ".bin");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(raw);
            }
            XposedEntry.log("[PROBE] payload dumped: " + out.getAbsolutePath());
        } catch (Throwable t) {
            XposedEntry.log("[PROBE] payload dump failed: " + t.getMessage());
        }
    }

    // ---------------------------------------------------------------- MediaDrm

    private static void hookMediaDrm() {
        try {
            XposedBridge.hookAllMethods(MediaDrm.class, "getPropertyByteArray",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object name = param.args.length > 0 ? param.args[0] : null;
                        Object result = param.getResult();
                        if (result instanceof byte[]) {
                            byte[] b = (byte[]) result;
                            XposedEntry.log("[PROBE] MediaDrm.getPropertyByteArray(" + name
                                + ") len=" + b.length + " head=" + hexHead(b, 8));
                        }
                    }
                });
            XposedBridge.hookAllMethods(MediaDrm.class, "getPropertyString",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedEntry.log("[PROBE] MediaDrm.getPropertyString("
                            + (param.args.length > 0 ? param.args[0] : null) + ") -> "
                            + param.getResult());
                    }
                });
            XposedEntry.log("[PROBE] Hooked MediaDrm property reads");
        } catch (Throwable t) {
            XposedEntry.logError("[PROBE] MediaDrm hook failed", t);
        }
    }

    // ----------------------------------------------------------------- wlogin

    private static void hookWloginDeviceInfo() {
        HookUtils.hookAllMethods("oicq.wlogin_sdk.request.w", "h",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    StringBuilder sb = new StringBuilder();
                    for (Object arg : param.args) {
                        if (sb.length() > 0) sb.append(',');
                        sb.append(arg == null ? "null" : arg.getClass().getSimpleName());
                    }
                    XposedEntry.log("[PROBE] wlogin device-info h() args=[" + sb + "]");
                }
            });
    }

    // ------------------------------------------------------------------ utils

    private static void dumpIntent(String where, Intent intent) {
        StringBuilder sb = new StringBuilder("[PROBE] ").append(where)
            .append(" action=").append(intent.getAction())
            .append(" data=").append(intent.getDataString())
            .append(" comp=").append(intent.getComponent())
            .append(" flags=0x").append(Integer.toHexString(intent.getFlags()));

        Bundle extras = intent.getExtras();
        if (extras != null) {
            for (String key : extras.keySet()) {
                Object v;
                try {
                    v = extras.get(key);
                } catch (Throwable t) {
                    v = "<" + t.getClass().getSimpleName() + ">";
                }
                sb.append(" | ").append(key).append('=').append(stringify(v));
            }
        }
        XposedEntry.log(limit(sb.toString(), 4000));
    }

    private static String stringify(Object v) {
        if (v == null) return "null";
        if (v instanceof byte[]) return "byte[" + ((byte[]) v).length + "]";
        String s = String.valueOf(v);
        return limit(s, 300);
    }

    private static String hexHead(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, b.length); i++) {
            sb.append(String.format(Locale.US, "%02x", b[i]));
        }
        return sb.toString();
    }

    private static String limit(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "...(" + s.length() + ")";
    }
}