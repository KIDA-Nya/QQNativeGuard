#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""反汇编指定方法，输出可读的指令序列 + 字符串常量

用法: python disasm_method.py "Lmqq/app/MobileQQ;" realExit
"""
import struct, sys, glob, os

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

DEX_DIR = r'D:\app\QQ_Enhanced_Bypass\extracted\dex_all'


def uleb(b, i):
    r = 0; s = 0
    while True:
        if i >= len(b): return None, i
        x = b[i]; i += 1
        r |= (x & 0x7F) << s
        if not (x & 0x80): return r, i
        s += 7
        if s > 35: return None, i


class Dex:
    def __init__(self, d, name):
        self.d = d; self.name = name
        self.ns, self.so = struct.unpack_from('<II', d, 0x38)
        self.nt, self.to = struct.unpack_from('<II', d, 0x40)
        self.np, self.po = struct.unpack_from('<II', d, 0x48)
        self.nm, self.mo = struct.unpack_from('<II', d, 0x58)
        self.ncls = struct.unpack_from('<I', d, 0x60)[0]
        self.cdoff = struct.unpack_from('<I', d, 0x64)[0]

    def u4(self, o): return struct.unpack_from('<I', self.d, o)[0]
    def u2(self, o): return struct.unpack_from('<H', self.d, o)[0]

    def st(self, i):
        if i >= self.ns: return None
        off = self.u4(self.so + i * 4)
        n, j = uleb(self.d, off)
        if n is None: return None
        e = self.d.find(b'\x00', j)
        try: return self.d[j:e].decode('utf-8', 'replace')
        except Exception: return None

    def ty(self, i):
        if i >= self.nt: return None
        return self.st(self.u4(self.to + i * 4))

    def method(self, mi):
        if mi >= self.nm: return (None, None, None)
        off = self.mo + mi * 8
        c = self.u2(off); p = self.u2(off + 2); n = self.u4(off + 4)
        cls = self.ty(c); nm = self.st(n)
        proto = None
        if p < self.np:
            po = self.po + p * 12
            ret = self.ty(self.u4(po + 4)); pa = self.u4(po + 8)
            params = []
            if pa:
                try:
                    cnt = self.u4(pa)
                    for i in range(min(cnt, 8)):
                        o = pa + 4 + i * 2
                        if o + 2 > len(self.d): break
                        params.append(self.ty(struct.unpack_from('<H', self.d, o)[0]))
                except Exception: pass
            proto = '(%s)%s' % (''.join(x or '?' for x in params), ret or '?')
        return (cls, nm, proto)


# 基础指令大小表（Dalvik）
# 只覆盖常见指令，够用即可
OP_SIZES = {}
for op in range(256):
    OP_SIZES[op] = 1

# 2 单元
for op in (0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x17, 0x18, 0x19,
           0x1a, 0x1b, 0x1c, 0x1f, 0x20, 0x22, 0x23, 0x24, 0x25, 0x26,
           0x27, 0x28, 0x29, 0x2a, 0x2b, 0x2c, 0x2d, 0x2e, 0x2f, 0x30,
           0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a,
           0x3b, 0x3c, 0x3d, 0x3e, 0x3f, 0x40, 0x41, 0x42, 0x43, 0x44,
           0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x4b, 0x4c, 0x4d, 0x4e,
           0x4f, 0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
           0x59, 0x5a, 0x5b, 0x5c, 0x5d, 0x5e, 0x5f, 0x60, 0x61, 0x62,
           0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x6b, 0x6c,
           0x6d, 0x73, 0x79, 0x7a, 0x7b, 0x7c, 0x7d, 0x7e, 0x7f, 0x80,
           0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a,
           0x8b, 0x8c, 0x8d, 0x8e, 0x8f, 0x90, 0x91, 0x92, 0x93, 0x94,
           0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0x9b, 0x9c, 0x9d, 0x9e,
           0x9f, 0xa0, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8,
           0xa9, 0xaa, 0xab, 0xac, 0xad, 0xae, 0xaf, 0xb0, 0xb1, 0xb2,
           0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xbb, 0xbc,
           0xbd, 0xbe, 0xbf, 0xc0, 0xc1, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6,
           0xc7, 0xc8, 0xc9, 0xca, 0xcb, 0xcc, 0xcd, 0xce, 0xcf, 0xd0,
           0xd1, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
           0xdb, 0xdc, 0xdd, 0xde, 0xdf, 0xe0, 0xe1, 0xe2, 0xe3, 0xe4,
           0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xeb, 0xec, 0xed, 0xee,
           0xef, 0xf0, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
           0xf9, 0xfa, 0xfb, 0xfc, 0xfd, 0xfe, 0xff):
    OP_SIZES[op] = 2
for op in (0x14, 0x17, 0x1d, 0x27, 0x28, 0x29, 0x2a, 0x2b, 0x2c, 0x2d,
           0x2e, 0x2f, 0x30, 0x31):
    OP_SIZES[op] = 2
# 3 单元
for op in (0x03, 0x06, 0x09, 0x1d, 0x2b, 0x6e, 0x6f, 0x70, 0x71, 0x72,
           0x74, 0x75, 0x76, 0x77, 0x78, 0x24, 0x25, 0x26, 0x2a, 0x2c,
           0x6d, 0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98,
           0x99, 0x9a, 0x9b, 0x9c, 0x9d, 0x9e, 0x9f, 0xa0, 0xa1, 0xa2,
           0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xab, 0xac,
           0xad, 0xae, 0xaf, 0xb0, 0xd0, 0xd1, 0xd2, 0xd3, 0xd4, 0xd5,
           0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xdb, 0xdc, 0xdd, 0xde, 0xdf,
           0xe0, 0xe1, 0xe2, 0xfa, 0xfb, 0xfc, 0xfd, 0xfe, 0xff):
    OP_SIZES[op] = 3


def disasm(dex, code_off, cls_name, mname, max_insns=400):
    insns_size = dex.u4(code_off + 12)
    base = code_off + 16
    print('=== %s#%s (%d units) ===' % (cls_name, mname, insns_size))

    units = [struct.unpack_from('<H', dex.d, base + i * 2)[0]
             for i in range(min(insns_size, max_insns * 3))]
    i = 0
    n = 0
    while i < len(units) and n < max_insns:
        u = units[i]
        op = u & 0xFF
        line = '  %04x: ' % i
        # 关键指令翻译
        if op == 0x1a:      # const-string
            reg = (u >> 8) & 0xFF
            sidx = units[i + 1] if i + 1 < len(units) else 0
            s = dex.st(sidx)
            line += 'const-string v%d, %r' % (reg, s)
        elif op == 0x1b:    # const-string/jumbo
            reg = (u >> 8) & 0xFF
            sidx = (units[i + 1] | (units[i + 2] << 16)) if i + 2 < len(units) else 0
            s = dex.st(sidx)
            line += 'const-string/jumbo v%d, %r' % (reg, s)
        elif op in (0x6e, 0x6f, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78):
            midx = units[i + 1] if i + 1 < len(units) else 0
            c, nm, proto = dex.method(midx)
            kind = {0x6e: 'invoke-virtual', 0x6f: 'invoke-super', 0x70: 'invoke-direct',
                    0x71: 'invoke-static', 0x72: 'invoke-interface',
                    0x74: 'invoke-virtual/r', 0x75: 'invoke-super/r',
                    0x76: 'invoke-direct/r', 0x77: 'invoke-static/r',
                    0x78: 'invoke-interface/r'}[op]
            cn = (c or '?').strip('L;').replace('/', '.')
            line += '%s %s#%s%s' % (kind, cn, nm or '?', proto or '?')
            mark = ''
            if nm and any(k in nm.lower() for k in ('exit', 'kill', 'halt')):
                mark = '   <<<< EXIT'
            if cn in ('java.lang.System', 'java.lang.Runtime', 'android.os.Process'):
                mark = '   <<<< TARGET'
            line += mark
        elif op == 0x13:    # const/16
            reg = (u >> 8) & 0xFF
            val = units[i + 1] if i + 1 < len(units) else 0
            if val & 0x8000: val -= 0x10000
            line += 'const/16 v%d, #%d' % (reg, val)
        elif op == 0x04 or op == 0x12:   # const/4
            reg = (u >> 8) & 0xF
            val = (u >> 12) & 0xF
            if val & 0x8: val -= 16
            line += 'const/4 v%d, #%d' % (reg, val)
        elif op in (0x28, 0x29):  # goto / goto/16
            off = units[i + 1] if i + 1 < len(units) else 0
            if op == 0x28:
                if off & 0x8000: off -= 0x10000
                line += 'goto %+d' % off
            else:
                line += 'goto/16'
        elif op == 0x38 or op == 0x39:   # if-eqz / if-nez
            reg = (u >> 8) & 0xFF
            off = units[i + 1] if i + 1 < len(units) else 0
            if off & 0x8000: off -= 0x10000
            line += '%s v%d, %+d' % ('if-eqz' if op == 0x38 else 'if-nez', reg, off)
        elif op == 0x0e:    # return-void
            line += 'return-void'
        elif op == 0x0f:    # return
            line += 'return v%d' % ((u >> 8) & 0xFF)
        elif op == 0x10:    # return-wide
            line += 'return-wide v%d' % ((u >> 8) & 0xFF)
        elif op == 0x11:
            line += 'return-object v%d' % ((u >> 8) & 0xFF)
        else:
            line += 'op_0x%02x' % op

        print(line)
        i += OP_SIZES.get(op, 1)
        n += 1
    print()


def find_method(cls_target, name_target):
    """在所有 dex 里找指定方法并反汇编"""
    found = 0
    for f in sorted(glob.glob(os.path.join(DEX_DIR, '*.dex'))):
        try:
            dx = Dex(open(f, 'rb').read(), os.path.basename(f))
        except Exception:
            continue
        for ci in range(dx.ncls):
            cb0 = dx.cdoff + ci * 32
            if cb0 + 32 > len(dx.d): break
            cls_idx = dx.u4(cb0)
            cn = dx.ty(cls_idx)
            if cn != cls_target:
                continue
            cdata = dx.u4(cb0 + 24)
            if cdata == 0: continue
            p = cdata
            sf, p = uleb(dx.d, p)
            inf, p = uleb(dx.d, p)
            dm, p = uleb(dx.d, p)
            vm, p = uleb(dx.d, p)
            if sf is None: continue
            for _ in range(sf + inf):
                _, p = uleb(dx.d, p); _, p = uleb(dx.d, p)
            midx = 0
            for _ in range(dm + vm):
                diff, p = uleb(dx.d, p)
                if diff is None: break
                midx += diff
                acc, p = uleb(dx.d, p)
                code_off, p = uleb(dx.d, p)
                if not code_off: continue
                _, nm, proto = dx.method(midx)
                if nm == name_target:
                    print('>>> %s (%s) in %s' % (nm, proto, dx.name))
                    disasm(dx, code_off, cls_target, nm)
                    found += 1
                    if found >= 2:
                        return
    if not found:
        print('未找到 %s#%s' % (cls_target, name_target))


if len(sys.argv) >= 3:
    find_method(sys.argv[1], sys.argv[2])
else:
    # 默认分析最可疑的几个
    find_method('Lmqq/app/MobileQQ;', 'realExit')