# 自杀时间完整公式（2026-11-26 补完）

> 承接 `SUICIDE_LOGIC.md`。本文补上「mGuardTick 从哪来」这一环，
> 使自杀时间从「固定 25/50 分钟」修正为「按内存与活跃度动态计算」。

---

## 一、完整调用链

```
GuardManager.<clinit>
  SUICIDE_FACTOR = 5

BackgroundGuard.onEnter(processName)
  └─ if (processName.equals("fake_p_msg")) {
         long mem = MemoryUtils.c(Process.myPid());
         long timeout = GuardConfig.getTimeout(mCountEvents, mLastIndex, mLastMinute, mem);
         mGuardTick = timeout / 12000 - 2;
     }
  └─ startTimer()  →  mHandler.sendEmptyMessageDelayed(5, 12000)

每 12 秒：GuardState.onTick()
  mGuardTick += 1;
  mClearTick += 1;

BackgroundUnguard.onTick()
  long T = SUICIDE_FACTOR * 50;              // 250
  if (mClearTick == T - mGuardTick ||
      mClearTick == T              ||
      mClearTick == T + mGuardTick) {
      if (mManager.mFgProcess == null) {
          System.exit(-1);                   // status = 255
      }
  }
```

---

## 二、timeout 的计算

### GuardConfig.getTimeout

```java
long getTimeout(long[][] countEvents, int lastIndex, int lastMinute, long memory) {
    int[] timeouts = GuardConfig.timeouts;
    int memLevel = getMemoryLevel(memory);
    int actLevel = getActiveLevel(countEvents, lastIndex, lastMinute);
    return timeouts[memLevel + actLevel];      // 直接查表
}
```

### GuardConfig.getMemoryLevel

```java
int getMemoryLevel(long memory) {
    int[] barriers = GuardConfig.memoryBarriers;
    int idx = Arrays.binarySearch(barriers, (int) memory);
    if (idx < 0) idx = -idx - 1;               // 转插入点
    idx -= GuardConfig.mMemoryMode;
    if (idx > 2) return 2;                     // 上限
    if (idx < 0) return 0;                     // 下限
    return idx;
}
```

### 数组实际值（从字节码常量读出）

`GuardConfig.<init>` @ classes3.dex 0x631eac：

```
const/high16 v3, #int 47185920    = 45 MB
const/high16 v4, #int 68157440    = 65 MB
const/high16 v0, #int 89128960    = 85 MB
const/high16 v2, #int 120586240   = 115 MB
filled-new-array {v3, v4, v0, v2}
→ memoryBarriers = {45MB, 65MB, 85MB, 115MB}
```

`GuardConfig.<init>` @ 0x631ec8：

```
const v3, #0x003a9800  = 3,840,000 ms = 64 分钟
const v4, #0x001d4c00  = 1,920,000 ms = 32 分钟
const v5, #0x000ea600  =   960,000 ms = 16 分钟
const v0, #0x000afc80  =   720,000 ms = 12 分钟
const v2, #0x000927c0  =   600,000 ms = 10 分钟
filled-new-array {v3, v4, v5, v0, v2}
→ timeouts = {64min, 32min, 16min, 12min, 10min}
```

### 内存等级映射（mMemoryMode 取默认 0）

| 内存 | binarySearch 插入点 | memLevel |
|---|---|---|
| < 45 MB | 0 | 0 |
| 45–65 MB | 1 | 1 |
| 65–85 MB | 2 | 2 |
| ≥ 85 MB | 3 | 2（clamp） |

---

## 三、时间推算

`mGuardTick = timeout / 12000 - 2`

| timeout | mGuardTick | 最早命中 `250 - mGuardTick` | 折合时间 |
|---|---|---|---|
| 64 min | 318 | −68（无效）→ 取 `250` | **50 分钟** |
| 32 min | 158 | 92 | **18.4 分钟** |
| 16 min | 78 | 172 | **34.4 分钟** |
| 12 min | 58 | 192 | 38.4 分钟 |
| 10 min | 48 | 202 | 40.4 分钟 |
| **未设置**（非 fake_p_msg） | **0** | **250** | **50 分钟** |

**所以实际自杀区间是 18–50 分钟**，取决于内存等级与活跃度。

**内存越高 → timeout 越短 → 越早退出**，设计意图非常明确：**内存紧张时尽快释放。**

### 为什么实测常在 2 小时以上

`GuardState.onEnter` 会把 `mClearTick` 与 `mGuardTick` **双双归零**：

```
GuardState#onEnter @0011:  iput-wide mClearTick = 0
GuardState#onEnter @0013:  iput-wide mGuardTick = 0
```

任何状态切换（切前台、切其他进程、锁屏解锁）都会重启计时。
用户偶尔看一眼 QQ，时钟就从头再来。**存活时长本质上取决于使用习惯，而不是某个固定阈值。**

---

## 四、与「检测风险」的关系（本次核实的核心）

| 输入变量 | 写入点 | 来源性质 |
|---|---|---|
| `mClearTick` | `onEnter`（归零）、`onTick`（+1） | **纯时间** |
| `mGuardTick` | `onEnter`（归零）、`onTick`（+1）、`BackgroundGuard.onEnter`（按 timeout）、`BackgroundGuard.onMessage`（按 timeout） | **内存 + 活跃度** |
| `mFgProcess` | `<init>`、`handleMessageForQQPluginBG` ×3、`handleMessageForQQPluginFG` ×1 | **进程/Activity 状态**（日志键名：`fgProcess` / `bgProcess` / `activityCount` / `foreground`） |
| `mStateId` | `<init>`、`nextState` | **状态机** |

**四个输入变量全部与「检测到篡改/root/hook」无关。**

`GuardConfig.getTimeout` 的输入只有 `countEvents`（事件计数）、`mLastIndex`、`mLastMinute`、`memory`。

**结论：自杀机制本身不读取任何检测结果。**

---

## 五、仍然不能确认的部分

| 项 | 状态 |
|---|---|
| 自杀机制的输入与检测无关 | ✅ 已确认（本表） |
| 20:11:58 那次自杀的实际路径 | ⚠️ 无运行时证据（当时没有 hook） |
| 进入 `BackgroundUnguard` 的业务条件 | ⚠️ 约 70%（`nextState` 已 hook，待观察） |
| 其余 5 处 `exit(-1)` | ⚠️ 未逐一排除 |
| `BackgroundGuard.onMessage` 的 timeout 重算是否含风险判断 | ⚠️ 未看该方法全文 |

**所以准确表述是：**

> 已定位并完整还原了**一条**与检测无关的自杀路径（内存回收）。
> 但不能据此断言**所有** `EXIT_SELF status=255` 都来自它。

---

## 六、已部署的观测

- `ExitTraceHook` 覆盖 13 个退出入口 + `GuardState.onTick` 采样 + `nextState` 转移记录
- 采样输出含：当前状态、`mClearTick` / `mGuardTick`、三个阈值、**距离命中还差多少拍和分钟**、`mFgProcess` 是否为 null
- 落盘 `files/qqng_exit_trace.log`

实测已确认状态转移可用：`state 0 -> 3 (null)`、`state 3 -> 4 (null)`。

---

## 七、状态 ID 映射（实测，A 级）

`ExitTraceHook` 在 `nextState` 里通过 `getState(id)` 取类名，实测记录：

| id | 类名 | 来源 |
|---|---|---|
| **0** | `GuardState` | 实测 `state 0(GuardState) -> 0(GuardState)` |
| **3** | `ForgroundOther` | 实测 `state 3(ForgroundOther) -> 4(BackgroundGuard)` |
| **4** | `BackgroundGuard` | 实测 `state=BackgroundGuard id=4`（TICK SAMPLE） |
| 1 / 2 / 5 / 6 / 7 | 未确认 | 尚未触发到这些状态 |

### 一次被推翻的推测

从 `getState` 的 `new-instance` 顺序（LiteUnguard → LiteGuard →
BackgroundUnguard → BackgroundGuard → ForgroundOther → ForgroundMain →
PrestartGuard → GuardState）曾推测「3 = BackgroundUnguard」。

**实测否定**：3 是 `ForgroundOther`。`packed-switch` 的 case 排列不是
按代码里的 `new-instance` 顺序，不能靠反汇编顺序推断 ID。

### 已观察到的状态序列

```
启动：  0(GuardState) → 0 → 0 → 4(BackgroundGuard)
一次运行：4 → 2 → 2 → 3(ForgroundOther) → 4
```

其中 `2` 停留时间极短（毫秒级），随后转到 `3(ForgroundOther)` 再到 `4`。

**关键观察：迄今为止 `Unguard` 类名从未在日志中出现。**

含义：进程没有长期停留在 `BackgroundUnguard`，而自杀需要在该状态
累计 125/250 拍（约 25/50 分钟）。**这正是自杀罕见的原因。**

### 待办

- 补齐 id 1/2/5/6/7 的类名（需触发对应状态）
- 确认 `BackgroundUnguard` 的 id，以及进入它的业务条件
- `ExitTraceHook.dump()` 在 hook 早期因 `Context` 未就绪会写文件失败，
  已把类名同时打到 logcat 作为兜底；后续应改为延迟初始化 Context
