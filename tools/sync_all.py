#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""git 不通时，把未推送 commit 涉及的文件全部用 API 同步到 GitHub"""
import json, os, sys, base64, time, subprocess, urllib.request, urllib.error

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

PAT = os.environ.get('GITHUB_TOKEN', '')
if not PAT:
    print('缺少 GITHUB_TOKEN')
    sys.exit(1)

O, R = 'KIDA-Nya', 'QQNativeGuard'
API = 'https://api.github.com'
REPO = r'D:\app\QQNativeGuard'
H = {
    'Authorization': 'Bearer ' + PAT,
    'Accept': 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
    'User-Agent': 'python-urllib',
}


def req(url, data=None, method='GET', retries=5):
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
                time.sleep(5); continue
            raise
        except Exception:
            if i < retries - 1:
                time.sleep(5); continue
            raise


# 取未推送 commit 涉及的所有文件
r = subprocess.run(['git', 'log', 'origin/main..HEAD', '--name-only', '--pretty=format:'],
                   cwd=REPO, capture_output=True)
files = sorted({l.strip() for l in r.stdout.decode('utf-8', 'replace').split('\n')
                if l.strip() and not l.strip().startswith('.git')})
print('待同步文件: %d 个' % len(files))
print()

pushed = same = fail = 0
for path in files:
    local = os.path.join(REPO, path)
    if not os.path.exists(local):
        print('  - 本地已删除，跳过: %s' % path)
        continue
    try:
        content = open(local, encoding='utf-8').read()
    except UnicodeDecodeError:
        print('  - 非文本，跳过: %s' % path)
        continue

    try:
        cur = req('%s/repos/%s/%s/contents/%s' % (API, O, R, path))
        remote = base64.b64decode(cur['content']).decode('utf-8')
        if remote == content:
            same += 1
            continue
        req('%s/repos/%s/%s/contents/%s' % (API, O, R, path), {
            'message': 'sync ' + os.path.basename(path),
            'content': base64.b64encode(content.encode('utf-8')).decode('ascii'),
            'sha': cur['sha'],
            'branch': 'main',
        }, method='PUT')
        print('  ✓ 更新 %s' % path)
        pushed += 1
    except urllib.error.HTTPError as e:
        if e.code == 404:
            try:
                req('%s/repos/%s/%s/contents/%s' % (API, O, R, path), {
                    'message': 'add ' + os.path.basename(path),
                    'content': base64.b64encode(content.encode('utf-8')).decode('ascii'),
                    'branch': 'main',
                }, method='PUT')
                print('  ✓ 新建 %s' % path)
                pushed += 1
            except Exception as e2:
                print('  ✗ %s (%s)' % (path, str(e2)[:40]))
                fail += 1
        else:
            print('  ✗ %s (HTTP %d)' % (path, e.code))
            fail += 1
    except Exception as e:
        print('  ✗ %s (%s)' % (path, str(e)[:40]))
        fail += 1

print()
print('推送 %d / 已同步 %d / 失败 %d' % (pushed, same, fail))
