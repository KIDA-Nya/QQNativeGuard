package io.github.kida.qqnativeguard.hooks;

import android.os.Build;
import android.telephony.TelephonyManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import io.github.kida.qqnativeguard.XposedEntry;
import io.github.kida.qqnativeguard.config.HookConfig;
import io.github.kida.qqnativeguard.utils.HookUtils;

/**
 * Device Info Hook - Consistency Protection
 *
 * Handles Pandora's multi-path cross-validation:
 * - Ensures consistent values across different API calls
 * - Prevents detection of single-API hooks
 * - Maintains device fingerprint consistency
 *
 * Pandora DeviceInfoMonitor uses 6+ different methods to get the same info:
 * - TelephonyManager.getImei()
 * - TelephonyManager.getDeviceId()
 * - Build.SERIAL (field)
 * - Build.getSerial() (method)
 * - SystemProperties.get()
 * - SubscriptionManager APIs
 */
public class DeviceInfoHook {

    private static String cachedImei = null;
    private static String cachedDeviceId = null;
    private static String cachedSerial = null;
    private static String cachedAndroidId = null;
    private static boolean qimeiFakeLogged = false;
    private static boolean ssidOriginLogged = false;
    private static boolean osPropLogged = false;
    private static boolean httpAgentLogged = false;
    private static boolean msfQimeiLogged = false;
    private static boolean oaidLogged = false;

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!HookConfig.ENABLE_DEVICE_SPOOF) return;

        XposedEntry.log("Initializing Device Info Hook");

        // Initialize cached values from config or real device
        initCachedValues();

        // Hook all device ID retrieval methods with consistent values
        hookTelephonyManager();
        hookBuildSerial();
        hookSystemProperties();
        hookSystemGetProperty();
        hookAndroidId();
        applyBuildIdentity();
        hookQimeiIdentity();
        hookSettingsIds();
        hookNetworkIds();
        hookOaid();

        // Hook Pandora monitoring framework
        hookPandoraMonitor();
    }

    private static void initCachedValues() {
        // Use configured fake values or null to pass through real values
        cachedImei = HookConfig.FAKE_IMEI;
        cachedDeviceId = HookConfig.FAKE_IMEI; // Same as IMEI for consistency
        cachedSerial = HookConfig.FAKE_SERIAL;
        cachedAndroidId = HookConfig.FAKE_ANDROID_ID;
    }

    private static void hookTelephonyManager() {
        // Hook all IMEI/DeviceId retrieval methods
        String[] methods = {"getImei", "getDeviceId", "getMeid"};

        for (String method : methods) {
            HookUtils.hookAllMethods("android.telephony.TelephonyManager", method,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        // 用 before：QQ 没有 READ_PRIVILEGED_PHONE_STATE 时真实方法会直接抛
                        // SecurityException，after 回调根本不会执行；before 才能稳定伪造。
                        if (cachedImei != null) {
                            if (HookConfig.PROBE) {
                                XposedEntry.log("[PROBE] TelephonyManager." + method
                                    + " -> " + cachedImei + " (before)");
                            }
                            param.setResult(cachedImei);
                        }
                    }
                });
        }

        // Hook getSimSerialNumber
        HookUtils.hookAllMethods("android.telephony.TelephonyManager", "getSimSerialNumber",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (cachedSerial != null) {
                        param.setResult(cachedSerial);
                    }
                }
            });
    }

    private static void hookBuildSerial() {
        // Build.SERIAL 是 static final 字段，先尝试直接改写（QQ 直接读字段时也一致）
        if (cachedSerial != null) {
            try {
                de.robv.android.xposed.XposedHelpers.setStaticObjectField(
                    Build.class, "SERIAL", cachedSerial);
                XposedEntry.log("Build.SERIAL set to " + cachedSerial);
            } catch (Throwable t) {
                XposedEntry.log("Failed to set Build.SERIAL: " + t.getMessage());
            }
        }

        // Hook Build.getSerial() method instead
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            HookUtils.hookMethod(Build.class, "getSerial",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (cachedSerial != null) {
                            param.setResult(cachedSerial);
                        }
                    }
                });
        }
    }

    private static void hookSystemProperties() {
        // Hook SystemProperties.get() to return consistent values
        HookUtils.hookAllMethods("android.os.SystemProperties", "get",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length > 0 && param.args[0] instanceof String) {
                        String key = (String) param.args[0];

                        if (HookConfig.PROBE && isProbeKey(key)) {
                            XposedEntry.log("[PROBE] SystemProperties.get(" + key
                                + ") -> " + param.getResult());
                        }

                        String fake = fakeSystemProperty(key);
                        if (fake != null) {
                            param.setResult(fake);
                        }
                    }
                }
            });
    }

    private static void applyBuildIdentity() {
        if (HookConfig.ENV_SPOOF) {
            // 【T12 教训】不伪造 HOST/USER（真机值 ReVork/Sabrina Carpenter 本身正常，
            // 伪造反而产生 abuild 这种真机不可能的值）。
            // 【V6 修正】但 FINGERPRINT 必须与 MODEL/DEVICE/DISPLAY 同步伪造 ——
            // 否则 "display=PGEM10 而 fingerprint=LE2123" 是更明显的矛盾。
            setField(Build.class, "DISPLAY", HookConfig.ENV_DISPLAY);
            setField(Build.class, "ID", HookConfig.ENV_BUILD_ID);
            setField(Build.class, "TAGS", "release-keys");
            setField(Build.class, "TYPE", "user");
            setField(Build.class, "MODEL", HookConfig.ENV_MODEL);
            setField(Build.class, "DEVICE", HookConfig.ENV_DEVICE);
            setField(Build.class, "PRODUCT", HookConfig.ENV_PRODUCT_NAME);
            setField(Build.class, "BRAND", HookConfig.ENV_BRAND);
            setField(Build.class, "MANUFACTURER", HookConfig.ENV_MANUFACTURER);
            // fingerprint 必须跟随机型一起换（V6）
            if (HookConfig.ENV_FINGERPRINT != null && !HookConfig.ENV_FINGERPRINT.isEmpty()) {
                setField(Build.class, "FINGERPRINT", HookConfig.ENV_FINGERPRINT);
            }
            setField(Build.class, "HARDWARE", "qcom");
            setField(Build.VERSION.class, "RELEASE", HookConfig.ENV_RELEASE);
            setIntField(Build.VERSION.class, "SDK_INT", parseInt(HookConfig.ENV_SDK, 34));
            setField(Build.VERSION.class, "INCREMENTAL", HookConfig.ENV_INCREMENTAL);
            setField(Build.VERSION.class, "SECURITY_PATCH", HookConfig.ENV_SECURITY_PATCH);
            setField(Build.VERSION.class, "CODENAME", "REL");
            setIntField(Build.VERSION.class, "PREVIEW_SDK_INT", 0);
            XposedEntry.log("Env profile (V6 unified): release="
                + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT + " id=" + Build.ID
                + " display=" + Build.DISPLAY + " model=" + Build.MODEL
                + " device=" + Build.DEVICE + " fp=" + Build.FINGERPRINT
                + " host(real)=" + Build.HOST);
            return;
        }

        try {
            if (HookConfig.ROM_BUILD_FINGERPRINT != null) {
                XposedHelpers.setStaticObjectField(Build.class, "FINGERPRINT",
                    HookConfig.ROM_BUILD_FINGERPRINT);
            }
            if (HookConfig.ROM_BUILD_HOST != null) {
                XposedHelpers.setStaticObjectField(Build.class, "HOST",
                    HookConfig.ROM_BUILD_HOST);
            }
            if (HookConfig.ROM_BUILD_USER != null) {
                XposedHelpers.setStaticObjectField(Build.class, "USER",
                    HookConfig.ROM_BUILD_USER);
            }
            if (HookConfig.ROM_BUILD_DISPLAY != null) {
                XposedHelpers.setStaticObjectField(Build.class, "DISPLAY",
                    HookConfig.ROM_BUILD_DISPLAY);
            }
            XposedEntry.log("Build identity normalized: host=" + HookConfig.ROM_BUILD_HOST
                + " user=" + HookConfig.ROM_BUILD_USER
                + " display=" + HookConfig.ROM_BUILD_DISPLAY);
            XposedEntry.log("Build after normalize: FINGERPRINT=" + Build.FINGERPRINT
                + " HOST=" + Build.HOST + " USER=" + Build.USER
                + " DISPLAY=" + Build.DISPLAY);
        } catch (Throwable t) {
            XposedEntry.log("applyBuildIdentity failed: " + t.getMessage());
        }
    }

    private static void setField(Class<?> cls, String name, String value) {
        if (value == null) return;
        try {
            XposedHelpers.setStaticObjectField(cls, name, value);
        } catch (Throwable t) {
            XposedEntry.log("setField " + cls.getSimpleName() + "." + name + " failed: "
                + t.getMessage());
        }
    }

    private static void setIntField(Class<?> cls, String name, int value) {
        try {
            XposedHelpers.setStaticIntField(cls, name, value);
        } catch (Throwable t) {
            XposedEntry.log("setIntField " + cls.getSimpleName() + "." + name + " failed: "
                + t.getMessage());
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static void hookSystemGetProperty() {
        if (!HookConfig.ENV_SPOOF) return;
        XC_MethodHook cb = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args.length == 0 || !(param.args[0] instanceof String)) return;
                String key = (String) param.args[0];
                if ("os.version".equals(key)) {
                    if (!osPropLogged) {
                        osPropLogged = true;
                        XposedEntry.log("System.getProperty(os.version) faked -> "
                            + HookConfig.ENV_KERNEL_RELEASE);
                    }
                    param.setResult(HookConfig.ENV_KERNEL_RELEASE);
                } else if ("os.name".equals(key)) {
                    param.setResult("Linux");
                } else if ("os.arch".equals(key)) {
                    param.setResult("aarch64");
                } else if ("http.agent".equals(key)) {
                    // 【T20 核心修复】AOSP RuntimeInit.commonInit() 在 zygote fork 时
                    // 就把 http.agent 算成静态字符串：
                    //   "Dalvik/" + java.vm.version + " (Linux; U; Android "
                    //   + Build.VERSION.RELEASE + "; " + Build.MODEL + " Build/" + Build.ID + ")"
                    // 该值建立在 hook 之前，改 Build.* 字段影响不到它 → 这里直接重算。
                    // 覆盖 10 个读取点（HTTP 主路径 / 小程序 / 广点通 / Bugly / gatherer）
                    String ua = "Dalvik/" + System.getProperty("java.vm.version", "2.1.0")
                        + " (Linux; U; Android " + Build.VERSION.RELEASE
                        + "; " + Build.MODEL + " Build/" + Build.ID + ")";
                    if (!httpAgentLogged) {
                        httpAgentLogged = true;
                        XposedEntry.log("System.getProperty(http.agent) rebuilt -> " + ua);
                    }
                    param.setResult(ua);
                }
            }
        };
        try {
            XposedBridge.hookAllMethods(System.class, "getProperty", cb);
        } catch (Throwable t) {
            XposedEntry.log("System.getProperty hook failed: " + t.getMessage());
        }
    }

    private static void hookOaid() {
        final String fake = HookConfig.FAKE_OAID;
        if (fake == null || fake.isEmpty()) return;
        try {
            Class<?> impl = XposedHelpers.findClass(
                "com.tencent.qmethod.pandoraex.monitor.OaidMonitor$1",
                HookConfig.getClassLoader());
            XposedBridge.hookAllMethods(impl, "convertToStorageData", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!oaidLogged) {
                        oaidLogged = true;
                        XposedEntry.log("OAID override -> " + fake);
                    }
                    param.setResult(fake);
                }
            });
            XposedEntry.log("Hooked OaidMonitor.convertToStorageData");
        } catch (Throwable t) {
            XposedEntry.log("OAID hook failed: " + t.getMessage());
        }
    }

    private static void hookNetworkIds() {
        // Wi-Fi / Bluetooth MAC：统一成 Android 现代版本的匿名 MAC
        XC_MethodHook macHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                param.setResult("02:00:00:00:00:00");
            }
        };
        try {
            Class<?> wifiInfo = XposedHelpers.findClass("android.net.wifi.WifiInfo",
                HookConfig.getClassLoader());
            XposedBridge.hookAllMethods(wifiInfo, "getMacAddress", macHook);
            XposedBridge.hookAllMethods(wifiInfo, "getBSSID", macHook);

            // 【V12】SSID 也要处理。
            // 依据：Turing devicetoken_report 配置里 "wifissid":"1" 表示会上报，
            // 而之前只 hook 了 MAC/BSSID，真实 SSID 一直是明文出去的。
            final String fakeSsid = HookConfig.FAKE_WIFI_SSID;
            if (fakeSsid != null && !fakeSsid.isEmpty()) {
                XC_MethodHook ssidHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object r = param.getResult();
                        // 只替换真实 SSID，保留 null / <unknown ssid> 这类系统值
                        if (r instanceof String) {
                            String v = (String) r;
                            if (!v.isEmpty() && !v.contains("unknown")) {
                                // 【验证】记录原始值，证明 hook 真的拦到了
                                if (!ssidOriginLogged) {
                                    ssidOriginLogged = true;
                                    XposedEntry.log("[SSID] origin=" + v
                                        + " -> fake=" + fakeSsid);
                                }
                                // Android 的 getSSID 返回带引号的值
                                param.setResult("\"" + fakeSsid + "\"");
                            }
                        }
                    }
                };
                XposedBridge.hookAllMethods(wifiInfo, "getSSID", ssidHook);
                XposedEntry.log("WifiInfo SSID forced: " + fakeSsid);
            }
        } catch (Throwable t) {
            XposedEntry.log("WifiInfo MAC hook failed: " + t.getMessage());
        }

        // 【V12】ScanResult.SSID：扫描列表里也有 SSID
        try {
            final String fakeSsid2 = HookConfig.FAKE_WIFI_SSID;
            if (fakeSsid2 != null && !fakeSsid2.isEmpty()) {
                Class<?> wm = XposedHelpers.findClass("android.net.wifi.WifiManager",
                    HookConfig.getClassLoader());
                XposedBridge.hookAllMethods(wm, "getScanResults", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object r = param.getResult();
                        if (!(r instanceof java.util.List)) return;
                        java.util.List<?> list = (java.util.List<?>) r;
                        // 只保留一项，且 SSID 改成伪造值，避免暴露周边网络环境
                        java.util.List<Object> kept = new java.util.ArrayList<>();
                        for (Object item : list) {
                            if (item == null) continue;
                            try {
                                java.lang.reflect.Field f = item.getClass()
                                    .getField("SSID");
                                f.set(item, fakeSsid2);
                            } catch (Throwable ignored) {
                            }
                            kept.add(item);
                            if (kept.size() >= 3) break;
                        }
                        if (!kept.isEmpty()) param.setResult(kept);
                    }
                });
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> bt = XposedHelpers.findClass("android.bluetooth.BluetoothAdapter",
                HookConfig.getClassLoader());
            XposedBridge.hookAllMethods(bt, "getAddress", macHook);
        } catch (Throwable t) {
            XposedEntry.log("Bluetooth MAC hook failed: " + t.getMessage());
        }

        // IMSI（订阅者 ID）
        HookUtils.hookAllMethods("android.telephony.TelephonyManager", "getSubscriberId",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult("000000000000000");
                }
            });
    }

    private static void hookSettingsIds() {
        if (!HookConfig.ENV_SPOOF) return;
        final String aaid = HookConfig.ENV_AAID;
        XC_MethodHook aaidHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args.length < 2 || !(param.args[1] instanceof String)) return;
                String key = (String) param.args[1];
                if ("ad_aaid".equals(key) || "oaid".equals(key)
                    || "mi_health_id".equals(key) || "gcbooster_uuid".equals(key)
                    || "key_mqs_uuid".equals(key)) {
                    param.setResult(aaid);
                }
            }
        };
        HookUtils.hookAllMethods("android.provider.Settings$Global", "getString", aaidHook);
        HookUtils.hookAllMethods("android.provider.Settings$Global", "getStringForUser", aaidHook);
        HookUtils.hookAllMethods("android.provider.Settings$Secure", "getStringForUser", aaidHook);
    }

    private static void hookQimeiIdentity() {
        // QIMEI36 是腾讯服务端下发的设备标识：清本地缓存无效，SDK 联网后照样取回
        // 属于旧设备的那个值，因此必须从读取点强制返回伪造值。
        final String fake36 = HookConfig.FAKE_QIMEI36;
        if (fake36 == null || fake36.isEmpty()) {
            return;
        }
        final String fake16 = fake36.length() >= 16 ? fake36.substring(0, 16) : fake36;

        // 1) QimeiSDK / Qimei 对象
        for (String clsName : new String[]{
                "com.tencent.qimei.sdk.Qimei",
                "com.tencent.qimei.sdk.QimeiSDK",
                "com.tencent.qimei.sdk.QimeiCore"}) {
            try {
                Class<?> c = XposedHelpers.findClass(clsName, HookConfig.getClassLoader());
                XposedBridge.hookAllMethods(c, "getQimei36", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(fake36);
                    }
                });
                XposedBridge.hookAllMethods(c, "getQimei16", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(fake16);
                    }
                });
                XposedEntry.log("Qimei identity forced on " + clsName);
            } catch (Throwable ignored) {
            }
        }

        // 2) MSF 侧接口的具体实现（接口方法本身是 abstract，hook 不到）
        try {
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("com.tencent.qphone.base.util.IMSFQimei",
                    HookConfig.getClassLoader()),
                "getQimei36", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(fake36);
                    }
                });
        } catch (Throwable ignored) {
        }

        // 3) 兜底：QQ 自己的 QimeiResolver 式入口
        for (String clsName : new String[]{
                "com.tencent.mobileqq.qimei.QQQimeiResolver",
                "com.tencent.mobileqq.msf.core.qimei.MSFQimei"}) {
            try {
                Class<?> c = XposedHelpers.findClass(clsName, HookConfig.getClassLoader());
                XposedBridge.hookAllMethods(c, "getQimei36", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(fake36);
                    }
                });
                XposedEntry.log("Qimei identity forced on " + clsName);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 与 native 层保持一致的伪造值 */
    private static String fakeSystemProperty(String key) {
        if (key == null) return null;

        if (HookConfig.ENV_SPOOF) {
            switch (key) {
                case "ro.build.version.release": return HookConfig.ENV_RELEASE;
                case "ro.build.version.sdk": return HookConfig.ENV_SDK;
                case "ro.build.id": return HookConfig.ENV_BUILD_ID;
                case "ro.build.display.id": return HookConfig.ENV_DISPLAY;
                case "ro.build.fingerprint":
                case "ro.system.build.fingerprint":
                case "ro.system_ext.build.fingerprint":
                case "ro.vendor.build.fingerprint":
                case "ro.odm.build.fingerprint":
                case "ro.product.build.fingerprint":
                case "ro.bootimage.build.fingerprint":
                    return HookConfig.ENV_FINGERPRINT;
                case "ro.build.description": return HookConfig.ENV_DESCRIPTION;
                // 【T12 修正】ro.build.host / ro.build.user 不再伪造：
                // 真机 ReVork / Sabrina Carpenter 本就是 OnePlus 原厂构建机名，完全正常。
                // 之前的伪造值（"build"/"builder"）反而产生了 abuild 这种真机不可能有的值，
                // 服务端拿指纹库一比即知被篡改 → 改为透传真机值。
                // case "ro.build.host": return HookConfig.ENV_HOST;
                // case "ro.build.user": return HookConfig.ENV_USER;
                case "ro.build.date": return HookConfig.ENV_DATE;
                case "ro.build.version.incremental": return HookConfig.ENV_INCREMENTAL;
                case "ro.build.version.security_patch": return HookConfig.ENV_SECURITY_PATCH;
                case "ro.build.version.opporom": return HookConfig.ENV_OPPOROM;
                case "ro.build.version.codename": return "REL";
                case "ro.build.version.all_codenames": return "REL";
                case "ro.build.version.preview_sdk": return "0";
                case "ro.build.version.preview_sdk_int": return "0";
                case "ro.product.model":
                case "ro.product.vendor.model":
                case "ro.product.system.model":
                case "ro.product.odm.model":
                case "ro.product.product.model":
                    return HookConfig.ENV_MODEL;
                case "ro.product.name":
                case "ro.product.vendor.name":
                case "ro.product.system.name":
                case "ro.product.odm.name":
                case "ro.product.product.name":
                    return HookConfig.ENV_PRODUCT_NAME;
                case "ro.product.device":
                case "ro.product.vendor.device":
                case "ro.product.system.device":
                case "ro.product.odm.device":
                case "ro.product.product.device":
                case "ro.build.product":
                    return HookConfig.ENV_DEVICE;
                case "ro.product.brand":
                case "ro.product.vendor.brand":
                case "ro.product.system.brand":
                    return HookConfig.ENV_BRAND;
                case "ro.product.manufacturer":
                case "ro.product.vendor.manufacturer":
                case "ro.product.system.manufacturer":
                    return HookConfig.ENV_MANUFACTURER;
                default: break;
            }
        }

        if ("ro.build.host".equals(key) && HookConfig.ROM_BUILD_HOST != null) {
            return HookConfig.ROM_BUILD_HOST;
        }
        if ("ro.build.user".equals(key) && HookConfig.ROM_BUILD_USER != null) {
            return HookConfig.ROM_BUILD_USER;
        }
        if ("ro.build.display.id".equals(key) && HookConfig.ROM_BUILD_DISPLAY != null) {
            return HookConfig.ROM_BUILD_DISPLAY;
        }
        if (HookConfig.ROM_BUILD_FINGERPRINT != null) {
            switch (key) {
                case "ro.build.fingerprint":
                case "ro.system.build.fingerprint":
                case "ro.system_ext.build.fingerprint":
                case "ro.vendor.build.fingerprint":
                case "ro.odm.build.fingerprint":
                case "ro.product.build.fingerprint":
                case "ro.bootimage.build.fingerprint":
                    return HookConfig.ROM_BUILD_FINGERPRINT;
                default:
                    break;
            }
        }

        if (cachedSerial != null && ("ro.serialno".equals(key)
                || "ro.boot.serialno".equals(key)
                || "gsm.serial".equals(key)
                || "ro.vendor.oplus.radio.serialno".equals(key)
                || "vendor.oplus.caihong.serialno".equals(key)
                || "persist.sys.oplus.serialno".equals(key))) {
            return cachedSerial;
        }
        if ("ro.boot.verifiedbootstate".equals(key) && HookConfig.FAKE_BOOT_STATE != null) {
            return HookConfig.FAKE_BOOT_STATE;
        }
        if ("ro.boot.flash.locked".equals(key)) return "1";
        if ("ro.boot.vbmeta.device_state".equals(key)) return "locked";
        if ("ro.boot.veritymode".equals(key)) return "enforcing";
        if ("ro.boot.warranty_bit".equals(key)) return "0";
        if ("ro.warranty_bit".equals(key)) return "0";
        if ("ro.vendor.warranty_bit".equals(key)) return "0";
        if ("ro.vendor.boot.warranty_bit".equals(key)) return "0";
        if ("ro.boot.selinux".equals(key)) return "enforcing";
        if ("ro.debuggable".equals(key)) return "0";
        if ("ro.secure".equals(key)) return "1";
        if ("ro.kernel.qemu".equals(key)) return "";
        return null;
    }

    private static boolean isProbeKey(String key) {
        if (key == null) return false;
        String[] kw = {"serial", "verifiedboot", "warranty", "flash.locked",
            "vbmeta", "debuggable", "qemu", "bootloader", "boot.hardware",
            "build.tags", "selinux", "soc", "imei", "android_id", "qimei"};
        for (String k : kw) {
            if (key.contains(k)) return true;
        }
        return false;
    }

    private static void hookAndroidId() {
        // Hook Settings.Secure.getString for android_id
        HookUtils.hookAllMethods("android.provider.Settings.Secure", "getString",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length >= 2) {
                        String key = (String) param.args[1];
                        if (HookConfig.PROBE && "android_id".equals(key)) {
                            XposedEntry.log("[PROBE] Settings.Secure.getString(android_id) -> "
                                + param.getResult());
                        }
                        if ("android_id".equals(key) && cachedAndroidId != null) {
                            param.setResult(cachedAndroidId);
                        }
                    }
                }
            });
    }

    private static void hookPandoraMonitor() {
        // Pandora (com.tencent.qmethod.pandoraex) is the single gateway QQ uses to
        // read device identifiers; it exposes several methods per identifier. Only
        // getImei used to be hooked, so AndroidID / Serial / OAID / MEID / IMSI were
        // still being read as their real values -- and reported to the server.
        final String[] pandoraClasses = {
            "com.tencent.qmethod.pandoraex.DeviceInfoMonitor",
            "com.tencent.qmethod.pandoraex.monitor.DeviceInfoMonitor",
            "com.tencent.qmethod.pandoraex.core.DeviceInfoMonitor"
        };
        final String[] pandoraMethods = {
            "getImei", "getMeid", "getDeviceId", "getAndroidId", "getSerial",
            "getSerialNumber", "getOaid", "getImsi", "getSubscriberId",
            "getSimSerialNumber", "getMacAddress", "getSimOperator",
            "getNetworkOperator", "getString",
            // 【T20 修复】getModel 从未被 hook → WebView 路径拿真机 LE2123
            // DeviceInfoMonitor.getModel() 内部有 lastModel 缓存，必须 afterHook
            // 用 setResult 覆盖（本处已是 afterHook，符合要求）
            "getModel",
            // 【T20 修复】反射直调 SystemProperties.get()，绕过 native __system_property_get
            "getStringFormSystemProperties"
        };

        for (final String clsName : pandoraClasses) {
            for (final String m : pandoraMethods) {
                HookUtils.hookAllMethods(clsName, m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object r = param.getResult();
                        String name = param.method.getName();
                        String fake = pandoraFake(name, param.args, r);
                        XposedEntry.log("[PROBE] Pandora." + name
                            + (fake != null ? " real=" + r + " -> forced" : " -> " + r));
                        if (fake != null && r instanceof String) {
                            param.setResult(fake);
                        }
                    }
                });
            }
        }

        // Hook MonitorReporter to block anomaly reports
        HookUtils.hookAllMethods("com.tencent.qmethod.pandoraex.MonitorReporter", "report",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // Block all monitoring reports
                    param.setResult(null);
                }
            });

        HookUtils.hookAllMethods("com.tencent.qmethod.pandoraex.MonitorReporter", "getStrategyAndReport",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // Return a safe strategy that doesn't trigger checks
                    param.setResult(0);
                }
            });
    }

    /** 按 Pandora 方法名返回一致的伪造值；不确定的返回 null（只记录不替换） */
    private static String pandoraFake(String method, Object[] args, Object realResult) {
        if (method == null) return null;
        switch (method) {
            case "getImei":
            case "getMeid":
            case "getDeviceId":
                return cachedImei != null ? cachedImei : HookConfig.FAKE_IMEI;
            case "getAndroidId":
                return cachedAndroidId != null ? cachedAndroidId : HookConfig.FAKE_ANDROID_ID;
            case "getSerial":
            case "getSerialNumber":
                return cachedSerial != null ? cachedSerial : HookConfig.FAKE_SERIAL;
            case "getOaid":
                return HookConfig.FAKE_OAID;
            case "getImsi":
            case "getSubscriberId":
            case "getSimSerialNumber":
                // 没有独立的伪造值，统一沿用 IMEI 派生串，避免暴露真实 ICCID/IMSI
                return null;
            case "getString":
                if (args != null && args.length > 1 && args[1] instanceof String) {
                    String key = (String) args[1];
                    if ("android_id".equals(key)) {
                        return cachedAndroidId != null ? cachedAndroidId : HookConfig.FAKE_ANDROID_ID;
                    }
                    if ("oaid".equals(key) || "ad_aaid".equals(key)) {
                        return HookConfig.FAKE_OAID;
                    }
                }
                return null;
            case "getModel":
                // 【T20】DeviceInfoMonitor.getModel() → 伪造机型（WebView UA 生产者用它）
                return HookConfig.ENV_MODEL;
            case "getStringFormSystemProperties":
                // 【T20】反射读 SystemProperties，绕过 native hook
                if (args != null && args.length > 0 && args[0] instanceof String) {
                    String k = (String) args[0];
                    if ("ro.product.model".equals(k)) return HookConfig.ENV_MODEL;
                    if ("ro.product.brand".equals(k)) return HookConfig.ENV_BRAND;
                    if ("ro.product.manufacturer".equals(k)) return HookConfig.ENV_MANUFACTURER;
                    if ("ro.product.device".equals(k)) return HookConfig.ENV_DEVICE;
                    if ("ro.build.id".equals(k)) return HookConfig.ENV_BUILD_ID;
                    if ("ro.build.display.id".equals(k)) return HookConfig.ENV_DISPLAY;
                    if ("ro.build.version.release".equals(k)) return HookConfig.ENV_RELEASE;
                    if ("ro.build.version.sdk".equals(k)) return HookConfig.ENV_SDK;
                    if ("ro.build.fingerprint".equals(k)) return HookConfig.ENV_FINGERPRINT;
                    if ("ro.serialno".equals(k)) return HookConfig.FAKE_SERIAL;
                }
                return null;
            default:
                return null;
        }
    }
}
