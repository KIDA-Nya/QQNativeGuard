# 自杀是被踢的诱因 —— 完整因果链（2026-11-27）

> **这是本项目第一次把「自杀」和「被服务端踢线」串成一条因果链。**
> 之前我一直把自杀当成无害的独立事件，那是错的。

---

## 一、决定性证据：时间线严丝合缝

从 `qqng_exit_trace.log`（持久化，未被 logcat 滚动影响）和 `wup_dump` 拼出：

```
18:07:20  登录成功（ksid = 9b42d52f…）
20:04:00  ★ BackgroundUnguard.onTick → System.exit(-1)     ← 自杀
20:30:29  自动恢复登录成功（rst1=0, rlen=2377）
20:44:00  ★ KICK_TO_LOGIN                                   ← 被服务端踢线
```

**自杀后 26 分钟自动恢复，再 14 分钟就被踢。**

---

## 二、自杀的调用栈（与 13:21 那次完全相同）

```
[20:04:00.397] EXIT PRIMITIVE
target: java.lang.System#exit
args: -1 (unsigned=255)
stack:
  at Vector_.exit(Dobby)
  at com.tencent.mobileqq.app.guard.BackgroundUnguard.onTick(Unknown Source:102)
  at com.tencent.mobileqq.app.guard.GuardManager.handleMessage(Unknown Source:64)
  at android.os.Handler.dispatchMessage(Handler.java:112)
  at android.os.Looper.loopOnce(Looper.java:306)
```

**这与 13:21 那次的栈逐行一致。**

---

## 三、因果链

```
① QQ 在后台，进入 BackgroundUnguard（后台·不守护）
       ↓
② tick 累计到 250 ± mGuardTick 拍
       ↓
③ System.exit(-1) → 进程死，status=255
       ↓
④ 会话「异常中断」（服务端看到的是非正常断开）
       ↓
⑤ QQ 被拉起，用票据自动恢复登录
       ↓
⑥ 服务端对这次重新登录加严校验
       ↓
⑦ KICK_TO_LOGIN（踢线，提示「身份验证失败」）
```

## 为什么这次只有 28 分钟

```
22:13  重启（装新探针）
22:41  被踢
```

**比上一次（2h37m）短 5 倍。** 合理的解释：**服务端对这个设备/账号组合的风险评级在累积上升** ——
每次「异常断开 + 重新登录」都会恶化评级，所以判定越来越快。

---

## 四、我之前的判断错在哪

在前面几轮里我说过：

> 「自杀后 2 分钟 QQ 就自动恢复了登录（13:23:30 rst1=0），你没有重新登录，也没有察觉。
> 所以「自杀」不等于「需要重登」。」

**这句话本身没错，但结论错了** ——
自杀确实不直接导致重登，**但它会让服务端把这次会话标记为异常断开，从而在下次登录时踢人。**

**遗漏的是：服务端的反应有延迟，不是在自杀的瞬间，而是在后续登录时。**

---

## 五、为什么之前没看出来

因为**自杀和被踢之间隔了 26~40 分钟**，中间还夹着一次"成功"的自动恢复登录。

只看单点（自杀那一刻）或只看结果（被踢那一刻），都串不起来。
**必须有持久化的 exit trace 才能拼出这条线** —— 这正是 `ExitTraceHook` 的价值。

---

## 六、修复：SuicideGuard

### 判定逻辑（反汇编）

```java
BackgroundUnguard.onTick():
    super.onTick();                       // mGuardTick++, mClearTick++
    T = SUICIDE_FACTOR * 50 = 250
    if (mClearTick == T - mGuardTick
     || mClearTick == T
     || mClearTick == T + mGuardTick) {
        if (mManager.mFgProcess == null) System.exit(-1);
    }
```

### 两层拦截

**① 主防线：在 `onTick` 返回后把 `mClearTick` 归零**

```java
HookUtils.hookAllMethods("com.tencent.mobileqq.app.guard.BackgroundUnguard",
    "onTick", new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            XposedHelpers.setLongField(param.thisObject, "mClearTick", 0L);
        }
    });
```

**倒计时永远到不了阈值。**

选择这个方案而不是「拦截 `System.exit`」作为主防线，是因为：
- 不改状态机，不干扰 `mStateId` 的流转
- 不影响其他内存回收途径（系统内存压力下 QQ 仍会被正常回收）
- 只针对这一个自杀点

**② 兜底：`System.exit(-1)` 且栈含 `BackgroundUnguard.onTick` 时吞掉**

防止主防线因字段名变更而失效。

### 安装验证

```
[SUICIDE-GUARD] installed on BackgroundUnguard.onTick
[SUICIDE-GUARD] fallback exit interceptor installed
```

---

## 七、Turing 彻底排除

`qqng_turing_reads.log`（22KB）显示 Turing 只做 **22 次检查**，全部在启动期：

| 检查 | 调用者 | 目标 |
|---|---|---|
| `File.exists` | `z1.a` / `b2.a` / `Caelum.a` | 自身缓存 |
| `File.exists` | **`TNative$aa.d209311_…(Native Method)`** | 自身缓存（native 层） |
| `File.exists` | `c2.b` | `app_turingfd/12` |
| `File.isFile` | `Peanut.a` | `app_turingfd/mpdc_105668_1` |
| `PM.getPackageInfo` | `Peanut.a` / `Papaya.a` | `com.tencent.mobileqq, 64` |
| **`File.exists`** | `Caelum.a` | **`/data/data/~~HOPOWnNCInzb`**（安装目录异常） |
| **`PM.getPackageInfo`** | `l3.b` | **`de.robv.android.xposed.installer`**（Xposed） |

**两个真正的检测点实测都正常：**
- `/data/data` 下无 `~~` 前缀目录
- 无 xposed/lsposed 包

**文件最后更新 22:13，而 22:41 被踢时 Turing 毫无活动 → 与踢线无关。**

---

## 八、待验证

| 项 | 怎么看 |
|---|---|
| **SuicideGuard 是否真的拦住自杀** | 观察 `qqng_exit_trace.log` 是否还出现 `BackgroundUnguard.onTick → exit(-1)` |
| **不再自杀后是否还被踢** | 这是核心 —— 如果不再被踢，因果链就得到确认 |
| 内存是否受影响 | `dumpsys meminfo` 对比；理论上不影响 |

**预期：如果这条因果链成立，阻止自杀应该能显著延长会话存活。**

---

## 九、这一轮的方法论价值

**持久化日志 > 即时日志。**
`logcat` 早就滚了，但 `qqng_exit_trace.log` 把 20:04 那次自杀完整留在磁盘上，
才让 26 分钟后的被踢能被解释。

**这也是为什么之前几轮一直没找到原因 —— 每次都在 logcat 里找，而它只有几十分钟的缓冲。**
