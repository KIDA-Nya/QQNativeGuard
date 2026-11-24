# GUID 伪造实施完成（2026-11-25）

> 第 5 指纹通道已控制。

## 一、实施结果

```
改前: generateGuid() -> <orig-guid-16B>
改后: generateGuid() -> <fake-guid-16B>
```

**日志确认**：
```
[WLOGIN-UTIL] generateGuid -> FAKED <fake-guid-16B>
[WLOGIN-UTIL] generateGuid() -> byte[16] <fake-guid-16B>
```

## 二、为什么必须 hook 而不是改文件

**三次单变量实验的结论**（docs/GUID_SOURCE_EXPERIMENT.md）：

| 实验 | 改动 | 结果 |
|---|---|---|
| 1 | `files/wlogin_device.dat` | ❌ 值不变 |
| 2 | MMKV `android_id` 键 | ❌ 值不变（彻底重启进程后） |
| 3 | **全部 20 处存储**（dat + MMKV 3 键 + MSF 18 处） | ❌ 仍不变 |

**结论**：`generateGuid()` **不读任何持久化存储**，是运行时从硬件标识派生。

**这与 NapCat 的实证一致**：
- Linux: `GUID = MD5(/etc/machine-id + MAC Address)`
- macOS: `GUID = MD5(machine_id[8] || sn)`
- Android（推断）：类似哈希，输入是硬件标识

我们找到的 20 处存储只是**计算结果被缓存**，每次启动会重新计算覆盖。

## 三、实施方案

```java
// HookConfig 新增
public static String FAKE_WLOGIN_GUID = null;   // 32 hex = 16 字节

// RootDetectionHook 新增
HookUtils.hookAllMethods("oicq.wlogin_sdk.tools.util", "generateGuid",
    new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            Object r = param.getResult();
            if (r instanceof byte[]) param.setResult(fakeGuid.clone());
        }
    });
```

**关键细节**：
- 返回类型是 `byte[16]`，配置用 32 hex 字符串，需 `hexToBytes` 转换
- 必须 `.clone()`，否则同一个数组被多处持有会相互影响
- 同时 hook `generateGuidForUser`（可能的重载）

## 四、副作用（预期内）

**QQ 停在登录页** —— 这符合 NapCat 的实证：

> **The QQ authentication server validates the session ticket against the hardware fingerprint (Device GUID). If the GUID changes, the server invalidates the stored ticket and demands a fresh scan or device verification.**

**GUID 变 → 票据作废 → 需要重新登录。** 这是设计行为，不是 bug。

**验证**：`pidof = 12213`（进程活着），`died = 0`（无崩溃），`KICK = 0`（非风控踢线）。

## 五、现在的设备身份完整性

| # | 通道 | 状态 |
|---|---|---|
| ① | QsecEst 85 字段 | ✅ k54=kalama 等全对齐 |
| ② | HTTP User-Agent | ✅ http.agent 重算 |
| ③ | MSF 8080 | ✅ QIMEI 对齐 |
| ④ | VOD_MSDK | ✅ 已 hook |
| ⑤ | **wlogin GUID** | ✅ **已伪造（本次）** |

**5 个通道全部可控。**

## 六、待验证

| # | 项目 | 方法 |
|---|---|---|
| 1 | 重新登录后能否正常进主界面 | 登录测试 |
| 2 | 新 GUID 是否持久（多次重启不变） | 重启 3 次对比 |
| 3 | 服务端是否接受新 GUID | 观察是否要求设备验证 |
| 4 | 该 GUID 是否进入上报载荷 | 抓包分析 |

## 七、方法论价值

**这次是严格的「单变量对照」**：

```
改 A → 输出不变
改 B → 输出不变
改 A+B+全部 → 输出仍不变
⇒ 断定：不在存储里
⇒ 转向：hook 函数本身
```

**如果一开始就直接 hook，会以为是"猜对了"；只有做完三次否定实验，才知道"改存储必然失败"。**

这个方法论应该复用到所有「改什么能影响什么」的判断上。