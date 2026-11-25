# 项目总纲（跨会话记忆）

> 最后更新：2026-11-25
> 这是**唯一必读文档**。其它 docs 都是细节，本文是骨架。

---

## 一、项目是什么

**QQ Native Guard** — QQ 环境检测绕过模块（Android / LSPosed）。
从 [Xalsace/QQEnhancedBypass](https://github.com/Xalsace/QQEnhancedBypass)（GPL-3.0）分叉继续开发，主打 **Native 层**。

- **仓库**：https://github.com/KIDA-Nya/QQNativeGuard
- **版本**：v1.4.0
- **包名**：`io.github.kida.qqnativeguard`
- **路径**：`D:\app\QQNativeGuard`

---

## 二、当前设备环境（实测值）

```
机型:   OPPO Find X7 (PGT110 / OPPO_PGT110)
平台:   kalama (骁龙 8 Gen 3)   ← 关键：k54 修复
Build:  UKQ1.231003.002 / Android 15 / SDK 35
FP:     OPPO/PGT110/OPPO_PGT110:15/UKQ1.231003.002/1710000000:user/release-keys
Serial: <8-hex> / SoC: <10-digit> / AID: <16-hex>
IMEI:   <15-digit>（Luhn 通过）
QIMEI:  <32-hex>
GUID:   <fake-guid-16B>（伪造，原值为 <orig-guid-16B>）
```

**配置文件**：`/data/local/tmp/qqbypass.conf`

---

## 三、核心模型：设备身份 5 通道

| # | 通道 | 载体 | 控制方式 | 状态 |
|---|---|---|---|---|
| ① | **QsecEst** | 1293B，AES-128-CBC | 属性伪造 | ✅ k54=kalama 全对齐 |
| ② | **HTTP User-Agent** | 明文 | `http.agent` 重算 | ✅ |
| ③ | **MSF 8080** | uin+QIMEI 明文 | QIMEI 对齐 | ✅ |
| ④ | **VOD_MSDK** | androidDesc | native hook | ✅ |
| ⑤ | **wlogin GUID** | 16B | hook `generateGuid` | ✅ 已伪造 |

**关键区分**（实测得出）：

| 用途 | 锚点 |
|---|---|
| **设备识别**（"这是哪台设备"） | QIMEI / AndroidID / 机型 / serial |
| **票据绑定**（"这张票是不是这台机器的"） | **GUID** |

→ 改 GUID 只让票据作废（需重登），**不被当新设备**。

---

## 四、已破解的加密

| 目标 | 算法 | 密钥来源 |
|---|---|---|
| **QsecEst** | AES-128-CBC | `.rodata` 硬编码，工具 `t9_decrypt.py` 可复现 |
| UploadEncrypt（Bugly） | AES-256-CBC | K=SHA256(`"692692a23ad61ea3"+appId+"16371bbed4f1a9a1"`) |
| Beacon 8081 | AES+RSA | 私钥在服务端 ❌ |
| MSF 8080 | SSO 会话密钥 | 握手协商 ❌ |

**QsecEst 容器**：`[8B MD5头][5B "00002"][密文1280B]`
**明文**：1278B JSON（85 键 k1-k105）+ PKCS#7

---

## 五、QsecEst 85 字段真相

**只有 9 个字段带真实设备身份**，其余 52 个是 `E_NU` 空值、24 个是常量。

| 字段 | 来源属性 | 状态 |
|---|---|---|
| k53 | `ro.product.manufacturer` | ✅ |
| **k54** | **`ro.product.board`** | ✅ **曾误 hook `ro.board.platform`，已修** |
| k55 | `ro.build.type` | ✅ |
| k56 | `ro.build.host` | ✅（保留真机 ReVork） |
| k57 | `ro.product.model` | ✅ |
| k58 | `ro.hardware` | ✅ |
| k59 | `ro.build.product` | ✅ |
| k60 | `ro.build.fingerprint` | ✅ |
| k61 | `ro.build.id` | ✅ |
| k62/63/64/66/84 | tags/brand/device/display/SoC | ✅ |

---

## 六、四种下线方式（必须区分）

| 类型 | 判据 | 能否本地解决 |
|---|---|---|
| **① w21 账号验证** | `KICK_TO_LOGIN=1` + 文案「身份验证失败(w21)」 | ❌ **账号级，本地无解** |
| **② 进程自杀** | `EXIT_SELF status=255` + `GuardManager.exit` | ⚠️ 可 hook exit |
| **③ 会话过期** | `rst1=17` + `rlen 2377→193` | ✅ 重登 |
| **④ 系统杀进程** | `reason=2 SIGNALED status=9` | — |

**w21 的确切机制**（重要）：
```
登录成功（QsecEst 已上报）
  ↓ 8 秒后
服务端异步 push「身份验证失败(w21)」
  ↓
用户视角：刚进去就被弹回，误以为"没登上"
```

**不是登录失败，是登录成功后异步踢。**

---

## 七、证据分级（严格遵守）

| 级别 | 含义 | 可陈述为 |
|---|---|---|
| **A** | 端到端实测，可复现 | 「已确认」 |
| **B** | 二进制/代码直接读出 | 「已确认」 |
| **C** | 观察现象，机制未证 | 「观察到」 |
| **D** | 推断 | **必须标「推断」** |

**A 级（真的能验证）**：
- QsecEst AES-128-CBC（FIPS-197 KAT 通过）
- k54 来源 = `ro.product.board`（解密前后对比）
- 5 通道存在（抓包+解密+MMKV 三路互证）
- `http.agent` 含真机机型（141/170 次）
- `sp_imei` 不上报（`wup_dump` 里 imei 行数=0）
- GUID 5 处存储（但**都不是源头**）

**D 级（推断，不能当结论）**：
- w21 = RiskType 1/201（腾讯云文档，未必是 QQ 那套）
- 服务端按账号信用评分
- `libnative-memory` 靠映射异常判定
- 四层独立判定机制
- GUID 是独立随机量
- 账号信用衰减规律

---

## 八、三次单变量实验（方法论范本）

**目标**：找出 `generateGuid` 的源头

| 实验 | 改动 | 结果 |
|---|---|---|
| 1 | `wlogin_device.dat` | ❌ 值不变 |
| 2 | MMKV `android_id` 键（重启进程） | ❌ 值不变 |
| 3 | **全部 20 处存储** | ❌ 仍不变 |

**结论**：运行时派生，**不读存储** → 只能 hook 函数本身。

**外部佐证**（NapCat）：
```
Linux:  GUID = MD5(/etc/machine-id + MAC)
macOS:  GUID = MD5(machine_id[8] || sn)
```

**方法论**：三次否定后才能断定「不在存储里」。

---

## 九、已知教训（全部踩过）

| # | 教训 | 具体事件 |
|---|---|---|
| 1 | **抓包 > hook 日志** | 靠 hook 推断「上报点4个」，实际 10+ SDK |
| 2 | **字段映射逐项核对** | k54 误 hook `ro.board.platform`（应 `ro.product.board`） |
| 3 | **审计用目标进程的域** | root shell ≠ untrusted_app，误判 3 个"缺口" |
| 4 | **确认日志 PID 归属** | 把 OPPO 进程的 `NO_CELLULAR` 当成 QQ 掉线 |
| 5 | **端到端验证** | 改了不等于生效 |
| 6 | **日志措辞不可信** | `intercepted` 实际只是记录 |
| 7 | **改本地存储 ≠ 改上报** | `sp_imei` 只本地缓存 |
| 8 | **心跳停止 ≠ 自杀前兆** | 与 App 前后台相关 |
| 9 | **MMKV hook 会崩** | 纯观测实现也崩，问题在 hook 本身 |
| 10 | **`prctl` hook 会让 QQ 自杀** | 已禁用 |

---

## 十、技术栈现状

### Java 层（`app/src/main/java/.../hooks/`）

| 文件 | 功能 |
|---|---|
| `DeviceInfoHook.java` | 设备信息 + **http.agent 重算** |
| `NetworkReportHook.java` | 上报观测/拦截（双开关）+ **已移除 device_lock** |
| `RootDetectionHook.java` | Root 检测 + **File 12 方法** + **PM 25+** + **GUID 伪造** + 懒加载 |
| `XposedDetectionHook.java` | Xposed 痕迹（**已移除 bin.mt.plus / uuremote**） |
| `DebugDetectionHook.java` | 调试检测 |
| `RuntimeMonitorHook.java` | Pandora 监控禁用 |
| `Config/HookConfig.java` | 配置加载 |

### Native 层（`app/src/main/cpp/hooks/`）

| 文件 | 行数 | 功能 |
|---|---|---|
| `hook_libc.cpp` | ~2100 | 主体：文件/属性/SoC/cmdline/加密 |
| `hook_libfekit.cpp` | ~123 | fekit 专属 |
| `hook_libturingxq.cpp` | ~227 | Turing deflate 注入 |
| `hook_libmsf.cpp` | 17 | MSF |

**覆盖**：30+ hook 点 / 70+ 属性 / 10 种 `/proc` 文件 / 40 个可疑路径

---

## 十一、待办（按价值排序）

| # | 任务 | 可验证性 | 价值 |
|---|---|---|---|
| 1 | **观察本次会话存活时长**（已启动监控） | A | ★★★ |
| 2 | 验证 GUID 多次重启后持久 | A | ★★ |
| 3 | 抓包确认 GUID 是否进上报 | A | ★★ |
| 4 | `libnative-memory` 的 syscall 路径分析 | B | ★★ |
| 5 | 补 `PackageManager` 的 `*AsUser` 变体 | B | ★ |
| 6 | 修日志措辞（`intercepted` → `observed`） | B | ★ |

---

## 十二、硬约束（不可违反）

- ❌ 不卸载 `bin.mt.plus.canary`（MT 管理器）
- ❌ 不卸载 `com.netease.uuremote`（UU远程）
- ✅ `me.yxp.qfun` 模块保持工作
- ❌ 不让 QQ 自杀（`VM exiting` / `EXIT_SELF 255`）
- ⚠️ **MMKV hook 必须禁用**（会崩溃循环）
- ⚠️ **`prctl` hook 必须禁用**（QQ 会自杀）
- ⚠️ **不得拦截 `wtlogin.device_lock`**（导致 w21）

---

## 十三、一句话总结

**客户端能做的已做到极致**（5 通道全对齐、4 种下线机制全摸清、A 级证据充分）。

**剩下的瓶颈在服务端账号状态**（D 级推断，本地无法验证也无法改变）。

**不再宣称「这样就能过」—— 因为无法验证服务端。**

---

## 附：文档地图

| 想了解 | 看 |
|---|---|
| 全部细节索引 | `INDEX.md` |
| 可信度标尺 | `EVIDENCE_LEVELS.md` |
| 已证伪的结论 | `CORRECTIONS.md` |
| k54 破绽 | `K54_ROOT_CAUSE.md` |
| GUID 实验 | `GUID_SOURCE_EXPERIMENT.md` |
| GUID 实现 | `GUID_FAKE_IMPLEMENTED.md` |
| GUID 服务端反应 | `GUID_SERVER_RESPONSE.md` |
| 外部资料 | `EXTERNAL_RESEARCH.md` |
| 验证结果 | `VERIFY_REPORT.md` |