#include "hook_libturingxq.h"
#include "../utils/log.h"
#include <bytehook.h>
#include <string.h>
#include <stdio.h>
#include <sys/socket.h>
#include <unistd.h>
#include <stdint.h>
#include <link.h>

namespace hook_libturingxq {

/**
 * libturingxq.so 高度混淆，只导出 JNI_OnLoad，抓不到内部发送函数符号。
 *
 * 之前的做法是在 buffer 里找 "riskCheckWup" 之类的明文关键字 —— 对加密上报
 * 完全无效（实测命中 0 次）。
 *
 * 现在的做法按**调用方地址**判定：Turing 的数据一定是从 libturingxq.so 里的
 * 代码发起的，因此 hook send/sendto 后取 __builtin_return_address(0)，只要它
 * 落在这个库的加载范围内就丢弃。QQ 自己的网络发送不受影响。
 */

static uintptr_t g_turing_lo = 0;
static uintptr_t g_turing_hi = 0;
static int g_blocked_count = 0;

// libfekit 是主检测库（7.6MB，Actually 从服务端动态下发 strip 版）。
// 把它的地址范围也解析出来，用于观测它自己的 syscall。
static uintptr_t g_fekit_lo = 0;
static uintptr_t g_fekit_hi = 0;
static int g_fekit_seen = 0;

static int find_fekit_cb(struct dl_phdr_info* info, size_t size, void* data) {
    (void) size; (void) data;
    if (!info || !info->dlpi_name) return 0;
    if (!strstr(info->dlpi_name, "libfekit")) return 0;
    uintptr_t lo = (uintptr_t) info->dlpi_addr;
    uintptr_t hi = lo;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr)* ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD) continue;
        uintptr_t seg_end = lo + ph->p_vaddr + ph->p_memsz;
        if (seg_end > hi) hi = seg_end;
    }
    g_fekit_lo = lo;
    g_fekit_hi = hi;
    LOGI("libfekit range resolved: %p - %p", (void*) lo, (void*) hi);
    return 1;
}

static inline void ensure_fekit_range() {
    if (g_fekit_lo == 0) dl_iterate_phdr(find_fekit_cb, nullptr);
}

static inline bool caller_in_fekit(void* caller) {
    uintptr_t a = (uintptr_t) caller;
    return g_fekit_lo != 0 && a >= g_fekit_lo && a < g_fekit_hi;
}

static int find_turing_cb(struct dl_phdr_info* info, size_t size, void* data) {
    (void) size;
    (void) data;
    if (!info || !info->dlpi_name) return 0;
    if (!strstr(info->dlpi_name, "libturing")) return 0;

    uintptr_t lo = (uintptr_t) info->dlpi_addr;
    uintptr_t hi = lo;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr)* ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD) continue;
        uintptr_t seg_end = lo + ph->p_vaddr + ph->p_memsz;
        if (seg_end > hi) hi = seg_end;
    }
    g_turing_lo = lo;
    g_turing_hi = hi;
    LOGI("libturingxq range resolved: %p - %p (%s)",
         (void*) g_turing_lo, (void*) g_turing_hi, info->dlpi_name);
    return 1;  // found, stop iterating
}

// 库可能晚于 hook 安装才被加载，因此在发送路径上做一次懒解析
static inline void ensure_range() {
    if (g_turing_lo == 0) {
        dl_iterate_phdr(find_turing_cb, nullptr);
    }
}

static inline bool caller_in_turing(void* caller) {
    uintptr_t a = (uintptr_t) caller;
    return g_turing_lo != 0 && a >= g_turing_lo && a < g_turing_hi;
}

// 记录调用者相对库基址的偏移：拿着这个偏移就能在 IDA 里直接定位到
// "构造明文 -> 加密 -> write" 链条上的那个函数。
static void log_caller(const char* what, void* caller, size_t len) {
    uintptr_t a = (uintptr_t) caller;
    if (!g_turing_lo) return;
    LOGI("turing %s len=%zu caller_off=0x%zx", what, len, (size_t)(a - g_turing_lo));
}

// 抓取实际发出的字节，用于判断加密强度（简单 XOR / 流加密 / 分块）
static void log_bytes(const void* buf, size_t len) {
    if (!buf || len == 0) return;
    size_t n = len < 128 ? len : 128;
    char hex[128 * 2 + 1];
    const unsigned char* p = (const unsigned char*) buf;
    for (size_t i = 0; i < n; i++) {
        snprintf(hex + i * 2, 3, "%02x", p[i]);
    }
    hex[n * 2] = 0;
    LOGI("turing bytes[%zu]=%s", n, hex);
}

// send 代理
static ssize_t fake_send(int sockfd, const void* buf, size_t len, int flags) {
    BYTEHOOK_STACK_SCOPE();
    ensure_range();
    // 实测：阻断 Turing 上报会让会话从 ~120 分钟骤降到 7 分钟 —— 服务端依赖它的
    // 数据，缺失即判异常。因此这里只观测、放行，绝不拦截。
    if (caller_in_turing(__builtin_return_address(0))) {
        if (__atomic_fetch_add(&g_blocked_count, 1, __ATOMIC_RELAXED) < 300) {
            log_caller("send", __builtin_return_address(0), len);
            log_bytes(buf, len);
        }
    }
    return BYTEHOOK_CALL_PREV(fake_send, sockfd, buf, len, flags);
}

// sendto 代理
static ssize_t fake_sendto(int sockfd, const void* buf, size_t len, int flags,
                           const struct sockaddr* dest_addr, socklen_t addrlen) {
    BYTEHOOK_STACK_SCOPE();
    ensure_range();
    if (caller_in_turing(__builtin_return_address(0))) {
        if (__atomic_fetch_add(&g_blocked_count, 1, __ATOMIC_RELAXED) < 300) {
            log_caller("sendto", __builtin_return_address(0), len);
            log_bytes(buf, len);
        }
    }
    return BYTEHOOK_CALL_PREV(fake_sendto, sockfd, buf, len, flags, dest_addr, addrlen);
}

// write 代理（部分 native 库直接对 socket fd 调 write）
// 手工遍历 frame pointer 链，定位 write 的调用者（即加密函数）。
// ARM64 栈帧：[fp] = 上一帧 fp，[fp+8] = saved LR。
static void dump_turing_stack() {
    void** fp = (void**) __builtin_frame_address(0);
    for (int i = 0; i < 8; i++) {
        if (!fp) break;
        uintptr_t next = (uintptr_t) fp[0];
        uintptr_t lr = (uintptr_t) fp[1];
        if (g_turing_lo && lr >= g_turing_lo && lr < g_turing_hi) {
            LOGI("turing frame[%d] off=0x%zx", i, (size_t)(lr - g_turing_lo));
        }
        if (next <= (uintptr_t) fp) break;
        fp = (void**) next;
    }
}

static ssize_t fake_write(int fd, const void* buf, size_t count) {
    BYTEHOOK_STACK_SCOPE();
    ensure_range();
    if (caller_in_turing(__builtin_return_address(0))) {
        if (__atomic_fetch_add(&g_blocked_count, 1, __ATOMIC_RELAXED) < 300) {
            log_caller("write", __builtin_return_address(0), count);
            log_bytes(buf, count);
        }
    }
    return BYTEHOOK_CALL_PREV(fake_write, fd, buf, count);
}

// ---------------------------------------------------------------------------
// syscall 观测：libturingmfa.so 导出 real_syscall，其内部转调 libc syscall。
// 只在调用者位于 Turing 库内时记录（syscall 号 + 前两个参数 + 路径），
// 从而拿到 Turing 完整的检测清单（它 stat/access/opendir 了哪些路径）。
//
// 安全要点：不使用 malloc/std::string/TLS，只写固定栈缓冲，避免重演之前在
// pthread_exit 路径上的 SIGSEGV。
// ---------------------------------------------------------------------------
static long fake_syscall(long number, long a1, long a2, long a3, long a4,
                         long a5, long a6) {
    BYTEHOOK_STACK_SCOPE();
    ensure_range();
    ensure_fekit_range();
    void* syscaller = __builtin_return_address(0);
    if (caller_in_fekit(syscaller)) {
        // fekit 是主检测库：它每一次 syscall 都说明一个检测动作
        int idx = __atomic_fetch_add(&g_fekit_seen, 1, __ATOMIC_RELAXED);
        if (idx < 120) {
            const char* pth = nullptr;
            if (number == 56 || number == 79) pth = (const char*) a2;
            else if (number == 48) pth = (const char*) a1;
            if (pth) {
                char b[160]; size_t i = 0;
                for (; i < sizeof(b) - 1; i++) { char ch = pth[i]; if (ch == 0) break; b[i] = ch; }
                b[i] = 0;
                LOGI("fekit syscall nr=%ld path=%s", number, b);
            } else {
                LOGI("fekit syscall nr=%ld a1=%ld a2=%ld", number, a1, a2);
            }
        }
    } else if (caller_in_turing(syscaller)) {
        int idx = __atomic_fetch_add(&g_blocked_count, 1, __ATOMIC_RELAXED);
        if (idx < 300) {
            // 常见路径类 syscall：openat(56)/access(48)/newfstatat(79)/statx(291)
            const char* path = nullptr;
            if (number == 56 || number == 79) {
                path = (const char*) a2;   // openat(dirfd, path, ...)
            } else if (number == 48) {
                path = (const char*) a1;   // access(path, mode)
            } else if (number == 291) {
                path = (const char*) a2;
            }
            if (path) {
                char buf[160];
                size_t i = 0;
                for (; i < sizeof(buf) - 1; i++) {
                    char ch = path[i];
                    if (ch == 0) break;
                    buf[i] = ch;
                }
                buf[i] = 0;
                LOGI("turing syscall nr=%ld path=%s", number, buf);
            } else {
                LOGI("turing syscall nr=%ld a1=%ld a2=%ld", number, a1, a2);
            }
        }
    }
    return BYTEHOOK_CALL_PREV(fake_syscall, number, a1, a2, a3, a4, a5, a6);
}

// ---------------------------------------------------------------------------
// 三层反检测验证探针（2026-11-26）
//
// libturingxq 静态导出了 real_syscall，反汇编确认其实现是内联 svc #1：
//   mov x8, x0 ; mov x0..x5, x1..x6 ; svc #1
// 也就是说它完全绕过 libc，hook libc 的 syscall 对它无效。
// 但它本身是导出符号，ByteHook 可以直接 hook 它 —— 参数固定 7 个，非可变参数。
//
// 注意：曾经 hook libc 的 syscall 导致 QQ 在登录路径异常退出（可变参数解引用
// 无效指针）。本探针只作用于 libturingxq 自己的 real_syscall，且原样转发，
// 不改任何行为，风险面小得多。
// ---------------------------------------------------------------------------

typedef long (*real_syscall_t)(long nr, long a1, long a2, long a3,
                               long a4, long a5, long a6);

static int g_rs_count = 0;
static const int RS_LOG_LIMIT = 150;

static long fake_real_syscall(long nr, long a1, long a2, long a3,
                              long a4, long a5, long a6) {
    BYTEHOOK_STACK_SCOPE();
    if (g_rs_count < RS_LOG_LIMIT) {
        g_rs_count++;
        // syscall 号对照：56=openat 57=close 63=read 78=readlinkat 79=newfstatat
        //                48=faccessat 178=gettid 172=getpid
        LOGI("REAL_SYSCALL nr=%ld a1=%ld a2=%ld a3=%ld a4=%ld",
             nr, a1, a2, a3, a4);
    }
    return BYTEHOOK_CALL_PREV(fake_real_syscall, nr, a1, a2, a3, a4, a5, a6);
}

// dl_iterate_phdr：Turing 的 L2 层（枚举已加载 so）如果启用，必然经 libc 调用它。
static int g_dlip_count = 0;
static const int DLIP_LOG_LIMIT = 30;

static int fake_dl_iterate_phdr(int (*cb)(struct dl_phdr_info*, size_t, void*),
                                void* data) {
    BYTEHOOK_STACK_SCOPE();
    // 先验证 hook 本身生效：记录前几次任意来源的调用
    static int any_count = 0;
    if (any_count < 8) {
        any_count++;
        void* ra = __builtin_return_address(0);
        bool ft = caller_in_turing(ra);
        LOGI("DLIP call#%d ra=%p from_turing=%d", any_count, ra, (int) ft);
    }
    bool from_turing = caller_in_turing(__builtin_return_address(0));
    if (from_turing && g_dlip_count < DLIP_LOG_LIMIT) {
        g_dlip_count++;
        LOGI("DLIP from libturingxq (cb=%p) <- L2 layer IS active", (void*) cb);
    }
    return BYTEHOOK_CALL_PREV(fake_dl_iterate_phdr, cb, data);
}

void install_hooks() {
    // 先尝试解析一次；解析不到也没关系，发送路径上会再试
    dl_iterate_phdr(find_turing_cb, nullptr);
    if (g_turing_lo == 0) {
        LOGI("libturingxq not loaded yet; caller check will resolve lazily");
    }

    LOGI("Installing libturingxq caller-based blocking (ByteHook)...");
    bytehook_hook_all(nullptr, "send", (void*) fake_send, nullptr, nullptr);
    bytehook_hook_all(nullptr, "sendto", (void*) fake_sendto, nullptr, nullptr);
    bytehook_hook_all(nullptr, "write", (void*) fake_write, nullptr, nullptr);
    // 审计用 syscall hook 已停用：在登录路径上会解引用无效指针导致 QQ 异常退出。
    // bytehook_hook_all(nullptr, "syscall", (void*) fake_syscall, nullptr, nullptr);

    // ---- 三层反检测验证（只记录，不改行为）----
    // L1: Turing 自己的 real_syscall（内联 svc #1，绕过 libc）
    bytehook_stub_t s1 = bytehook_hook_all(nullptr, "real_syscall",
                                           (void*) fake_real_syscall, nullptr, nullptr);
    LOGI("hook real_syscall -> %s", s1 ? "ok" : "FAILED");

    // L2: dl_iterate_phdr（Turing 若枚举已加载 so 必经此）
    bytehook_stub_t s2 = bytehook_hook_all(nullptr, "dl_iterate_phdr",
                                           (void*) fake_dl_iterate_phdr, nullptr, nullptr);
    LOGI("hook dl_iterate_phdr -> %s", s2 ? "ok" : "FAILED");

    LOGI("libturingxq hooks installed (blocked=%d)", g_blocked_count);
}

} // namespace hook_libturingxq