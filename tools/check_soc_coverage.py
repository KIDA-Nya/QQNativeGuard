#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""核查 SoC 特征值的伪装完整性"""
import subprocess, re, sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

PROPS = [
    'ro.board.platform', 'ro.soc.model', 'ro.soc.manufacturer',
    'ro.soc.family', 'ro.soc.id', 'ro.hardware', 'ro.boot.hardware',
    'ro.product.board', 'ro.product.cpu.abi', 'ro.product.cpu.abilist',
    'ro.chipname', 'ro.hardware.chipname', 'ro.config.low_ram',
]

print('=== 真机值 ===')
for p in PROPS:
    r = subprocess.run(['adb', 'shell', 'getprop ' + p], capture_output=True)
    v = r.stdout.decode('utf-8', 'replace').strip()
    print('  %-28s = %s' % (p, v if v else '(空)'))

print()
print('=== 我们伪造了哪些（fake_prop_value 中）===')
src = open(r'D:\app\QQNativeGuard\app\src\main\cpp\hooks\hook_libc.cpp',
           encoding='utf-8', errors='replace').read()
i = src.find('static const char* fake_prop_value')
j = src.find('\n}', i)
body = src[i:j]
covered = set()
for m in re.finditer(r'strcmp\(name, "([^"]+)"\)', body):
    covered.add(m.group(1))

for p in PROPS:
    mark = 'FAKE' if p in covered else '-   '
    print('  [%s] %s' % (mark, p))

print()
print('=== 结论 ===')
missing = [p for p in PROPS if p not in covered]
print('  未伪造的 SoC 相关属性: %d 个' % len(missing))
for p in missing:
    print('    ', p)

# 关键：模板块（ro.product.board 应该被伪造）
if 'ro.product.board' not in covered:
    print()
    print('  ⚠ ro.product.board 未在 fake_prop_value 中，但 QsecEst k54 依赖它')
