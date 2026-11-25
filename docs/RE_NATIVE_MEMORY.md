# `libnative-memory-library-lib.so` 完整逆向（2026-11-25）

> **核心结论：它是未启用的 APM 监控框架，不是安全检测层。**
> 之前根据参考项目说法把它列为「漏掉的检测层」是**误判**。

---

## 一、决定性证据：库已加载但从未初始化

| 证据 | 方法 | 结果 |
|---|---|---|
| **单例指针为 NULL** | 读 `.bss` 的 `0xa8478`（NativeMonitor 单例） | **全零 `00 00 00 00 00 00 00 00`** |
| `.bss` 仅 1 页 | `maps` 里 `[anon:.bss]` 只有 4KB | 基本未使用 |
| **检测日志 0 次** | `logcat \| grep -c` | `SoLoadHooker`=0 / `terminate`=0 / `nativememorymonitor`=0 |
| Java 层无痕迹 | `logcat \| grep -c nativememorymonitor` | **0** |

**库在 maps 里有 3 个段**（r-xp / r--p / rw-p），说明**被 dlopen 了**，
但 **`JNI_OnLoad` 没跑完**（或没被 Java 层调用），所以：
- 单例未创建
- 所有 hook（SoLoadHooker / OpenDexFileHooker / socket）**都没安装**

---

## 二、它到底是什么（B 级静态证据）

### 导出符号暴露功能

```
Java_..._NativeMemoryMonitor_nativeGetCurrentMessageQueueCount
Java_..._NativeMemoryMonitor_nativeGetCurrentHookAppThreadCount
Java_..._NativeMemoryMonitor_nativeAddAppHandlerThreadTid
Java_..._NativeMemoryMonitor_nativeGetMessageQueueSetFdInvokeInfoList
Java_..._NativeMemoryMonitor_nativeOpenDexFileHook
unwind_backtrace
```

### JNI 实现是「读计数器」，不是「做检测」

`nativeGetCurrentHookAppThreadCount` @ `0x44540` 的完整反汇编：

```
adrp x20, 0xa8dd0
add  x19, x20, #24            ; mutex
mov  x0, x19
bl   pthread_mutex_lock
ldr  x20, [x20, #16]          ; ★ 读一个全局计数器
mov  x0, x19
bl   pthread_mutex_unlock
mov  x0, x20                  ; 返回计数值
ret
```

**`nativeGetCurrentMessageQueueCount` @ `0x4457c` 结构完全相同。**

→ **这两个"最可疑"的接口只是监控指标读取器。**

### 它自己就是 hook 框架（不是检测 hook 的）

内部字符串（B 级）：

```
SoLoadHooker / HookAndCheckSo / dlopen_hooker / HasHooked
OpenDexFileHooker / onDexFileOpen / HookOpenDexFile
nativeFileHook / nativeOpenFileHook / nativeLightMessageQueueHook
nativeJavaMethodHook / nativeJavaFieldHook
nativeThreadHook / nativeThreadCreateHookInit
HookedConnect / HookedReadBytes / HookedWriteBytes / HookedSendmsg ...
```

**它 hook 的范围和我们的 ByteHook 高度重叠**（dlopen、文件、socket、ART）。

---

## 三、"terminate for safe reason" 的真相

### 字符串确实存在

```
find so base addr but the file is deleted, terminate for safe reason
```

### 但它是 CHECK 断言，不是主动扫描

相关断言（B 级）：

```
Check failed: module && module.GetOffset() == 0 && !module.HasDeleted().
Check failed: module && !IsSystemSo(module.GetPath()) && module.GetOffset() == 0 && !module.HasDeleted().
Check failed: ProcMaps::IsValidPtr((void *) module.GetStartAddress(), module.GetLength()).
```

**语义**：在 **hook 某个 so** 时断言「模块存在、非系统 so、offset==0、文件未删除」。
这是 hook 动作的**前置条件**，不是周期性扫描。

### 从未触发

```
logcat | grep -c 'terminate for safe reason'  →  0
```

**而且这个库没有导入 `abort` / `exit` / `_exit` / `kill`**（B 级，重定位表核对）：
```
终止类导入只有：__cxa_atexit / __cxa_guard_abort / _ZSt9terminatev
```

---

## 四、我们的 so 是否触发那些 CHECK

### 实际映射状态

```
7190944000-71909b4000 r-xp 000df000 ... /data/app/~~umkSg.../io.github.kida.qqnativeguard-.../base.apk
```

**关键**：我们的 native 库**直接从 `base.apk` mmap**（`extractNativeLibs=false`），
不是独立 `.so` 文件。

### 三项检查逐条对照

| CHECK 条件 | 我们的状态 | 结论 |
|---|---|---|
| `module` 存在 | ✓ | 通过 |
| `!IsSystemSo(GetPath())` | 路径是 `/data/app/.../base.apk`，**非系统** | ✓ 通过（会走检查分支） |
| `GetOffset() == 0` | 文件偏移 `0xdf000` 等，**非 0** | ⚠️ 取决于 `GetOffset()` 语义 |
| `!HasDeleted()` | maps 里 **0 个 deleted**（我们自己的 so） | ✓ 通过 |

**`(deleted)` 全进程有 52 处，但全是 `/dev/ashmem/`**（匿名共享内存，正常现象），
**没有一个是 so 模块**。

### 结论

**当前配置不会触发这些 CHECK。** 而且即使触发，该库未初始化，代码根本不会执行。

**唯一风险场景**：模块热更新后，旧 APK 路径的映射变成 `(deleted)`。
但需该库先被启用，故当前无风险。

---

## 五、修正之前的误判

### ❌ 原判断（来自参考项目，D 级）

> 原生库 `libnative-memory-library-lib.so`：自带 `native_hook_monitor`，
> 读 maps 找 so 基址、检测"文件已删除"的映射

### ✅ 实测修正（A/B 级）

| 原说法 | 实际 |
|---|---|
| 检测"文件已删除"的映射 | **有这段代码，但从未执行**（库未初始化） |
| 是我们的漏掉的检测层 | **不是威胁**，是未启用的 APM |
| `native_hook_monitor` | 实际是 `NativeMemoryMonitor`（内存监控）+ `NativeTrafficMonitor`（流量） |

**教训**：**参考项目的说法必须独立验证**，不能直接当作事实采用。
这次是因为信任了参考项目，把它列进了「三处技术缺口」，白花了一轮排查。

---

## 六、逆向方法记录（可复用）

### 静态部分

1. **ELF 节表解析** — 定位 `.text` / `.rodata` / `.rela.plt`
2. **导出/导入符号** — 从 `.dynsym` 读出 `Java_*` 和 JW 导入（发现无 abort）
3. **字符串分类** — `.rodata` 按「路径/检测目标/ART/错误」分组
4. **ARM64 交叉引用** — `ADRP+ADD` 模式定位字符串引用点
5. **PLT 解析** — `adrp x16 / ldr x17 / add x16 / br x17` 模式映射 GOT → 符号
6. **重定位表** — `.rela.plt` + `.rela.dyn` 把 GOT 地址反查符号名

### 动态部分（决定性）

7. **`maps` 状态检查** — 确认库已加载
8. **读 `.bss` 判断初始化** — 单例指针为 NULL = 未初始化 ← **最关键一步**
9. **`logcat` 计数** — 所有检测日志为 0

### 关键教训

**静态分析能看出"它有什么能力"，动态验证才能确定"它是否启用"。**

第 8 步（读 `.bss` 单例指针）是整个逆向的转折点：
- 静态看：它有完整的 hook 框架、有 CHECK 断言、有 terminate 路径 → 像威胁
- 动态看：单例 NULL、日志全 0 → **根本没跑**

---

## 七、剩余疑问

| # | 问题 | 状态 |
|---|---|---|
| 1 | 为什么库被加载但没初始化？ | Java 层未调用，或 QQ 配置关闭 |
| 2 | 什么条件下会启用？ | 未知，需追踪 Java 层 `NativeMemoryMonitor` 的调用方 |
| 3 | 若启用，是否会与 ByteHook 冲突？ | 未知（都是 inline hook 框架） |
| 4 | `GetOffset()` 的确切语义 | 未确认 |

**这些不影响当前结论：该库当前不构成威胁。**

---

## 八、对项目的意义

### 三处「技术缺口」的重新评估

| 缺口 | 原评估 | 修正后 |
|---|---|---|
| `File.isFile/isDirectory/length` | 真实缺口 | ✅ **已补（12 方法）** |
| `PackageManager` 25 方法 | 真实缺口 | ✅ **已补（25+）** |
| **`libnative-memory-library`** | 真实缺口 | ❌ **误判，它不是检测层** |

**所以实际只有 2 处真缺口，不是 3 处。**

### 当前真正的未知面

按威胁程度排序：

1. **账号维度**（D 级推断，无法验证）
2. **Turing 风控**（`libturingxq.so`，已做 deflate 注入但覆盖不全）
3. **`libqimei.so`**（设备指纹，仅部分对齐）
4. ~~libnative-memory~~（已排除）