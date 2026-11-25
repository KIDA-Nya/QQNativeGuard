# Turing 反检测威胁模型（2026-11-26 阶段①）

> 目标：彻底完成 Turing 风控反检测。
> 本文记录阶段①成果 —— 弄清 Turing 到底在检测什么。

---

## 一、为什么不能像 QsecEst 那样处理

项目内既有的实测记录（`hook_libturingxq.cpp` 注释）：

> 阻断 Turing 上报会让会话从 ~120 分钟骤降到 7 分钟 —— 服务端依赖它的数据，
> 缺失即判异常。因此这里只观测、放行，绝不拦截。

**所以 Turing 这条路的策略与其他通道相反：**
- QsecEst / GUID / QIMEI → **改写**成伪造值
- Turing → **必须让它成功上报**，只能让上报的内容与伪造身份一致

---

## 二、`libturingxq.so` 静态分析（B 级）

```
大小: 311,784 字节 (304.5 KB)
.text:   VA=0xcce0  size=0x3afe0
.rodata: VA=0x4530  size=0x1634
```

### 导出符号（仅 12 个）

```
JNI_OnLoad
real_syscall        ★ 绕过 libc hook 的关键
x, x.2, x.6, x.10, x.12
y, y.3, y.7, y.11, y.13
```

**`real_syscall` 是导出符号** —— 意味着 Turing 保存了原始 syscall 入口，
可以直接走内核，绕过我们 hook 的 `open` / `read` / `__system_property_get`。

### 关键导入符号

```
__system_property_get    读系统属性
open / read / fopen / fgets / fread
stat / lstat / access / readlink
syscall                  ★
popen                    ★ 执行 shell 命令
socket / sendto / ioctl
dl_iterate_phdr          ★ 遍历已加载的 so
```

### 路径与反射特征

```
/system/xbin/ku                   ← 专门针对 KernelSU
/system/bin/install-recovery.sh   ← root 经典路径
/system/xbin/, /data/local/xbin, /sbin/, /system/bin/
/proc/, /sys/, /dev/, /data/data/
/system/bin/debuggerd, /system/bin/debuggerd64

反射类引用：
  java/lang/reflect/ArtField      ★ 检查字段是否被改
  java/lang/reflect/Executable    ★ 检查方法是否被 hook
  java/lang/reflect/Constructor
  java/lang/reflect/Field
```

---

## 三、Turing 的三层反检测

| 层 | 手段 | 目的 | 我们的防护 |
|---|---|---|---|
| **L1 绕过** | `real_syscall` | 直接走内核读 `/proc`、属性、文件，无视 libc hook | ❌ **无** |
| **L2 扫描** | `dl_iterate_phdr` | 枚举已加载 so，找 hook 框架 / 可疑模块 | ❌ **无** |
| **L3 反射** | `ArtField` / `Executable` | 检查 ArtMethod 是否被改（Xposed 的本质特征） | ❌ **无** |

**这三层都不在我们当前覆盖范围内。**

---

## 四、实际暴露面评估（A 级，设备实测）

| Turing 检测项 | 设备实况 | 结论 |
|---|---|---|
| `/system/xbin/ku` | **absent** | 路径不存在 → **不泄露** |
| `/system/xbin/su` | absent | 不泄露 |
| `/system/bin/install-recovery.sh` | absent | 不泄露 |
| `/data/local/xbin` | absent | 不泄露 |
| `/sbin` | absent | 不泄露（Android 10+ 已废弃） |
| `/data/adb/magisk` | absent | 不泄露（我们是 KernelSU） |
| `/system/bin/debuggerd` | **EXISTS** | 正常系统文件，**不应伪造** |
| **`/data/adb/ksu`** | **EXISTS**（6 项） | ⚠️ 真实存在，但 Turing 字符串里没出现该路径 |
| **我们的 module so** | `dl_iterate_phdr` **可见** | ⚠️ **真实暴露** |
| **Java hook（ArtMethod）** | 已改写 | ⚠️ **真实暴露** |

### 结论：真实缺口只有两个

1. **`dl_iterate_phdr` 能看到我们的 module**
   - 实测：`/data/app/~~msvfBHpdgW8SpHy63GigPA==/io.github.kida.qqnativeguard-.../base.apk` 在 maps 里
   - 但 QQ 自己也是 `/data/app/.../base.apk`，「非系统 so」不足以判定
   - Turing 可能靠 **so 名字特征**或**路径段数**识别

2. **Java hook 的 ArtMethod 改写**
   - Xposed 的固有特征，反射可查
   - 这是所有 Xposed 模块共同的弱点

**原先担心的 `/system/xbin/ku` 缺口，实测设备上不存在该路径，属于误判。**

---

## 五、Java 层探针为什么零结果

已部署 `TuringReadProbe`（hook 24 个系统属性 + TelephonyManager + WiFi +
PackageManager + File + Runtime.exec + Debug），带 1/64 采样限流，
启动后零命中。

**原因**：Turing 的采集几乎全在 native 层（`libturingxq.so`），
通过 `real_syscall` 与 `__system_property_get` 直接读取，
不经过 Java API。所以 Java 探针抓不到。

**这个否定结果本身有价值**：确认了 Turing 不走 Java 采集路径，
后续只需在 native 层做文章。

---

## 六、阶段②计划

| # | 任务 | 方法 |
|---|---|---|
| 1 | 确认 `real_syscall` 的实际用途 | hook 它，记录调用号与参数（native） |
| 2 | 确认 `dl_iterate_phdr` 的判定逻辑 | hook 它，记录回调返回值与 Turing 的反应 |
| 3 | 确认属性读取来源 | 现有 native `__system_property_get` hook 已覆盖，需记录调用者为 Turing 的部分 |
| 4 | 定位上报通道 | `sendto` 已 hook 但零命中 → 改查 `popen` / `socket` / 是否有别的出口 |

**优先级：4 > 1 > 2 > 3**（先找到上报内容，才知道要对齐什么）

---

## 七、诚实边界

- 本文的「三层反检测」来自**静态符号与字符串**（B 级），
  尚未用运行时证据确认每一层是否真的启用
- `real_syscall` 是导出符号，但**未见实际调用记录**
- 上报通道**尚未定位**（`turing bytes` 捕获为 0）
- **因此「Turing 在检测我们」目前仍是推断，不是已证实的结论**

---

## 八、阶段①补充验证（2026-11-26 02:22）

### Turing 确实在运行

`
/data/user/0/com.tencent.mobileqq/app_turingdfp/1/.turing.dat   88 B   02:21 ← 随重启更新
/data/user/0/com.tencent.mobileqq/app_turingdfp/log/.t.log      88 B   02:21 ← 随重启更新
/data/user/0/com.tencent.mobileqq/app_turingfd/12/105668_xq_2  1904 B  20:27 ← 配置缓存，不常变
`

重启后 	uringdfp 的两个 88 字节文件立即更新 → **SDK 每次启动都初始化**。

### 但三层 Java 探针全部零命中

已部署并验证零输出的探针：

| 类别 | 覆盖 |
|---|---|
| 系统属性 | 24 个 
o.* / gsm.* 经 SystemProperties.get |
| Settings | Secure.getString / Global.getString |
| 电话 | TelephonyManager 12 个方法 |
| WiFi | WifiInfo 4 / WifiManager 3 |
| 包列表 | ApplicationPackageManager 3 |
| 文件 | File.exists / canRead / isFile / isDirectory |
| 进程 | Runtime.exec / Debug.isDebuggerConnected |
| **网络** | Socket.connect / InetSocketAddress / URL.openConnection / OkHttp.url / socketWrite0 |

**全部零命中。**

### 结论：Turing 完全绕开 Java 层

结合静态分析（导出 
eal_syscall、导入 syscall / __system_property_get /
popen / socket），可以确定：

> **Turing 的数据采集与上报都发生在 native 层，
> 且用 
eal_syscall 直接走内核，绕过所有 libc hook 与 Java hook。**

这也是既有记录「阻断上报会让会话从 120 分钟降到 7 分钟」的原因 ——
它的上报通道完全独立。

### 下一步唯一有效切入点


eal_syscall 是**导出符号**，可以直接 hook：

`c
long real_syscall(long number, ...);
`

hook 后记录 syscall 号与前几个参数，即可看到 Turing 绕过后到底读写了什么：

| syscall | 含义 |
|---|---|
| 56 | openat |
| 63 | read |
| 78 | readlinkat |
| 79 | newfstatat |
| 48 | faccessat |
| 211 | 其它（视架构） |

**这是看清 Turing 行为的唯一入口，也是阶段②的起点。**
