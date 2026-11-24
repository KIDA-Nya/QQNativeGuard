# QQ Native Guard

**QQ 环境检测绕过 · Native 层**

[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green.svg)]()
[![Hook](https://img.shields.io/badge/hook-ByteHook-orange.svg)]()
[![Verified](https://img.shields.io/badge/verified-23%2F23%20PASS-brightgreen.svg)](docs/VERIFY_REPORT.md)

> 基于 [Xalsace/QQEnhancedBypass](https://github.com/Xalsace/QQEnhancedBypass) 继续开发，
> 补上它 README 里明确列出但**尚未处理**的 Native 层检测。
> 遵循上游 GPL-3.0，详见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。

---

## 一句话

**不用猜、不用试——把 QQ 上报给服务器的每一个设备字段，逐个找到来源属性、逐个对上。**

---

## 这个项目解决什么

网上大部分 QQ 改机教程让你改 `Build.MODEL`，然后发现**没用**。

因为设备身份不是一处泄露，是**十几处**，而且互相印证：

```
QsecEst 上报:  model=PGT110, board=kalama, soc=SM8650
HTTP UA 上报:  Dalvik/...; PGT110 Build/UKQ1.231003.002
MSF 上报:      QIMEI=<32-hex>
VOD_MSDK 上报: androidDesc=OPPO_PGT110__15_35
```

**服务端一比对：四个通道必须指向同一台设备**。只改一处 → 立刻暴露。

本项目做的就是把**全部通道 + 全部来源属性**一次性对齐。

---

## 硬核发现（全部实测，非推测）

### 🔬 发现 1：85 个上报字段里，只有 9 个带真实设备身份

解密 QsecEst（AES-128-CBC，已完全破解）后逐字段核对：

```
85 个字段
  ├─ 52 个是 "E_NU"（空值）
  ├─ 24 个是常量/通用值（qcom、arm64-v8a、release-keys…）
  └─ 9 个才是真设备身份  ← 只有这些需要伪造
```

**为什么重要**：把工作量从"处理 85 个字段"缩到"精确定位 9 个属性"。

### 🔬 发现 2：`ro.product.board` 与 `ro.board.platform` 是两个属性

**这是本项目最值钱的一条。**

真机上两个属性都存在、值都是 `lahaina`（骁龙 888）。看起来改一个就够：

```bash
getprop ro.product.board    → lahaina    # ← fekit 上报的是这个
getprop ro.board.platform   → lahaina    # ← 大多数人 hook 的是这个
```

**结果**：自检显示"已伪装成 kalama"，实际上报**仍然是 lahaina**。

服务端看到 `board=lahaina`（骁龙888）却 `model=PGT110`（骁龙8Gen3）——
**这个组合物理上不存在，直接判定设备特征被篡改。**

> 这个坑我们踩了两轮才发现在哪，详细复现过程见 [K54_ROOT_CAUSE.md](docs/K54_ROOT_CAUSE.md)

### 🔬 发现 3：`http.agent` 在 zygote 里就定型了

AOSP `RuntimeInit.commonInit()` 在 **fork 出应用进程时**就把 UA 算成静态字符串：

```java
System.setProperty("http.agent",
    "Dalvik/" + System.getProperty("java.vm.version")
    + " (Linux; U; Android " + Build.VERSION.RELEASE
    + "; " + Build.MODEL + " Build/" + Build.ID + ")");
```

**这发生在 Xposed 模块加载之前** → 改 `Build.MODEL` 对它**完全无效**。

实测：**141 / 170 次 HTTP 请求（83%）** 把真机机型广播给了所有服务端。

修复只需拦截 `System.getProperty("http.agent")` 重算，10 个读取点同时收敛。

### 🔬 发现 4：设备信息其实只走 4 个通道

抓包 3 小时（539 MB → 过滤后 17.5 MB 纯上报流量）统计：

| # | 通道 | 泄露内容 | 是否加密 |
|---|---|---|---|
| 1 | **HTTP User-Agent** | `Dalvik/...; MODEL Build/ID)` | ❌ 明文 |
| 2 | **QsecEst** | 85 字段（9 个真设备特征） | AES-128-CBC |
| 3 | **MSF 长连接** | uin + QIMEI | 部分明文 |
| 4 | **VOD_MSDK** | `androidDesc: BRAND_MODEL__SDK_VER` | ❌ 明文 |

**其余 10+ 个 SDK（Bugly/RMonitor/Mars/Qimei…）虽然各自出网，但不上报新的设备维度。**

### 🔬 发现 5：`/proc/cmdline` 泄露的最凶

```
androidboot.project_codename=lemonadep     ← OnePlus 9 Pro 内部代号！
androidboot.platform_name=SM8350
androidboot.verifiedbootstate=orange       ← bootloader 已解锁
androidboot.bootreason=reboot,PMIC_WD       ← 看门狗重启（异常）
androidboot.type=nonrelease
```

**`lemonadep` 直接把真机型号写在里面。** 现在净化 37 个 token。

### 🔬 发现 6：拦截本身才是异常信号

很多人第一反应是"拦住上报"。但 **"该发的不发"本身就是异常信号**。

所以本项目提供观测模式：

```properties
NETWORK_OBSERVE_ONLY=true    # 挂 hook 但放行，只记录
```

行为完全正常化，同时保留全部取证能力。

### 🔬 发现 7：唯一能读到服务端裁决的接口

```java
com.tencent.qqnt.kernelgpro.nativeinterface.GProGuildSafetyRisk
  → getRiskLevel()    // 服务端下发的风险等级
```

**这是极少数能直接看到"服务端判了几级"的入口。** 项目加了只读观测：

```
[RISK-OBSERVE] GProGuildSafetyRisk.getRiskLevel -> <等级>
```

### 🔬 发现 8：账号维度不可解（诚实说明）

**本项目解决的是设备维度。**

实测（同一套伪装环境）：

| 账号类型 | 存活时长 |
|---|---|
| 全新账号 | **9+ 小时** |
| 已被标记账号 | 2h36m → 20min → 3min → **身份验证失败** |

**每次重登都在加速降权。** 客户端能做的已做到极致，剩下的在服务端账号评分里——
**本地任何技术都无法重置**。

把这个说清楚，比继续宣称"能绕过一切"更有价值。

---

## 能力矩阵

| 能力 | 说明 |
|---|---|
| **Native hook 框架** | ByteHook inline hook，**30+ hook 点** |
| **系统属性** | 三入口全覆盖：`get` / `read_callback` / `find`，**70+ 属性** |
| **文件伪造** | 10 种 `/proc` 与 `/sys` 文件（含 cmdline 37 token 净化） |
| **SoC 身份** | `ro.product.board` + `ro.board.platform` + `/proc/cpuinfo` 三处同步 |
| **设备标识** | IMEI / AndroidID / Serial / OAID / QIMEI / SoC serial |
| **Java 检测覆盖** | **13 个类 / 60+ 方法**（Root / Xposed / 调试 / 模拟器） |
| **HTTP UA** | 运行时重算，修复 zygote 静态缓存 |
| **Turing 注入** | deflate 层明文替换（绕过 `.real_syscall`） |
| **懒加载重试** | 延迟加载的 SDK 类自动补齐 hook |
| **观测模式** | 行为正常化 + 完整取证 |

---

## 证据链

每个结论都有可复现的验证：

| 验证 | 工具 | 结果 |
|---|---|---|
| 端到端检查 | `tools/verify_all2.py` | **[23/23 PASS](docs/VERIFY_REPORT.md)** |
| 检测覆盖审计 | dex 全字符串池扫描 | [13 类 / 60+ 方法](docs/DETECTION_AUDIT.md) |
| 暴露面审计 | `tools/audit_full.sh` | [7 大类完整清单](docs/EXPOSURE_AUDIT.md) |
| QsecEst 加解密 | AES-128-CBC（已破解） | 85 字段全可见 |

**验证的关键方法**：分三种视角，因为 root shell 和 untrusted_app 看到的是不同世界：

| 视角 | 预期 | 实测 |
|---|---|---|
| 模块自检（QQ 进程内） | 伪造值 | `kalama` / `SM8650` / `green` ✅ |
| 系统属性（root shell） | 真值 | `lahaina` / `SM8350` / `orange` ✅ |
| SELinux 隔离 | 读不到 | QQ 读不到 `lspd` ✅ |

**这个区分推翻了 3 个误报的"缺口"**，说明**审计方法本身也会出错**。

---

## 快速开始

### 环境要求

```
Android 8.0+  ·  KernelSU/Magisk  ·  LSPosed/Vector
NDK 27.3.13750724  ·  JDK 21  ·  Gradle 8.5+
```

### 编译

```bash
# Windows
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat assembleDebug

# Linux / macOS
JAVA_HOME=/path/to/jdk ./gradlew assembleDebug
```

### 部署

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb push qqguard.conf.example /data/local/tmp/qqguard.conf
# 编辑配置，填入你自己的身份值
adb shell am force-stop com.tencent.mobileqq
adb shell am start -n com.tencent.mobileqq/.activity.SplashActivity
```

### 验证

```bash
python -X utf8 tools/verify_all2.py     # 23 项端到端检查
```

### 核心配置

```properties
ENABLE_NATIVE_HOOKS=true
ENABLE_DEVICE_SPOOF=true

# 机型画像（必须整套自洽）
ENV_MODEL=<MODEL>
ENV_FINGERPRINT=<BRAND>/<PRODUCT>/<DEVICE>:<RELEASE>/<BUILD_ID>/<INCREMENTAL>:user/release-keys

# SoC 必须与机型匹配 ★ 最容易漏
ENV_SOC_PLATFORM=kalama

# 观测模式（推荐）
NETWORK_OBSERVE_ONLY=true
```

**自洽校验规则**：
```
FINGERPRINT == BRAND/PRODUCT/DEVICE:RELEASE/BUILD_ID/INCREMENTAL:user/release-keys
DISPLAY 必须含 MODEL
IMEI 必须 Luhn 校验通过
SoC 平台代号必须与机型匹配    ← 最容易漏，也是最早的暴露点
```

完整配置见 [qqguard.conf.example](qqguard.conf.example)。

---

## 已知限制（不粉饰）

| # | 限制 | 说明 |
|---|---|---|
| 1 | **账号维度不可解** | 服务端评分累积，本地技术无法重置 |
| 2 | **MMKV hook 会崩** | 已禁用；纯观测实现也会崩，问题在 hook 本身 |
| 3 | **`prctl` hook 必须关** | QQ 自检会让它自杀（`VM exiting code 0`） |
| 4 | **Turing 走裸 syscall** | `.real_syscall` 绕过 libc，只能从 deflate 切入 |
| 5 | **三条通道不可离线解** | MSF(SSO密钥) / Beacon(RSA) / Bugly(需 keyStr) |
| 6 | **物理锚改不了** | KeyStore TEE attestation、boot_id |

详见 [EXPOSURE_AUDIT.md](docs/EXPOSURE_AUDIT.md)。

---

## 项目结构

```
app/src/main/cpp/
├── native-bypass.cpp            # JNI 入口
├── hooks/
│   ├── hook_libc.cpp            # 主体 1900+ 行（文件/属性/SoC/加密）
│   ├── hook_libfekit.cpp        # fekit 专属
│   ├── hook_libturingxq.cpp     # Turing 专属
│   └── hook_libmsf.cpp          # MSF 专属
└── utils/log.cpp

app/src/main/java/io/github/kida/qqnativeguard/
├── hooks/
│   ├── DeviceInfoHook.java      # 设备信息 + http.agent 重算
│   ├── NetworkReportHook.java   # 上报观测/拦截
│   ├── RootDetectionHook.java   # Root 检测 + 懒加载重试
│   ├── XposedDetectionHook.java # Xposed 痕迹
│   ├── DebugDetectionHook.java
│   └── RuntimeMonitorHook.java
└── utils/
```

---

## 文档

| 文档 | 内容 |
|---|---|
| [K54_ROOT_CAUSE.md](docs/K54_ROOT_CAUSE.md) | ★ `ro.product.board` vs `ro.board.platform` 完整复现 |
| [VERIFY_REPORT.md](docs/VERIFY_REPORT.md) | 23 项验证结果 |
| [DETECTION_AUDIT.md](docs/DETECTION_AUDIT.md) | dex 扫描方法与 13 个已覆盖类 |
| [EXPOSURE_AUDIT.md](docs/EXPOSURE_AUDIT.md) | 7 大类暴露面完整清单 |
| [SETUP_REPORT.md](docs/SETUP_REPORT.md) | 搭建过程与 10 个踩坑记录 |

---

## 致谢

| 项目 | 贡献 |
|---|---|
| [Xalsace/QQEnhancedBypass](https://github.com/Xalsace/QQEnhancedBypass) | Java 层检测绕过基础（GPL-3.0） |
| [jhl337/QQNTHookBypass](https://github.com/jhl337/QQNTHookBypass) | 早期检测机制分析 |
| [ByteHook](https://github.com/bytedance/android-inline-hook) | inline hook 框架（Apache-2.0） |

原始版权归各自作者。本项目增量部分同样 GPL-3.0，修改声明见 [NOTICE](NOTICE)。

---

## 许可证

**GNU General Public License v3.0** — 继承上游，详见 [LICENSE](LICENSE)。

- ✅ 自由使用、修改、分发
- ⚠️ 衍生作品必须同样 GPL-3.0 开源
- ⚠️ 必须保留原作者版权
- ⚠️ 必须声明修改内容

---

## 免责声明

仅供**安全研究**与**个人设备环境定制**。

使用者自行确认所在地区法律法规，并对使用后果负责。作者不对账号封禁、数据丢失或其他损害承担责任。

**这个项目不承诺"能绕过一切"——它承诺的是"每个结论都有实测证据"。**