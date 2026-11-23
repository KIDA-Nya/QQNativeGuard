package io.github.kida.qqnativeguard.hooks;

import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import io.github.kida.qqnativeguard.NativeBypass;
import io.github.kida.qqnativeguard.XposedEntry;
import io.github.kida.qqnativeguard.config.HookConfig;
import io.github.kida.qqnativeguard.utils.HookUtils;

/**
 * Unified Hook Coordinator
 *
 * This module coordinates between Java and Native hooks to provide
 * seamless protection across both layers.
 *
 * Java Hook → If failed → Try Native Hook
 * Native Hook → Notify Java layer
 */
public class UnifiedHookCoordinator {

    private static boolean nativeLayerAvailable = false;
    /** Native hooks 是否实际启用（受 HookConfig.ENABLE_NATIVE_HOOKS 控制） */
    private static boolean nativeLayerEnabled = false;

    public static void initialize(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedEntry.log("=== Initializing Unified Hook Coordinator ===");

        boolean anyCoordinatorFeature = HookConfig.ENABLE_NATIVE_HOOKS
            || HookConfig.ENABLE_ROOT_BYPASS
            || HookConfig.ENABLE_XPOSED_BYPASS
            || HookConfig.ENABLE_DEBUG_BYPASS
            || HookConfig.ENABLE_DEVICE_SPOOF
            || HookConfig.ENABLE_NETWORK_INTERCEPT
            || HookConfig.ENABLE_RUNTIME_BYPASS;
        if (!anyCoordinatorFeature) {
            nativeLayerAvailable = NativeBypass.isLibraryLoaded();
            nativeLayerEnabled = false;
            XposedEntry.log("All coordinator features disabled; installing no Java/native hooks");
            return;
        }

        // Check Native layer availability
        nativeLayerAvailable = NativeBypass.isLibraryLoaded();
        nativeLayerEnabled = nativeLayerAvailable && HookConfig.ENABLE_NATIVE_HOOKS;

        if (nativeLayerEnabled) {
            XposedEntry.log("Native layer available - Full protection (80%)");
            initNativeIntegration();
        } else if (nativeLayerAvailable) {
            XposedEntry.log("Native hooks disabled by config - Java only (30%)");
        } else {
            XposedEntry.log("Native layer unavailable - Java only (30%)");
        }

        // Install coordinated hooks
        installCoordinatedHooks(lpparam);
    }

    /**
     * Initialize Native integration
     */
    private static void initNativeIntegration() {
        // 先注入配置（探测开关 / 伪造值），再安装 hooks
        NativeBypass.setNativeConfig(HookConfig.PROBE, HookConfig.FAKE_SERIAL,
            HookConfig.FAKE_SOC_SERIAL, HookConfig.FAKE_BOOT_STATE,
            HookConfig.ROM_BUILD_HOST, HookConfig.ROM_BUILD_USER,
            HookConfig.ROM_BUILD_DISPLAY, HookConfig.ROM_BUILD_FINGERPRINT);

        if (HookConfig.ENV_SPOOF) {
            NativeBypass.setEnvProfile(buildEnvProfile());
        }

        // 调用者归因（观测）：必须在 install_hooks 之前生效，否则早期访问没有归属
        NativeBypass.setAttrConfig(HookConfig.ATTR_PROBE, HookConfig.ATTR_DEEP);

        // Initialize Native hooks
        NativeBypass.initNativeHooks();

        // Register callback from Native layer
        registerNativeCallbacks();
    }

    /**
     * Register callbacks for Native → Java communication
     */
    private static String buildEnvProfile() {
        StringBuilder sb = new StringBuilder();
        String[] fields = {
            HookConfig.ENV_RELEASE, HookConfig.ENV_SDK, HookConfig.ENV_BUILD_ID,
            HookConfig.ENV_DISPLAY, HookConfig.ENV_FINGERPRINT, HookConfig.ENV_DESCRIPTION,
            HookConfig.ENV_HOST, HookConfig.ENV_USER, HookConfig.ENV_DATE,
            HookConfig.ENV_INCREMENTAL, HookConfig.ENV_SECURITY_PATCH, HookConfig.ENV_OPPOROM,
            HookConfig.ENV_KERNEL_RELEASE, HookConfig.ENV_KERNEL_VERSION,
            HookConfig.ENV_MODEL, HookConfig.ENV_PRODUCT_NAME, HookConfig.ENV_DEVICE,
            HookConfig.ENV_BRAND, HookConfig.ENV_MANUFACTURER
        };
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(fields[i] == null ? "" : fields[i]);
        }
        return sb.toString();
    }

    private static void registerNativeCallbacks() {
        // Native layer can notify Java layer through JNI
        // e.g., when a detection is blocked at Native level
    }

    /**
     * Install hooks with Java/Native coordination
     */
    private static void installCoordinatedHooks(XC_LoadPackage.LoadPackageParam lpparam) {
        // Hook critical detection points with fallback
        hookWithFallback(lpparam);

        // Setup bidirectional communication
        setupCommunicationChannel(lpparam);
    }

    /**
     * Hook with Java → Native fallback
     */
    private static void hookWithFallback(XC_LoadPackage.LoadPackageParam lpparam) {
        // Example: File existence check
        // Java layer hooks File.exists()
        // If it fails, Native layer hooks access()

        HookUtils.hookMethod(java.io.File.class, "exists",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    java.io.File file = (java.io.File) param.thisObject;
                    String path = file.getAbsolutePath();

                    // Check if this is a sensitive path
                    if (isSensitivePath(path)) {
                        param.setResult(false);
                        XposedEntry.log("[Java] Blocked file check: " + path);

                        // Notify Native layer (if available)
                        if (nativeLayerAvailable) {
                            notifyNativeLayer("file_blocked", path);
                        }
                    }
                }
            });
    }

    /**
     * Setup bidirectional communication
     */
    private static void setupCommunicationChannel(XC_LoadPackage.LoadPackageParam lpparam) {
        // Java can query Native status
        // Native can notify Java about detections

        if (nativeLayerEnabled) {
            // Check Native hook status periodically
            new Thread(() -> {
                try {
                    Thread.sleep(3000);
                    String status = NativeBypass.getHookStatus();
                    XposedEntry.log("[Coordinator] Native status: " + status);
                } catch (Exception e) {
                    XposedEntry.logError("[Coordinator] Status check failed", e);
                }
            }).start();
        }
    }

    /**
     * Notify Native layer about Java detections
     */
    private static void notifyNativeLayer(String event, String data) {
        if (!nativeLayerEnabled) return;

        try {
            // JNI call to Native layer
            NativeBypass.notifyDetection(event, data);
        } catch (Throwable t) {
            // Silent fail
        }
    }

    /**
     * Check if path is sensitive
     */
    private static boolean isSensitivePath(String path) {
        if (path == null) return false;

        String[] sensitivePaths = {
            "/system/bin/su",
            "/system/xbin/su",
            "/data/adb/magisk",
            "/data/adb/lsposed"
        };

        for (String sensitive : sensitivePaths) {
            if (path.contains(sensitive)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Get protection level
     */
    public static String getProtectionLevel() {
        if (nativeLayerEnabled && NativeBypass.isHooksInstalled()) {
            return "Full (80%)";
        } else if (nativeLayerEnabled) {
            return "Native loading (60%)";
        } else {
            return "Java only (30%)";
        }
    }
}
