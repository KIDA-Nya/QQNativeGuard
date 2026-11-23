#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""修复 res/strings.xml 编码 + 创建 assets/xposed_init"""
import sys, os

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = r'D:\app\QQNativeGuard\app\src\main'

# 1. 重写 strings.xml（UTF-8 无 BOM）
strings = '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">QQ Native Guard</string>
    <string name="module_description">QQ native 层环境检测绕过（ByteHook + 设备指纹一致性）</string>
</resources>
'''
p = os.path.join(ROOT, 'res', 'values', 'strings.xml')
open(p, 'w', encoding='utf-8', newline='\n').write(strings)
print('rewrote strings.xml')

# 2. 创建 assets/xposed_init
assets = os.path.join(ROOT, 'assets')
os.makedirs(assets, exist_ok=True)
init_path = os.path.join(assets, 'xposed_init')
open(init_path, 'w', encoding='utf-8', newline='\n').write('io.github.kida.qqnativeguard.XposedEntry\n')
print('created assets/xposed_init -> io.github.kida.qqnativeguard.XposedEntry')

# 3. 验证 XposedEntry 类有正确的 hook 入口方法
entry = os.path.join(ROOT, 'java', 'io', 'github', 'kida', 'qqnativeguard', 'XposedEntry.java')
if os.path.exists(entry):
    s = open(entry, encoding='utf-8', errors='replace').read()
    import re
    for m in re.finditer(r'public\s+void\s+(handleLoadPackage|initZygote|handleInitPackageResources)', s):
        print('entry method:', m.group(1))
    if 'IXposedHookLoadPackage' in s:
        print('implements IXposedHookLoadPackage: yes')
else:
    print('XposedEntry.java MISSING')