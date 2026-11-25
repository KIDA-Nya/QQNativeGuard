#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 dexdump 缓存提取指定方法的完整反汇编（含方法边界）"""
import sys, os, re

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

CACHE = r'D:\app\QQ_Enhanced_Bypass\tmp\dexdump'

CLASS_HDR = re.compile(r"^\s+#\d+\s+:\s+\(in (L[^;]+;)\)")
METHOD_RE = re.compile(r"^\s+name\s+:\s+'(.+?)'")
TYPE_RE = re.compile(r"^\s+type\s+:\s+'(.+?)'")
INSN = re.compile(r"^\s*[0-9a-f]{4,8}:\s+[0-9a-f ]+\s+\|([0-9a-f]{4}):\s+(.+?)$")


def dump_method(dexfile, cls, method, max_lines=200):
    path = os.path.join(CACHE, dexfile + '.txt')
    if not os.path.exists(path):
        print('缓存不存在: %s' % path)
        return False
    cur_cls = '?'
    cur_m = '?'
    buf = []
    capture = False
    found = False
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            m = CLASS_HDR.match(line)
            if m:
                cur_cls = m.group(1)
                capture = False
                continue
            m = METHOD_RE.match(line)
            if m:
                cur_m = m.group(1)
                capture = (cur_cls == cls and cur_m == method)
                if capture:
                    found = True
                    buf.append('=== %s#%s (%s) ===' % (cur_cls, cur_m, dexfile))
                continue
            if capture:
                im = INSN.match(line)
                if im:
                    buf.append('  %s: %s' % (im.group(1), im.group(2)))
                    if len(buf) > max_lines:
                        capture = False
    if found:
        print('\n'.join(buf))
    return found


if len(sys.argv) >= 4:
    dump_method(sys.argv[1], sys.argv[2], sys.argv[3])
else:
    # 默认：两个最可疑的
    for dex, cls, mth in [
        ('classes11', 'Lcom/tencent/mobileqq/app/guard/BackgroundUnguard;', 'onTick'),
    ]:
        if not dump_method(dex, cls, mth, 120):
            print('未找到 %s#%s in %s' % (cls, mth, dex))