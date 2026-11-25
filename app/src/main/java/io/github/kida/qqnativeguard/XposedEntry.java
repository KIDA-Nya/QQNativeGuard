package io.github.kida.qqnativeguard;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import io.github.kida.qqnativeguard.config.HookConfig;
import io.github.kida.qqnativeguard.hooks.*;

/**
 * QQ Enhanced Bypass - Comprehensive Detection Bypass Module
 *
 * This module provides multi-layered protection against QQ's environment detection:
 * - Java Layer: Root/Xposed/Debug detection bypass (30%)
 * - Native Layer: libc/libfekit/libturingxq hooks (70%)
 * - Network reporting interception
 * - Device info spoofing with cross-validation protection
 */
public class XposedEntry implements IXposedHookLoadPackage {

    private static final String TAG = "QQEnhancedBypass";
    private static final String TARGET_PACKAGE = "com.tencent.mobileqq";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        // Initialize configuration first (读取外部配置 + 初始化文件日志 LOG_FILE)
        HookConfig.init(lpparam);

        // Diagnostics only. Do not mutate QQ identity caches during process startup.

        log("=== QQ Enhanced Bypass v2.0 ===");
        log("Package: " + lpparam.packageName);
        log("Process: " + lpparam.processName);

        try {
            // === Initialize Unified Hook Coordinator ===
            // This coordinates Java and Native layers seamlessly
            UnifiedHookCoordinator.initialize(lpparam);

            // === Java Layer Hooks (30% detection coverage) ===

            // Layer 1: Network reporting interception (highest priority)
            // 注：wtlogin Reporter/report_t 观测已并入 NetworkReportHook.hookWloginReporter
            //（WloginReportHook 已删除，避免双重 hook 重复日志）
            NetworkReportHook.hook(lpparam);

            // Diagnostic probe remains opt-in; disabling all feature flags produces a no-hook control run.
            // PROBE alone is enough: the probe is read-only and is what tells us why the
            // server rejects a login while every mutating feature stays off.
            if (HookConfig.PROBE
                || HookConfig.ENABLE_ROOT_BYPASS || HookConfig.ENABLE_XPOSED_BYPASS
                || HookConfig.ENABLE_DEBUG_BYPASS || HookConfig.ENABLE_DEVICE_SPOOF
                || HookConfig.ENABLE_NETWORK_INTERCEPT || HookConfig.ENABLE_RUNTIME_BYPASS) {
                DiagnosticHook.hook(lpparam);
            }

            // Exit 追踪：尽早挂载，记录所有退出路径（只观测不改行为）
            ExitTraceHook.hook(lpparam);

            if (HookConfig.ENABLE_ROOT_BYPASS) RootDetectionHook.hook(lpparam);
            if (HookConfig.ENABLE_XPOSED_BYPASS) XposedDetectionHook.hook(lpparam);
            if (HookConfig.ENABLE_DEVICE_SPOOF) DeviceInfoHook.hook(lpparam);
            if (HookConfig.ENABLE_DEBUG_BYPASS) DebugDetectionHook.hook(lpparam);
            if (HookConfig.ENABLE_RUNTIME_BYPASS) RuntimeMonitorHook.hook(lpparam);

            // === Report Status ===
            String protection = UnifiedHookCoordinator.getProtectionLevel();
            log("=== Initialization Complete ===");
            log("Protection Level: " + protection);
            log("Config: native=" + HookConfig.ENABLE_NATIVE_HOOKS
                + " root=" + HookConfig.ENABLE_ROOT_BYPASS
                + " xposed=" + HookConfig.ENABLE_XPOSED_BYPASS
                + " debug=" + HookConfig.ENABLE_DEBUG_BYPASS
                + " deviceSpoof=" + HookConfig.ENABLE_DEVICE_SPOOF
                + " network=" + HookConfig.ENABLE_NETWORK_INTERCEPT
                + " runtime=" + HookConfig.ENABLE_RUNTIME_BYPASS
                + " verbose=" + HookConfig.VERBOSE_LOGGING
                + " probe=" + HookConfig.PROBE
                + " bootState=" + HookConfig.FAKE_BOOT_STATE
                + " fakeSerial=" + HookConfig.FAKE_SERIAL
                + " logFile=" + (HookConfig.LOG_FILE == null ? "null" : HookConfig.LOG_FILE));

            // 行为自检：只跑主进程，延迟 10 秒等 Application 就绪。
            // 目的不是日志，而是实际调用被 hook 的方法并落盘结果，
            // 用来验证「hook 生效后行为正确」而不只是「hook 装上」。
            if (TARGET_PACKAGE.equals(lpparam.processName)) {
                SelfTest.schedule();
            }

        } catch (Throwable t) {
            log("Error during hook initialization: " + t.getMessage());
            t.printStackTrace();
        }
    }

    /**
     * Initialize native layer hooks (deprecated - now handled by UnifiedHookCoordinator)
     */
    @Deprecated
    private void initNativeHooks() {
        // This method is kept for compatibility but functionality moved to UnifiedHookCoordinator
    }

    public static void log(String message) {
        Log.i(TAG, message);
        io.github.kida.qqnativeguard.utils.FileLog.write("I/" + TAG + ": " + message);
    }

    public static void logError(String message, Throwable t) {
        Log.e(TAG, message, t);
        io.github.kida.qqnativeguard.utils.FileLog.write("E/" + TAG + ": " + message
            + " | " + (t != null ? t.toString() : ""));
    }
}
