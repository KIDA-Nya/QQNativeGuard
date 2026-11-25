# Turing 三层反检测：运行时验证结果（2026-11-26）

> 结论：三层能力**静态存在**，但在启动期**全部未激活**。
> 并提出一个认知修正：Turing 的职责可能不是 root 检测。

---

## 一、L1 层：`real_syscall` 的真实实现

### 反汇编（`libturingxq.so`，VA 0x46ad0）

```asm
0x046ad0: aa0003e8   mov  x8, x0        ; syscall 号
0x046ad4: aa0103e0   mov  x0, x1        ; arg1
0x046ad8: aa0203e1   mov  x1, x2        ; arg2
0x046adc: aa0303e2   mov  x2, x3        ; arg3
0x046ae0: aa0403e3   mov  x3, x4        ; arg4
0x046ae4: aa0503e4   mov  x4, x5        ; arg5
0x046ae8: aa0603e5   mov  x5, x6        ; arg6
0x046aec: d4000001   svc  #1            ; ★ 内联汇编直接陷入内核
0x046af0: b140041f   cmn  x8, #1
0x046af4: da809400   cneg x0, x0, ...
0x046af8: 54fffd48   b.hi ...
0x046afc: d65f03c0   ret
```

**关键确认**：`real_syscall` 用 `svc #1` 内联汇编执行系统调用，
**完全不经过 libc**。所以 hook libc 的 `syscall` 对它无效 ——
这正是它设计上的目的。

**但它是导出符号，且参数固定 7 个（非可变参数），可直接 hook。**

### 项目内的历史教训

`hook_libturingxq.cpp` 里留有：

```cpp
// 审计用 syscall hook 已停用：在登录路径上会解引用无效指针导致 QQ 异常退出。
// bytehook_hook_all(nullptr, "syscall", (void*) fake_syscall, nullptr, nullptr);
```

**hook libc 的 `syscall` 会崩 QQ** —— 因为它被全系统大量使用，
且可变参数在转发时容易踩到无效指针。

**本探针只 hook `libturingxq` 自己的 `real_syscall`，参数固定、原样转发，
风险面小得多。**

---

## 二、运行时验证：三层全部未激活

### 探针部署

| 层 | 探针 | 安装结果 |
|---|---|---|
| **L1** | hook `real_syscall`（Turing 导出符号） | ✅ ok |
| **L2** | hook `dl_iterate_phdr`（libc，记录调用者） | ✅ ok |
| **L3** | Java 层反射探针（`TuringReadProbe`，11 类 API） | ✅ 已装 |

### hook 有效性自证

为避免「hook 假成功」，L2 探针额外记录前 8 次**任意来源**的调用：

```
DLIP call#1 ra=0x719098ac38 from_turing=0
DLIP call#2 ra=0x719098ac38 from_turing=0
DLIP call#3 ra=0x7190c98c38 from_turing=0
...
DLIP call#6 ra=0x7190c98c38 from_turing=0
```

**捕获到 6+ 次真实调用** → hook 确实生效，不是假安装。

### 但 Turing 一次都没调用

| 指标 | 结果 |
|---|---|
| `from_turing=1` 的 DLIP 次数 | **0** |
| `REAL_SYSCALL` 记录数 | **0** |
| Java 反射探针命中 | **0**（前一轮已确认） |

**观察窗口：重启后 40 秒（覆盖启动全过程），QQ 停在启动页。**

---

## 三、结论：三层能力存在但未启用

| 层 | 静态能力 | 启动期运行状态 |
|---|---|---|
| L1 `real_syscall` 绕过 libc | ✅ 确实实现（svc #1） | ❌ **零调用** |
| L2 `dl_iterate_phdr` 枚举 so | ✅ 导入符号存在 | ❌ **零调用** |
| L3 `ArtField`/`Executable` 反射 | ✅ 字符串引用存在 | ❌ **零命中** |

**这三层是「具备但未使用」，不是「正在监视我们」。**

---

## 四、认知修正：Turing 可能不负责 root 检测

把三轮实测拼在一起：

| 观察 | 数据 |
|---|---|
| Turing 的文件访问 | 仅 5 次，全是自己的缓存（`.turing.dat` / `.t.log`） |
| Turing 的 syscall 绕过 | 零调用 |
| Turing 的 so 枚举 | 零调用 |
| Turing 的 Java 反射 | 零命中 |
| Turing 的网络行为（启动期 60s） | 零记录 |
| **fekit 的 root 路径探测** | **~187 个 su/ku 路径** |
| **fekit 的属性读取** | **14 个（含 first_api_level 强指纹）** |

**所以合理的角色划分是：**

```
fekit   → 环境完整性检测（root/hook/so/属性）+ 指纹采集   ← 主力
Turing  → 设备令牌（deviceToken）的生成与上报            ← 可能只做这一件事
```

**「Turing 是反检测核心」这个前提本身可能是错的。**
它更像一个「设备身份令牌服务」，而 root/hook 检测主要由 fekit 承担。

### 这个修正的意义

之前把重心全放在 Turing 上是资源误配。
**fekit 的 344 个路径 + 14 个属性现已全部验证干净**（见 `FEKIT_SURFACE_AUDIT.md`），
**这才是「反检测」真正的战场，而且已经打完了。**

---

## 五、仍未闭合

| 项 | 状态 | 为什么 |
|---|---|---|
| Turing 登录后的上报 | ⚠️ 未观察 | 需要实际登录 + 5-10 分钟窗口 |
| Turing 三层是否在检测到异常时才激活 | ⚠️ 未知 | 需要构造触发场景（但受「不许让 QQ 自杀/掉线」约束） |
| L3 反射检测的运行时证据 | ⚠️ 无法直接验证 | Turing 若用 native 反射查 ArtMethod，Java 探针看不到 |

## 六、对目标的影响

原目标写的是「彻底完成 Turing 风控反检测」。
按三轮实测，**更准确的目标应为「彻底完成设备指纹自洽」**：

- ✅ fekit 属性面（14 个，含 first_api_level / soc.model 修复）
- ✅ fekit 路径面（344 个，全部无泄露）
- ✅ 品牌特征（9/9 自洽）
- ✅ 平台特征（无非高通节点）
- ⚠️ Turing 上报内容（待登录验证）
- ⚠️ 其余 SDK（Qimei / Bugly / Beacon）的归因面未逐一复查
