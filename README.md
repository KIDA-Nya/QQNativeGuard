# QQ Native Guard

腾讯系 App（QQ）**Native 层**环境检测绕过模块。

基于 [Xalsace/QQEnhancedBypass](https://github.com/Xalsace/QQEnhancedBypass) 的 Java 层能力继续开发，
主要贡献集中在 **native 层（ByteHook）**、**设备指纹四通道一致性**、**可观测的拦截策略**。

遵循上游 GPL-3.0 许可，详见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。

---

## 为什么有这个项目

上游项目解决了 Java 层检测（Root / Xposed / 调试 / Pandora），但其 README 明确列出三类**未处理**的 Native 层检测：

- `libfekit.so` — `/proc/self/maps` 扫描、Magisk/KernelSU 特征、ART CheckJNI 校验
- `libturingxq.so` — ART 反射检测、ArtField 结构校验、传感器模拟器检测
- `libMSFKernel.so` — APK 签名 MD5 校验

本项目就是补上这一块。

---

## 核心能力

| 能力 | 说明 |
|---|---|
| **Native hook 框架** | ByteHook，30+ hook 点（上游无 native hook） |
| **系统属性三入口** | `__system_property_get` / `read_callback` / `find` |
| **内容级文件伪造** | 9 种 `/proc` 与 `/sys` 文件 |
| **SoC 平台标识** | `ro.board.platform` + `/proc/cpuinfo` Hardware 行 |
| **HTTP UA 重建** | 修复 zygote 静态缓存导致的真机机型泄露 |
| **观测/拦截分离** | 行为正常化 + 完整取证 |
| **Turing 明文注入** | deflate 层字节替换（绕过 `.real_syscall`） |

### 设备指纹四通道一致

实测发现，携带设备信息的通道**只有 4 个**：

| # | 通道 | 泄露内容 | 处理方式 |
|---|---|---|---|
| 1 | HTTP User-Agent | `Dalvik/...; MODEL Build/ID)` | 重建 `http.agent` |
| 2 | QsecEst 1293B | 85 字段（9 个是真设备特征） | 字段级一致 |
| 3 | MSF 长连接 | uin + QIMEI 明文 | 观测模式 |
| 4 | VOD_MSDK | `androidDesc: BRAND_MODEL__SDK_VER` | native property 层 |

**只改一处会怎样**：服务端在同一会话里同时看到「QsecEst 报伪造机型」和「HTTP UA 报真机机型」，直接判定设备特征被篡改。

### 两个最容易被漏的破绽

**1. `http.agent` 的 zygote 缓存**

AOSP `RuntimeInit.commonInit()` 在 zygote fork 时就把 UA 算成静态字符串，**早于 Xposed 加载**：

```java
System.setProperty("http.agent",
    "Dalvik/" + System.getProperty("java.vm.version")
    + " (Linux; U; Android " + Build.VERSION.RELEASE
    + "; " + Build.MODEL + " Build/" + Build.ID + ")");
```

改 `Build.MODEL` 对已缓存的 UA **完全无效**。实测 **141/170 次 HTTP 请求（83%）**把真机机型广播出去。

**2. SoC 平台代号与机型的矛盾**

```
k54 = ro.board.platform = "lahaina"   ← 骁龙 888 (SM8350)
k57 = Build.MODEL       = "PGT110"    ← 骁龙 8 Gen 3 机型
```

**这个组合物理上不可能存在。** 服务端一对照即知「设备改了机型名」，不会认作新设备。
必须同时伪造属性和 `/proc/cpuinfo`（后者读文件不走属性）。

---

## 快速开始

### 环境要求

- Android 8.0+（API 26+）
- KernelSU 或 Magisk
- LSPosed / Vector
- NDK 27.3.13750724
- JDK 21 + Gradle 8.5+

### 编译

```bash
# Windows
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat assembleDebug

# Linux / macOS
JAVA_HOME=/path/to/jdk ./gradlew assembleDebug
```

### 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb push qqguard.conf.example /data/local/tmp/qqguard.conf
# 编辑配置填入你的身份值
adb shell am force-stop com.tencent.mobileqq
adb shell am start -n com.tencent.mobileqq/.activity.SplashActivity
```

### 配置

参考 [qqguard.conf.example](qqguard.conf.example)，关键点：

```properties
ENABLE_NATIVE_HOOKS=true
ENABLE_DEVICE_SPOOF=true

# 机型画像（必须整套自洽）
ENV_MODEL=<MODEL>
ENV_DEVICE=<BRAND_MODEL>
ENV_FINGERPRINT=<BRAND>/<PRODUCT>/<DEVICE>:<RELEASE>/<BUILD_ID>/<INCREMENTAL>:user/release-keys

# SoC 必须与机型匹配（★ 最容易漏）
ENV_SOC_PLATFORM=kalama

# 观测模式（推荐）
NETWORK_OBSERVE_ONLY=true
```

**自洽校验规则**：
```
FINGERPRINT == BRAND/PRODUCT/DEVICE:RELEASE/BUILD_ID/INCREMENTAL:user/release-keys
DISPLAY 必须包含 MODEL
IMEI 必须 Luhn 有效
SoC 平台必须与机型匹配
```

---

## 验证

### Native hook 是否生效

```bash
adb logcat -s QQNativeBypass:V | grep -E "All Native Hooks|Self-test"
```

预期：
```
QQNativeBypass: === Native Bypass Library Loaded ===
QQNativeBypass: ByteHook initialized (AUTOMATIC mode)
QQNativeBypass: Self-test ro.build.fingerprint='...'
QQNativeBypass: Self-test soc_serial='...'
QQNativeBypass: === All Native Hooks Installed Successfully ===
```

### UA 是否已修正

```bash
adb logcat -s QQNativeBypass:V | grep "http.agent"
```

### 抓包复核

```bash
adb shell "tcpdump -i any -n -s 0 -w /data/local/tmp/ua.pcap 'tcp port 80' &"
# 触发 HTTP 请求
adb pull /data/local/tmp/ua.pcap
strings ua.pcap | grep -E "User-Agent|Dalvik"
```

---

## 已知限制

### 1. MMKV hook 会导致崩溃循环

```
MMKV::set hook installed → 5-6 秒后进程死亡 → 无限重启
```

根因：`bytehook_hook_single` 对 libmmkv 的 `set` 系列 inline hook 时，trampoline 破坏了库内部状态。
**即使纯观测不改写也会崩** —— 问题在 hook 本身。

**默认禁用**。

**附**：`libmmkv.so` 从 base.apk 直接 mmap 加载，**不在 dlopen 命名空间**，`dlopen("libmmkv.so", RTLD_NOLOAD)` 返回 NULL，必须先 `dl_iterate_phdr` 取真实路径。

### 2. `prctl` hook 必须关闭

QQ 会自检 `PR_SET_DUMPABLE`，发现被拦后**直接自杀**（`VM exiting with result code 0`）。

### 3. Turing 走裸 syscall

`libturingxq.so` 使用 `.real_syscall` 绕过 libc，`sendto`/`sendmsg` hook 抓不到。
改用 `deflate` 层注入明文。

### 4. 账号维度无法本地解决

**必须说清楚**：本项目解决的是**设备维度**。

实测（同一套伪装环境）：

| 账号类型 | 存活时长 |
|---|---|
| 全新账号 | **9+ 小时** |
| 已被标记账号 | 2h36m → 20min → 3min → 身份验证失败 |

**客户端能做的已做到极致，剩下的在服务端账号评分里，本地技术无法重置。**

### 5. 加密未破解部分

| 通道 | 加密 | 可解性 |
|---|---|---|
| QsecEst | AES-128-CBC | ✅ |
| MSF 8080 | SSO 会话密钥 | ❌ 需运行时抓取 |
| Beacon 8081 | RSA 包裹 AES key | ❌ 私钥在服务端 |
| Bugly HTTP | AES-256-CBC | ⚠️ 需 keyStr |

通用解法：hook `javax.crypto.Cipher.doFinal`。

---

## 项目结构

```
app/src/main/cpp/
├── native-bypass.cpp              # 入口，JNI_OnLoad
├── hooks/
│   ├── hook_libc.cpp              # 主体：文件/属性/环境（1900+ 行）
│   ├── hook_libfekit.cpp          # fekit 专属
│   ├── hook_libturingxq.cpp       # Turing 专属
│   └── hook_libmsf.cpp            # MSF 专属
└── utils/log.cpp

app/src/main/java/io/github/kida/qqnativeguard/
├── NativeBypass.java              # JNI 桥
├── XposedEntry.java               # 入口
├── config/HookConfig.java         # 配置
├── hooks/
│   ├── DeviceInfoHook.java        # 设备信息 + http.agent
│   ├── NetworkReportHook.java     # 上报观测/拦截
│   ├── RootDetectionHook.java
│   ├── XposedDetectionHook.java
│   ├── DebugDetectionHook.java
│   └── RuntimeMonitorHook.java
└── utils/
```

---

## 致谢

- **[Xalsace/QQEnhancedBypass](https://github.com/Xalsace/QQEnhancedBypass)** — Java 层检测绕过基础（GPL-3.0）
- **[jhl337/QQNTHookBypass](https://github.com/jhl337/QQNTHookBypass)** — 更早的探索与检测机制分析
- **[ByteHook](https://github.com/bytedance/android-inline-hook)** — 字节跳动 inline hook 框架（Apache-2.0）

原始版权归各自作者所有。本项目的增量部分同样以 GPL-3.0 发布。

---

## 许可证

**GNU General Public License v3.0** —— 继承上游许可，详见 [LICENSE](LICENSE)。

- ✅ 可以自由使用、修改、分发
- ⚠️ 衍生作品必须同样以 GPL-3.0 开源
- ⚠️ 必须保留原作者版权声明
- ⚠️ 必须声明修改内容

详见 [NOTICE](NOTICE)。

---

## 免责声明

仅供**安全研究**与**个人设备环境定制**使用。

使用者应自行确认所在地区的法律法规，并对使用后果负责。作者不对任何账号封禁、数据丢失或其他损害承担责任。