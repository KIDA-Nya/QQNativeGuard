# Turing 检查清单已完全摸清（2026-11-26 22:05）

> 通过去掉采样限流 + 记录参数，终于抓到 Turing 到底在查什么。
> **结果：7 条检查全部正常，Turing 不是这次 w21 的元凶。**

---

## 一、方法改进（这次能抓到全靠它）

原来的 `TuringReadProbe` 有 `SAMPLE_MASK = 0x3F`（1/64 采样），
为避免 `File.exists` 这类高频 API 拖慢 QQ。

**代价是漏掉了 98% 的 Turing 调用** —— 之前 5 条记录，实际可能有 300+ 次。

改法：

1. **去掉采样限流**
2. 改用 `quickTuringCheck()`：只看前 14 层栈、用 `startsWith("com.tencent.turing")` 而非 `contains`
3. 靠已有的 `seen` 去重防止日志爆炸
4. `File` 探针改为记录 **`getPath()` 的具体路径**
5. `PackageManager` 探针改为记录 **包名参数**

**这样才看到真东西 —— 之前只记录 `File.exists` 这个方法名，等于白抓。**

---

## 二、Turing 的完整检查清单（7 条，A 级）

```
22:05:36.061  File.exists(/data/user/0/com.tencent.mobileqq/app_turingfd/12)
              <- com.tencent.turingfd.sdk.xq.c2

22:05:36.152  PM.getPackageInfo(com.tencent.mobileqq, 64)
              <- com.tencent.turingfd.sdk.xq.Peanut

22:05:36.158  File.exists(/data/user/0/com.tencent.mobileqq/app_turingfd/mpdc_105668_1)
              <- com.tencent.turingfd.sdk.xq.Peanut

22:05:36.161  PM.getPackageInfo(com.tencent.mobileqq, 64)
              <- com.tencent.turingfd.sdk.xq.Papaya

22:05:36.162  File.isFile(/data/user/0/com.tencent.mobileqq/app_turingfd/mpdc_105668_1)
              <- com.tencent.turingfd.sdk.xq.Peanut

22:05:36.192  File.exists(/data/data/~~HOPOWnNCInzb)                        ★
              <- com.tencent.turingfd.sdk.xq.Caelum

22:05:36.492  PM.getPackageInfo(de.robv.android.xposed.installer, 0)        ★★
              <- com.tencent.turingfd.sdk.xq.l3
```

### 检查项归类

| 类别 | 数量 | 说明 |
|---|---|---|
| 自身缓存 | 3 | `app_turingfd/12`、`mpdc_105668_1` |
| 自身包信息 | 2 | 拿 `getPackageInfo` 的签名/版本 |
| **安装目录异常** | 1 | `/data/data/~~HOPOWnNCInzb`（APK 路径 hash 前缀） |
| **Xposed 检测** | 1 | `de.robv.android.xposed.installer` |

---

## 三、两个关键检查点的实测结果

### ① `/data/data/~~HOPOWnNCInzb` —— 不存在 ✓

Turing 在查 `/data/data` 下有没有以 APK 安装路径 hash 前缀命名的目录
（`~~HOPOWnNCInzb` 正是重装后的新路径前缀）。
这是检测「APK 被移动/重打包」的手法。

```
ls: /data/data/~~HOPOWnNCInzb: No such file or directory
/data/data 下的 ~~ 前缀目录: 无
```

**正常。**

### ② `de.robv.android.xposed.installer` —— 未安装 ✓

```
pm list packages | grep -iE 'xposed|lsposed|edxposed|riru'   → 无输出
```

**这台设备用的是 LSPosed 核心模式，没有装 manager APP，所以这个包不存在。**

**正常。**

---

## 四、结论：Turing 不是 w21 的元凶

**7 条检查全部通过，没有一条命中真实泄露。**

这修正了我上一轮的猜测（当时看到 `File.isFile` 就怀疑 Turing 在检测）。
**现在有了具体路径，可以确定它在查什么，且都没问题。**

---

## 五、那 w21 到底为什么

### 已知事实

```
18:07:20  登录成功（ksid 9b42d52f…）
19:03:29  rst1=0  rlen=2377  used=2016ms    ✓
19:24:48  rst1=0  rlen=2377  used=1637ms    ✓
19:53:37  rst1=0  rlen=2377  used=1604ms    ✓
20:30:29  rst1=0  rlen=2377  used=2453ms    ✓
20:44:00  KICK_TO_LOGIN                     ✗ 被踢
```

- 登录全程无失败
- 四次周期上报全部 `rst1=0`
- GUID 三条路径（wlogin / MSF / 文件）全部一致为 `2a828fa0…`
- Turing 检查全部正常
- `act=real` 归因零记录（虽然 logcat 已滚动，无法回溯被踢前）

### 无法确定的

**20:30 到 20:44 这 14 分钟内发生了什么，现在已经查不到了** —— `logcat` 滚动、`qqbypass.log` 也在本轮被我清空（为了干净观察 Turing）。

**这是一个操作失误：清日志前应该先归档上一轮的。**

### 可能的解释（未证实）

| 假设 | 依据 | 如何验证 |
|---|---|---|
| 服务端周期性校验某个指纹 | 2h37m 后踢，时间点不像随机 | 需保留完整日志重跑一次 |
| `sp_imei` 存的是 ANDROID_ID 而非 IMEI | MSF 里实测 `sp_imei=ad1a616d9b789112` | 查 `sp_imei` 的写入方与用途 |
| `keys_accounts_invalid=1` 有影响 | MSF MMKV 里有此标记 | 查该 key 的语义 |
| 账号维度被标记（与设备无关） | 之前你提到另一账号稳定 | 需要对照实验 |

---

## 六、下一步

1. **保留日志**：这次观察 Turing 结束后，把 `qqbypass.log` 归档再清
2. **盯住被踢前的最后 15 分钟**：下次被踢后立刻抓，不要等
3. **查 `sp_imei` 的写入方**：确认存 ANDROID_ID 是正常行为还是矛盾点
4. **查 `keys_accounts_invalid` 语义**

---

## 七、诚实记录

**这轮工作的价值在于：把「Turing 在检测什么」从猜测变成了确定的 7 条清单。**

**但对「为什么被 w21 踢」这个问题，仍然没有答案。**

**而且我犯了一个错误：清空 `qqbypass.log` 导致被踢前的归因数据丢失。** 下次必须先归档。

---

## 八、新增：踢线瞬间快照（KickSnapshot）

### 为什么需要

每次被 w21 踢下线后，logcat 都已经滚动，拿不到被踢时刻的状态。
20:44 那次就是如此 —— 只知道被踢，不知道之前发生了什么。

### 做法

hook LoginActivity.onCreate 和 LoginPublicFragmentActivity.onCreate，
当 Intent action 含 KICK_TO_LOGIN 时，立刻落盘：

- 触发时间
- **Intent 的 extras（错误码很可能在这里）**
- 当前进程内的设备指纹（确认伪造是否还在生效）
- sp_login_auto 的全部键值
- wlogin_device.dat 的现状（存在与否 + hex）

输出到 iles/qqng_kick_snapshot.log。

### 验证

`
[TURING-PROBE] installed
[KICK-SNAP]    installed
`

**下次被踢时，证据一定能拿到。**

---

## 九、另外两个疑点已排除

| 疑点 | 结论 |
|---|---|
| sp_imei 存的是 ANDROID_ID 而非 IMEI | **正常** —— Android 10+ 限制 IMEI 访问，QQ 用 ANDROID_ID 替代 |
| keys_accounts_invalid = 1 | **正常** —— 只是 classes18.dex 里某库的字符串常量，非 QQ 主逻辑 |

两个都不是矛盾点。
