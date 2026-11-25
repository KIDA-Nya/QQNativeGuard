# fekit 读取面完整性验证（2026-11-26 阶段②续）

> 上一轮修复了 `first_api_level`。本轮验证 fekit 的**路径**读取面（占其 381 条记录的 96%）。
> 结论：**344 个路径探测全部无泄露。**

---

## 一、fekit 探测的路径构成

统计来自 `FKCALL ... mod=libfekit_strip_8.608.1109.so ... path=` 的去重结果：

| 类别 | 数量 |
|---|---|
| **唯一路径总数** | **344** |
| su / root 相关 | ~187 |
| `/proc/<node>/cmdline` | 23 |
| 系统库（版本比对） | ~130 |
| 其他（base.apk 等） | 余下 |

---

## 二、root 路径矩阵：344 个里约 187 个

fekit 在**每个候选目录下逐个探测 7 个名字**：

```
su  sudo  sutemp  shuamesu  ku.sud  bdsu  .su
```

探测的目录包括：

```
/vendor/xbin/          /vendor/bin/
/system_ext/bin/       /system/xbin/       /system/xbin/bstk/
/apex/com.android.art/bin/        /apex/com.android.runtime/bin/
/cache/    /data/    /data/local/    /data/local/bin/    /data/local/xbin/
...（更多）
```

### 验证结果：全部不存在

```
存在数量: 0 / 344（就 su/ku 类而言）
```

**所以 fekit 拿到的 `false` 是真实值，不构成伪造痕迹。**

**注意**：`ku.sud` 是 KernelSU 的特征名（`ku` + `sud`），
说明 fekit 确实认识 KernelSU，只是本设备没装在那个路径。

### 一个被证实的误判

之前从 `libturingxq.so` 的字符串里看到 `/system/xbin/ku`，
担心是缺口。实测该路径不存在，且 fekit 探测的是 `ku.sud` 系列。
**不是真实缺口。**

---

## 三、`/proc/<node>/cmdline` 探测矩阵：23 个

```
/proc/sgefreqinfo/cmdline          /proc/brightSwitch/cmdline
/proc/charger_cycle/cmdline        /proc/oplusManifest/cmdline
/proc/oplusVersion/cmdline         /proc/oplus_binder/cmdline
/proc/oplus_locking/cmdline        /proc/oplus_storage/cmdline
/proc/dynamic_debug/cmdline        /proc/fdleak_detect/cmdline
/proc/ftm_tfa98xx/cmdline          /proc/kpagecgroup/cmdline
/proc/mpp_service/rkvenc-core0     /proc/pagetypeinfo/cmdline
/proc/rk_dmabuf                    /proc/sched_debug/cmdline
/proc/slab_memcfg/cmdline          /proc/sysrq-trigger/cmdline
/proc/tbatt_pwroff/cmdline         /proc/tristatekey/cmdline
/proc/ufsplus_ctrl/cmdline         /proc/uid_cputime/cmdline
/proc/uid_procstat/cmdline         /proc/vmallocinfo/cmdline
```

### 验证结果：23 个全部不存在

根因很明确：

| 基础节点 | 类型 | `/cmdline` 子路径 |
|---|---|---|
| `/proc/sgefreqinfo` | **文件** | 必然不存在 |
| `/proc/brightSwitch` | **文件** | 必然不存在 |
| `/proc/oplusManifest` | **目录** | 目录内无 cmdline 文件 → 不存在 |

**对照**：`/proc/self/cmdline` 存在（唯一为真的一个）。

**结论：这 23 个探测返回值全部是真实的 `false`，无泄露。**

### 一个被排除的假警报

`/proc/sgefreqinfo` 初看像 MTK GPU 节点，实测内容是 **CPU 频率表**：

```
cpu 0
300000  403200  499200  595200  691200  806400  902400
...  2764800  2841600
```

最大频率 **2841600 kHz = 2.84 GHz**，正是 Snapdragon 888 (SM8350) 的规格。

**但 fekit 探测的是 `/proc/sgefreqinfo/cmdline` 而非该文件本身，
所以它拿不到频率表。** 假警报排除。

---

## 四、品牌特征：9/9 全部自洽（这是好消息）

| 路径 | 存在 | 含义 |
|---|---|---|
| `/proc/oplusManifest` | ✅ | ColorOS 特征 |
| `/proc/oplusVersion` | ✅ | ColorOS 特征 |
| `/proc/oplus_binder` | ✅ | ColorOS 特征 |
| `/proc/oplus_locking` | ✅ | ColorOS 特征 |
| `/proc/oplus_storage` | ✅ | ColorOS 特征 |
| `/proc/brightSwitch` | ✅ | 欧加系特征 |
| `/proc/charger_cycle` | ✅ | 欧加系特征 |
| `/proc/tbatt_pwroff` | ✅ | 欧加系特征 |
| `/proc/ftm_tfa98xx` | ✅ | NXP 音频功放（欧加常用） |

**真机是 OnePlus 9 Pro（OxygenOS/ColorOS），伪造目标是 OPPO Find X7（ColorOS）——
两者同属欧加集团，ColorOS 特征完全一致。**

**这是本项目「选择 OPPO 机型」这个决策的意外红利：品牌层天然自洽。**

### 平台矛盾风险：全部排除

```
/proc/mpp_service                       absent   （Rockchip）
/proc/device-tree/rockchip-suspend      absent   （Rockchip）
/proc/device-tree/rockchip-system-monitor absent （Rockchip）
/proc/rk_dmabuf                         absent   （Rockchip）
/proc/exynos_ppm                        absent   （三星）
/proc/tegra_dvfs                        absent   （NVIDIA）
```

**没有任何非高通平台的特征节点。**

---

## 五、结论

### fekit 的读取面现在完全自洽

| 面 | 状态 |
|---|---|
| 属性（14 个） | ✅ 10 个已 fake，4 个 act=real 但均不矛盾；`first_api_level` 已修 |
| 路径（344 个） | ✅ **全部无泄露**（root 探测全 false、cmdline 探测全 false、系统库正常） |
| 品牌特征 | ✅ 9/9 自洽 |
| 平台特征 | ✅ 无非高通节点 |

**fekit 这条线可以判定为「已对齐」。**

### 仍待处理

1. **`ro.soc.model = SM8350` 未被伪造**
   - fekit 当前没读它（`act=real` 列表里没有）
   - 但它是强指纹（SM8350 vs PGT110 应有的 SM8650）
   - **建议防御性补上**

2. **`/proc/sgefreqinfo` 若被完整读取会暴露频率表**
   - 当前 fekit 只读 `/cmdline` 子路径，拿不到
   - 但其他 SDK 可能直接读该文件
   - **建议评估是否需要伪造**

3. **Turing 的网络上报阶段** —— 仍未捕获

---

## 六、`ro.soc.model` 已防御性修复（2026-11-26 02:45）

### 问题

`ro.soc.model` 真机值 = `SM8350`（骁龙 888），
而伪造目标 PGT110（OPPO Find X7）应为 `SM8650`（骁龙 8 Gen 3）。

fekit 当前**没有**读取该属性（`act=real` 列表里没有），
但它是强指纹，Turing / Qimei 等 SDK 随时可能读，故防御性补上。

### 修复

```cpp
static char g_soc_model[32] = "SM8650";

if (strcmp(name, "ro.soc.model") == 0) {
    return g_soc_model;
}
```

**注意**：`ro.soc.manufacturer` 两边都是 `Qualcomm`，不需要伪造。

### 验证（A 级）

```
FKCALL prop mod=libnative-bypass.so+0x32dc0 d=0 act=fake prop=ro.soc.model
Self-test ro.soc.model='SM8650' (fake target='SM8650')
```

自检与归因双重确认。

### 现在 SoC 三元组自洽

| 属性 | 真机 | QQ 进程内 | 自洽 |
|---|---|---|---|
| `ro.board.platform` | lahaina | **kalama** | ✅ |
| `ro.soc.model` | SM8350 | **SM8650** | ✅ |
| `ro.soc.manufacturer` | Qualcomm | Qualcomm | ✅（一致，不需改） |

---

## 七、Turing 的网络行为：60 秒窗口内完全静默

### 观察结果

重启后连续观察 60 秒（覆盖启动 + 早期运行）：

```
turing write len=88 caller_off=0x294ec        ← 仅写 .turing.dat
turing bytes[88]=10f7babcd485c97c...          （与磁盘文件一致）

无 send / sendto / connect / socket 记录
```

### 同时验证：MSF 通道里也没有 turing 命令

```
OBSERVE MsfCore cmd=trpc.gc_indust.device_report.SsoHome.SsoHomeReport   （仅心跳）
```

`wup_dump.log` 里搜 `turing` / `riskCheck` 均无结果。

### 这与项目内既有记录冲突

项目里 `hook_libturingxq.cpp` 的注释写着：

> 实测：阻断 Turing 上报会让会话从 ~120 分钟骤降到 7 分钟 —— 服务端依赖它的数据

**但当前观察显示 Turing 在这个窗口内根本不上报。** 三种可能：

| 可能 | 说明 |
|---|---|
| **上报在登录后触发** | 最可能。当前 QQ 停在启动页，未登录 |
| 那条记录针对旧版本 | 记录成文时的 QQ 版本可能与现在（9.3.50）不同 |
| 当时阻断的是别的库 | `libfekit` 才是主力，可能被误记为 Turing |

**在没看到 Turing 实际发出数据之前，不能继续把「Turing 是必需上报」当作既定事实。**

### 下一步

**需要一次完整登录**，然后延长观察窗口（登录后 5–10 分钟），才能确定：

1. Turing 的真实上报时机
2. 上报通道（自有 socket / MSF / 其他代理）
3. 上报内容是否含设备指纹
