#ifndef NATIVE_BYPASS_HOOK_LIBC_H
#define NATIVE_BYPASS_HOOK_LIBC_H

namespace hook_libc {

/**
 * Hook libc functions to intercept file I/O and system properties:
 * - fopen/fopen64: redirect /proc/self/maps, smaps, mountinfo, cmdline, status
 * - open/open64/openat/openat64/__open_2: same, bypassing fortify wrappers
 * - readlink: hide /proc/self/exe path
 * - access/stat/lstat: hide suspicious files
 * - __system_property_get / __system_property_read_callback:
 *     hide bootloader-unlocked / debuggable properties
 *
 * 配置由 Java 层在 initNativeHooks() 之前通过 set_config() 注入。
 */
void install_hooks();

/**
 * @param probe       是否输出探测日志（PROBE）
 * @param fake_serial 非空时替换 ro.serialno / ro.boot.serialno / 命令行序列号
 * @param fake_soc    非空时替换 /sys/devices/soc0/serial_number 内容
 * @param boot_state  非空时替换 verifiedbootstate（默认 green）
 */
void set_config(bool probe, const char* fake_serial, const char* fake_soc, const char* boot_state,
                const char* rom_host, const char* rom_user, const char* rom_display,
                const char* rom_fingerprint);

/**
 * 完整环境画像（19 个字段，'|' 分隔）：
 * release|sdk|build_id|display|fingerprint|description|host|user|date|
 * incremental|security_patch|opporom|kernel_release|kernel_version|
 * model|product_name|device|brand|manufacturer
 */
void set_env_profile(const char* profile);

/**
 * 调用者归因（观测）：记录 libc 访问的真实发起方（libfekit / libturing* / libMSF）。
 * @param enabled 总开关（默认开）
 * @param deep    RA 落在 libc/libbytehook 中转层时是否 backtrace 往上找（默认开）
 */
void set_attr_config(bool enabled, bool deep);

bool probe_enabled();
const char* fake_serial();
const char* fake_soc();
const char* boot_state();

} // namespace hook_libc

#endif // NATIVE_BYPASS_HOOK_LIBC_H