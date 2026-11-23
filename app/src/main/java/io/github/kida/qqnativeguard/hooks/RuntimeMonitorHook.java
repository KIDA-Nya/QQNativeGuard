package io.github.kida.qqnativeguard.hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import io.github.kida.qqnativeguard.XposedEntry;
import io.github.kida.qqnativeguard.config.HookConfig;
import io.github.kida.qqnativeguard.utils.HookUtils;

/**
 * Runtime Monitor Bypass
 *
 * Handles Pandora RuntimeMonitor which monitors:
 * - Runtime.exec() calls for suspicious commands (ip, pm, getprop, sh)
 * - Command outputs via ProcessBuilder
 * - System command execution patterns
 *
 * Also handles various command processors:
 * - IPProcessor
 * - PackageManagerProcessor
 * - PropProcessor
 */
public class RuntimeMonitorHook {

    private static final String[] MONITORED_COMMANDS = {
        "ip", "pm", "getprop", "sh", "/system/bin/sh",
        "ps", "cat", "mount", "which", "su"
    };

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!HookConfig.ENABLE_RUNTIME_BYPASS) return;

        XposedEntry.log("Initializing Runtime Monitor Bypass");

        // Hook 1: Pandora RuntimeMonitor
        hookPandoraRuntimeMonitor();

        // Hook 2: Command processors
        hookCommandProcessors();

        // Hook 3: ProcessBuilder monitoring
        hookProcessBuilder();
    }

    private static void hookPandoraRuntimeMonitor() {
        // Disable RuntimeMonitor entirely
        HookUtils.hookAllMethods("com.tencent.qmethod.pandoraex.RuntimeMonitor", "monitor",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // Block all runtime monitoring
                    param.setResult(null);
                }
            });

        HookUtils.hookAllMethods("com.tencent.qmethod.pandoraex.RuntimeMonitor", "onExec",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(null);
                }
            });

        HookUtils.hookAllMethods("com.tencent.qmethod.pandoraex.RuntimeMonitor", "shouldMonitor",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(false);
                }
            });
    }

    private static void hookCommandProcessors() {
        // Hook IPProcessor
        HookUtils.hookAllMethods("com.tencent.qmethod.pandoraex.IPProcessor", "process",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(null);
                }
            });

        // Hook PackageManagerProcessor
        HookUtils.hookAllMethods("com.tencent.qmethod.pandoraex.PackageManagerProcessor", "process",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(null);
                }
            });

        // Hook PropProcessor
        HookUtils.hookAllMethods("com.tencent.qmethod.pandoraex.PropProcessor", "process",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(null);
                }
            });
    }

    private static void hookProcessBuilder() {
        // Hook ProcessBuilder.start() to monitor command execution
        HookUtils.hookMethod(ProcessBuilder.class, "start",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    ProcessBuilder pb = (ProcessBuilder) param.thisObject;
                    java.util.List<String> command = pb.command();

                    if (command != null && !command.isEmpty()) {
                        String cmd = command.get(0);

                        if (HookConfig.PROBE) {
                            StringBuilder sb = new StringBuilder();
                            for (String part : command) {
                                if (sb.length() > 0) sb.append(' ');
                                sb.append(part);
                            }
                            String full = sb.toString();
                            if (full.length() > 400) full = full.substring(0, 400) + "...";
                            XposedEntry.log("[PROBE] ProcessBuilder: " + full);
                        }

                        // Log monitored commands
                        if (isMonitoredCommand(cmd)) {
                            XposedEntry.log("ProcessBuilder command intercepted: " + cmd);
                            // We don't block it, just log - blocking might break functionality
                        }
                    }
                }
            });
    }

    private static boolean isMonitoredCommand(String command) {
        if (command == null) return false;

        for (String monitored : MONITORED_COMMANDS) {
            if (command.contains(monitored)) {
                return true;
            }
        }
        return false;
    }
}
