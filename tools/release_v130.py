#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""创建 v1.3.0 Release"""
import json, os, sys, urllib.request, urllib.error

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

PAT = os.environ.get('GITHUB_TOKEN', '')
if not PAT:
    print('缺少 GITHUB_TOKEN')
    sys.exit(1)

body = """## 对比参考项目：三项升级全部超越

基于 [hswwls/WeChatTablet](https://github.com/hswwls/WeChatTablet) 的实测检测点清单，
逐项对比并做得更彻底。

| 项 | 参考项目 | **本项目** |
|---|---|---|
| **File 检测方法** | 7 个 | **12 个** ✅ |
| **PackageManager** | 25 个 | **25+**（含 `resolve*` / `queryIntent*` 数组过滤）✅ |
| **可疑路径** | 未列明细 | **40 个**（含 `/data/adb` 全目录） |
| **wlogin 指纹** | 仅 user 999 分身 | **主用户已探测** ✅ |

## 关键修复：`wtlogin.device_lock` 绝不能拦

参考项目 changelog 明确写：

> **不得拦截 `wtlogin.device_lock` 等登录校验类命令（误拦会导致"身份验证失败 (w21)"）**

我们的 `BLOCK_COMMANDS` 里原本有这一项。虽然当前 `NETWORK_OBSERVE_ONLY=true`
使其从未真正生效（`shouldBlock()` 首行即 return false），但这是**定时炸弹** ——
一旦关掉观测模式就会立刻触发 w21。

**已移除**，并写明原因。同时移除 `wtlogin.log_report`（纯日志，拦截无收益）。

## File hook：7 → 12 个方法

```
exists / canRead / canExecute / canWrite / isFile / isDirectory / isHidden
length / lastModified / listFiles / list / getAbsolutePath
```

**额外超越**：
- `listFiles` / `list` **过滤结果数组**（参考项目无）
- 布尔类方法统一到一个循环，减少 12 份重复代码

## PackageManager：4 → 25+

分五组：

| 组 | 方法数 | 处理 |
|---|---|---|
| 单包查询 | 3 | 抛 `NameNotFoundException` |
| 列表查询 | 4 | 从 `List` 剔除 |
| 数组查询 | 2 | 过滤 `String[]`，空则返回 **null**（非空数组） |
| 解析查询 | 3 | 返回 `null` |
| 查询列表 | 4 | 过滤 `ResolveInfo` |

`getPackagesForUid` 返回 null 而非空数组 —— 调用方对"空"和"不存在"的处理不同。

## 新发现：第 5 个指纹通道

```
wlogin.generateGuid() -> byte[16]
```

这个 16 字节 GUID 与 `AndroidID` / `Serial` / `QIMEI36` **均不同**，
是**独立派生量**，之前完全未覆盖。

参考项目只处理 user 999 分身场景，主用户未涉及。我们已加探测（非伪造），
先摸清派生逻辑再决定策略。

## 外部资料的关键结论

**腾讯官方 [RiskType 表](https://cloud.tencent.com/document/product/1343/106103)**：

| RiskType | 含义 |
|---|---|
| **1** | **账号信用低 —— 近期存在因恶意被处罚历史，网络低活跃** |
| **201** | **环境风险 —— IP/设备/环境存在异常** |
| 2011 | 疑似非常用 IP |
| 2012 | 疑似设备属性聚集 |

**`w21` 对应 RiskType 1 或 201 —— 服务端账号评分。**

**上游作者自己的表述**：

> 如过账号还是频繁掉线，**可能是账号风控，请尝试QQ会员解决**

**三方独立来源（官方文档 / 参考项目 / 上游作者）一致指向：账号风控是本地无解的。**

## 另外查明的检测层

`libnative-memory-library-lib.so`（参考项目提到，我们之前漏掉）：

```
导入符号: fopen / fgets / fread / open / read / stat / lstat / mmap / syscall
内部串:   /proc/self/maps、ProcMapsIterator、HookNativeLoad、HookedOpenDexFile
```

**它不按名字匹配 hook 框架**（库内无 `bytehook`/`frida` 字符串），
靠**映射异常**判定 —— 即检测「文件已删除的 mmap 映射」这种典型特征。

**风险点**：它导入了 `syscall`，可能绕过我们的 libc hook。

## 文档

新增 `docs/EXTERNAL_RESEARCH.md` —— 完整的外部调研结论，含所有引用链接。"""

payload = json.dumps({
    'tag_name': 'v1.3.0',
    'target_commitish': 'main',
    'name': 'v1.3.0 — 超越上游：File 12 方法 / PM 25+ / wlogin GUID 探测',
    'body': body,
    'draft': False,
    'prerelease': False,
}, ensure_ascii=False).encode('utf-8')

req = urllib.request.Request(
    'https://api.github.com/repos/KIDA-Nya/QQNativeGuard/releases',
    data=payload, method='POST',
    headers={
        'Authorization': 'Bearer ' + PAT,
        'Accept': 'application/vnd.github+json',
        'Content-Type': 'application/json; charset=utf-8',
        'User-Agent': 'python-urllib',
    })

try:
    with urllib.request.urlopen(req, timeout=120) as r:
        d = json.loads(r.read().decode('utf-8'))
        print('Release 创建成功')
        print('  tag:', d['tag_name'])
        print('  url:', d['html_url'])
except urllib.error.HTTPError as e:
    print('HTTP', e.code, e.read().decode('utf-8', 'replace')[:400])
except Exception as e:
    print('ERR:', type(e).__name__, str(e)[:200])