#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""验证远端代码状态，必要时用 API 补推"""
import json, os, sys, base64, urllib.request, urllib.error

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

PAT = os.environ.get('GITHUB_TOKEN', '')
OWNER, REPO = 'KIDA-Nya', 'QQNativeGuard'
API = 'https://api.github.com'
H = {
    'Authorization': 'Bearer ' + PAT,
    'Accept': 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
    'User-Agent': 'python-urllib',
}


def req(url, data=None, method='GET'):
    body = json.dumps(data, ensure_ascii=False).encode('utf-8') if data is not None else None
    r = urllib.request.Request(url, data=body, method=method,
                               headers={**H, 'Content-Type': 'application/json; charset=utf-8'})
    with urllib.request.urlopen(r, timeout=120) as resp:
        return json.loads(resp.read().decode('utf-8'))


FILES = {
    'app/src/main/java/io/github/kida/qqnativeguard/hooks/RootDetectionHook.java':
        'feat(detection): File 12 methods + PackageManager 25+ + wlogin GUID probe',
    'app/src/main/java/io/github/kida/qqnativeguard/hooks/NetworkReportHook.java':
        'fix(report): remove wtlogin.device_lock and log_report from block list',
    'docs/EXTERNAL_RESEARCH.md':
        'docs: add external research findings',
}

print('=== 检查远端文件状态 ===')
for path, msg in FILES.items():
    local_path = os.path.join(r'D:\app\QQNativeGuard', path)
    if not os.path.exists(local_path):
        print('  本地缺失:', path)
        continue
    local = open(local_path, encoding='utf-8').read()
    try:
        cur = req('%s/repos/%s/%s/contents/%s' % (API, OWNER, REPO, path))
        remote = base64.b64decode(cur['content']).decode('utf-8')
        if remote == local:
            print('  ✓ 一致:', os.path.basename(path))
            continue
        print('  差异:', os.path.basename(path), '-> 补推')
        req('%s/repos/%s/%s/contents/%s' % (API, OWNER, REPO, path), {
            'message': msg,
            'content': base64.b64encode(local.encode('utf-8')).decode('ascii'),
            'sha': cur['sha'],
            'branch': 'main',
        }, method='PUT')
        print('    ✓ 已推送')
    except urllib.error.HTTPError as e:
        if e.code == 404:
            print('  远端缺失:', path, '-> 新建')
            try:
                req('%s/repos/%s/%s/contents/%s' % (API, OWNER, REPO, path), {
                    'message': msg,
                    'content': base64.b64encode(local.encode('utf-8')).decode('ascii'),
                    'branch': 'main',
                }, method='PUT')
                print('    ✓ 已创建')
            except Exception as e2:
                print('    ✗', str(e2)[:100])
        else:
            print('  HTTP', e.code, str(e)[:100])
    except Exception as e:
        print('  ERR:', type(e).__name__, str(e)[:100])

print()
print('=== 最终验证 ===')
try:
    c = req('%s/repos/%s/%s/contents/app/src/main/java/io/github/kida/qqnativeguard/hooks/RootDetectionHook.java' % (API, OWNER, REPO))
    t = base64.b64decode(c['content']).decode('utf-8')
    for k in ['12 methods covered', '25+ methods covered', 'hookWloginUtil', 'listFiles', 'isHidden']:
        print('  %s: %s' % ('OK ' if k in t else 'MISS', k))
except Exception as e:
    print('  ERR:', str(e)[:100])