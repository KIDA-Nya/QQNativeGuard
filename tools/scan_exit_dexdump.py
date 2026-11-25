#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""基于官方 dexdump 的精确 exit 调用点扫描

替代自写解析器（后者有误报）。
dexdump 输出结构：
      name          : 'method'
      type          : '()V'
      0000: <bytes>  |0000: <mnemonic> <operands>
"""
import subprocess, sys, os, glob, re, json

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

DEXDUMP = r'C:\Users\Admin\AppData\Local\Android\Sdk\build-tools\36.1.0\dexdump.exe'
DEX_DIR = r'D:\app\QQ_Enhanced_Bypass\extracted\dex_all'
CACHE = r'D:\app\QQ_Enhanced_Bypass\tmp\dexdump'

os.makedirs(CACHE, exist_ok=True)

METHOD_RE = re.compile(r"^\s+name\s+:\s+'(.+?)'")
CLASS_HDR = re.compile(r"^\s+#\d+\s+:\s+\(in (L[^;]+;)\)")
# dexdump 行首是文件偏移（无前导空格，4-8 位十六进制），竖线后是指令序号（4 位）
CODE_INSN = re.compile(r"^\s*[0-9a-f]{4,8}:\s+[0-9a-f ]+\s+\|([0-9a-f]{4}):\s+(.+?)$")
TARGET_RE = re.compile(
    r'invoke-\w+\s+\{([^}]*)\},\s+'
    r'(Ljava/lang/System;|Ljava/lang/Runtime;|Landroid/os/Process;|'
    r'Lcom/tencent/mobileqq/perf/block/SystemMethodProxy;)'
    r'\.(\w+):\(?')

EXIT_METHODS = {'exit', 'halt', 'killProcess', 'killProcessQuiet'}


def dump_dex(path):
    """跑 dexdump，带缓存"""
    base = os.path.basename(path)
    cf = os.path.join(CACHE, base + '.txt')
    if os.path.exists(cf) and os.path.getsize(cf) > 1000:
        return open(cf, encoding='utf-8', errors='replace').read()
    try:
        r = subprocess.run([DEXDUMP, '-d', path], capture_output=True, timeout=300)
        txt = r.stdout.decode('utf-8', 'replace')
    except Exception as e:
        print('  dexdump failed %s: %s' % (base, e))
        return ''
    with open(cf, 'w', encoding='utf-8') as f:
        f.write(txt)
    return txt


def reg_num(tok):
    """'v3' -> 3 ; 'p1' -> -1 (参数寄存器，忽略)"""
    tok = tok.strip()
    if tok.startswith('v'):
        try: return int(tok[1:])
        except Exception: return None
    return None


def analyze(txt, dexname):
    out = []
    cur_class = '?'
    cur_method = '?'
    consts = {}     # reg -> (value, insn_idx)
    insn_n = 0

    for line in txt.split('\n'):
        m = CLASS_HDR.match(line)
        if m:
            cur_class = m.group(1)
            continue
        m = METHOD_RE.match(line)
        if m:
            cur_method = m.group(1)
            consts = {}
            insn_n = 0
            continue
        m = CODE_INSN.match(line)
        if not m:
            continue
        insn_i = int(m.group(1), 16)
        body = m.group(2)

        # 记录常量
        cm = re.match(r'const(?:/4|/16|/high16)?\s+\{?(v\d+)\}?,\s*#?-?(?:int\s+)?(-?\d+)',
                      body)
        if cm:
            r = reg_num(cm.group(1))
            if r is not None:
                consts[r] = (int(cm.group(2)), insn_i)
        else:
            cm2 = re.match(r'const/4\s+\{?(v\d+)\}?,\s*#?(-?\d+)', body)
            if cm2:
                r = reg_num(cm2.group(1))
                if r is not None:
                    consts[r] = (int(cm2.group(2)), insn_i)

        tm = TARGET_RE.search(body)
        if tm:
            regs = [reg_num(x) for x in tm.group(1).split(',') if x.strip()]
            owner = tm.group(2)
            mth = tm.group(3)
            if mth in EXIT_METHODS:
                code = None
                src = None
                if regs:
                    r0 = regs[0]
                    if r0 is not None and r0 in consts:
                        v, ci = consts[r0]
                        if insn_i - ci <= 16:
                            code = v
                            src = 'const@%04x' % ci
                out.append({
                    'dex': dexname,
                    'class': cur_class,
                    'method': cur_method,
                    'owner': owner.strip('L;').replace('/', '.'),
                    'target': mth,
                    'code': code,
                    'src': src,
                    'insn': '%04x' % insn_i,
                })
    return out


all_results = []
dexes = sorted(glob.glob(os.path.join(DEX_DIR, '*.dex')))
print('扫描 %d 个 dex...' % len(dexes))
for p in dexes:
    txt = dump_dex(p)
    if not txt:
        continue
    r = analyze(txt, os.path.basename(p))
    all_results += r
    if r:
        print('  %-16s %d 个 exit 调用' % (os.path.basename(p), len(r)))

print('\n=== 总计 %d 个 exit 调用点 ===' % len(all_results))

# 按 code 分组
from collections import Counter, defaultdict
by_code = defaultdict(list)
for r in all_results:
    by_code[r['code']].append(r)

print('\n=== 按 exit code 分组 ===')
for code in sorted(by_code, key=lambda c: (c is None, c)):
    label = 'UNKNOWN' if code is None else str(code)
    print('  code=%-10s %d 个' % (label, len(by_code[code])))

print('\n=== ★ code=-1（即进程 status=255）===')
hits = by_code.get(-1, [])
seen = set()
for r in hits:
    key = (r['class'], r['method'])
    if key in seen: continue
    seen.add(key)
    print('  %s#%s' % (r['class'].strip('L;').replace('/', '.'), r['method']))
    print('      dex=%s insn=%s owner=%s.%s src=%s'
          % (r['dex'], r['insn'], r['owner'], r['target'], r['src']))
if not hits:
    print('  (无)')

print('\n=== code=255 直接（若有）===')
for r in by_code.get(255, []):
    print('  %s#%s' % (r['class'], r['method']))

out = r'D:\app\QQ_Enhanced_Bypass\tmp\exit_sites_dexdump.json'
with open(out, 'w', encoding='utf-8') as f:
    json.dump(all_results, f, ensure_ascii=False, indent=1)
print('\n已写入 %s' % out)