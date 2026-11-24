# ★ k54 破绽的真正根因（2026-11-24 13:47）

## 发现

解密最新 QsecEst（`k16=<ts>`，13:37 登录）后发现：

```
k54 = 'lahaina'     ← 仍是真机值！
```

**但模块自检显示 `ro.board.platform='kalama'`（已伪装）。**

## 根因：hook 错了属性名

**T9 文档早就写明了字段映射**（我之前没有核对）：

| 字段 | 真机值 | **来源属性** | 我之前的处理 |
|---|---|---|---|
| k53 | OnePlus | `ro.product.manufacturer` | ✅ 已覆盖 |
| **k54** | **lahaina** | **`ro.product.board`** | ❌ **完全没覆盖！** |
| k55 | user | `ro.build.type` | ✅ |
| k57 | LE2123 | `ro.product.model` | ✅ |
| k58 | qcom | `ro.hardware` | ✅ |
| k59 | OnePlus9Pro | `ro.build.product` | ✅ |

**我之前 hook 的是 `ro.board.platform`**（值也是 lahaina，看起来"对"），**但 fekit 读的是 `ro.product.board`** —— 两个是不同的属性！

```bash
# 真机验证：两个属性都存在，值都是 lahaina
getprop ro.product.board    → lahaina
getprop ro.board.platform   → lahaina
```

**这就是为什么"自检显示已伪装，实际上报仍是真值"** —— 自检读的是我 hook 的那个属性，上报读的是另一个。

## 验证 fekit 的读取方式

```
libfekit.so 导入符号：
  __system_property_get            YES
  __system_property_find           no
  __system_property_read_callback  no
```

**fekit 走 `__system_property_get`**，我们确实 hook 了这个函数 —— 所以只要属性名对，就能拦住。

## 修复

补上 `ro.product.board` 全系变体：

```cpp
if (strcmp(name, "ro.product.board") == 0 ||
    strcmp(name, "ro.product.vendor.board") == 0 ||
    strcmp(name, "ro.product.system.board") == 0 ||
    strcmp(name, "ro.product.odm.board") == 0 ||
    strcmp(name, "ro.product.product.board") == 0 ||
    strcmp(name, "ro.board.platform") == 0 ||
    strcmp(name, "ro.chipname") == 0 ||
    strcmp(name, "ro.hardware.chipname") == 0) {
    return "kalama";
}
```

**验证**（模块自检）：
```
Self-test ro.board.platform='kalama'  ✅
Self-test ro.product.board='kalama'   ✅ ← 新增
```

## 教训

**必须用 T9 文档的字段映射表逐项核对**，而不是凭属性名"看起来像"就 hook。

85 个字段里只有 9 个是真设备特征，每一个都要精确定位来源属性：

| 字段 | 来源属性 | 状态 |
|---|---|---|
| k53 | ro.product.manufacturer | ✅ |
| **k54** | **ro.product.board** | ✅ **本次修复** |
| k55 | ro.build.type | ✅ |
| k56 | ro.build.host | ✅（保留真机 ReVork） |
| k57 | ro.product.model | ✅ |
| k58 | ro.hardware | ✅ |
| k59 | ro.build.product | ✅ |
| k60 | ro.build.fingerprint | ✅ |
| k61 | ro.build.id | ✅ |
| k62 | ro.build.tags | ✅ |
| k63 | ro.product.brand | ✅ |
| k64 | ro.product.device | ✅ |
| k65 | ro.build.user | ✅（保留真机） |
| k66 | ro.build.display.id | ✅ |
| k69 | CPU ABI | ✅ |
| k84 | SoC serial | ✅ |

## 待验证

**需要一次完整登录**才能产生新 QsecEst，确认 k54 变成 `kalama`。

当前是免密登录（进程自动恢复），不触发完整上报流程。

## 这次会话的状态（13:47）

- 13:37 登录成功，直接进主界面
- **KICK=0，RISK-OBSERVE 无输出** → 服务端未下发风险判定
- QsecEst 上报 6 次，k54 仍是 lahaina（修复前的数据）
- 修复已应用，待下次完整登录验证