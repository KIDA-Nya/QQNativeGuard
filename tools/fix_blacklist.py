#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""替换 native 层黑名单里的旧模块名"""
import sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

p = r'D:\app\QQNativeGuard\app\src\main\cpp\hooks\hook_libc.cpp'
s = open(p, encoding='utf-8', errors='replace').read()

old = '"qqenhanced", "native-bypass"'
new = '"qqnativeguard", "native-bypass"'

if old in s:
    s = s.replace(old, new)
    open(p, 'w', encoding='utf-8').write(s)
    print('replaced')
else:
    print('pattern not found, checking...')
    for line in s.split(chr(10)):
        if 'qqenhanced' in line.lower():
            print('  found:', line.strip()[:100])