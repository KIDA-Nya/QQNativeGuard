# 验证报告（2026-11-24）

> 端到端验证：23 项检查，全部通过。

## 验证方法

分三类视角，避免混淆：

| 视角 | 说明 | 预期 |
|---|---|---|
| **模块自检日志** | QQ 进程内实际读到的值 | 伪造值 |
| **系统属性（root shell）** | hook 只影响 QQ 进程，不影响系统 | 真值 |
| **SELinux 隔离** | QQ 是 untrusted_app 域，读不到系统敏感数据 | 读不到 |

**关键教训**：之前用 root shell 审计，误判了 3 个"缺口"，实际都不成立。

---

## 验证结果（23/23 PASS）

### 1. 模块加载（3/3）

```
✅ Native 库加载
✅ Native hook 全装        All Native Hooks Installed Successfully
✅ JNI 无缺失              无 No implementation found
```

### 2. Java hook（5/5）

```
✅ 懒加载 hook >=4          attached=14
✅ Pandora >=20 方法        hooked=32
✅ camerasdk isDeviceRooted
✅ gathererga 完整路径      internal.provider.impl.UserInfoImpl
✅ GProGuildSafetyRisk      getRiskLevel
```

### 3. QQ 进程内设备身份（5/5）★ 核心

来自模块自检日志，是 QQ 进程内真实读到的值：

| 属性 | QQ 内读到 | 真值 | 状态 |
|---|---|---|---|
| `ro.board.platform` | **kalama** | lahaina | ✅ |
| `ro.soc.model` | **SM8650** | SM8350 | ✅ |
| `soc_serial` | **<soc>** | 1539308571 | ✅ |
| `ro.boot.verifiedbootstate` | **green** | orange | ✅ |
| `ro.serialno` | **<serial>** | 5bbffc1b | ✅ |

**这解决了 k54 破绽**：之前 `k54=lahaina` 与伪造机型 `PGT110` 矛盾，现在一致。

### 4. 系统侧仍为真值（3/3）

验证 hook 只影响 QQ 进程，不破坏系统：

```
✅ ro.board.platform        = lahaina    （未被改动）
✅ ro.soc.model             = SM8350      （未被改动）
✅ ro.boot.verifiedbootstate = orange     （未被改动）
```

### 5. 稳定性（2/2）

```
✅ 无崩溃                   died=0
✅ 进程存活
```

### 6. 硬约束（4/4）

```
✅ bin.mt.plus.canary     包完好
✅ com.netease.uuremote   包完好
✅ me.yxp.qfun            包完好
✅ 硬约束包未被拦           blocked=0
```

### 7. SELinux 隔离（1/1）

```
✅ QQ 读不到 lspd          run-as 验证 count=0
```

**结论**：`/proc/net/unix` 里的 `/data/adb/lspd/.cli_sock` 对 root 可见，
但 **QQ 作为 untrusted_app 域本来就无权限读取**，不构成泄漏。

---

## 验证脚本

`tools/verify_all2.py` —— 可重复运行，输出 JSON 结果。

```bash
python -X utf8 tools/verify_all2.py
```

---

## 本轮修复清单

| # | 修复 | 类型 |
|---|---|---|
| 1 | `camerasdk.DeviceInfo` 方法名 `isRooted` → `isDeviceRooted` | 方法名错误 |
| 2 | `gathererga.UserInfoImpl` 包名补 `internal.provider.impl` | 类名错误 |
| 3 | `gathererga.UserInfoProvider` 加懒加载重试 | 时机问题 |
| 4 | 移除 `qqperf...isXposedInstalled` | 方法不存在 |
| 5 | 新增 `GProGuildSafetyRisk.getRiskLevel` 观测 | 新发现 |
| 6 | 新增 `FK_PROC_CMDLINE`（防御性） | 加固 |
| 7 | `sanitize_cmdline` 从 8 个 token 补到 37 个 | 严重缺口 |
| 8 | 新增 SoC 自检日志（`board.platform` / `soc.model` / `cpuinfo`） | 可观测性 |
| 9 | 实现真正的 `hookPackageManager`（原为空函数） | 空实现 |
| 10 | 移除 `bin.mt.plus` / `com.netease.uuremote` 风险包误判 | 硬约束冲突 |
| 11 | 移除 `camerasdk.isRooted` 冗余 hook | 代码清理 |

---

## 端口扫描结论

**3 个疑似缺口，实际只有 1 个成立**：

| 疑似缺口 | 实际 | 结论 |
|---|---|---|
| `/proc/<pid>/cmdline` 可见 magiskd | QQ 无 ptrace 权限 | ❌ 不成立 |
| `/proc/net/unix` 可见 lspd | SELinux 阻止 | ❌ 不成立 |
| `isXposedInstalled` hook | 方法不存在 | ❌ 不成立（已移除） |

**真正成立的缺口**：
- `camerasdk` 方法名错（一直无效）
- `gathererga` 类名错（一直无效）
- `sanitize_cmdline` 覆盖不足（37 vs 8）
- `hookPackageManager` 空实现
- 硬约束误伤