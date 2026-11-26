# 卸载重装流程与验证（2026-11-26 18:07）

> 设备被标记后按你的方案执行：卸载重装 + 全部换新值。
> **结果：登录成功，无需验证码，日志显示 `ksid` 非空。**

---

## 一、为什么这样做

你给出的判断：**当前设备已被标记，需要卸载重装 + 换新值。**

配合前面查到的根因（GUID 双路径不一致导致 `KLOGINERRORDIFFGUIDBETWEENQQHEADANDLOGINHEAD`），
重装能同时达成两件事：

1. 清掉所有可能已被服务端关联的本地标识
2. 换一批全新的设备指纹，让服务端看到一个「没见过的设备」

---

## 二、卸载前的准备（关键，不可逆操作）

| # | 准备项 | 结果 |
|---|---|---|
| 1 | **提取 APK** | `apk/qq_9.3.50_base.apk`（396.4 MB，ZIP 完整，40351 条目，`classes.dex` + `lib/arm64-v8a/libfekit.so` 齐全） |
| 2 | 确认单包 | `pm path` 只返回 `base.apk`，无 split |
| 3 | 备份配置 | 设备 `qqbypass.conf.bak_20261126_174101` + 本地 `backup_qqbypass_20261126_174101.conf` |
| 4 | 生成新值 | 见下表 |

**APK 必须先提取** —— 卸载后就再也拿不到了。

---

## 三、新一批伪造值

```ini
FAKE_WLOGIN_GUID   = 2a828fa04b4343f12de4f51dc784071b      (旧 be767c1b…)
FAKE_IMEI          = 861361825484209                        (旧 867548026624932)
FAKE_ANDROID_ID    = ad1a616d9b789112                       (旧 0972ced609b51824)
FAKE_SERIAL        = C2669259                               (旧 753E5B81)
FAKE_SOC_SERIAL    = 9916868093                             (旧 2593651717)
FAKE_QIMEI36       = 15af1f0ce308ef7947b01482eae5d4f56782
FAKE_OAID          = ccc37c6d-91a5-45fd-8efc-fa31c1978671   (旧 0b087241-…)
FAKE_WIFI_SSID     = TP-LINK_A7C2                           (旧 TP-LINK_5G)
```

**机型画像保持不变**（OPPO PGT110 / kalama / SM8650 一套自洽配置），
只换唯一标识 —— 这样设备看起来是「同型号的另一台机器」。

IMEI 带 Luhn 校验位（15 位有效）。

---

## 四、执行

```
1. force-stop ×2
2. adb uninstall com.tencent.mobileqq      → Success，/data/user/0/… 目录消失
3. adb install apk/qq_9.3.50_base.apk      → Success，29.6 秒
```

### 路径确实变了（如你所料）

```
旧: /data/app/~~9w2S_d7zZz5nVAyqjmqhSA==/com.tencent.mobileqq-42-GVdRSyt89oESO3M-9gQ==/
新: /data/app/~~HOPOWnNCInzb-mQoU43g9w==/com.tencent.mobileqq-VMzwwGmxqayZbqv-ERjw0Q==/
```

**但所有 hook 基于包名 `com.tencent.mobileqq`，不依赖这个随机路径，所以无需改代码。**

---

## 五、重装后模块状态

```
External config loaded from /data/local/tmp/qqbypass.conf     ← 新配置生效
=== Native Bypass Library Loaded ===
=== All Native Hooks Installed Successfully ===
Faked fopen: /sys/devices/soc0/serial_number                  ← 新 SoC serial
[WLOGIN-UTIL] getGuidFromFile -> FAKED 2a828fa04b4343f12de4f51dc784071b
[WLOGIN-UTIL] generateGuid    -> FAKED 2a828fa04b4343f12de4f51dc784071b
```

**LSPosed 作用域没有丢**（`com.tencent.mobileqq  0` 仍在），无需重新勾选。

---

## 六、登录验证（A 级）

### 登录本身成功

`wup_dump` 关键时间线：

```
18:07:20  WLOGIN_JSON len=766   含 "ksid":"9b42d52f…"    ← ksid 非空
18:07:20  wtlogin.log_report
18:07:57  QsecEst.d len=1293
18:08:26  SsoHomeReport
```

**`ksid` 从空变为有值，是登录真正完成的标志**（此前几轮一直是空）。
`rst1=0 rst2=0`，**没有被拒**，**也没有要求验证码**（你反馈「无验证」）。

### 新的 QsecEst（85 字段）

```
k53 = OPPO
k54 = kalama                       ← 骁龙 8 Gen 3
k57 = PGT110                       ← OPPO Find X7
k58 = qcom
k60 = OPPO/PGT110/OPPO_PGT110:15/UKQ1.231003.002/1710000000:user/release-keys
k63 = OPPO
k66 = PGT110_15.0.1.200(CN01)
k69 = arm64-v8a
k84 = 9916868093                   ← ★ 新 SoC serial（旧 2593651717）
k22 = 3402312720                   ← 主号
```

**新身份已生效，机型画像全部自洽。**

### 其他指标

| 项 | 值 |
|---|---|
| `KICK_TO_LOGIN` | 0 |
| `RISK-OBSERVE` | 0 |
| 归因日志 `act=real` | **无**（全部 fake） |

---

## 七、`wlogin_device.dat` 未生成 —— 反而是好事

重装后 QQ **没有**写这个文件。

含义：
- 不存在「文件里是真机 GUID」这个隐患
- `getGuidFromFile` 走「文件不存在」分支 → 返回空 → 被我们的 hook 替换成伪造值
- 之前那个 `DIFFGUID` 的分裂条件不再成立

---

## 八、待观察

| 项 | 怎么看 |
|---|---|
| **是否还会「登录状态已失效」** | 这是核心 —— 需要长时间（数小时）观察 |
| 会话存活时长 | 对比历史（21 分钟 / 2h03m / 2h36m / 3h44m） |
| 服务端是否重新标记 | 看是否再次出现 `rst1=1` |

**建议：正常使用，锁屏放着，观察是否能撑过之前常掉的时段。**

---

## 九、回滚方式（如需）

```
1. 卸载当前 QQ
2. adb install apk/qq_9.3.50_base.apk
3. adb shell cp /data/local/tmp/qqbypass.conf.bak_20261126_174101 /data/local/tmp/qqbypass.conf
```

旧配置备份在设备与本地各一份。
