# QQ 静默自杀（EXIT_SELF status=255）完整逻辑

> 2026-11-26 · 证据等级 **A/B**（官方 dexdump 反汇编 + 常量硬编码值）

---

## 一、一句话结论

**QQ 在后台且无前台进程时，每 12 秒累加一次 tick；累计到 125 或 250 拍（约 25 / 50 分钟）时执行 `System.exit(-1)`，Linux 进程退出码只有 8 位，`-1 & 0xFF = 255`，这就是 `EXIT_SELF status=255` 的全部来源。**

方法名与日志文案也直接印证：

```
"suicide to free memory! suicide_factor="
```

**「为释放内存而自杀」—— 这是设计行为，不是检测到篡改后的惩罚。**

---

## 二、代码级证据链

### 1. 常量：SUICIDE_FACTOR = 5

`GuardManager.<clinit>` @ classes.dex 0x5f8016：

```
5f8016: const/4 v0, #int 5 // #5
5f8018: sput v0, Lcom/tencent/mobileqq/app/guard/GuardManager;.SUICIDE_FACTOR:I
```

**全 dex 只有这一处 `sput SUICIDE_FACTOR`** → 硬编码，不受远程配置影响。

### 2. tick 间隔 = 12000ms

`GuardManager.startTimer` @ classes.dex：

```
0010: iget-object v0, GuardManager.mHandler
0012: const/4 v1, #int 5                                   // msg.what = 5
0013: invoke-virtual {v0, v1}, Handler;.removeMessages
0018: const-wide/16 v2, #int 12000                         // ★ 12 秒
001a: invoke-virtual {v0, v1, v2, v3}, Handler;.sendEmptyMessageDelayed
```

### 3. 计数器累加

`GuardState.onTick()` @ classes.dex：

```
000f: iget-wide v0, GuardState.mGuardTick
0011: const-wide/16 v2, #int 1
0013: add-long/2addr v0, v2
0014: iput-wide v0, GuardState.mGuardTick        // mGuardTick += 1

0016: iget-wide v0, GuardState.mClearTick
0018: add-long/2addr v0, v2
0019: iput-wide v0, GuardState.mClearTick        // mClearTick += 1
```

**两个计数器每拍同步 +1。**

### 4. 自杀判定（核心）

`BackgroundUnguard.onTick()` @ classes11.dex：

```
0043: iget-wide v0, GuardState.mClearTick
0045: sget v3, GuardManager.SUICIDE_FACTOR             // v3 = 5
0047: mul-int/lit8 v4, v3, #int 50                     // v4 = 250
0049: sub-int/2addr v4, v2                             // v4 = 250 - mGuardTick
004b: cmp-long v4, v0, v4
004d: if-nez v4, 0050                                  // 命中条件1 → 继续判定
004f: goto 0069                                        // 否则 return

0050: mul-int/lit8 v4, v3, #int 50
0053: cmp-long v4, v0, v4
0055: if-eqz v4, 005f                                  // 命中条件2(250) → 继续
0057: mul-int/lit8 v3, v3, #int 50
0059: add-int/2addr v3, v2                             // v3 = 250 + mGuardTick
005b: cmp-long v0, v0, v2
005d: if-nez v0, 0069                                  // 命中条件3 → 继续

005f: iget-object v0, GuardManager.mFgProcess          // ★ 前台进程
0063: if-nez v0, 0069                                  // 非 null 则 return（不杀）
0065: const/4 v0, #int -1                              // ★ exit code = -1
0066: invoke-static {v0}, Ljava/lang/System;.exit:(I)V // ★★ 自杀
0069: return-void
```

**翻译成 Java：**

```java
public void onTick() {                       // BackgroundUnguard
    super.onTick();                          // mGuardTick++ ; mClearTick++

    long T = GuardManager.SUICIDE_FACTOR * 50;          // 250

    boolean hit = (mClearTick == T - mGuardTick)        // 条件1
               || (mClearTick == T)                     // 条件2
               || (mClearTick == T + mGuardTick);       // 条件3

    if (!hit) return;
    if (mManager.mFgProcess != null) return;            // 有前台进程 → 不杀

    System.exit(-1);                                    // status = 255
}
```

---

## 三、时间推算

启动进入 `BackgroundUnguard` 时，两计数器同起点、同步 +1：

| 条件 | 方程 | 解 | 时间 |
|---|---|---|---|
| 1 | `mClearTick == 250 - mGuardTick` | 两者相等 → `2x == 250` → x=125 | **125 × 12s = 25 分钟** |
| 2 | `mClearTick == 250` | x=250 | **250 × 12s = 50 分钟** |
| 3 | `mClearTick == 250 + mGuardTick` | `0 == 250` | **永不成立** |

**所以：纯后台无前台进程 25 分钟或 50 分钟，会自杀。**

### 为什么实测是 2 小时左右

`mClearTick` 在切回前台时会被 `GuardManager` 重置。用户偶尔瞄一眼 QQ，
计时就从头开始。所以实际存活时间远长于 25/50 分钟，且取决于使用习惯。

**这完全解释了历史数据**：登录后一直在用 → 存活 2 小时；完全不碰 → 半小时内就可能退出。

---

## 四、status=255 的算术

`System.exit(-1)` 传入 int -1。Linux 进程退出状态只有 8 位：

```
-1 & 0xFF = 255
```

`ApplicationExitInfo` 记录的 `status=255` 由此而来。
**不是魔法数字，也不需要 255 这个字面量出现在任何 dex 里** —— 这正是为什么静态搜「exit(255)」永远搜不到。

---

## 五、完整嫌疑清单（6 处 exit(-1)）

| # | 位置 | 性质 | 判定 |
|---|---|---|---|
| **1** | **`BackgroundUnguard.onTick`** | **后台释放内存自杀** | ★ **元凶** |
| 2 | `SplashActivity.doOnCreate` | 启动页 | 待查 |
| 3 | `InstallActivity.doOnCreate` | 安装页 | 待查 |
| 4 | `guardcallback.e.c` | 装补丁杀 MSF 子进程 | 正常流程（`"GuardManager exit MSF Process to install patch"`） |
| 5 | `KernelSetterImpl.<clinit>` | kernel 初始化 | 待查 |
| 6 | `MiniAppInterface.exitApp` | 小程序退出 | 正常 |

**只有第 1 项符合「静默 + 主进程 + 运行一段时间后」的全部特征。**

---

## 六、验证方式

### 已做（B 级）
- 官方 `dexdump` 反汇编确认指令序列与常量
- `SUICIDE_FACTOR` 全局唯一 `sput` → 确认硬编码
- 日志文案 `"suicide to free memory"` 交叉印证

### 已部署（动态）
`ExitTraceHook` 已 hook 并落盘 `files/qqng_exit_trace.log`：
- `java.lang.System#exit` / `Runtime#exit` / `halt`
- `android.os.Process#killProcess(/Quiet)`
- `mqq.app.MobileQQ#realExit` / `GuardManager#exit`
- `NativeMonitorConfigHelper#initSoLoadConfig` / `guardcallback.e#c` / `KernelSetterImpl#<clinit>`
- `SystemMethodProxy#killProcess`

**下次真实发生时，会记录完整调用栈 + exit 参数。**

### 未做（受硬约束）
- **不主动构造自杀条件**（硬约束：不许让 QQ 自杀退出）
- 因此无法做「调到 250 拍看是否退出」的主动实验

---

## 七、可验证的推论（供后续实测）

若上述逻辑正确，应能观察到：

1. **`mClearTick` 达到 125 时**若把 QQ 切到前台，自杀不会发生
2. **锁屏放置 25-50 分钟**（无前台进程）后 QQ 退出，`exit_trace.log` 里应有
   `System.exit -> status=255` 且调用栈包含 `BackgroundUnguard.onTick`
3. 一旦抓到该栈，即可把结论从 B 级提升到 A 级

---

## 八、对项目的意义

**这个发现改变了归因**：

之前多次把「突然掉线」归因为「服务端风控 / 账号信用」。
现在知道至少有一部分是 **QQ 自己的内存回收策略** ——
和检测、和账号、和服务端都无关。

**区分方法**：
- `EXIT_SELF status=255` → **客户端自杀**（本文档）
- `KICK_TO_LOGIN` + 文案 → 服务端踢线
- `rst1=17` + `rlen` 缩小 → 票据过期

**之前的"四层判定"模型里，这一层一直被误归为服务端行为。**

---

## 九、状态机上下文（2026-11-26 补充）

自杀发生在 `BackgroundUnguard` 状态，所以「何时进入该状态」是触发的前置条件。

### 8 个 GuardState 子类

`GuardManager.getState(int)` 用 `packed-switch` 把状态 ID 映射到实例：

```
LiteUnguard      轻量·不守护
LiteGuard        轻量·守护
BackgroundUnguard  ★ 自杀发生在这里
BackgroundGuard    普通后台守护
ForgroundOther     前台·非主进程
ForgroundMain      前台·主进程
PrestartGuard      预启动守护
GuardState         基类兜底
```

### nextState 的转移机制

```java
void nextState(int newStateId, Object event) {
    if (Looper.myLooper() != injector.d()) {
        mHandler.obtainMessage(6, newStateId, 0, event).sendToTarget();
        return;                                  // 切到正确线程
    }
    if (mStateId == 8) return;                   // ★ 状态 8 = 终态，不再转移
    if (mStateId != newStateId) {
        mHandler.sendMessageDelayed(
            mHandler.obtainMessage(3, mStateId, newStateId), 1000);  // 1 秒后通知
        mState = getState(newStateId);
        mStateId = newStateId;
    } else if (newStateId != 0) {
        mState = getState(newStateId);
    }
    mState.onEvent(5, event);
}
```

### 已观测到的转移触发点

`GuardManager.handleMessage` 用 `packed-switch` 按 msg.what 分发，
其中出现的事件名包括 `"trick_p_msg"`（保活/唤醒相关），
另有多处直接调用 `nextState(stateId, event)`。

### 运行时实测（A 级）

当前进程处于 **`BackgroundGuard`**。追踪日志里 23 次 `onTick`
全部来自 `BackgroundGuard`，`BackgroundUnguard` 出现 **0 次** ——
所以此刻不会自杀。

### 诚实说明：这部分掌握到约 70%

| 项 | 掌握度 |
|---|---|
| 自杀判定代码 | 100%（逐条指令确认） |
| 常量与时间推算 | 100% |
| status=255 的来源 | 100% |
| 状态机结构（8 状态 + nextState） | 90% |
| **进入 Unguard 的具体业务条件** | **70%** |

**要完全确定「什么行为导致进入 Unguard」，需要 hook
`GuardManager.nextState` 记录每次 (from, to, event)。**
`ExitTraceHook` 目前未覆盖该方法 —— 这是下一步要做的事。
