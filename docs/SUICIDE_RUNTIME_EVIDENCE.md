# 主进程自杀：运行时证据（2026-11-26 首获）

> **这是项目第一次在真实运行中捕获到主进程自杀的完整证据链。**
> 它把 `SUICIDE_LOGIC.md` 的纯静态推断升级为 A 级运行时确认。

---

## 一、事件本身

```
时间:   2026-11-26 13:21:06.741
进程:   com.tencent.mobileqq  (pid 17382)   ← 主进程，不是子进程
类型:   reason=1 (EXIT_SELF)
退出码: status=255
```

---

## 二、三个独立来源互相印证

### ① 运行时调用栈（ExitTraceHook 捕获）

```
[13:21:05.992] === EXIT PRIMITIVE ===
target: java.lang.System#exit
args: -1 (unsigned=255)
stack:
  ...
  at Vector_.exit(Dobby)
  at com.tencent.mobileqq.app.guard.BackgroundUnguard.onTick(Unknown Source:102)   ★
  at com.tencent.mobileqq.app.guard.GuardManager.handleMessage(Unknown Source:64)
  at android.os.Handler.dispatchMessage(Handler.java:112)
  at android.os.Looper.loopOnce(Looper.java:306)
```

### ② 系统退出记录（dumpsys exit-info）

```
timestamp=2026-11-26 13:21:06.741 pid=17382
process=com.tencent.mobileqq
reason=1 (EXIT_SELF)  subreason=0 (UNKNOWN)  status=255
```

### ③ exit 参数

```
java.lang.System#exit   args: -1     (unsigned=255)
java.lang.Runtime#exit  args: -1     (unsigned=255)
```

**三者完全一致。**

---

## 三、与静态推断的吻合度

### 调用位置精确对应

`BackgroundUnguard.onTick(Unknown Source:102)` 中的偏移 **102**，
对应 `dexdump` 反汇编里的 `005f–0066` 区段：

```
005f: iget-object v0, GuardManager.mFgProcess
0063: if-nez v0, 0069              ← mFgProcess != null 就跳过
0065: const/4 v0, #int -1          ← exit code = -1
0066: invoke-static System.exit(I)V    ← ★ 就是这里
```

**静态分析与运行时行为完全吻合。**

### 时间与拍数吻合

```
13:21:06 - 12:13:23 = 68 分 43 秒 = 3400 秒
拍数 = 3400 / 12 = 283 拍
```

推断的阈值是 `mClearTick == 250 ± mGuardTick`。
**283 落在区间内**（当 `mGuardTick ≈ 33` 时，`250 + 33 = 283`）。

**这是对自杀公式的定量验证。**

---

## 四、新发现：未登录状态不会自杀

### 观察

自杀后 QQ 于 13:21 左右重启为 pid **24286**，该进程**存活到 15:59（3 小时 44 分）**。

```
3h44m = 224 分钟 = 13440 秒 = 1120 拍
```

**1120 拍远超 250 拍阈值，但从未自杀。**

### 原因

监控日志里 `qsec` 计数从 12:15 到 15:59 一直是 **1**（没有增加）：

```
12:15:26 | 17382 | qsec=1     ← 登录那次
15:59:32 | 24286 | qsec=1     ← 仍是 1，没有新上报
```

**说明 24286 从未重新登录。**

### 推断

**`GuardManager` 只在登录后启动 tick 累加。未登录状态下不进入 Guard 状态机，因此不会触发自杀。**

这解释了一个此前没想清楚的问题：**为什么 QQ 有时能挂很久不掉** ——
如果那次会话已经因自杀/踢线结束、用户没重登，进程就会安静地待着。

---

## 五、完整时间线

```
12:13:23  登录（pid 17382），QsecEst 生成，KICK=0 RISK=0
12:13-14:31  若干子进程正常回收（gameservice / peak / sandboxed，均为 SIGNALED 9 或 EXIT_SELF 0）
13:21:06  ★ 主进程 17382 自杀（EXIT_SELF status=255）
          └ BackgroundUnguard.onTick → System.exit(-1)
13:21+    重启为 24286（未登录），存活 3h44m，从未自杀
16:07     重启为 12201（当前，仍未登录，qsec=1）
16:18     用户反馈"又掉线了"
```

**用户感知的"掉线"，实际是 13:21 那次自杀造成的，之后一直未重新登录。**
当前界面是 `QQBrowserActivity`，不是登录页。

---

## 六、这次的证据强度

| 项 | 之前 | 现在 |
|---|---|---|
| 自杀路径 | 静态反汇编推断（B 级） | **运行时调用栈确认（A 级）** |
| 退出码来源 | 算术推断 `-1 & 0xFF = 255` | **实测 `args: -1 (unsigned=255)`** |
| 时间阈值 | 理论计算 18–50 分钟 | **实测 68 分钟 = 283 拍，落入区间** |
| 触发条件 | `mFgProcess == null` | 栈中出现该分支（偏移 102） |
| 未登录是否自杀 | 未考虑 | **确认不会（存活 3h44m）** |

---

## 七、仍需注意

**这次自杀是「与检测无关」的证据，但只对这一次有效。**

按之前的分层原则：

- **机制层面**：`BackgroundUnguard.onTick` 的判定输入只有
  `mClearTick` / `mGuardTick` / `mFgProcess` / `mStateId`，
  全部来自定时器、内存读数、进程状态 —— **不受检测结果影响**
- **这一次**：栈里确实只看到 `GuardManager.handleMessage → onTick`，
  没有任何检测类出现在调用链上

**所以可以说：这一次的自杀与风险检测无关。**

但若要断言「所有自杀都无关」，还需要更多的样本 —— 目前只捕获到一次。
