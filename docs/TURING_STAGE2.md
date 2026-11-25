# Turing 反检测阶段②成果（2026-11-26）

> 阶段②原定目标：捕获 Turing 上报内容，找真实通道。
> 结果：**发现 Turing 不是主要采集者，真正的采集主力是 `libfekit`。**
> 并据此修复了一个真实且可验证的指纹矛盾。

---

## 一、方法突破：启用调用者归因

项目里本就有成熟的**调用者归因**机制（`attr_report`），
输出格式：

```
FKCALL <fn> mod=<模块名>+0x<偏移> d=<深度> act=<fake|real|pass> <prop|path>=<名字>
```

但配置里默认关着：

```
ATTR_PROBE=false
ATTR_DEEP=false
```

**打开后**（`ATTR_DEEP` 会对 libc/bytehook 中转层做 backtrace 往上找真实发起方）：

```
ATTR_PROBE=true
ATTR_DEEP=true
```

立即获得按模块归因的完整读取清单。

**这是本轮最关键的方法改进** —— 之前所有 Java 层探针都零命中，
而 native 归因一开就看到了全部真相。

---

## 二、重大修正：Turing 不是采集主力

### 启动阶段各模块的读取次数

| 模块 | 记录数 | 占比 |
|---|---|---|
| **`libfekit_strip_8.608.1109.so`** | **381** | **86%** |
| `libopenjdk.so` | 33 | |
| `libnative-bypass.so`（我们） | 30 | |
| `libjavacore.so` | 21 | |
| `libc.so` | 14 | |
| **`libturingxq.so`** | **5** | **1%** |

### `libturingxq` 的全部 5 条记录

```
access +0x1f6b0  path=/data/user/0/.../app_turingdfp/log
access +0x1f8b8  path=/data/user/0/.../app_turingdfp/1
fopen  +0x1faf4  path=/data/user/0/.../app_turingdfp/log/.t.log
stat   +0x1fffc  path=/data/user/0/.../app_turingdfp/log/.t.log
stat   +0x1fffc  path=/data/user/0/.../app_turingdfp/1/.turing.dat
```

**Turing 在启动阶段只读写自己的缓存文件，没有读取任何设备属性。**

同时捕获到它写出的 88 字节：

```
turing write len=88 caller_off=0x294ec
turing bytes[88]=10f7babcd485c97c8706fb70aa6326361c87f1bd248ef33029108a1ab57a177d...
```

与设备上 `.turing.dat` 的内容逐字节一致 —— 证实捕获准确。

### 结论修正

| 原假设 | 实际 |
|---|---|
| Turing 是主要设备采集中枢 | **fekit 是（381 次），Turing 只碰自己的文件（5 次）** |
| Turing 三层反检测正在监视我们 | 三层能力确实存在，但**启动阶段未使用** |
| 需要优先对齐 Turing 的数据 | **应优先对齐 fekit 的读取面** |

**这个修正很重要：之前把重心放在 Turing 上是判断偏差。**

---

## 三、真实缺口：`ro.product.first_api_level`

### 发现

fekit 读取的 14 个属性中，10 个已被我们的 hook 伪装（`act=fake`），
但 4 个仍是 `act=real`：

```
ro.product.first_api_level   ← 强指纹，与机型强绑定
ro.product.cpu.abi
ro.hardware
ro.boot.hardware
```

### 为什么 first_api_level 是矛盾

```
真机:  ro.product.first_api_level = 30   （Android 11 首发，LE2123 是 2019 年机型）
伪造:  ro.product.model = PGT110          （OPPO Find X7，2024 年上市，Android 14 → 应为 34）
```

**相差 4 代。服务端只要把 `model` 与 `first_api_level` 一对，立刻矛盾。**

这是一个「机型强指纹」：与 `model` 天然成对，单独伪造 model 而不管它会留下明显破绽。

### 其余三个为什么不矛盾

| 属性 | 真机 | PGT110 应然 | 判定 |
|---|---|---|---|
| `ro.product.cpu.abi` | arm64-v8a | arm64-v8a | 一致 |
| `ro.hardware` | qcom | qcom | 一致 |
| `ro.boot.hardware` | qcom | qcom | 一致 |

**骁龙 888（lahaina）与骁龙 8 Gen 3（kalama）都是高通平台，硬件属性名相同，
所以这三项不需要伪造。** 这也说明之前的对齐方向是对的。

---

## 四、修复与验证

### 改动

`hook_libc.cpp`：

```cpp
static char g_first_api_level[8] = "34";   // PGT110 = Android 14 首发

// fake_prop_value() 中新增：
if (strcmp(name, "ro.product.first_api_level") == 0 ||
    strcmp(name, "ro.product.vendor.first_api_level") == 0 ||
    strcmp(name, "ro.product.system.first_api_level") == 0 ||
    strcmp(name, "ro.product.odm.first_api_level") == 0 ||
    strcmp(name, "ro.product.product.first_api_level") == 0) {
    return g_first_api_level;
}
```

五个变体全部覆盖（不同分区可能读不同前缀）。

### 验证（A 级）

```
修复前: FKCALL prop mod=libfekit_strip_8.608.1109.so+0x33e3a0 d=6 act=real prop=ro.product.first_api_level
修复后: FKCALL prop mod=libfekit_strip_8.608.1109.so+0x33e3a0 d=6 act=fake prop=ro.product.first_api_level
```

**同一个调用点（`+0x33e3a0`，深度 6），从 `real` 变为 `fake`，修复确凿生效。**

---

## 五、全面复查：其余 act=real 是否构成矛盾

打开归因后扫全部 `act=real prop=`：

| 模块 | 属性 | 真机值 | 是否矛盾 |
|---|---|---|---|
| fekit | `ro.boot.hardware` | qcom | 否（PGT110 同为 qcom） |
| fekit | `ro.hardware` | qcom | 否 |
| fekit | `ro.product.cpu.abi` | arm64-v8a | 否 |
| Bugly | `ro.build.nubia.rom.name` | 空 | 否（PGT110 亦非努比亚） |
| Bugly | `ro.build.version.emui` | 空 | 否（PGT110 亦非华为） |
| cutils | `ro.vendor.gfx.32bit.target` | 图形配置 | 否（非设备指纹） |

**全部不构成矛盾。**

**所以 fekit 侧的设备属性现已全部自洽。**

---

## 六、阶段②结论

| 项 | 状态 |
|---|---|
| Turing 的读取行为 | ✅ 已捕获（只碰自己的缓存文件） |
| Turing 的上报通道 | ⚠️ 未捕获（启动阶段无网络行为） |
| **fekit 的完整读取清单** | ✅ **已获得（381 条，按属性归因）** |
| **fekit 侧的指纹矛盾** | ✅ **已全部消除** |
| Turing 三层反检测是否启用 | ⚠️ 启动期未观察到（`real_syscall` / `dl_iterate_phdr` / 反射均无记录） |

### 下一步

1. **观察 Turing 在网络上报阶段的行为**（启动后延迟采样，而非只看启动窗口）
2. **检查 fekit 的 `path=` 读取**（除属性外，它还读了大量 `/proc`、`/sys` 路径）
3. `real_syscall` 的实际调用记录（Turing 是否真在用）

**阶段②的核心价值：把「Turing 黑盒」这个假设纠正为「fekit 才是主力」，
并且真的修掉了一个可验证的指纹矛盾。**
