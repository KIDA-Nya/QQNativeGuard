#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""替换 native 层 JNI 函数名前缀：qqenhanced_bypass -> kida_qqnativeguard"""
import sys, re, glob, os

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = r'D:\app\QQNativeGuard\app\src\main\cpp'
PAT = re.compile(r'Java_io_github_qqenhanced_bypass_')

files = glob.glob(os.path.join(ROOT, '**', '*.cpp'), recursive=True) + \
        glob.glob(os.path.join(ROOT, '**', '*.h'), recursive=True)

total = 0
for p in files:
    s = open(p, encoding='utf-8', errors='replace').read()
    n = len(PAT.findall(s))
    if n:
        s = PAT.sub('Java_io_github_kida_qqnativeguard_', s)
        open(p, 'w', encoding='utf-8').write(s)
        print('%s: %d 处' % (os.path.basename(p), n))
        total += n

print()
print('total:', total)

# 验证 Java 侧声明
java_dir = r'D:\app\QQNativeGuard\app\src\main\java\io\github\kida\qqnativeguard'
for f in glob.glob(os.path.join(java_dir, '*.java')):
    s = open(f, encoding='utf-8', errors='replace').read()
    if 'system.loadLibrary' in s or 'native ' in s:
        print('Java:', os.path.basename(f))
        for m in re.finditer(r'private\s+(?:static\s+)?native\s+\w+\s+(\w+Native)\(', s):
            print('   native method:', m.group(1))
