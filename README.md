# QQ Native Guard

腾讯系 App（QQ）**Native 层**环境检测绕过模块。

基于 [Xalsace/QQEnhancedBypass](https://github.com/Xalsace/QQEnhancedBypass) 的 Java 层能力继续开发，
主要贡献集中在 **native 层（ByteHook）**、**设备指纹四通道一致性**、**可观测的拦截策略**。

遵循上游 GPL-3.0 许可，详见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。

---

## 技术亮点

> 以下每一条都是**实测抓包/反汇编**得出的结论，不是推测。

### 🔍 亮点 1：设备信息其实只走 4 个通道

大多数同类项目假设"设备信息随处泄露，必须全面拦截"。实测（3 小时抓包 / 17.5 MB 上报流量 / 5.6k 帧）后发现：

| # | 通道 | 泄露内容 | 是否加密 |
|---|---|---|---|
| 1 | **HTTP User-Agent** | `Dalvik/...; MODEL Build/ID)` | ❌ 明文 |
| 2 | **QsecEst 1293B** | 85 字段（**仅 9 个是真设备特征**） | AES-128-CBC |
| 3 | **MSF 长连接** | uin + QIMEI | 部分明文 |
| 4 | **VOD_MSDK** | `androidDesc: BRAND_MODEL__SDK_VER` | ❌ 明文 |

**其余 10+ 个 SDK（Bugly / RMonitor / Mars / Statistics / Qimei…）虽然各自出网，但不上报新的设备维度。**

这带来两个实际收益：
- **工作量从"覆盖 10 个 SDK"缩小到"4 个具体点"**
- 不必为了覆盖全部而 hook 高频 libc 函数（那会崩）

### 🔍 亮点 2：`http.agent` 的 zygote 静态缓存陷阱

**这是最容易漏、也最致命的一条。**

AOSP `RuntimeInit.commonInit()` 在 **zygote fork 出应用进程时**就把 UA 算成静态字符串：

```java
// frameworks/base/core/java/android/app/RuntimeInit.java
System.setProperty("http.agent",
    "Dalvik/" + System.getProperty("java.vm.version")
    + " (Linux; U; Android " + Build.VERSION.RELEASE
    + "; " + Build.MODEL + " Build/" + Build.ID + ")");
```

**它早于 Xposed 模块加载。**

所以：**改 `Build.MODEL` 字段对已缓存的 UA 完全无效** —— 这是很多人以为"已经伪装好了"却仍在泄露的原因。

实测：**141 / 170 次 HTTP 请求（83%）把真机机型广播给所有 HTTP 服务端。**

修复只需一处（拦截 `System.getProperty("http.agent")` 重算），10 个读取点同时收敛。

### 🔍 亮点 3：SoC 平台代号与机型的物理矛盾

QsecEst 上报的 85 个字段里，真正携带设备特征的只有 9 个。其中最隐蔽的一个：

```
k54 = ro.board.platform = "lahaina"    ← 骁龙 888 (SM8350) 平台代号
k57 = Build.MODEL       = "PGT110"     ← 骁龙 8 Gen 3 机型
```

**`lahaina` 与任何非 SM8350 机型物理上不可能共存。**

服务端一对照即知「设备改了机型名」→ **不会认作新设备**，直接按旧设备处理。

这解释了一个反直觉现象：**换全套指纹后，登录反而不弹"新设备验证"了** —— 因为服务端根本不认为这是新设备。

修复必须同时处理两处（读文件不等同读属性）：
- 属性路径：`ro.board.platform` / `ro.chipname` / `ro.soc.model`
- **文件路径**：`/proc/cpuinfo` 的 `Hardware:` / `Processor:` / `CPU part:` 行

### 🔍 亮点 4：`__system_property_find` 是隐藏的第二个入口

多数 native hook 只覆盖 `__system_property_get`，但腾讯的部分 SDK（PCDN/VOD）走的是：

```c
const prop_info* pi = __system_property_find(name);   // ← 常被漏掉
__system_property_read_callback(pi, callback, cookie);
```

**如果 `find` 未被 hook，`pi` 指向真实结构**，`read_callback` 里改值可能来不及（或调用方直接读结构体）。

本项目同时 hook 三个入口，并在 `find` 时记录 `pi → name` 映射供回调使用。

### 🔍 亮点 5：Turing 走裸 syscall，只能从 deflate 层切入

`libturingxq.so` / `libturingmfa.so` 使用 `.real_syscall` —— **直接发起系统调用，绕过 libc**。

所以 `sendto` / `sendmsg` hook **抓不到它的任何流量**。

本项目的解法：在 **`deflate` 压缩入口**做字节替换（`inject.txt` 规则），因为 Turing 上报前必然经过压缩。

### 🔍 亮点 6：拦截本身才是异常信号，所以做了观测模式

很多人第一反应是"拦住上报"。但 **"该发的不发"本身就是异常信号** —— 服务端看到客户端突然不发心跳/不上报，反而更容易被标记。

而且原实现有个坑：`ENABLE_NETWORK_INTERCEPT=false` 时 `hook()` 首行直接 `return`，**所有观测点全挂**，完全无法诊断。

本项目拆成两个开关：

| 开关 | 行为 |
|---|---|
| `ENABLE_NETWORK_INTERCEPT=true` | 挂 hook **并拦截** |
| `NETWORK_OBSERVE_ONLY=true` | 挂 hook **但放行**（只记录）|

观测模式下 QQ 行为完全正常化，同时保留全部取证能力。

### 🔍 亮点 7：负面结果同样有价值 —— 三条加密通道都不可离线解

如实记录：以下三条通道**无法离线解密**，唯一的实用路径是运行时 hook。

| 通道 | 加密 | 结论 |
|---|---|---|
| MSF 8080 | SSO 会话密钥（握手协商） | 需在握手时抓 `SecureKey` |
| Beacon 8081 | RSA-OAEP 包裹的 AES key | **私钥只在服务端** |
| Bugly HTTP | AES-256-CBC，K=SHA256(keyStr) | 需 keyStr，但可由 appId 推导 |

**用 132,804 个候选 appId 对 8080 帧爆破 → 0 命中**，反证它不是 Bugly 的产物。

通用解法：hook `javax.crypto.Cipher.doFinal`，一网打尽所有本地加解密（同时看到 key / iv / 明文 / 密文）。

### 🔍 亮点 8：账号维度不可解 —— 诚实说明边界

**本项目解决的是设备维度。** 实测数据（同一套伪装环境）：

| 账号类型 | 存活时长 |
|---|---|
| 全新账号 | **9+ 小时**（稳定在线） |
| 已被标记账号 | 2h36m → 20min → 3min → **身份验证失败** |

**每次重登都在加速降权，换指纹只能换来一次性窗口。**

客户端能做的已经做到极致，剩下的在服务端账号评分里 —— **本地任何技术都无法重置**。

把这个说清楚，比继续宣称"能绕过一切"更有价值。

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