# 环境上报面完整排查（2026-11-24）

> 回答：除了设备身份，还有哪些"注入/修改/root"痕迹会被服务端看到？

## 核心结论

**服务端判定"设备被动过手脚"依赖的不是单一信号，而是多层信号的一致性。**

客户端把这些信号分成 5 类上报：

| 类别 | 信号源 | QQ 能看到的接口 | 我们是否覆盖 |
|---|---|---|---|
| **A. 系统属性** | `__system_property_get` | 67 个属性已伪造 | ⚠️ 有缺口 |
| **B. 文件系统** | `fopen` / `stat` / `access` | `/data/adb` 等已隐藏 | ✅ 完整 |
| **C. `/proc` 接口** | `open` / `fread` | cmdline 已净化 | ⚠️ **刚补全** |
| **D. 进程列表** | `/proc/*/cmdline` | 未覆盖 | ❌ **缺口** |
| **E. maps 自身痕迹** | `/proc/self/maps` | 行过滤 | ⚠️ 部分 |
| **F. 内核信息** | `uname` / `/proc/version` | 已伪造 | ✅ |
| **G. 包名扫描** | `PackageManager` | 已隐藏 | ⚠️ 部分 |

---

## 详细排查结果

### A. 系统属性 —— 67 项已覆盖，3 项缺口

**已覆盖（root/解锁关键）**：
```
ro.boot.verifiedbootstate      → green
ro.boot.flash.locked           → 1
ro.boot.vbmeta.device_state    → locked
ro.boot.veritymode             → enforcing
ro.boot.warranty_bit           → 0
ro.warranty_bit                → 0
ro.vendor.warranty_bit         → 0
ro.debuggable                  → 0
ro.secure                      → 1
ro.boot.selinux                → enforcing
ro.kernel.qemu                 → ""
```

**❌ 缺口（本次发现）**：
```
ro.build.type                  → 真值 user（恰好正确，但未显式覆盖）
ro.build.tags                  → 真值 release-keys（恰好正确）
ro.boot.secboot                → 真值 enabled（未覆盖）
ro.boot.rpmb_enable            → 真值 true（未覆盖）
ro.adb.secure                  → 真值 1（未覆盖）
persist.sys.usb.config         → 真值 mtp（未覆盖）
```

**评估**：这几项真值本身是"正常"的，所以**当前不构成暴露**。但需要显式固定，防止未来设备状态变化。

### B. 文件系统 —— 完整

```cpp
is_hidden_path() 覆盖：
  /data/adb, magisk, kernelsu, lsposed, xposed, frida
  /system/bin/su, /system/xbin/su, /sbin/su, superuser
  busybox, apatch, /data/local/tmp/lsp, re.frida.server
→ 返回 ENOENT
```

**评估**：✅ 完整。

### C. `/proc/cmdline` —— ★ 本次发现严重缺口，已补

**真实暴露（未覆盖前）**：
```
androidboot.verifiedbootstate=orange       ← 解锁铁证
androidboot.project_codename=lemonadep     ← ★★ OnePlus 9 Pro 代号！
androidboot.platform_name=SM8350           ← ★ SM8350 真机平台
androidboot.type=nonrelease                ← 非发布版
androidboot.bootreason=reboot,PMIC_WD      ← 看门狗重启（异常）
androidboot.prjname=19815                  ← 真机项目号
androidboot.serialno=<real-serial>              ← 真机序列号
```

**`project_codename=lemonadep` 是最致命的**：`lemonadep` 是 OnePlus 9 Pro 的内部代号，与伪造的 `PGT110`（OPPO Find X7）**直接矛盾**。

**已补全覆盖**（新增 24 个 token）：
```
project_codename → pgem10
platform_name    → SM8650
prjname          → 0
hw_version/rf_version/prj_version → 0
platform_id      → 0
bootcount / power_cut_test / cust → ""
startupmode      → reboot
type             → release
bootreason       → reboot
...
```

### D. 进程列表 —— ❌ 缺口

**QQ 可以扫 `/proc/*/cmdline` 看到**：
```
magiskd / ksud / apd    ← root 管理进程
```

**当前未覆盖。**

**风险等级**：中。多数 App 会扫，但需要 root 权限才能看到其他进程的 cmdline。QQ 有 root（我们给的），所以能看到。

### E. maps 自身痕迹 —— 部分覆盖

```cpp
kBlacklist[] = {
  "lsposed", "lspd", "xposed", "edxposed", "riru", "zygisk", "susfs",
  "kernelsu", "apatch", "magisk", "frida", "substrate", "bytehook",
  "qqnativeguard", "native-bypass", "qfun", "sandhook", "lspatch",
  "libwhale", "libkpatch", "data/adb"
}
→ 行级过滤
```

**评估**：
- ✅ 覆盖常见框架名
- ⚠️ **但只过滤 `/proc/self/maps`**，如果 QQ 读 `/proc/<pid>/maps`（别的进程）会漏
- ⚠️ smaps 已覆盖，但 `/proc/self/task/*/maps` 未覆盖

### F. 内核信息 —— 完整

```
uname release/version → 伪造
/proc/version         → 伪造
/proc/cpuinfo Hardware → 伪造（本次新增）
```

### G. 包名扫描 —— 部分

**已隐藏**（`pm list packages` 查不到 Root 管理器），但**我们的模块包名本身可见**：
```
io.github.kida.qqnativeguard  ← 在包列表里！
```

**风险**：QQ 的 `PackageManager` 扫描如果包含"未知模块包"检测，会看到我们的模块。

---

## 优先级排序（按风险）

| # | 缺口 | 风险 | 状态 |
|---|---|---|---|
| 1 | **`/proc/cmdline` 的 `project_codename=lemonadep`** | ★★★ | ✅ **已补** |
| 2 | **`/proc/cmdline` 的 `platform_name=SM8350`** | ★★★ | ✅ **已补** |
| 3 | **`bootreason=reboot,PMIC_WD`（看门狗）** | ★★ | ✅ 已补 |
| 4 | **进程列表里的 magiskd/ksud** | ★★ | ❌ 未覆盖 |
| 5 | **我们的模块包名可见** | ★★ | ❌ 未覆盖 |
| 6 | `/proc/<other_pid>/maps` | ★ | ❌ 未覆盖 |
| 7 | 属性 `secboot`/`rpmb_enable`/`adb.secure` 未显式固定 | ★ | ⚠️ 真值正常 |

---

## 关键洞察

**为什么"只改设备身份"不够**：

```
服务端看到的完整画面（改身份前）：
  上报: model=PGT110, fingerprint=OPPO/PGT110/...
  但 cmdline 说: project_codename=lemonadep（OnePlus 9 Pro）
                platform_name=SM8350
  但进程列表说: magiskd 在跑
  但包列表说: io.github.kida.qqnativeguard 装着
               
→ 服务端：这台设备声称是 OPPO Find X7，但处处是 OnePlus 9 Pro + root 的痕迹
→ 判定：设备特征被篡改 + 环境不可信
```

**改身份只解决了"它叫什么"，没解决"它被动过手脚"。**

服务端要的是**完整性 attestation**：所有信号必须指向同一台"没被动过的正常设备"。

---

## 下一步

1. **验证 cmdline 补丁生效**（构建 + 装机 + 抓包对比）
2. **补进程列表过滤**（`/proc/*/cmdline` 读 magiskd/ksud 时返回伪造）
3. **隐藏模块包名**（`PackageManager` 层）
4. **补 `/proc/<pid>/maps`**（不只 self）
5. **显式固定剩余属性**（secboot/rpmb_enable/adb.secure/usb.config）

**但要注意：这些全部做完，仍然改变不了"账号评分"在服务端累积的事实。** 它们能保证"设备侧干净"，让新账号长期在线；对已标记账号无效。