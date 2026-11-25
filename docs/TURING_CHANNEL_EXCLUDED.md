# Turing 上报通道全面排除（2026-11-26 轮 4）

> 结论：**Turing 在当前环境下不上报**。
> 全部四条可能通道逐一排除，且 2.7 天完整记录里零 Turing 命令。

---

## 一、四条通道逐一排除

| # | 可能的通道 | 验证方式 | 结果 |
|---|---|---|---|
| 1 | **raw socket**（`send`/`sendto`） | native hook 已覆盖 | **0 记录** |
| 2 | **MSF**（`MsfCore.sendSsoMsg`） | wup_dump 全量命令统计 | **0 条 Turing 命令** |
| 3 | **Java 网络**（OkHttp / HttpURLConnection / Socket） | 5 类 Java 探针 | **0 命中** |
| 4 | **自有 HTTP 栈** | `libturingxq` 内零域名零 IP | 无地址可连 |

### 通道 1：raw socket

`hook_libturingxq.cpp` 已 hook `send` / `sendto` / `write`，
按调用者地址判定归属。实测：

```
turing write len=88 caller_off=0x294ec     ← 仅写 .turing.dat
无 send / sendto 记录
```

### 通道 2：MSF —— 这是最决定性的一条

`wup_dump.log` 覆盖 **2.7 天**（2026-11-23 13:06 → 2026-11-26 02:54），
共 361 行，命令分布：

```
230  trpc.gc_indust.device_report.SsoHome.SsoHomeReport
 59  WLOGIN_JSON
 56  wtlogin.log_report
 16  QsecEst.d
```

**搜 `turing` / `riskCheck` / `deviceReport` 全部零结果。**

**所以 Turing 不走 MSF —— 这是一个跨越 2.7 天的强否定。**

### 通道 3：Java 网络

已部署 5 个探针：`Socket.connect` / `InetSocketAddress` /
`URL.openConnection` / `OkHttp Request.Builder.url` / `SocketOutputStream.socketWrite0`。

**全部零命中。**

### 通道 4：自有 HTTP 栈

`libturingxq.so` 全库扫描：

```
域名: 0 个（只有 android.googlesource.com/toolchain/llvm-project 一条编译器路径）
IP:   0 个
```

**没有地址可连。** 即使它自建 HTTP，也不知道该发去哪里。

---

## 二、Turing 在当前环境的全部行为

把三轮观察合在一起：

| 行为 | 记录 |
|---|---|
| `access` 自己的 log 目录 | 1 |
| `access` 自己的数据目录 | 1 |
| `fopen` `.t.log` | 1 |
| `stat` `.t.log` / `.turing.dat` | 2 |
| `write` 88 字节到 `.turing.dat` | 2 |
| `real_syscall` 调用 | **0** |
| `dl_iterate_phdr` 调用 | **0** |
| Java 反射检测 | **0** |
| 任何网络行为 | **0** |
| 读取任何设备属性 | **0** |

**总计：只用 5 次文件操作读写自己的缓存，其余全无。**

---

## 三、与项目内既有记录的矛盾（必须指出）

`hook_libturingxq.cpp` 里写着：

> 实测：阻断 Turing 上报会让会话从 ~120 分钟骤降到 7 分钟 ——
> 服务端依赖它的数据，缺失即判异常。因此这里只观测、放行，绝不拦截。

**但与本次全部实测冲突** —— Turing 根本不上报，阻断它无从谈起。

三种可能，按可能性排序：

1. **那条记录是旧版本的经验**（当时 QQ 版本可能不同，Turing 参与登录流程）
2. **当时阻断的其实是 fekit** —— fekit 才是采集主力（381 次 vs 5 次），
   若把 fekit 的读取拦掉，服务端确实会立刻发现数据缺失
3. Turing 的上报需要特定触发条件（如检测到风险后才启用）

**在拿到 Turing 实际发送数据的证据前，不应该继续把那条记录当作事实依据。**

**它已经误导了本项目的方向选择** —— 让我以为 Turing 是核心，
实则 fekit 才是战场，而 fekit 现在已全部验证干净。

---

## 四、归因面完整收口

### native 层：只有 fekit 在采集

本次观察窗口内出现的模块：

```
386  libfekit_strip_8.608.1109.so    ← 唯一采集者
 30  libnative-bypass.so             （我们自己）
 27  libopenjdk.so                   （JVM）
 14  libc.so
 10  libjavacore.so
  5  libandroid_runtime.so
  2  libmeminfo.so                   （smaps，已 fake）
  1  libbinder.so
  1  libbasic_share.so               （/dev/urandom，正常）
```

**没有 `libqimei` / `libturingxq` / `libBugly_Native`。**

### 全模块仅剩 3 个 act=real，且全部不矛盾

```
ro.hardware           = qcom          （PGT110 同为 qcom）
ro.product.cpu.abi    = arm64-v8a     （PGT110 同为 arm64-v8a）
ro.boot.hardware      = qcom          （PGT110 同为 qcom）
```

### Java 层覆盖面确认

`DeviceInfoHook` 已 hook：

```
android.telephony.TelephonyManager          （多方法：getDeviceId/getImei/getSubscriberId/...）
android.os.SystemProperties.get
android.net.wifi.WifiInfo                   （getMacAddress/getBSSID/getSSID）
android.bluetooth.BluetoothAdapter.getAddress
android.provider.Settings$Secure/Global     （ANDROID_ID / AAID）
com.tencent.qphone.base.util.IMSFQimei
com.tencent.qmethod.pandoraex.MonitorReporter
com.tencent.qimei.sdk.Qimei / QimeiSDK      （getQimei16/getQimei36）
oicq.wlogin_sdk.tools.util                  （getQimei16/getQimei36）
```

**Qimei 等 SDK 走 Java 层取值，这条路已被覆盖** ——
这解释了为什么它们不出现在 native 归因里。

---

## 五、本轮结论

| 项 | 状态 |
|---|---|
| 采集点定位（原阶段①） | ✅ **完成**：fekit 是唯一 native 采集者，已完整枚举 344 路径 + 14 属性 |
| 上报内容捕获（原阶段②） | ⚠️ **Turing 无上报可捕获**；结论是「它不上报」，不是「抓不到」 |
| 数据对齐（原阶段③） | ✅ **完成**：first_api_level=34、soc.model=SM8650，全部 act=fake 已验证 |
| 端到端验证（原阶段④） | ⚠️ 需一次真实登录后解密 QsecEst 复核 |

**目标从「Turing 反检测」实际演化为「设备指纹自洽」，
而后者在客户端层面已经收口。**

**唯一还没做的，是一次登录后的端到端复核。**
