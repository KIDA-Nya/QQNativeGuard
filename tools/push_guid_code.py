#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""确保 RootDetectionHook 和 HookConfig 的 GUID 改动推到远端"""
import json, os, sys, base64, time, urllib.request, urllib.error

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

PAT = os.environ.get('GITHUB_TOKEN', '')
O, R = 'KIDA-Nya', 'QQNativeGuard'
API = 'https://api.github.com'
H = {
    'Authorization': 'Bearer ' + PAT,
    'Accept': 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
    'User-Agent': 'python-urllib',
}


def req(url, data=None, method='GET', retries=4):
    body = json.dumps(data, ensure_ascii=False).encode('utf-8') if data is not None else None
    for i in range(retries):
        try:
            r = urllib.request.Request(url, data=body, method=method,
                                       headers={**H, 'Content-Type': 'application/json; charset=utf-8'})
            with urllib.request.urlopen(r, timeout=90) as resp:
                return json.loads(resp.read().decode('utf-8'))
        except urllib.error.HTTPError as e:
            if e.code in (404, 422):
                raise
            if i < retries - 1:
                time.sleep(4)
                continue
            raise
        except Exception:
            if i < retries - 1:
                time.sleep(4)
                continue
            raise


FILES = [
    ('app/src/main/java/io/github/kida/qqnativeguard/hooks/RootDetectionHook.java',
     'feat(wlogin): hook generateGuid to return the configured fake GUID',
     ['FAKE_WLOGIN_GUID', 'hexToBytes']),
    ('app/src/main/java/io/github/kida/qqnativeguard/config/HookConfig.java',
     'feat(config): add FAKE_WLOGIN_GUID',
     ['FAKE_WLOGIN_GUID']),
]

for path, msg, keys in FILES:
    local = os.path.join(r'D:\app\QQNativeGuard', path)
    content = open(local, encoding='utf-8').read()
    name = os.path.basename(path)
    # 先检查远端当前状态
    try:
        cur = req('%s/repos/%s/%s/contents/%s' % (API, O, R, path))
        remote = base64.b64decode(cur['content']).decode('utf-8')
        missing = [k for k in keys if k not in remote]
        if not missing and remote == content:
            print('%-28s ✓ 已是最新（%d 字节）' % (name, len(remote)))
            continue
        print('%-28s 需更新（远端缺 %s）' % (name, missing or '内容不同'))
        req('%s/repos/%s/%s/contents/%s' % (API, O, R, path), {
            'message': msg,
            'content': base64.b64encode(content.encode('utf-8')).decode('ascii'),
            'sha': cur['sha'],
            'branch': 'main',
        }, method='PUT')
        print('%-28s ✓ 已推送' % name)
    except urllib.error.HTTPError as e:
        print('%-28s ✗ HTTP %d' % (name, e.code))
    except Exception as e:
        print('%-28s ✗ %s' % (name, str(e)[:50]))

# 最终验证
print()
print('=== 最终验证 ===')
for path, _, keys in FILES:
    name = os.path.basename(path)
    try:
        c = req('%s/repos/%s/%s/contents/%s' % (API, O, R, path))
        t = base64.b64decode(c['content']).decode('utf-8')
        res = ['%s=%s' % (k, 'OK' if k in t else 'MISS') for k in keys]
        print('  %-28s %s' % (name, ', '.join(res)))
    except Exception as e:
        print('  %-28s ✗ %s' % (name, str(e)[:40]))