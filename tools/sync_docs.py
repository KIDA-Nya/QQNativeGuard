#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""扫描本地 docs/*.md，把远端缺失或内容不同的全部同步"""
import json, os, sys, base64, time, glob, urllib.request, urllib.error

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

PAT = os.environ.get('GITHUB_TOKEN', '')
if not PAT:
    print('缺少 GITHUB_TOKEN')
    sys.exit(1)

O, R = 'KIDA-Nya', 'QQNativeGuard'
API = 'https://api.github.com'
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


LOCAL = r'D:\app\QQNativeGuard\docs'
files = sorted(glob.glob(os.path.join(LOCAL, '*.md')))
print('本地 docs 文档: %d 个' % len(files))

pushed = 0
same = 0
fail = 0

for lp in files:
    name = os.path.basename(lp)
    path = 'docs/' + name
    content = open(lp, encoding='utf-8').read()
    try:
        cur = req('%s/repos/%s/%s/contents/%s' % (API, O, R, path))
        remote = base64.b64decode(cur['content']).decode('utf-8')
        if remote == content:
            same += 1
            continue
        req('%s/repos/%s/%s/contents/%s' % (API, O, R, path), {
            'message': 'docs: sync ' + name,
            'content': base64.b64encode(content.encode('utf-8')).decode('ascii'),
            'sha': cur['sha'],
            'branch': 'main',
        }, method='PUT')
        print('  ✓ 更新 %s' % name)
        pushed += 1
    except urllib.error.HTTPError as e:
        if e.code == 404:
            try:
                req('%s/repos/%s/%s/contents/%s' % (API, O, R, path), {
                    'message': 'docs: add ' + name,
                    'content': base64.b64encode(content.encode('utf-8')).decode('ascii'),
                    'branch': 'main',
                }, method='PUT')
                print('  ✓ 新建 %s' % name)
                pushed += 1
            except Exception as e2:
                print('  ✗ %s (%s)' % (name, str(e2)[:40]))
                fail += 1
        else:
            print('  ✗ %s (HTTP %d)' % (name, e.code))
            fail += 1
    except Exception as e:
        print('  ✗ %s (%s)' % (name, str(e)[:40]))
        fail += 1

print()
print('推送 %d / 已同步 %d / 失败 %d' % (pushed, same, fail))

# 最终确认
print()
print('=== 远端 docs 最终状态 ===')
try:
    c = req('%s/repos/%s/%s/contents/docs' % (API, O, R))
    names = sorted(x['name'] for x in c)
    print('  远端文件数: %d' % len(names))
    local_names = sorted(os.path.basename(f) for f in files)
    missing = [n for n in local_names if n not in names]
    if missing:
        print('  仍缺失: %s' % ', '.join(missing))
    else:
        print('  ✓ 本地全部已同步')
except Exception as e:
    print('  验证失败:', str(e)[:60])