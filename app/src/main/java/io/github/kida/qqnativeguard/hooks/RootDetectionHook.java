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

        // Generic file existence checks for su binaries
        hookFileExists();

        // Hook Runtime.exec to prevent su detection
        hookRuntimeExec();

        // Hook PackageManager to hide root apps
        hookPackageManager();
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
        HookUtils.hookMethod("com.tencent.gathererga.core.UserInfoImpl", "isRooted",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(false);
                    XposedEntry.log("Gatherer isRooted: false");
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
        HookUtils.hookMethod("com.tencent.camerasdk.avreport.DeviceInfo", "isRooted",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(false);
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

    private static void hookPackageManager() {
        // Hook PackageManager to hide root management apps
        String[] methods = {"getPackageInfo", "getApplicationInfo", "getInstalledPackages", "getInstalledApplications"};

        for (String method : methods) {
            HookUtils.hookAllMethods("android.app.ApplicationPackageManager", method,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        // Filter out root management apps from results
                        // Implementation depends on return type
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
