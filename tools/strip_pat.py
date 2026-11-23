#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""移除工具脚本中硬编码的 GitHub PAT，改为环境变量读取"""
import sys, re, os

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

FILES = [
    r'D:\app\QQNativeGuard\tools\api_push.py',
    r'D:\app\QQNativeGuard\tools\create_repo.py',
]

PAT_AI = re.compile(r"^\s*PAT\s*=\s*['\"](?:github_pat_|ghp_)[^'\"]+['\"]", re.MULTILINE)
REPL = "PAT = os.environ.get('GITHUB_TOKEN', '')"

for p in FILES:
    if not os.path.exists(p):
        print('skip', p)
        continue
    s = open(p, encoding='utf-8', errors='replace').read()
    before = 'github_pat_' in s or 'ghp_' in s
    s = PAT_AI.sub(REPL, s)

    # 确保 import os
    if re.search(r'^import os\b', s, re.MULTILINE) is None:
        # 在第一个 import 行前加
        m = re.search(r'^import ', s, re.MULTILINE)
        if m:
            s = s[:m.start()] + 'import os\n' + s[m.start():]
        else:
            s = 'import os\n' + s

    open(p, 'w', encoding='utf-8', newline='\n').write(s)
    after = 'github_pat_' in s or 'ghp_' in s
    print('%s: had PAT=%s -> now=%s' % (os.path.basename(p), before, after))