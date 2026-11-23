#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""移除所有 Java 源文件的 UTF-8 BOM（PowerShell Set-Content 会加 BOM）"""
import sys, glob, os

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = r'D:\app\QQNativeGuard\app\src\main\java'
BOM = b'\xef\xbb\xbf'

fixed = 0
for p in glob.glob(os.path.join(ROOT, '**', '*.java'), recursive=True):
    raw = open(p, 'rb').read()
    if raw.startswith(BOM):
        open(p, 'wb').write(raw[3:])
        fixed += 1
        print('BOM removed:', os.path.basename(p))

print()
print('fixed: %d files' % fixed)