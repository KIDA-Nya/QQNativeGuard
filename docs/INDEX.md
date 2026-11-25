# 文档索引

> 最后更新：2026-11-25

## 一、按重要性排序

| # | 文档 | 内容 | 更新 |
|---|---|---|---|
| **1** | **[README.md](README.md)** | ★ **技术总纲（跨会话必读）**：项目概况/5通道/4种下线/证据分级/10条教训 | 2026-11-25 |
| **2** | **[important.md](important.md)** | ★ **复盘核查报告**：实机验证结果 + 发现的2个问题 | 2026-11-25 |
| 3 | [EVIDENCE_LEVELS.md](EVIDENCE_LEVELS.md) | 证据分级 A/B/C/D，区分「已验证」与「推断」 | 2026-11-25 |
| 4 | [CORRECTIONS.md](CORRECTIONS.md) | 9 个被证伪的结论 + 3 个被修正的判断 | 2026-11-25 |
| 5 | [GUID_SOURCE_EXPERIMENT.md](GUID_SOURCE_EXPERIMENT.md) | 三次单变量实验（方法论范本） | 2026-11-25 |
| 6 | [GUID_FAKE_IMPLEMENTED.md](GUID_FAKE_IMPLEMENTED.md) | GUID 伪造实现细节 | 2026-11-25 |
| 7 | [GUID_SERVER_RESPONSE.md](GUID_SERVER_RESPONSE.md) | 换 GUID 后服务端无验证（票据绑定≠设备识别） | 2026-11-25 |
| 8 | [CHANNEL5_WLOGIN_GUID.md](CHANNEL5_WLOGIN_GUID.md) | 第 5 通道的存储位置查证 | 2026-11-25 |
| 9 | [K54_ROOT_CAUSE.md](K54_ROOT_CAUSE.md) | `ro.product.board` vs `ro.board.platform` | 已验证 |
| 10 | [EXTERNAL_RESEARCH.md](EXTERNAL_RESEARCH.md) | 外部资料调研（腾讯 RiskType / WeChatTablet / NapCat） | 2026-11-25 |
| 11 | [FLAW_SP_IMEI.md](FLAW_SP_IMEI.md) | `sp_imei` 装 AndroidID（但不上报，风险已降级） | 2026-11-25 |
| 12 | [VERIFY_REPORT.md](VERIFY_REPORT.md) | 23 项端到端验证结果（23/23 PASS） | 2026-11-24 |
| 13 | [DETECTION_AUDIT.md](DETECTION_AUDIT.md) | dex 扫描方法与 13 个已覆盖检测类 | 2026-11-24 |
| 14 | [EXPOSURE_AUDIT.md](EXPOSURE_AUDIT.md) | 7 大类暴露面完整清单 | 2026-11-24 |
| 15 | [SETUP_REPORT.md](SETUP_REPORT.md) | 项目搭建过程与技术债 | 2026-11-24 |

## 二、阅读顺序建议

**新会话接手**：
```
1. docs/README.md        ← 只读这个就够（技术总纲）
2. docs/important.md     ← 看最近一次核查的实际状态
3. docs/CORRECTIONS.md   ← 避免重复踩过的坑
```

**要动某个具体技术点**：
```
QsecEst/k54      → K54_ROOT_CAUSE.md
GUID             → GUID_SOURCE_EXPERIMENT.md → GUID_FAKE_IMPLEMENTED.md
可信度存疑        → EVIDENCE_LEVELS.md
外部方案参考      → EXTERNAL_RESEARCH.md
```

## 三、根目录文件的区别

| 位置 | 内容 | 读者 |
|---|---|---|
| **根目录 `README.md`**（11.7KB） | 面向 GitHub 访客：项目介绍、技术亮点、安装使用 | 外部用户 |
| **`docs/README.md`**（8.5KB） | 面向开发者：技术总纲、状态、教训 | 内部/自己 |

**根目录 README 是「对外宣传」，docs/README 是「对内记忆」。**

## 四、工具清单

| 工具 | 用途 |
|---|---|
| `tools/verify_all2.py` | 23 项端到端验证（分三视角） |
| `tools/audit_full.sh` | 7 大类暴露面审计 |
| `tools/observe_v4.sh` | 会话监控（按时间戳过滤） |
| `tools/diag_suicide.sh` | 自杀诊断日志 |
| `tools/verify_master.sh` | 核查配置与硬约束 |
| `tools/api_push.py` | git 不通时用 API 推文件 |
| `tools/create_repo.py` | 创建仓库 |

`tools/archive/` 有 57 个一次性脚本（按需查阅）。

## 五、核心结论速查

### 设备信息 5 通道

| # | 通道 | 状态 |
|---|---|---|
| ① | QsecEst 85 字段 | ✅ k54=kalama |
| ② | HTTP User-Agent | ✅ http.agent 重算 |
| ③ | MSF 8080 | ✅ QIMEI 对齐 |
| ④ | VOD_MSDK | ✅ 已 hook |
| ⑤ | wlogin GUID | ✅ 已伪造 |

### 「设备识别」vs「票据绑定」

| 用途 | 锚点 |
|---|---|
| 设备识别 | QIMEI / AndroidID / 机型 / serial |
| **票据绑定** | **GUID** |

改 GUID → 票据作废（需重登），**不被当新设备**（实测）。

### 四种下线方式

| 类型 | 判据 | 本地可解 |
|---|---|---|
| ① w21 账号验证 | `KICK_TO_LOGIN=1` + 文案 | ❌ |
| ② 进程自杀 | `EXIT_SELF 255` | ⚠️ 可 hook |
| ③ 会话过期 | `rst1=17` + `rlen 2377→193` | ✅ 重登 |
| ④ 系统杀进程 | `SIGNALED 9` | — |

### 硬约束

- ❌ 不卸载 MT 管理器 / UU远程
- ✅ qfun 保持工作
- ❌ 不让 QQ 自杀
- ⚠️ MMKV hook 禁用（会崩）
- ⚠️ `prctl` hook 禁用（QQ 自杀）
- ⚠️ 不拦 `wtlogin.device_lock`（致 w21）