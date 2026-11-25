# QIMEI 通道验证（2026-11-25）

> 之前文档声称「MSF 8080 通道 QIMEI 已对齐 ✅」但**没有给出证据**。
> 本次补上 A 级证据，并加入 `libqimei.so` 的逆向结论。

---

## 一、A 级证据：伪造值已落盘且被多 SDK 采用

### 存储键（关键发现）

```
/data/user/0/com.tencent.mobileqq/files/mmkv/common_mmkv_configurations
  DeviceToken-qimei36-V001 = <fake-qimei-32hex>
```

**这就是 QIMEI 的持久化键名。** 值是我们配置的 `FAKE_QIMEI36`。

### 被哪些组件采用（同一文件内）

| 组件 | 位置 | 值 |
|---|---|---|
| **QIMEI SDK** | `DeviceToken-qimei36-V001` | ✓ 完整伪造值 |
| **BUGLY** | `BUGLY_COMMON_VALUES_@Stri_deviceId` | ✓ 完整伪造值 |
| **RMonitor** | `RMonitor_SP_@Stri_last_save_unique_id` | ✓ 伪造值前 16 位 |
| **qddownloader** | `sp_key_qddownloader_user_id` | ✓ 完整伪造值 |

### 外部文件佐证

```
shared_prefs/BUGLY_COMMON_VALUES.xml
  <string name="deviceId"><fake-qimei-32hex></string>

shared_prefs/sp_name_qddownloader_invoke_data.xml
  <string name="sp_key_qddownloader_user_id"><fake-qimei-32hex></string>
```

### 组装成上报 JSON 的证据

```
{"...":"4.4.7.12","uin":"3402312720","deviceid":"<fake-qimei-32hex>",
 "os":"15","manu":"OPPO","device":"PGT110"...}
```

**这是 Bugly 的上报载荷**：`deviceid` 用 QIMEI36，与 `os`/`manu`/`device` 一起组装。
**说明伪造值已进入实际上报链路，不只是本地缓存。**

---

## 二、但 QIMEI 不在 QsecEst 里（重要边界）

解密最新 QsecEst（85 字段）后逐项检查：

| 检查 | 结果 |
|---|---|
| 32-hex 值（QIMEI36 形状） | **0 个** |
| 16-hex 值（QIMEI16 形状） | **0 个** |
| 配置的 FAKE 值 | **未出现** |

**QIMEI 不在 QsecEst 通道。** 它走的是：
- Bugly（HTTP，deviceId 字段）
- MSF（加密长连接）
- 各 SDK 自己的上报

**这是正确的认知：5 通道里，QIMEI 属于「MSF/Bugly」这条，不属于 QsecEst。**

---

## 三、`libqimei.so` 逆向结论（B 级）

### 基本信息

```
大小: 498,616 字节 (486.9 KB)
.text:   VA=0x92e0  size=0x5efac
.rodata: VA=0x68290 size=0x863d
```

### 导出符号只有 1 个

```
JNI_OnLoad @ 0x9cd8
```

**没有 `Java_com_*` 函数** → 全部通过 `RegisterNatives` 动态注册。
（这就是为什么静态搜不到 QIMEI 计算函数）

### 导入符号（关键：不走 syscall 绕过）

```
✓ __system_property_get      ← 我们已 hook
✓ __system_property_find     ← 我们已 hook
✓ open / read                ← 我们已 hook
✓ fopen / fgets / fread      ← 我们已 hook
✓ stat / lstat / access
✓ dlopen / dlsym

✗ syscall                    ← ★ 没有！不像 Turing 用 .real_syscall 绕过
```

**结论：libqimei.so 全程走 libc 标准接口，我们的 native hook 能覆盖它。**

### 可疑字符串

```
plzdontcrackme856c91q16
```

**这是密钥/盐**（"please don't crack me" + 十六进制 + q16）。
表明 QIMEI 计算可能带混淆或签名，但**不影响我们 hook 输入值**。

### 关键空白

- 无 `.real_syscall` / `.real_` 前缀的绕过符号
- 无 `hook`/`frida`/`xposed` 等检测字符串

**所以 libqimei.so 是纯计算库，不做反 hook 检测。**

---

## 四、Java 层 hook 已确认挂载

重启后即时日志（A 级）：

```
Qimei identity forced on com.tencent.qimei.sdk.Qimei
Qimei identity forced on com.tencent.qimei.sdk.QimeiSDK
Hooked all methods: oicq.wlogin_sdk.tools.util.getQimei16
Hooked all methods: oicq.wlogin_sdk.tools.util.getQimei36
```

**四处 hook 全部生效**：
1. `com.tencent.qimei.sdk.Qimei.getQimei36/16`
2. `com.tencent.qimei.sdk.QimeiSDK.getQimei36/16`
3. `oicq.wlogin_sdk.tools.util.getQimei16`
4. `oicq.wlogin_sdk.tools.util.getQimei36`

**之前误判「hook 未挂载」** —— 实际是 logcat 缓冲滚动（第 6 次踩这个坑）。

---

## 五、方法论教训（第 6 次同类错误）

**「logcat 没日志」不等于「代码没执行」。**

本次和之前几次：

| # | 误判 | 真相 |
|---|---|---|
| 1 | Native Bypass Library Loaded = 0 | 日志滚动 |
| 2 | Qimei identity forced 无日志 | 日志滚动 |
| 3 | 心跳停止=自杀前兆 | 与前后台相关 |
| 4 | 网络切换导致掉线 | 那是 OPPO 进程日志 |
| 5 | ProcessBuilder intercepted | 只是记录不拦截 |
| 6 | w21 是登录时拒绝 | 登录成功后异步 push |

**正确做法**：验证「是否执行」要看**持久化痕迹**（落盘文件、配置、内存状态），
不是看 logcat 即时输出。

**本次正是因为转向「搜落盘文件」才拿到 QIMEI 生效的铁证。**

---

## 六、通道状态更新

| # | 通道 | 载体 | 验证方式 | 状态 |
|---|---|---|---|---|
| ① | QsecEst | AES 加密 85 字段 | 解密 | ✅ k54=kalama |
| ② | HTTP UA | 明文 | 抓包 | ✅ 已对齐 |
| ③ | MSF 8080 | 加密 | — | ⚠️ 不可直接验证（但 QIMEI 落盘证明输入已伪造） |
| ④ | VOD_MSDK | 明文 androidDesc | 抓包 | ✅ 已 hook |
| ⑤ | wlogin GUID | 16 字节 | hook 返回值 | ✅ 已伪造 |
| **⑥** | **Bugly HTTP** | **明文 JSON** | **落盘 + 明文** | ✅ **deviceid=伪造值（本次新增证据）** |

**所以实际是 6 个通道，第 6 个（Bugly）之前没单独列出。**

---

## 七、待办

| # | 任务 | 可验证性 |
|---|---|---|
| 1 | 抓包确认 Bugly 上报里的 deviceid | A |
| 2 | 检查 `app_turingfd/12/105668_xq_2`（1904B，活跃更新） | A/B |
| 3 | 确认 MSF 加密通道里的 QIMEI（需解密，可能不可行） | — |