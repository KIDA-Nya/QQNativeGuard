package io.github.kida.qqnativeguard.hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import io.github.kida.qqnativeguard.XposedEntry;
import io.github.kida.qqnativeguard.config.HookConfig;
import io.github.kida.qqnativeguard.utils.HookUtils;

import java.io.File;

/**
 * Root Detection Bypass Hook
 *
 * Bypasses 6 independent Java-layer root detection implementations:
 * 1. oicq.wlogin_sdk.request.w.h() - Wlogin SDK
 * 2. com.tencent.gathererga.core.UserInfoImpl.isRooted()
 * 3. org.light.device.LightDeviceUtils.isRooted()
 * 4. com.tencent.bugly.proguard.cp
 * 5. com.tenpay.charge.v2.util.ChargeV2Utils.isDeviceRooted()
 * 6. com.tencent.camerasdk.avreport.DeviceInfo
 *
 * Note: Native-layer detection (libfekit.so) requires native hooks
 */
public class RootDetectionHook {

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!HookConfig.ENABLE_ROOT_BYPASS) return;

        XposedEntry.log("Initializing Root Detection Bypass");

        // Hook 1: Wlogin SDK root check
        hookWloginRoot();

        // Hook 2: Gatherer GA root check
        hookGathererRoot();

        // Hook 3: Light Device Utils
        hookLightDeviceRoot();

        // Hook 4: Bugly crash SDK
        hookBuglyRoot();

        // Hook 5: Tenpay charge utils
        hookTenpayRoot();

        // Hook 6: Camera SDK
        hookCameraSDKRoot();
        hookGathererProvider();
        hookRiskLevelObserver();

        // Generic file existence checks for su binaries
        hookFileExists();

        // Hook Runtime.exec to prevent su detection
        hookRuntimeExec();

        // Hook PackageManager to hide root apps
        hookPackageManager();
   
    startLazyHookRetry();
 
    // 【V8 补全】懒加载类重试。
    // 部分 SDK 类（gathererga / camerasdk）在 handleLoadPackage 时还没加载，
    // 直接 hook 会 ClassNotFoundException。起后台线程周期性重试，直到类出现。
    startLazyHookRetry();
}

private static void startLazyHookRetry() {
    Thread t = new Thread(new Runnable() {
        @Override
        public void run() {
            String[][] targets = {
                {"com.tencent.gathererga.core.internal.provider.impl.UserInfoImpl", "isRooted"},
                {"com.tencent.gathererga.core.UserInfoProvider", "isRooted"},
                {"com.tencent.camerasdk.avreport.DeviceInfo", "isDeviceRooted"},
                {"com.tencent.qqnt.kernelgpro.nativeinterface.GProGuildSafetyRisk", "getRiskLevel"},
            };
            for (int round = 0; round < 60; round++) {   // 最多 60 轮，每轮 2 秒
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
                for (String[] t : targets) {
                    try {
                        Class<?> c = de.robv.android.xposed.XposedHelpers.findClass(t[0], HookConfig.getClassLoader());
                        if (c == null) continue;
                        // 已 hook 过的不重复
                        if (lazyHooked.contains(t[0] + "." + t[1])) continue;
                        HookUtils.hookAllMethods(t[0], t[1], new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                Class<?> rt = param.method.getDeclaringClass();
                                String n = param.method.getName();
                                if ("getRiskLevel".equals(n)) {
                                    return;   // 观测型，不改值
                                }
                                param.setResult(Boolean.FALSE);
                                XposedEntry.log("[LAZY-HOOK] " + rt.getSimpleName() + "." + n + " -> false");
                            }
                        });
                        lazyHooked.add(t[0] + "." + t[1]);
                        XposedEntry.log("[LAZY-HOOK] attached " + t[0] + "." + t[1] + " (round " + round + ")");
                    } catch (Throwable ignored) {
                    }
                }
                if (lazyHooked.size() >= targets.length) return;
            }
        }
    }, "QNG-LazyHook");
    t.setDaemon(true);
    t.start();
}


    private static void hookWloginRoot() {
        HookUtils.hookAllMethods("oicq.wlogin_sdk.request.w", "h",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    // This method collects device info including root status
                    // We let it run but will block the network report
                    XposedEntry.log("Wlogin root check intercepted");
                }
            });
    }

    private static void hookGathererRoot() {
        HookUtils.hookMethod("com.tencent.gathererga.core.internal.provider.impl.UserInfoImpl", "isRooted",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(false);
                    XposedEntry.log("Gatherer isRooted: false");
                }
            });
    }

    // 【V8 补全】gatherer SDK 的接口定义（UserInfoProvider）。
// 实现类 UserInfoImpl 已 hook，但接口层可能被单独调用（多态），需一并处理。
private static void hookGathererProvider() {
    HookUtils.hookMethod("com.tencent.gathererga.core.UserInfoProvider", "isRooted",
        new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                param.setResult(false);
            }
        });
}

private static void hookLightDeviceRoot() {
        HookUtils.hookMethod("org.light.device.LightDeviceUtils", "isRooted",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(false);
                    XposedEntry.log("LightDevice isRooted: false");
                }
            });
    }

    private static void hookBuglyRoot() {
        HookUtils.hookMethod("com.tencent.bugly.proguard.cp", "a",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(false);
                }
            });
    }

    private static void hookTenpayRoot() {
        HookUtils.hookMethod("com.tenpay.charge.v2.util.ChargeV2Utils", "isDeviceRooted",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(false);
                    XposedEntry.log("Tenpay isDeviceRooted: false");
                }
            });
    }

    private static void hookCameraSDKRoot() {
        // 【V8 修正】实际方法名是 isDeviceRooted，不是 isRooted（dex 实证）
        HookUtils.hookMethod("com.tencent.camerasdk.avreport.DeviceInfo", "isDeviceRooted",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(false);
                }
            });
    }

    /** V8 新增观测点：GProGuildSafetyRisk.getRiskLevel 返回服务端下发的风险等级。
     *  这是罕见能直接看到"服务端裁决结果"的接口——不篡改，只记录，
     *  用于确认服务端到底把我们判定为什么等级。 */
    private static void hookRiskLevelObserver() {
        HookUtils.hookAllMethods("com.tencent.qqnt.kernelgpro.nativeinterface.GProGuildSafetyRisk",
            "getRiskLevel", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object r = param.getResult();
                XposedEntry.log("[RISK-OBSERVE] GProGuildSafetyRisk.getRiskLevel -> " + r);
            }
        });
}

private static void hookFileExists() {
        // Hook File.exists() to hide su binaries and root manager apps
        HookUtils.hookMethod(File.class, "exists",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    File file = (File) param.thisObject;
                    String path = file.getAbsolutePath();

                    // Common su paths
                    if (isSuspiciousPath(path)) {
                        param.setResult(false);
                    }
                }
            });
        // T6 头号嫌疑：fekit Java 探测集用 File.canRead 探测 su
        // （native access deny 不覆盖 Java canRead 走的 stat 系 syscall）
        HookUtils.hookMethod(File.class, "canRead",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    File file = (File) param.thisObject;
                    String path = file.getAbsolutePath();
                    if (isSuspiciousPath(path)) {
                        XposedEntry.log("Blocked File.canRead: " + path);
                        param.setResult(false);
                    }
                }
            });
        HookUtils.hookMethod(File.class, "canExecute",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    File file = (File) param.thisObject;
                    String path = file.getAbsolutePath();
                    if (isSuspiciousPath(path)) {
                        XposedEntry.log("Blocked File.canExecute: " + path);
                        param.setResult(false);
                    }
                }
            });
        HookUtils.hookMethod(File.class, "canWrite",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    File file = (File) param.thisObject;
                    String path = file.getAbsolutePath();
                    if (isSuspiciousPath(path)) {
                        param.setResult(false);
                    }
                }
            });
    }

    private static void hookRuntimeExec() {
        // Hook Runtime.exec to prevent command-based detection
        HookUtils.hookAllMethods("java.lang.Runtime", "exec",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length > 0) {
                        String command = param.args[0].toString();

                        // Block root-detection commands
                        if (command.contains("su") ||
                            command.contains("magisk") ||
                            command.contains("/system/xbin/which") ||
                            command.contains("mount") && command.contains("grep")) {

                            XposedEntry.log("Blocked Runtime.exec: " + command);
                            // Return a dummy process that exits immediately
                            param.setResult(new ProcessBuilder("echo").start());
                        }
                    }
                }
            });
    }

    // 【V8 补全】需要隐藏的包名：root 管理器 + 我们的模块自身。
// QQ 的 PackageManager 扫描会看到 io.github.kida.qqnativeguard，
// 这本身就是"设备装了 hook 模块"的直接证据。
private static final java.util.Set<String> lazyHooked = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

private static final String[] HIDDEN_PKGS = {
    // Root 管理器
    "com.topjohnwu.magisk", "me.weishu.kernelsu", "com.kernelsu.kernelsu",
    "eu.chainfire.supersu", "me.bmax.apatch", "com.tsng.hidemyapplist",
    // Xposed / LSPosed
    "org.lsposed.manager", "de.robv.android.xposed.installer",
    "org.meowcat.edxposed.manager", "io.va.exposed",
    // 我们的模块自身
    "io.github.kida.qqnativeguard",
    "io.github.qqenhanced.bypass",
    // 其他 hook 框架
    "com.saurik.substrate", "re.frida.server",
};

private static boolean isHiddenPkg(String name) {
    if (name == null) return false;
    for (String h : HIDDEN_PKGS) {
        if (h.equals(name)) return true;
    }
    return false;
}

private static void hookPackageManager() {
    String pmCls = "android.app.ApplicationPackageManager";

    // 1) 单包查询：命中隐藏包时抛出 NameNotFoundException，让调用方认为"未安装"
    String[] singleQuery = {"getPackageInfo", "getApplicationInfo"};
    for (final String method : singleQuery) {
        HookUtils.hookAllMethods(pmCls, method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args.length > 0 && param.args[0] instanceof String) {
                    String pkg = (String) param.args[0];
                    if (isHiddenPkg(pkg)) {
                        throw new android.content.pm.PackageManager.NameNotFoundException(pkg);
                    }
                }
            }
        });
    }

    // 2) 列表查询：从返回的 List 中移除隐藏包
    String[] listQuery = {"getInstalledPackages", "getInstalledApplications"};
    for (final String method : listQuery) {
        HookUtils.hookAllMethods(pmCls, method, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object r = param.getResult();
                if (!(r instanceof java.util.List)) return;
                java.util.List<?> list = (java.util.List<?>) r;
                java.util.List<Object> kept = new java.util.ArrayList<>(list.size());
                for (Object item : list) {
                    String pkg = null;
                    if (item instanceof android.content.pm.PackageInfo) {
                        pkg = ((android.content.pm.PackageInfo) item).packageName;
                    } else if (item instanceof android.content.pm.ApplicationInfo) {
                        pkg = ((android.content.pm.ApplicationInfo) item).packageName;
                    }
                    if (pkg == null || !isHiddenPkg(pkg)) {
                        kept.add(item);
                    }
                }
                if (kept.size() != list.size()) {
                    param.setResult(kept);
                }
            }
        });
    }
}

    private static boolean isSuspiciousPath(String path) {
        // Comprehensive list of root-related paths
        String[] suspiciousPaths = {
            "/data/local/su",
            "/data/local/bin/su",
            "/data/local/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/system/bin/su",
            "/system/bin/.ext/.su",
            "/system/sd/xbin/su",
            "/system/usr/we-need-root/su",
            "/system/xbin/su",
            "/system/app/Superuser.apk",
            "/system/app/SuperSU.apk",
            "/system/etc/init.d/99SuperSUDaemon",
            "/dev/com.koushikdutta.superuser.daemon/",
            "/system/xbin/daemonsu",
            // Magisk
            "/data/adb/magisk",
            "/sbin/.magisk",
            "/cache/magisk.log",
            "/data/magisk.img",
            "/data/adb/magisk.img",
            // KernelSU
            "/data/adb/ksu",
            "/data/adb/ksud"
        };

        for (String suspicious : suspiciousPaths) {
            if (path.contains(suspicious)) {
                return true;
            }
        }
        return false;
    }
}
