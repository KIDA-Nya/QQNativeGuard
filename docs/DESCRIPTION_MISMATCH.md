# `ro.build.description` 与 fingerprint 不同源 —— 重大自洽性缺陷（2026-11-27）

> 你问「有没有可能阻断了某些必要的上报」，这个方向指引我检查了数据完整性。
> 结果不是「阻断」，而是**数据自相矛盾** —— 而且是一个很严重的矛盾。

---

## 一、先回答你的问题：没有阻断上报

逐项排查结果：

| 检查项 | 结论 |
|---|---|
| `ENABLE_NETWORK_INTERCEPT` | **false**（关闭） |
| `NETWORK_OBSERVE_ONLY` | **true**（只观测） |
| `shouldBlock()` | `if (OBSERVE_ONLY) return false;` —— 恒不拦截 |
| `fake_send` / `fake_sendto` / `fake_write`（turingxq） | 全部 `BYTEHOOK_CALL_PREV` 原样转发 |
| `fake_write`（libc） | 只记录，不拦截 |
| `kBlocked` 命令拦截 | 实测 **0 次触发** |

**没有任何阻断。**

（顺带发现一个隐患：`fake_system` 对 `kBlocked` 命令返回 `0`（成功），
对 `which su` 这类命令而言等于告诉对方「找到了」。但实测这些命令从未被调用，
所以当前无影响。已记录待修。）

---

## 二、真正的缺陷：description 与 fingerprint 不同源

### 实测对比

```
ENV_FINGERPRINT = OPPO/PGT110/OPPO_PGT110:15/UKQ1.231003.002/1710000000:user/release-keys
ENV_DESCRIPTION = qssi-user 16 BP2A.250605.015 1785335584578 release-keys   ← 真机原值！
```

`ENV_DESCRIPTION` **从未被伪造过，一直是真机透传**。

### 逐字段核对

Android 定义里这两者必须同源：

```
fingerprint: <brand>/<product>/<device>:<release>/<id>/<incremental>:<type>/<tags>
description: <product>-<type> <release> <id> <incremental> <tags>
```

| 字段 | description（真机） | fingerprint（伪造） | 结果 |
|---|---|---|---|
| product | `qssi` | `PGT110` | ❌ |
| release | `16` | `15` | ❌ |
| build id | `BP2A.250605.015` | `UKQ1.231003.002` | ❌ |
| incremental | `1785335584578` | `1710000000` | ❌ |
| type | `user` | `user` | ✓ |
| tags | `release-keys` | `release-keys` | ✓ |

**四个字段全部对不上。**

### 这意味着什么

**服务端只要把 `ro.build.description` 和 `ro.build.fingerprint` 放在一起比对，
立刻就能判定这台设备被篡改过。**

这比「某个字段没伪造」严重得多 —— 因为它是**内部矛盾**，
不需要任何外部指纹库就能发现。

而且 QsecEst 里 `k60` 上报的就是 fingerprint（伪造值），
若 fekit 同时读到 description（真机值），两条通道一交叉就露了。

---

## 三、修复

```
旧: ENV_DESCRIPTION=qssi-user 16 BP2A.250605.015 1785335584578 release-keys
新: ENV_DESCRIPTION=PGT110-user 15 UKQ1.231003.002 1710000000 release-keys
```

新值由 fingerprint 反推：

```
product      PGT110
type         user
release      15
build id     UKQ1.231003.002
incremental  1710000000
tags         release-keys
```

### 验证映射链路

`UnifiedHookCoordinator.buildEnvProfile()` 的字段顺序：

```
[0] ENV_RELEASE      [1] ENV_SDK          [2] ENV_BUILD_ID
[3] ENV_DISPLAY      [4] ENV_FINGERPRINT  [5] ENV_DESCRIPTION
[6] ENV_HOST         [7] ENV_USER         ...
```

`hook_libc.cpp` 的 `fake_prop_value()`：

```cpp
if (strcmp(name, "ro.build.description") == 0) return g_env[5];
```

**`g_env[5]` ↔ `ENV_DESCRIPTION` 映射正确，修复会生效。**

---

## 四、修复后的一致性

```
ENV_RELEASE     = 15
ENV_SDK         = 35              (= Android 15)
ENV_BUILD_ID    = UKQ1.231003.002
ENV_INCREMENTAL = 1710000000
ENV_DISPLAY     = PGT110_15.0.1.200(CN01)
ENV_FINGERPRINT = OPPO/PGT110/OPPO_PGT110:15/UKQ1.231003.002/1710000000:user/release-keys
ENV_DESCRIPTION = PGT110-user 15 UKQ1.231003.002 1710000000 release-keys
```

**六项全部指向同一个构建。**

---

## 五、还剩一个已知矛盾（未修）

```
ENV_KERNEL_RELEASE = 5.4.254-qgki-tqmane-g9e38a9fabb4d    ← 真机透传
```

**问题**：5.4.x 是骁龙 888（lahaina）的内核版本，
而伪造的 PGT110（骁龙 8 Gen 3 / kalama）应该跑 6.1.x。

**为什么暂时没改**：改内核版本串风险较高（可能影响依赖 `uname` 的逻辑），
且服务端是否把它和机型交叉校验尚不确定。

**但这是明确的次要矛盾，需要评估。**

### 另一个次要项

```
ENV_DATE = Wed Jul 29 22:33:04 CST 2026       ← 真机
ENV_SECURITY_PATCH = 2026-08-01               ← 真机
```
「安全补丁日期晚于构建日期」看起来像矛盾，但**真机本身就是这个值**，
所以不是我们引入的问题，不处理。

---

## 六、教训

**「没伪造」和「伪造错」都会暴露，但后者更严重 —— 它制造的是内部矛盾。**

之前几轮我一直关注「哪些字段还没 fake」（`act=real` 归因），
却忽略了「已经 fake 的字段之间是否互相对得上」。

**正确的做法是双向检查：**
1. 有没有漏掉的（`act=real`）
2. **伪造的值之间是否自洽**（fingerprint ↔ description ↔ build.id ↔ incremental ↔ display）

这次是第 2 条抓到的，而且是靠你的提问才想到去查。
