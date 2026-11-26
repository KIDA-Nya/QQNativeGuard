#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""查 00:46 之后的全部上报记录"""
import subprocess, re, datetime, sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

WUP = '/storage/emulated/0/Android/data/com.tencent.mobileqq/wup_dump.log'
r = subprocess.run(['adb', 'shell', 'cat ' + WUP], capture_output=True)
txt = r.stdout.decode('utf-8', 'replace')

# 00:46:13 = 1795717573 (本地时间换算)
CUT = 1795717573

print('=== 00:46 之后的全部记录 ===')
n = 0
for line in txt.split('\n'):
    m = re.match(r'^(\d+)\s+(\S+)', line)
    if not m:
        continue
    ts = int(m.group(1)) / 1000.0
    if ts < CUT:
        continue
    n += 1
    t = datetime.datetime.fromtimestamp(ts).strftime('%H:%M:%S')
    cmd = m.group(2)
    extra = ''
    mm = re.search(r'"rst1":"(\d+)","rst2":"(\d+)"', line)
    if mm:
        extra = '  rst1=%s rst2=%s' % (mm.group(1), mm.group(2))
    ml = re.search(r'"rlen":"(\d+)"', line)
    if ml:
        extra += ' rlen=%s' % ml.group(1)
    print('  %s  %-45s%s' % (t, cmd, extra))

print()
print('记录数:', n)
if n == 0:
    print('（wup_dump 可能没被写，或时间换算有误）')
    # 打印最后几条作为对照
    print()
    print('=== wup_dump 最后 5 条（对照）===')
    for line in txt.strip().split('\n')[-5:]:
        m = re.match(r'^(\d+)\s+(\S+)', line)
        if m:
            t = datetime.datetime.fromtimestamp(int(m.group(1)) / 1000).strftime('%m-%d %H:%M:%S')
            print('  %s  %s' % (t, m.group(2)))
