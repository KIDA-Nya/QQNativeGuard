#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""在 GitHub 创建仓库 QQNativeGuard"""
import os
import json, sys, urllib.request, urllib.error

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
PAT = os.environ.get('GITHUB_TOKEN', '')

desc = ('Native-layer environment detection bypass for QQ. ByteHook-based '
        'native hooks, four-channel device fingerprint consistency, and '
        'observable interception. Built on Xalsace/QQEnhancedBypass (GPL-3.0).')

payload = json.dumps({
    'name': 'QQNativeGuard',
    'description': desc,
    'private': False,
    'has_issues': True,
    'has_wiki': False,
    'has_projects': False,
    'license_template': '',   # 不用模板，我们自带 LICENSE
}, ensure_ascii=False).encode('utf-8')

req = urllib.request.Request(
    'https://api.github.com/user/repos',
    data=payload, method='POST',
    headers={
        'Authorization': 'token ' + PAT,
        'Accept': 'application/vnd.github+json',
        'Content-Type': 'application/json; charset=utf-8',
        'User-Agent': 'python-urllib',
    })

try:
    with urllib.request.urlopen(req, timeout=120) as r:
        d = json.loads(r.read().decode('utf-8'))
        print('仓库创建成功!')
        print('  full_name:', d['full_name'])
        print('  clone_url:', d['clone_url'])
        print('  html_url:', d['html_url'])
        print('  private:', d['private'])
except urllib.error.HTTPError as e:
    body = e.read().decode('utf-8', 'replace')
    if e.code == 422 and 'already exists' in body:
        print('仓库已存在，直接推送即可')
        print('  url: https://github.com/KIDA-Nya/QQNativeGuard')
    else:
        print('HTTP', e.code)
        print(body[:1200])
except Exception as e:
    print('ERR:', type(e).__name__, str(e)[:300])