#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""解析所有 WLOGIN_JSON 记录，还原登录成败时间线"""
import subprocess, re, json, datetime, sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

WUP = '/storage/emulated/0/Android/data/com.tencent.mobileqq/wup_dump.log'
r = subprocess.run(['adb', 'shell', "grep -a 'WLOGIN_JSON' " + WUP],
                   capture_output=True)
txt = r.stdout.decode('utf-8', 'replace')

print('=== 全部登录记录 ===')
print()
rows = []
for line in txt.strip().split('\n'):
    if 'WLOGIN_JSON' not in line:
        continue
    m = re.search(r'^(\d+).*?text=(\{.*\})\s*$', line)
    if not m:
        continue
    ts = int(m.group(1))
    try:
        o = json.loads(m.group(2))
    except Exception:
        continue
    lst = (o.get('lst') or [{}])[0]
    log = (lst.get('log') or [{}])[0]
    t = datetime.datetime.fromtimestamp(ts / 1000).strftime('%m-%d %H:%M:%S')
    rows.append({
        'ts': ts, 't': t, 'uin': lst.get('uin'),
        'rst1': lst.get('rst1'), 'rst2': lst.get('rst2'),
        'rlen': log.get('rlen'), 'slen': log.get('slen'),
        'cmd': log.get('cmd'), 'used': lst.get('used'),
        'type': lst.get('type'),
        'device': o.get('device'),
    })

rows.sort(key=lambda x: x['ts'])
for x in rows:
    print('%s  type=%-6s uin=%s device=%s' % (x['t'], x['type'], x['uin'], x['device']))
    print('           rst1=%s rst2=%s cmd=%s rlen=%s slen=%s used=%sms' % (
        x['rst1'], x['rst2'], x['cmd'], x['rlen'], x['slen'], x['used']))
    print()

if rows:
    print('=== 时间跨度 ===')
    print('  首次: %s' % rows[0]['t'])
    print('  末次: %s' % rows[-1]['t'])
    fail = [x for x in rows if str(x['rst1']) != '0']
    print('  rst1 != 0 的记录数: %d' % len(fail))
    for x in fail:
        print('     %s rst1=%s rst2=%s' % (x['t'], x['rst1'], x['rst2']))
