#include "hook_libfekit.h"
#include "hook_libc.h"
#include "../utils/log.h"
#include <bytehook.h>
#include <stdio.h>
#include <string.h>

#include <string>

namespace hook_libfekit {

/**
 * libfekit.so 的检测函数符号被剥离，无法直接 hook。
 * 策略：拦截它依赖的 libc 系统调用（命令执行、字符串匹配）。
 *
 * 重要：native 层检测常通过 popen("getprop ro.boot.verifiedbootstate") /
 * system("cat /proc/mounts | grep magisk") 之类的子进程完成，
 * 子进程里的属性读取不会经过本进程的 hook，所以必须在 popen/system 出口
 * 直接伪造输出或拦截命令。
 */

static bool contains_any(const char* s, const char* const* list) {
    if (!s) return false;
    for (int i = 0; list[i]; i++) {
        if (strstr(s, list[i])) return true;
    }
    return false;
}

static const char* kBlocked[] = {
    "magisk", "kernelsu", "apatch", "lsposed", "xposed", "frida",
    "substrate", "busybox", "/proc/mounts", "/proc/self/mounts",
    "grep -i su", "which su", "superuser", nullptr
};

static bool is_getprop_sensitive(const char* cmd) {
    if (!cmd || !strstr(cmd, "getprop")) return false;
    static const char* keys[] = {
        "verifiedboot", "serialno", "debuggable", "qemu", "warranty",
        "flash.locked", "vbmeta", "ro.secure", "bootloader", "boot.hardware",
        nullptr
    };
    return contains_any(cmd, keys);
}

static std::string fake_getprop_output(const char* cmd) {
    if (strstr(cmd, "verifiedboot")) {
        return std::string(hook_libc::boot_state()) + "\n";
    }
    if (strstr(cmd, "serialno") && hook_libc::fake_serial()[0]) {
        return std::string(hook_libc::fake_serial()) + "\n";
    }
    if (strstr(cmd, "debuggable")) return "0\n";
    if (strstr(cmd, "ro.secure")) return "1\n";
    if (strstr(cmd, "qemu")) return "\n";
    if (strstr(cmd, "warranty")) return "0\n";
    if (strstr(cmd, "flash.locked")) return "1\n";
    if (strstr(cmd, "vbmeta")) return "locked\n";
    return "\n";
}

static FILE* fake_tmpfile_with(const std::string& content) {
    FILE* f = tmpfile();
    if (!f) return nullptr;
    if (!content.empty()) fwrite(content.data(), 1, content.size(), f);
    rewind(f);
    return f;
}

// system 代理
static int fake_system(const char* command) {
    BYTEHOOK_STACK_SCOPE();

    if (!command) return BYTEHOOK_CALL_PREV(fake_system, command);

    if (hook_libc::probe_enabled()) {
        LOGI("PROBE system: %.200s", command);
    }
    if (contains_any(command, kBlocked)) {
        LOGI("Blocked system command: %.200s", command);
        return 0;
    }
    if (is_getprop_sensitive(command)) {
        LOGI("Faked system getprop: %.200s", command);
        return 0;
    }
    return BYTEHOOK_CALL_PREV(fake_system, command);
}

// popen 代理
static FILE* fake_popen(const char* command, const char* type) {
    BYTEHOOK_STACK_SCOPE();

    if (!command) return BYTEHOOK_CALL_PREV(fake_popen, command, type);

    if (hook_libc::probe_enabled()) {
        LOGI("PROBE popen: %.200s", command);
    }
    if (contains_any(command, kBlocked)) {
        LOGI("Blocked popen command: %.200s", command);
        return fake_tmpfile_with("\n");
    }
    if (is_getprop_sensitive(command)) {
        std::string out = fake_getprop_output(command);
        LOGI("Faked popen getprop: %.160s -> %.40s", command, out.c_str());
        return fake_tmpfile_with(out);
    }
    return BYTEHOOK_CALL_PREV(fake_popen, command, type);
}

// strstr 代理：仅拦截 libfekit.so 对敏感关键字的匹配
static char* fake_strstr(const char* haystack, const char* needle) {
    BYTEHOOK_STACK_SCOPE();

    char* result = BYTEHOOK_CALL_PREV(fake_strstr, haystack, needle);
    if (result && needle) {
        const char* sensitive[] = {
            "lsposed", "xposed", "riru", "zygisk", "magisk", "frida", "KSU",
            ".magisk", "kernelsu", "apatch", nullptr
        };
        for (int i = 0; sensitive[i]; i++) {
            if (strcmp(needle, sensitive[i]) == 0) {
                LOGD("Blocked strstr match: %s", needle);
                return nullptr;
            }
        }
    }
    return result;
}

void install_hooks() {
    LOGI("Installing libfekit hooks (ByteHook)...");

    bytehook_stub_t s1 = bytehook_hook_all(nullptr, "system", (void*)fake_system, nullptr, nullptr);
    bytehook_stub_t s2 = bytehook_hook_all(nullptr, "popen", (void*)fake_popen, nullptr, nullptr);
    bytehook_stub_t s3 = bytehook_hook_single("libfekit.so", nullptr, "strstr",
                                              (void*)fake_strstr, nullptr, nullptr);
    LOGI("libfekit hooks installed (system=%s popen=%s strstr=%s)",
         s1 ? "ok" : "fail", s2 ? "ok" : "fail", s3 ? "ok" : "fail");
}

} // namespace hook_libfekit