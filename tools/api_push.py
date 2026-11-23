#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""通过 GitHub API 更新文件 + 创建 release（git 443 不通时的备选）"""
import os
import json, sys, base64, urllib.request, urllib.error

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
PAT = os.environ.get('GITHUB_TOKEN', '')
OWNER = 'KIDA-Nya'
REPO = 'QQNativeGuard'
API = 'https://api.github.com'
HDRS = {
    'Authorization': 'Bearer ' + PAT,
    'Accept': 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
    'User-Agent': 'python-urllib',
}


def req(url, data=None, method='GET'):
    body = json.dumps(data, ensure_ascii=False).encode('utf-8') if data is not None else None
    r = urllib.request.Request(url, data=body, method=method,
                               headers={**HDRS, 'Content-Type': 'application/json; charset=utf-8'})
    with urllib.request.urlopen(r, timeout=120) as resp:
        return json.loads(resp.read().decode('utf-8'))


# 1. 更新 README.md
print('=== 更新 README.md ===')
local = open(r'D:\app\QQNativeGuard\README.md', encoding='utf-8').read()

# 拿当前 sha
try:
    cur = req('%s/repos/%s/%s/contents/README.md' % (API, OWNER, REPO))
    sha = cur['sha']
    print('  current sha:', sha[:8])
    res = req('%s/repos/%s/%s/contents/README.md' % (API, OWNER, REPO), {
        'message': 'docs: add technical highlights section to README',
        'content': base64.b64encode(local.encode('utf-8')).decode('ascii'),
        'sha': sha,
        'branch': 'main',
    }, method='PUT')
    print('  updated, new sha:', res['content']['sha'][:8])
except urllib.error.HTTPError as e:
    print('  HTTP', e.code, e.read().decode('utf-8', 'replace')[:400])

# 2. 创建 Release + tag
print()
print('=== 创建 Release v1.0.0 ===')
release_body = '''Native-layer environment detection bypass for QQ, built on
[Xalsace/QQEnhancedBypass](https://github.com/Xalsace/QQEnhancedBypass) (GPL-3.0).

## 技术亮点

- **设备信息只走 4 个通道**（实测 3 小时抓包）：HTTP UA / QsecEst / MSF / VOD_MSDK。其余 10+ SDK 虽各自出网，但不上报新的设备维度。
- **`http.agent` 的 zygote 静态缓存**：AOSP `RuntimeInit` 在模块加载前就把 UA 算好，改 `Build.MODEL` 对它无效。实测 141/170 次请求泄露真机机型。
- **SoC 平台代号矛盾**：`k54=lahaina`（骁龙888）与任意非 SM8350 机型物理上不可能共存。这解释了"换全套指纹仍不触发新设备验证"。
- **`__system_property_find` 是隐藏的第二入口**：PCDN/VOD 走 find + read_callback，只 hook get 会漏。
- **Turing 走裸 syscall**：`.real_syscall` 绕过 libc，只能在 deflate 层注入。
- **观测模式**：拦截本身才是异常信号，所以提供 `NETWORK_OBSERVE_ONLY`。
- **负面结果同样记录**：三条加密通道都不可离线解；132,804 候选 appId 爆破 0 命中。
- **账号维度不可解**：同一环境，新号 9+h，已标记号 2h36m → 20min → 3min → w21。

## 能力

- ByteHook native hook 层，30+ hook 点
- 系统属性三入口（get / read_callback / find）
- 内容级 `/proc` 与 `/sys` 文件伪造（9 种）
- SoC 平台标识伪造（`ro.board.platform` + `/proc/cpuinfo`）
- `http.agent` 运行时重建
- 观测/拦截分离
- Turing deflate 层明文注入

## 验证

真机通过：Vector 正常加载，`All Native Hooks Installed Successfully`，QQ 零崩溃。

## 已知问题

MMKV hook 会导致 QQ 崩溃循环（5-6 秒一次），默认禁用。

## 许可证

GPL-3.0，继承上游。版权与修改声明见 NOTICE。
'''

try:
    rel = req('%s/repos/%s/%s/releases' % (API, OWNER, REPO), {
        'tag_name': 'v1.0.0',
        'target_commitish': 'main',
        'name': 'QQ Native Guard v1.0.0',
        'body': release_body,
        'draft': False,
        'prerelease': False,
    }, method='POST')
    print('  Release 创建成功!')
    print('  url:', rel['html_url'])
    print('  tag:', rel['tag_name'])
except urllib.error.HTTPError as e:
    body = e.read().decode('utf-8', 'replace')
    if e.code == 422 and 'already_exists' in body:
        print('  Release 已存在')
    else:
        print('  HTTP', e.code, body[:500])