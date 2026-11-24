# 风险检测上报面完整排查（2026-11-24）

> 穷尽扫描 40 个 dex，比对当前 60+ hook 点，找出未覆盖的风险检测

## 一、扫描方法

1. **dex 全字符串池扫描**：896 个候选类 → 收紧到 106 个精确命中 → 筛出 8 个真安全检测类
2. **实测验证**：开 VERBOSE_LOGGING，看 hook 挂载日志的实际成功/失败
3. **失败项反查**：对 ClassNotFound / method-not-found 的项，回 dex 查真实类名/方法名

## 二、最终覆盖情况

### ✅ 已成功覆盖（实测日志确认）

| 类 | 方法 | 说明 |
|---|---|---|
| `com.tencent.qmethod.pandoraex.monitor.DeviceInfoMonitor` | **32 个 getter** | ★ 设备信息伪造核心通道 |
| `com.tencent.gathererga.core.internal.provider.impl.UserInfoImpl` | `isRooted` | gatherer SDK 实现 |
| `com.tencent.gathererga.core.UserInfoProvider` | `isRooted` | gatherer SDK 接口 |
| `com.tencent.camerasdk.avreport.DeviceInfo` | `isDeviceRooted` | 相机 SDK |
| `com.tencent.tenpay.charge.v2.util.ChargeV2Utils` | `isDeviceRooted` | 微信支付 SDK |
| `org.light.device.LightDeviceUtils` | `isRooted` | 腾讯光影 SDK |
| `com.tencent.bugly.proguard.cp` | `a` | Bugly |
| `oicq.wlogin_sdk.request.w` | `h` | 登录 SDK |
| `com.tencent.turingfd.sdk.ams.au.TuringFDCoreInfo` | `getIsDebug` | Turing |
| `java.io.File` | `exists/canRead/canExecute/canWrite` | 文件检测 |
| `java.lang.Runtime` | `exec` | 命令检测 |
| `android.app.ApplicationPackageManager` | 4 个查询方法 | 包名扫描 |
| `com.tencent.qqnt...GProGuildSafetyRisk` | `getRiskLevel` | ★ **服务端裁决观测点** |

### 二、本次修复的 5 个真实缺口

| # | 缺口 | 根因 | 修复 |
|---|---|---|---|
| 1 | `camerasdk.DeviceInfo.isRooted` 一直失败 | **真实方法名是 `isDeviceRooted`** | 改用正确方法名 |
| 2 | `gathererga.UserInfoImpl` ClassNotFound | **完整包名缺 `internal.provider.impl`** | 补全路径 |
| 3 | `gathererga.UserInfoProvider` 未 hook | 类加载时机晚于 handleLoadPackage | **懒加载重试** |
| 4 | `qqperf.monitor.crash.c.isXposedInstalled` | **该类只有 `<init>/a/b/c`，无此方法** | 移除错误 hook |
| 5 | `GProGuildSafetyRisk.getRiskLevel` 未观测 | 之前完全未发现 | **新增观测点** |

### 三、关键发现：GProGuildSafetyRisk 是罕见的"服务端裁决可观测点"

```java
com.tencent.qqnt.kernelgpro.nativeinterface.GProGuildSafetyRisk
  → getRiskLevel()
```

**这是极少数能直接读到"服务端下发的风险等级"的接口。** 不篡改，只记录：

```java
XposedEntry.log("[RISK-OBSERVE] GProGuildSafetyRisk.getRiskLevel -> " + r);
```

**用途**：当 QQ 弹出风险提示时，可以从日志看到服务端到底判了几级，验证我们的猜测。

### 四、懒加载机制（新增）

```java
private static void startLazyHookRetry() {
    // 后台线程，每 2 秒重试一次，最多 60 轮
    // 覆盖 4 个延迟加载的 SDK 类
    // 用 lazyHooked Set 去重，避免重复挂载
}
```

**为什么需要**：`handleLoadPackage` 执行时，部分 SDK 类（gatherer/camerasdk）还没被加载，
直接 hook 会 `ClassNotFoundException`。

**实测效果**：4 个类全部在 `round 0`（第一轮）挂上。

## 五、仍未覆盖的项（评估）

| 项 | 风险 | 说明 |
|---|---|---|
| `/proc/<other_pid>/cmdline` 进程列表 | ★★ | 已插入 `is_root_process_name()` 但**未接入 open/read** |
| `/proc/<other_pid>/maps` | ★ | 只过滤了 self |
| `kotlin.io.FilePathComponents.isRooted` | 无 | Kotlin 标准库的路径组件方法，非安全检测 |
| 原生层检测（libfekit/libturingxq） | ★★★ | 已由 native hook 层处理 |

## 六、结论

**Java 层的风险检测已基本全覆盖（13 个类、60+ 方法）。**

**本轮修复的价值**：
- 之前 `camerasdk` 的 hook 一直是无效的（方法名错）
- 之前 `gathererga` 的两个检测一直没挂上（类名错 + 时机问题）
- 新增了服务端裁决的观测能力

**剩余最大缺口是 native 层的进程列表过滤**，需要接入 `open`/`read` 的 hook 才能真正生效。

## 七、验证命令

```bash
# 看 hook 挂载情况
adb logcat -s QQEnhancedBypass:V | grep -E "Hooked|Failed|LAZY-HOOK"

# 看服务端裁决等级（风险提示出现时）
adb logcat -s QQEnhancedBypass:V | grep "RISK-OBSERVE"
```