#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把项目配置中的模块标识从 QQEnhancedBypass 改为 QQNativeGuard"""
import sys, os, re, glob

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = r'D:\app\QQNativeGuard'

TARGETS = [
    os.path.join(ROOT, 'settings.gradle'),
    os.path.join(ROOT, 'app', 'build.gradle'),
    os.path.join(ROOT, 'app', 'src', 'main', 'AndroidManifest.xml'),
]

REPL = [
    ('io.github.kida.qqnativeguard', 'io.github.kida.qqnativeguard'),  # 已是新包名
    ('QQEnhancedBypass', 'QQNativeGuard'),
    ('qqenhanced.bypass', 'kida.qqnativeguard'),
    ('qq-enhanced-bypass', 'qq-native-guard'),
    ('qqbypass', 'qqguard'),
]

for p in TARGETS:
    if not os.path.exists(p):
        print('SKIP (missing):', p)
        continue
    try:
        s = open(p, encoding='utf-8', errors='replace').read()
    except Exception as e:
        print('ERR read', p, e)
        continue
    orig = s
    for a, b in REPL:
        s = s.replace(a, b)
    if s != orig:
        open(p, 'w', encoding='utf-8').write(s)
        print('updated:', os.path.basename(p))
    else:
        print('no change:', os.path.basename(p))

# 检查 native 层引用旧包名的地方
print()
print('=== 检查 native 层旧包名引用 ===')
for p in glob.glob(os.path.join(ROOT, 'app', 'src', 'main', 'cpp', '**', '*.cpp'), recursive=True):
    s = open(p, encoding='utf-8', errors='replace').read()
    if 'qqenhanced' in s.lower() or 'QQEnhancedBypass' in s:
        cnt = s.lower().count('qqenhanced')
        print('  %s: %d 处' % (os.path.basename(p), cnt))