# w21 踢线分析与 Turing 活动修正（2026-11-26 21:21）

> 重装后存活 **2 小时 37 分**（18:07 登录 → 20:44 被踢），比之前 68 分钟长，但仍被服务端踢下线。

---

## 一、这次是 w21（服务端踢线），不是「登录失效」

```
20:44:00.901  wm_create_activity: com.tencent.mobileqq/.activity.LoginActivity,
              mqq.intent.action.KICK_TO_LOGIN
20:44:00.917  START {act=mqq.intent.action.KICK_TO_LOGIN ... mCallingUid=10371}
```

**`mCallingUid=10371` 就是 QQ 自己**（重装后 uid 从 10369 变成 10371），
所以是 QQ 收到服务端通知后自己跳转登录页 —— 标准踢线流程。

---

## 二、登录过程本身完全正常

```
19:03:29  uin=3402312720  rst1=0 rst2=0  rlen=2377  used=2016ms
19:24:48  uin=3402312720  rst1=0 rst2=0  rlen=2377  used=1637ms
19:53:37  uin=3402312720  rst1=0 rst2=0  rlen=2377  used=1604ms
20:30:29  uin=3402312720  rst1=0 rst2=0  rlen=2377  used=2453ms
```

**四次周期性上报全部成功，`ksid` 稳定为 `9b42d52f…`，没有任何失败。**

**这不是本地数据不一致导致的拒绝，而是服务端在会话中途改变判定后主动踢线。**

---

## 三、GUID 三条路径现在都已对齐

| 路径 | 存储 | 值 |
|---|---|---|
| wlogin `generateGuid` / `getGuidFromFile` | hook 拦截 | `2a828fa0…` |
| MSF `MSFSharedPreUtils.getGuid` | `files/mmkv/msf_mmkv_file` 里的 `msf_guid` | **`2a828fa0…`（一致）** |
| `wlogin_device.dat` | **未生成** | 不存在冲突 |

**实测 `msf_mmkv_file` 内容：**
```
msf_guid! 2a828fa04b4343f12de4f51dc784071b
```
**与 wlogin 的伪造值完全一致 —— 这一条不是缺口。**

---

## 四、重大修正：Turing 一直在活动

### 之前的结论是错的

前面几轮我通过 hook `real_syscall` 和 `dl_iterate_phdr` 得出「Turing 三层反检测全零调用」，
并据此判断「Turing 未集成、不上报」。

**但这次在 `qqbypass.log` 里抓到了 Turing 的 Java 层检测记录：**

```
18:02:47  ApplicationPackageManager(getPackageInfo) <- turingfd.sdk.xq.Peanut
18:02:47  File(exists)                              <- turingfd.sdk.xq.b2
20:04:14  File(exists)                              <- turingfd.sdk.xq.Caelum
20:04:14  File(exists)                              <- turingfd.sdk.xq.b2
20:44:12  File(isFile)                              <- turingfd.sdk.xq.Peanut
```

### 两个关键认识

**① Turing 走的是 Java 层检测，不是 native**

我此前验证的 `real_syscall` / `dl_iterate_phdr` 确实是零调用 —— 但那只是 native 绕过路径。
Turing 实际用的是 `ApplicationPackageManager.getPackageInfo`（查是否装了 root 管理工具）
和 `File.exists` / `File.isFile`（查 su 路径）。

**② 这 5 条是 1/64 采样后的结果**

`TuringReadProbe` 里设了 `SAMPLE_MASK = 0x3F`，为避免高频 API 拖慢 QQ。
所以实际调用次数约为 **5 × 64 ≈ 320 次**量级。

**「Turing 不活动」这个结论必须撤回。**

### 时间上的疑点

```
20:44:00  被踢
20:44:12  Turing 检查（File.isFile）
```

**Turing 是在被踢之后 12 秒才活动的**，所以看起来是「响应踢线」而非「导致踢线」。

**但这不能排除它此前就检查过**（采样漏掉了）。要确认，得关掉采样限流再观察。

---

## 五、其他发现

### `keys_accounts_invalid = 1`

`files/mmkv/msf_mmkv_file` 里有这个标记，值为 1。
含义未确认 —— 可能是「该账号曾出现无效状态」的历史记录，也可能影响当前判定。

### `sp_imei = ad1a616d9b789112`

MSF 的 `sp_imei` 存的是 **ANDROID_ID 的值**，而不是我们设的 `FAKE_IMEI`（`861361825484209`）。

这可能是 QQ 的正常行为（`sp_imei` 在某些版本存 android_id），
但也可能是服务端比对时的一个矛盾点。**需要进一步确认。**

---

## 六、下一步

| # | 任务 | 目的 |
|---|---|---|
| 1 | **关掉 TuringReadProbe 的采样限流**，记录 Turing 检查的**具体路径/包名** | 确认它在查什么，是否命中真实泄露 |
| 2 | 查 `w21` 包（21 个混淆类）的方法体 | 确定它对应的错误分类 |
| 3 | 确认 `sp_imei` 的用途 | 判断是否是矛盾点 |
| 4 | 确认 `keys_accounts_invalid` 语义 | 判断是否影响判定 |

---

## 七、诚实结论

**这次重装让会话从 68 分钟延长到 2 小时 37 分，但没解决问题。**

**服务端仍然在会话中途判定后踢线，说明还有某个指纹或行为特征不对。**

**而我上一轮「Turing 不活动」的判断被这次数据推翻了 —— 这是一个必须记录的教训：**
**native 层零调用 ≠ 整个 SDK 不活动。检测可能完全走 Java 层。**
