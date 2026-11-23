package io.github.kida.qqnativeguard.hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import io.github.kida.qqnativeguard.XposedEntry;
import io.github.kida.qqnativeguard.config.HookConfig;
import io.github.kida.qqnativeguard.utils.HookUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Xposed/LSPosed/Hook Framework Detection Bypass
 *
 * Mitigates detection of:
 * - LSPosed/Xposed installer package
 * - Xposed framework artifacts
 * - /proc/self/maps scanning for hook libraries
 * - Cydia Substrate detection
 * - ART method hook detection (limited effectiveness)
 *
 * Note: Native-layer detection (libfekit.so, libturingxq.so) requires native hooks
 * This module can only handle Java-layer checks and file I/O interception
 */
public class XposedDetectionHook {

    private static final String[] RISKY_PACKAGES = {
        // Xposed / LSPosed / Vector 管理器
        "de.robv.android.xposed.installer",
        "org.meowcat.edxposed.manager",
        "io.github.lsposed.manager",
        "org.lsposed.manager",
        "io.github.lsposed",
        "io.github.vector.manager",
        "com.vector.manager",
        // Root 管理器（KSU / SukiSU / Magisk / SUSFS）
        "com.topjohnwu.magisk",
        "me.weishu.kernelsu",
        "com.resukisu.resukisu",
        "com.rifsx.susfs",
        "org.sukisu.manager",
        // 改机 / 多开 / 修改器 / 自动化
        "bin.mt.plus",
        "com.mekhontsev.magicdesk",
        "com.lbe.parallel",
        "com.parallel.space",
        "com.qihoo.magic",
        "com.dualspace",
        "com.dual.space",
        "com.multi.parallel",
        "com.applisto.appcloner",
        "com.gg.mod",
        "com.speed.script",
        "org.autojs",
        "com.autojs",
        "com.toucher",
        "com.and.gk",
        "com.scriptelf",
        "com.np.manager",
        // 远控
        "com.netease.uuremote",
        "com.oray.sunlogin",
        "com.teamviewer",
        // 代理 / 抓包
        "com.github.kr328.clash",
        "io.nekohasekai.sagernet",
        "com.v2ray.ang",
        "com.guoshi.httpcanary",
        "com.httpcanary",
        "com.minhui.networkcapture",
        // Xposed QQ 增强模块与工具链 —— 本机真实装着的这些必须一并隐藏。
        // 依据 ColorOS 自己的 OplusRiskAppCache mRiskApps 清单核对后补齐。
        "me.yxp.qfun",
        "com.luckyzyx.luckytool",
        "top.hookvip.pro",
        "com.op.lspdoze",
        "io.github.mekhontsev.magicdesk",
        "io.github.mekhontsev.wallpaperfit",
        "com.termux",
        "in.hridayan.ashell",
        "io.github.kida.qqnativeguard",
        null
    };

    private static final java.util.Set<String> PROBED =
        java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static final String[] HOOK_LIBRARY_PATTERNS = {
        "lsposed",
        "xposed",
        "libriru",
        "zygisk",
        "edxposed",
        "libsubstrate",
        "libAndroidCydia",
        "libDalvikLoader"
    };

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!HookConfig.ENABLE_XPOSED_BYPASS) return;

        XposedEntry.log("Initializing Xposed Detection Bypass");

        // Hook 1: Crash monitor Xposed detection
        hookCrashMonitor();

        // Hook 2: PackageManager to hide Xposed packages
        hookPackageManagerForXposed();

        // Hook 2b: per-package probing (getPackageInfo etc.) — QQ queries specific
        // module package names one by one, which the list-level hooks never see.
        hookPerPackageProbing();

        // Hook 3: File I/O to filter /proc/self/maps
        hookProcMapsReading();

        // Hook 4: Stack trace filtering
        hookStackTrace();

        // Hook 5: ClassLoader detection
        hookClassLoaderCheck();
    }

    private static void hookCrashMonitor() {
        // QQPerf crash monitor checks for Xposed
        HookUtils.hookAllMethods("com.tencent.qqperf.monitor.crash.c", "a",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // Block Xposed detection in crash reports
                    param.setResult(false);
                }
            });

        // Alternative method names
        HookUtils.hookMethod("com.tencent.qqperf.monitor.crash.c", "isXposedInstalled",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(false);
                    XposedEntry.log("Blocked isXposedInstalled check");
                }
            });
    }

    private static void hookPackageManagerForXposed() {
        // 对已知风险包：查询直接抛 NameNotFound，枚举列表过滤掉
        for (String method : new String[]{"getPackageInfo", "getApplicationInfo"}) {
            HookUtils.hookAllMethods("android.app.ApplicationPackageManager", method,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] instanceof String) {
                            String packageName = (String) param.args[0];
                            if (isRiskyPackage(packageName)) {
                                XposedEntry.log("Blocked PackageManager query for: " + packageName);
                                throw new android.content.pm.PackageManager.NameNotFoundException(packageName);
                            }
                        }
                    }
                });
        }

        String[] listMethods = {
            "getInstalledPackages", "getInstalledPackagesAsUser",
            "getInstalledApplications", "getInstalledApplicationsAsUser"
        };
        for (String method : listMethods) {
            HookUtils.hookAllMethods("android.app.ApplicationPackageManager", method,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object result = param.getResult();
                        if (result instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<Object> list = (List<Object>) result;
                            List<Object> filtered = new ArrayList<>();
                            for (Object pkg : list) {
                                String pkgName = null;
                                try {
                                    pkgName = (String) XposedHelpers.getObjectField(pkg, "packageName");
                                } catch (Throwable ignored) {
                                }
                                if (!isRiskyPackage(pkgName)) {
                                    filtered.add(pkg);
                                } else {
                                    XposedEntry.log("Hidden package from list: " + pkgName);
                                }
                            }
                            param.setResult(filtered);
                        }
                    }
                });
        }
    }

    /**
     * 逐包探测拦截：QQ（以及 Turing SDK 的 Java 侧）通常不是拉全量包列表，而是对
     * 已知的模块包名逐个 getPackageInfo。列表级 hook 看不到这种探测，必须单独拦。
     */
    private static void hookPerPackageProbing() {
        final String pmClass = "android.app.ApplicationPackageManager";

        for (final String method : new String[]{"getPackageInfo", "getApplicationInfo"}) {
            HookUtils.hookAllMethods(pmClass, method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length == 0 || !(param.args[0] instanceof String)) return;
                    String pkg = (String) param.args[0];
                    boolean risky = isRiskyPackage(pkg);
                    // Record every probed name once, not only the blocked ones: the
                    // names QQ asks about are the detection list itself.
                    if (PROBED.size() < 800 && PROBED.add(pkg)) {
                        XposedEntry.log("PkgProbe " + pkg + (risky ? " [BLOCKED]" : ""));
                    }
                    if (risky) {
                        param.setThrowable(
                            new android.content.pm.PackageManager.NameNotFoundException(pkg));
                    }
                }
            });
        }

        HookUtils.hookAllMethods(pmClass, "getPackageUid", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args.length == 0 || !(param.args[0] instanceof String)) return;
                String pkg = (String) param.args[0];
                if (isRiskyPackage(pkg)) {
                    XposedEntry.log("Blocked pkg uid probe: " + pkg);
                    param.setResult(-1);
                }
            }
        });

        HookUtils.hookAllMethods(pmClass, "getApplicationEnabledSetting",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length == 0 || !(param.args[0] instanceof String)) return;
                    String pkg = (String) param.args[0];
                    if (isRiskyPackage(pkg)) {
                        param.setResult(android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
                    }
                }
            });
    }

    private static void hookProcMapsReading() {
        // Hook file reading to filter /proc/self/maps content
        HookUtils.hookMethod(FileReader.class, "read", char[].class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    // This is complex - would need to track file paths and filter content
                    // Simplified implementation: just log the attempt
                }
            });

        // Hook BufferedReader.readLine for /proc/self/maps
        HookUtils.hookMethod(BufferedReader.class, "readLine",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String line = (String) param.getResult();
                    if (line != null && containsHookLibrary(line)) {
                        // Try to filter out hook library references
                        // This is tricky because we need context about which file is being read
                        Object reader = param.thisObject;
                        // Check if this BufferedReader is reading from /proc/self/maps
                        try {
                            Object lock = XposedHelpers.getObjectField(reader, "lock");
                            if (lock instanceof FileReader) {
                                // We can't easily get the file path, so this is limited
                                // Return null to skip this line
                                param.setResult(null);
                                XposedEntry.log("Filtered hook library from maps: " + line.substring(0, Math.min(50, line.length())));
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });

        // Hook File.exists for LSPosed artifacts
        HookUtils.hookMethod(File.class, "exists",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    File file = (File) param.thisObject;
                    String path = file.getAbsolutePath();

                    if (isXposedArtifact(path)) {
                        param.setResult(false);
                        XposedEntry.log("Hidden Xposed artifact: " + path);
                    }
                }
            });
    }

    private static void hookStackTrace() {
        // Filter Xposed from stack traces
        HookUtils.hookMethod(Throwable.class, "getStackTrace",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    StackTraceElement[] original = (StackTraceElement[]) param.getResult();
                    if (original == null) return;

                    List<StackTraceElement> filtered = new ArrayList<>();
                    for (StackTraceElement element : original) {
                        String className = element.getClassName();
                        if (!className.contains("xposed") &&
                            !className.contains("lsposed") &&
                            !className.contains("edxposed")) {
                            filtered.add(element);
                        }
                    }

                    if (filtered.size() != original.length) {
                        param.setResult(filtered.toArray(new StackTraceElement[0]));
                    }
                }
            });
    }

    private static void hookClassLoaderCheck() {
        // Some apps check the ClassLoader chain for Xposed
        HookUtils.hookMethod(ClassLoader.class, "toString",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String result = (String) param.getResult();
                    if (result != null && (result.contains("xposed") || result.contains("lsposed"))) {
                        // Sanitize the string
                        result = result.replaceAll("(?i)xposed", "dalvik")
                                       .replaceAll("(?i)lsposed", "dalvik");
                        param.setResult(result);
                    }
                }
            });
    }

    private static boolean isRiskyPackage(String packageName) {
        if (packageName == null) return false;
        for (String risky : RISKY_PACKAGES) {
            if (risky == null) continue;
            if (packageName.equals(risky) || packageName.startsWith(risky + ".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsHookLibrary(String line) {
        if (line == null) return false;
        String lowerLine = line.toLowerCase();
        for (String pattern : HOOK_LIBRARY_PATTERNS) {
            if (lowerLine.contains(pattern.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isXposedArtifact(String path) {
        return path != null && (
            path.contains("/data/user_de/0/org.lsposed.manager") ||
            path.contains("/data/user/0/org.lsposed.manager") ||
            path.contains("/data/data/de.robv.android.xposed.installer") ||
            path.contains("XposedBridge") ||
            path.contains("/system/framework/XposedBridge")
        );
    }
}
