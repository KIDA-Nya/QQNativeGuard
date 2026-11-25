# Turing `devicetoken_report` 揭示的上报字段（2026-11-25）

> 从 Turing 的 MMKV 配置里挖出一份**明确的上报字段清单**，
> 由此定位了 `wifissid` 字段，并补上了 `getSSID` 的 hook。
>
> ⚠️ **2026-11-25 实测修正**：配置虽写 `wifissid: "1"`，但 QQ **从不调用**
> `WifiInfo.getSSID()`（重启后持续观察 0 次），各 native 库（libturingxq /
> libqimei / libmsfbootV2）也无 SSID 字符串。说明该字段**配置存在但客户端
> 不采集**。hook 作为防御保留，当前未被触发。

---

## 一、证据来源（A 级）

拉取 `/data/user/0/com.tencent.mobileqq/files/mmkv/4cd6974be1_10001_null_3402312720`
（524,288 字节，MMKV 格式），解析出 Turing 的配置项。

### 关键配置片段（原文，已反转义）

```json
{
  "key": "devicetoken_report",
  "value": {
    "config_value_type": 1,
    "config_value": {
      "android_id": "1",
      "duration": "1",
      "imei": "1",
      "mac": "1",
      "model": "1",
      "other": "1",
      "turingid": "0",
      "wifissid": "1",
      "turingidnew": "1",
      "uin_per_": "10",
      "report_background": "1",
      "tinyid": "0"
    },
    "switch_state": 1
  },
  "op": 1,
  "debugInfo": "850198#1",
  "report": {"hitSubTaskID": "608357"}
}
```

**`switch_state: 1` = 该上报任务已启用。**

---

## 二、字段含义（`"1"` = 上报，`"0"` = 不上报）

| 字段 | 上报 | 我们的覆盖 |
|---|---|---|
| `android_id` | ✅ | ✅ FAKE_ANDROID_ID |
| `imei` | ✅ | ✅ FAKE_IMEI |
| `mac` | ✅ | ✅ macHook 返回 `02:00:00:00:00:00` |
| `model` | ✅ | ✅ ENV_MODEL=PGT110 |
| **`wifissid`** | ✅ | ❌ **完全没处理** ← 本次发现 |
| `turingidnew` | ✅ | ？ 未确认是什么 |
| `other` | ✅ | ？ |
| `duration` | ✅ | 上报周期（非标识） |
| `uin_per_` | `10` | 每 10 个 uin 上报一次（策略） |
| `turingid` | ❌ | 不上报 |
| `tinyid` | ❌ | 不上报 |

---

## 三、缺口：`getSSID` 从未被 hook

### 原代码（只有 MAC 和 BSSID）

```java
XposedBridge.hookAllMethods(wifiInfo, "getMacAddress", macHook);
XposedBridge.hookAllMethods(wifiInfo, "getBSSID", macHook);
// ← 没有 getSSID
```

### 真机实际值

```
系统 SSID = "<real-ssid>"          ← 校园网，真实环境特征
WiFi MAC  = <real-wifi-mac>  （已随机化，但被 macHook 覆盖）
```

**所以 `wifissid` 字段一直在上报真实的校园网名称。**

### 为什么这有害

`wifissid` 是**环境指纹**：

- 固定网络（家/办公室）→ 稳定特征，有助于「同一设备」判定
- 校园网/公共 WiFi → 大量设备共享，反而可能是负分
- **但关键是：它是否与我们上报的其它字段自洽**

如果设备伪装成「OPPO Find X7 用户」，而其 WiFi 是某大学校园网，
本身不矛盾（学生也用 Find X7）。真正的问题是**变化**：

> 同一套设备指纹 + 不断变化的 WiFi 名称 = 环境不稳定信号

**而且我们无法知道服务端如何加权这个字段**（D 级，不可验证）。

---

## 四、修复实施

### 1. `WifiInfo.getSSID`

```java
XposedBridge.hookAllMethods(wifiInfo, "getSSID", ssidHook);
// 只替换真实 SSID，保留 null / <unknown ssid>
// Android 的 getSSID 返回带引号的值，所以设为 "\"" + fake + "\""
```

### 2. `WifiManager.getScanResults` 的 `ScanResult.SSID`

扫描列表里的周边网络也会泄露位置特征（能看到哪些 AP）。
修复方式：反射改 `ScanResult.SSID` 字段，并截断到 3 条。

### 3. 配置项

```
FAKE_WIFI_SSID=TP-LINK_5G
```

**选值考虑**：
- 用最常见的企业/家用路由器名，不显眼
- 不用 `<unknown ssid>`（有 WiFi 权限却拿不到 SSID 反而可疑）

---

## 五、验证状态

### 已确认（A 级）

```
WifiInfo SSID forced: TP-LINK_5G   × 3 个进程（主进程 + 2 子进程）
```

**hook 安装成功。**

### 实测结论：hook 从未被触发

持续观察后确认：

```
[SSID] origin=... 日志   → 0 次
getSSID 实际调用          → 0 次
```

**交叉验证，四个方向全部指向「客户端不采集 SSID」**：

| 检查项 | 结果 |
|---|---|
| `libturingxq` 的 ssid/wifi 字符串 | **0 条** |
| `libqimei` 的 ssid/wifi 字符串 | **0 条** |
| `libmsfbootV2` 的 ssid/wifi 字符串 | **0 条** |
| `libkernel` 的 wifi 命中（401 条） | 全是 `WiFiPhoto`（WiFi 传文件功能），与 SSID 采集无关 |
| QQ 的 `ACCESS_WIFI_STATE` 权限 | `granted=true` —— **有权限但不调用** |

**所以 `wifissid: "1"` 是服务端下发的模板项，客户端并未实现该字段的采集。**

### 那 hook 还要留吗

**要，作为防御性措施。**

理由：
- 成本为零（已安装，未触发）
- 若 QQ 将来启用这条路径（配置项已存在，随时可能打开），hook 立即生效
- 不引入任何副作用（只在 `afterHookedMethod` 检查，不改其它行为）

**这是「防御性覆盖」而不是「已修复的漏洞」——两者要分清。**


---

## 六、方法论价值

**这次是从「配置」反推「要 hook 什么」，比逐个 API 猜更高效。**

具体路径：
```
MMKV 里的 Turing 配置
  → devicetoken_report 的字段清单
  → wifissid 需要上报
  → 查我们的 hook 覆盖
  → 发现 getSSID 缺失
  → 补上
```

**如果只靠「QQ 可能读什么」来猜，几乎不可能想到 `wifissid`。**

**建议复用**：其它 SDK（Bugly / Qimei / Beacon）的配置里可能也有类似的字段清单，
值得逐个挖出来对照。

---

## 七、待办

| # | 任务 | 优先级 |
|---|---|---|
| 1 | 等 `[SSID] origin=` 日志出现，确认替换生效 | 高 |
| 2 | 挖 Bugly / Qimei / Beacon 的配置字段清单 | 中 |
| 3 | 确认 `turingidnew` 是什么（可能是新设备 ID） | 中 |
| 4 | 确认 `other` 字段具体指什么 | 低 |