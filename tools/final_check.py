#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""最终确认远端状态（带重试）"""
import json, os, sys, base64, time, urllib.request, urllib.error

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


def fetch(url, retries=5):
    for i in range(retries):
        try:
            r = urllib.request.Request(url, headers=H)
            with urllib.request.urlopen(r, timeout=60) as resp:
                return resp.read()
        except Exception as e:
            if i < retries - 1:
                time.sleep(3)
                continue
            raise


def check(path, keywords):
    try:
        raw = fetch('%s/repos/%s/%s/contents/%s' % (API, OWNER, REPO, path))
        d = json.loads(raw.decode('utf-8'))
        t = base64.b64decode(d['content']).decode('utf-8')
        print('%-50s' % os.path.basename(path))
        for k in keywords:
            print('    %s %s' % ('OK ' if k in t else 'MISS', k))
        return all(k in t for k in keywords)
    except Exception as e:
        print('%-50s ERR %s' % (os.path.basename(path), str(e)[:60]))
        return False


print('=== 远端代码验证 ===')
ok1 = check('app/src/main/java/io/github/kida/qqnativeguard/hooks/RootDetectionHook.java',
            ['12 methods covered', '25+ methods covered', 'hookWloginUtil', 'listFiles'])
ok2 = check('app/src/main/java/io/github/kida/qqnativeguard/hooks/NetworkReportHook.java',
            ['登录校验类命令', '纯登录日志'])
ok3 = check('docs/EXTERNAL_RESEARCH.md',
            ['wtlogin.device_lock', 'RiskType', 'libnative-memory-library'])

print()
print('=== Releases / Tags ===')
try:
    rs = json.loads(fetch('%s/repos/%s/%s/releases' % (API, OWNER, REPO)).decode('utf-8'))
    print('  Releases: %s' % (', '.join(r['tag_name'] for r in rs) if rs else '无'))
except Exception as e:
    print('  ERR', str(e)[:60])
try:
    ts = json.loads(fetch('%s/repos/%s/%s/tags' % (API, OWNER, REPO)).decode('utf-8'))
    print('  Tags: %s' % ', '.join(t['name'] for t in ts))
except Exception as e:
    print('  ERR', str(e)[:60])

print()
print('=== 总结 ===')
print('  代码推送: %s' % ('✅ 完整' if (ok1 and ok2 and ok3) else '⚠️ 部分缺失'))