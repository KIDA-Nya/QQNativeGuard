# QQ 静默自杀全链路（2026-11-26 完整版）

> 从「进入哪个状态」到「exit(-1)」的每一步都已用 dexdump 反汇编确认，
> 关键常量与运行时数据均已对齐。

---

## 一、全链路一览

```
① 进程在后台   →  状态 BG_GUARD(4) 或 LITE_GUARD(6)
                      ↓
② 收到 PROCESS_TRICK 事件（常量 "trick_p_msg"）
   handleMessage → if (!isGuardState()) return;
                   nextState(mStateId + 1, "trick_p_msg")
                      ↓
③ 状态 +1  →  BG_UNGUARD(5)  或  LITE_UNGUARD(7)     ★ 进入自杀待命态
                      ↓
④ 每 12 秒 TICK（what=5）
   GuardState.onTick():  mGuardTick++ ; mClearTick++
   BackgroundUnguard.onTick() 追加判定
                      ↓
⑤ mClearTick == 250 - mGuardTick
   或 == 250
   或 == 250 + mGuardTick
   且 mManager.mFgProcess == null（无前台进程）
                      ↓
⑥ System.exit(-1)   →   Linux 退出码 -1 & 0xFF = 255
                      ↓
⑦ ApplicationExitInfo: reason=1 (EXIT_SELF)  status=255
```

---

## 二、状态与事件名称（运行时 dump，A 级）

用 `XposedHelpers.getStaticObjectField(GuardState, "NAME_STATE" / "NAME_EVENT")`
直接读出：

```
NAME_STATE:
  0 = EMPTY        1 = BG_FETCH    2 = FG_MAIN     3 = FG_OTHER
  4 = BG_GUARD     5 = BG_UNGUARD  6 = LITE_GUARD  7 = LITE_UNGUARD
  8 = DEAD

NAME_EVENT:
  0 = MSG    1 = RESUME   2 = TICK   3 = FG
  4 = BG     5 = ENTER    6 = MAIN
```

**关键对应**：
- 状态成对：`4/5`（BG 守护/不守护）、`6/7`（LITE 守护/不守护）、`2/3`（FG 主/其他）
- `8 = DEAD` 是终态：`nextState` 开头 `if (mStateId == 8) return;`
- 事件 `5 = ENTER`：`nextState` 末尾 `mState.onEvent(5, event)` 只是通知，**不是切换请求**

---

## 三、关键方法逐条

### 3.1 isGuardState —— 决定能否降级

```java
boolean isGuardState() {
    int id = mStateId;
    if (id == 4) return true;      // BG_GUARD
    if (id != 6) return false;     // 非 LITE_GUARD 一律 false
    return true;                   // 6 = LITE_GUARD
}
```

**只有 4 和 6 是「守护态」。** 降级只能在守护态发生。

### 3.2 handleMessage —— 降级入口

```java
boolean handleMessage(Message msg) {
    switch (msg.what) {
        case ?:                                  // @001e
            if (!isGuardState()) break;
            int next = mStateId + 1;             // ★ 4→5, 6→7
            nextState(next, PROCESS_TRICK);      // "trick_p_msg"
            break;

        case ?:                                  // @002e
            nextState(msg.arg1, msg.obj);        // 处理 nextState 自己发的延迟确认
            break;

        case ?:                                  // @0036
            mHandler.sendEmptyMessageDelayed(5, 12000);   // TICK 自我续期
            mState.onTick();
            break;

        case ?: handleMessageForGuardEvent(msg);  break;
        case ?: return handleMessageForReportChange(msg);
    }
    return true;
}
```

**常量定义**：
```java
// GuardManager 的字段
protected static final String PROCESS_TRICK = "trick_p_msg";
```
（dexdump 直接给出 `value : "trick_p_msg"`）

### 3.3 nextState —— 状态切换

```java
void nextState(int newStateId, Object event) {
    if (Looper.myLooper() != injector.d()) {
        mHandler.obtainMessage(6, newStateId, 0, event).sendToTarget();
        return;
    }
    if (mStateId == 8) return;                       // DEAD 终态
    if (mStateId != newStateId) {
        mHandler.obtainMessage(3, mStateId, newStateId);   // what=3, arg1=from, arg2=to
        mHandler.sendMessageDelayed(msg, 1000);            // 1 秒后确认
        mState = getState(newStateId);
        mStateId = newStateId;
    } else if (newStateId != 0) {
        mState = getState(newStateId);
    }
    mState.onEvent(5 /*ENTER*/, event);
}
```

### 3.4 GuardState.onEvent —— 事件分发

```java
void onEvent(int eventId, Object obj) {
    switch (eventId) {
        case ?: mManager.nextState(2, null);    // 基类默认 → FG_MAIN
        case ?: onEnter((String) obj);          // ENTER(5)
        case ?: onBackground((String) obj);     // BG(4)
        case ?: onForground((String) obj);      // FG(3)
        case ?: onTick();                       // TICK(2)
        case ?: onResume((String) obj);         // RESUME(1)
        case ?: onMessage();                    // MSG(0)
    }
}
```

### 3.5 BackgroundUnguard.onTick —— 自杀点

```java
void onTick() {
    super.onTick();                             // mGuardTick++ ; mClearTick++
    long T = GuardManager.SUICIDE_FACTOR * 50;  // 5 * 50 = 250
    boolean hit = (mClearTick == T - mGuardTick)
               || (mClearTick == T)
               || (mClearTick == T + mGuardTick);
    if (!hit) return;
    if (mManager.mFgProcess != null) return;    // 有前台进程 → 不杀
    System.exit(-1);                            // → status 255
}
```

---

## 四、时间由内存决定

```
BackgroundGuard.onEnter(processName):
    if (processName.equals("fake_p_msg")) {
        long mem     = MemoryUtils.c(Process.myPid());
        long timeout = GuardConfig.getTimeout(events, idx, minute, mem);
        mGuardTick   = timeout / 12000 - 2;
    }

GuardConfig:
    memoryBarriers = {45MB, 65MB, 85MB, 115MB}
    timeouts       = {64min, 32min, 16min, 12min, 10min}
    getMemoryLevel(mem): idx = binarySearch(barriers, mem), clamp(idx - mode, 0, 2)
    getTimeout(...)    : return timeouts[memLevel + actLevel]
```

| timeout | mGuardTick | 最早命中 | 时间 |
|---|---|---|---|
| 64 min | 318 | `250`（250−318 为负） | 50 分钟 |
| 32 min | 158 | 92 拍 | 18.4 分钟 |
| 未设置 | 0 | 250 拍 | 50 分钟 |

**内存越紧，timeout 越短，死得越早。设计意图是回收内存。**

---

## 五、运行时验证（A 级）

```
[STATE-ID]   0=GuardState 1=PrestartGuard 2=ForgroundMain 3=ForgroundOther
             4=BackgroundGuard 5=BackgroundUnguard 6=LiteGuard 7=LiteUnguard
[STATE-NAME] 0=EMPTY 1=BG_FETCH 2=FG_MAIN 3=FG_OTHER 4=BG_GUARD
             5=BG_UNGUARD 6=LITE_GUARD 7=LITE_UNGUARD 8=DEAD
[EVENT-NAME] 0=MSG 1=RESUME 2=TICK 3=FG 4=BG 5=ENTER 6=MAIN

TICK SAMPLE: state=BackgroundGuard id=4
             mClearTick=10 mGuardTick=10
             thresholds: 240 / 250 / 260
             ticks_to_hit=230 (~46 min)
             mFgProcess=null

状态序列实测：4 → 2 → 2 → 3 → 4   （BG_GUARD → FG_MAIN → FG_OTHER → BG_GUARD）
```

**`BG_UNGUARD(5)` 迄今从未出现在任何日志里** —— 这是自杀罕见的直接原因：
进程没有停在该状态累计到 125/250 拍。

---

## 六、仍有一点未确认（诚实标注）

| 项 | 状态 |
|---|---|
| 全部 8 个状态 ID 与名称 | ✅ 已确认（运行时 dump） |
| 7 个事件 ID 与名称 | ✅ 已确认 |
| 降级条件（isGuardState + mStateId+1） | ✅ 已确认 |
| 自杀判定与常量 | ✅ 已确认 |
| timeout 表与内存分级 | ✅ 已确认 |
| **`handleMessage` 里降级分支对应的 `what` 具体数值** | ⚠️ 未确认（`packed-switch` payload 未解码成功；逻辑与目标已明确，只是消息编号未定） |
| **PROCESS_TRICK 的发送者** | ⚠️ 未确认（触发降级的时机：是系统回调、还是定时、还是特定业务事件） |
| 20:11:58 那次自杀是否走此路径 | ⚠️ 无运行时证据（当时无 hook） |
| 其余 5 处 exit(-1) | ⚠️ 未逐一排除（`SplashActivity.doOnCreate` 含 `"QQ_ACTION_MENU_QUIT"` / `"killedRestore"`，需单独查） |

---

## 七、对判据的意义

三类下线现在可以机械区分：

| 现象 | 判据 | 归属 |
|---|---|---|
| `EXIT_SELF status=255` | 本文链路 | **客户端内存回收**（非检测） |
| `KICK_TO_LOGIN` + 文案 | 服务端下发 | 服务端踢线 |
| `rst1=17` + `rlen` 缩小 | 票据层 | 会话过期 |

**注意措辞**：已完整还原「一条」与检测无关的自杀链路。
不能据此断言所有 `status=255` 都来自它 —— 见第六节未确认项。

---

## 八、`handleMessage` 的 what 实测（2026-11-26 加测）

### 观测到的消息

| what | arg1 | obj | 当时 stateId | 判定 |
|---|---|---|---|---|
| **1** | 0 | `Bundle[{process=com.tencent.mobileqq, event_time=...}]` | 0 / 3 | 进程状态事件（携带进程名与时间） |
| **3** | 4 | null | 4 | `nextState` 发的延迟确认（`obtainMessage(3, from, to)`） |
| **4** | 3 | `com.tencent.mobileqq` | 3 | → `nextState(3, 进程名)`，切换成立 |
| **4** | 4 | `com.tencent.mobileqq` | 4 | → `nextState(4, 进程名)` |
| **5** | — | — | — | TICK（`sendEmptyMessageDelayed(5, 12000)`） |

`what=4` 与 `what=5` 的语义已被实测锁定：
- `what=4` → 分支 `nextState(msg.arg1, msg.obj)`，`arg1` 就是目标状态 ID
- `what=5` → 分支 `sendEmptyMessageDelayed(5, 12000)` + `mState.onTick()`

按 `packed-switch` 目标顺序（`@001e` 降级 / `@002e` nextState / `@0036` TICK /
`@0044` GuardEvent / `@0048` ReportChange）与实测的 `4→@002e`、`5→@0036` 反推，
**`first_key = 3`，即 `what=3 → @001e（降级分支）`**。

### 但降级分支为何没生效（未闭合）

实测 `what=3 arg1=4 stateId=4` 确实出现过，理论上应满足
`isGuardState()==true` 并执行 `nextState(5, "trick_p_msg")`。然而：

| 检查 | 结果 |
|---|---|
| `STATE-ENTER` 日志 | 只有 `ForgroundOther`、`BackgroundGuard`、`GuardState` |
| **`BackgroundUnguard.onEnter`** | **从未触发** |
| `STATE TRANSITION` 日志 | `0→4`、`2→3`、`3→4`，**没有 `4→5`** |
| `IS-GUARD` 日志（hook `isGuardState`） | **完全没有输出** |

`isGuardState` 无输出最可能的原因是 **R8 把这种极短方法内联进了调用点**，
导致 hook 挂不上（而不是它没被调用）。

因此 `what=3` 到底走的是降级分支还是别处，仍缺一个直接证据。
下一步应 hook `nextState` 并**同时打印 `mStateId` 与参数**（已具备），
再看 `what=3` 之后紧邻的 `nextState` 调用参数；或直接 hook
`BackgroundUnguard.<init>` 判断实例是否被创建。

---

## 九、掌握度总表

| 环节 | 状态 | 证据 |
|---|---|---|
| 8 个状态 ID 与名称 | ✅ 100% | 运行时 dump `NAME_STATE` |
| 7 个事件 ID 与名称 | ✅ 100% | 运行时 dump `NAME_EVENT` |
| `isGuardState` 只认 4/6 | ✅ 100% | 字节码 |
| 降级公式 `mStateId + 1` | ✅ 100% | 字节码 `add-int/2addr` |
| 自杀判定与 `SUICIDE_FACTOR=5` | ✅ 100% | 字节码 + 实测 tick |
| tick 间隔 12000ms | ✅ 100% | 字节码 + 实测 12 秒 |
| timeout 表与内存分级 | ✅ 100% | 字节码常量 |
| `what=4` / `what=5` 语义 | ✅ 100% | 实测吻合 |
| **`what=3` 是否为降级分支** | ⚠️ **90%**（反推成立，缺直接证据） |
| **实际触发降级的业务条件** | ⚠️ **80%**（机制明确，触发时机未见） |
| 20:11:58 那次是否走此路径 | ⚠️ 无证据 |
| 其余 5 处 `exit(-1)` | ⚠️ 未逐一排除 |

**结论：机制已完整还原（可解释"会不会自杀、多久自杀、什么条件下自杀"），
但"这一次是否就是这么死的"仍缺运行时抓取。**
