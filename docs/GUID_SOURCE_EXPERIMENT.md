# GUID 源头实验：三次证伪，最终确认是内存派生（2026-11-25）

> 方法：三次单变量实验，逐一排查「generateGuid() 的 16 字节从哪来」。

## 一、实验设计与结果

| # | 改动 | 结果 | 判定 |
|---|---|---|---|
| **1** | 改 `files/wlogin_device.dat`（裸 16 字节）为 `deadbeef...` | `generateGuid()` 仍返回原值 | ❌ **文件不是源头** |
| **2** | 改 `mmkv/wtlogin_mmkv_file` 的 `android_id` 键（偏移 95，等长替换） | 彻底重启进程后仍返回原值 | ❌ **MMKV 该键不是源头** |
| **3** | **同时改**：dat 文件 + MMKV（`android_id` / `last_android_id` / `last_guid`）+ MSF（18 处） | 仍返回原值 | ❌ **所有存储都不是源头** |

**关键**：实验 3 覆盖了**全部已发现的存储位置**（共 20 处），值依然不变。

## 二、结论：运行时从硬件标识派生

`generateGuid()` 在**每次调用时实时计算**，不读任何持久化存储。

这与 [NapCat 的实证](https://git.nite07.com/nite/skills/src/branch/main/napcat-onebot/references/login-persistence.md) 完全对应：

| 平台 | 算法 | 存储（可选） |
|---|---|---|
| **Linux NTQQ** | `GUID = MD5(/etc/machine-id + MAC Address)` | `nt_qq/global/nt_data/msf/machine-info` |
| **macOS** | `GUID = MD5(machine_id[8] \|\| sn)` | `machineid-info`（QQ私有TEA容器） |
| **Android（推断）** | **类似哈希，输入是硬件标识** | 我们找到的 20 处只是**缓存副本** |

**重要认识**：我们找到的那 20 处存储，都是**计算结果被缓存下来**，而不是**源头**。
**改缓存无效，因为每次启动都会重新计算并覆盖。**

## 三、这解释了之前的一个困惑

**为什么改了 `FAKE_ANDROID_ID` 但 GUID 没变？**

如果 Android 端的算法是 `GUID = hash(android_id + 其它硬件标识)`，那么：
- 我们改的 `FAKE_ANDROID_ID` 是 Java 层 `Settings.Secure.ANDROID_ID` 的值
- 但 **native 层（wlogin）可能直接读系统属性或硬件节点**，不经过 Java hook

**这与 k54 的教训完全一致**：Java 层改了不等于 native 层能看到。

## 四、被证伪的三个假设

| 假设 | 级别 | 状态 |
|---|---|---|
| ~~`wlogin_device.dat` 是 GUID 源头~~ | 原以为 A 级 | ❌ **已证伪** |
| ~~MMKV 的 `android_id` 键是源头~~ | 原以为 A 级 | ❌ **已证伪** |
| ~~改了存储就能换 GUID~~ | 原以为可操作 | ❌ **已证伪** |

**这些判断写进 `CORRECTIONS.md`。**

## 五、正确的处理方式（未实施）

既然 GUID 是运行时派生的，**要换它只能改派生输入**：

**方案 A：hook `generateGuid()` 直接返回伪造值**

```java
HookUtils.hookAllMethods("oicq.wlogin_sdk.tools.util", "generateGuid",
    new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            param.setResult(HookConfig.FAKE_WLOGIN_GUID);  // byte[16]
        }
    });
```

**优点**：直接、可控、可验证
**风险**：`generateGuid` 可能被多处调用，返回值需全局一致

**方案 B：改派生输入（native 层）**

需要先逆向出 Android 端的确切算法（输入是什么、用什么哈希），工作量大。

**方案 C：改 native 层的 `__system_property_get` 等读取路径**

如果它读 `ro.serialno` 之类的属性，我们的 native hook 已覆盖 —— 但实测 GUID 没变，
说明**它读的是我们没覆盖的东西**（可能是 kernel 层的硬件 ID）。

## 六、待办

- [ ] **实施方案 A**（hook generateGuid 返回伪造值）
- [ ] 验证 hook 后 GUID 是否稳定为伪造值
- [ ] 观察改 GUID 后是否影响登录（NapCat 说「GUID 变 → 票据作废」）
- [ ] 逆向 Android 端的确切派生算法（方案 B 的前提）

## 七、方法论价值

**这是本项目第一次做「严格单变量对照实验」**：

```
改 A → 看输出变不变
改 B → 看输出变不变
改 A+B+全部 → 看输出变不变
```

**三次都否定后，才能断定「不在存储里」。**

之前我直接假设「改了存储就能换身份」，是被这次实验否定的。
**这个方法论应该用于所有后续的「改什么能影响什么」的判断。**