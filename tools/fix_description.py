#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""修复 ro.build.description 与 fingerprint 不同源的问题

Android 里 ro.build.description 必须与 ro.build.fingerprint 同源：
  fingerprint: <brand>/<product>/<device>:<release>/<id>/<incremental>:<type>/<tags>
  description: <product>-<type> <release> <id> <incremental> <tags>

当前 fingerprint 是伪造的：
  OPPO/PGT110/OPPO_PGT110:15/UKQ1.231003.002/1710000000:user/release-keys

但 description 是真机原值：
  qssi-user 16 BP2A.250605.015 1785335584578 release-keys

→ 四个字段全部对不上，服务端一比对即知被篡改。
"""
import subprocess, sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

CFG = '/data/local/tmp/qqbypass.conf'

# 由 fingerprint 反推出的正确 description
#   product=PGT110, type=user, release=15, id=UKQ1.231003.002,
#   incremental=1710000000, tags=release-keys
NEW_DESC = 'PGT110-user 15 UKQ1.231003.002 1710000000 release-keys'

r = subprocess.run(['adb', 'shell', 'cat ' + CFG], capture_output=True)
text = r.stdout.decode('utf-8', 'replace')

out = []
old = None
for line in text.split('\n'):
    s = line.strip()
    if s.startswith('ENV_DESCRIPTION='):
        old = s.split('=', 1)[1]
        out.append('ENV_DESCRIPTION=' + NEW_DESC)
    elif s:
        out.append(s)

new_text = '\n'.join(out) + '\n'

import base64
b64 = base64.b64encode(new_text.encode('utf-8')).decode('ascii')
r2 = subprocess.run(['adb', 'shell', 'echo %s | base64 -d > %s' % (b64, CFG)],
                    capture_output=True)
if r2.returncode != 0:
    print('写入失败:', r2.stderr.decode('utf-8', 'replace')[:200])
    sys.exit(1)
subprocess.run(['adb', 'shell', 'chmod 644 ' + CFG], capture_output=True)

print('=== 修复 ENV_DESCRIPTION ===')
print('  旧值:', old)
print('  新值:', NEW_DESC)
print()
print('=== 与 fingerprint 的一致性核对 ===')
fp = 'OPPO/PGT110/OPPO_PGT110:15/UKQ1.231003.002/1710000000:user/release-keys'
parts = fp.split(':')
pre = parts[0].split('/')
tail = parts[1].split('/')
print('  fingerprint 拆解:')
print('    brand=%s product=%s device=%s' % (pre[0], pre[1], pre[2]))
print('    release=%s id=%s incremental=%s' % (pre[3], tail[0], tail[1]))
print('    type=%s tags=%s' % (tail[2], tail[3]))
print()
print('  description 拆解:')
d = NEW_DESC.split(' ')
dp = d[0].split('-')
print('    product=%s type=%s' % (dp[0], dp[1]))
print('    release=%s id=%s incremental=%s tags=%s' % (d[1], d[2], d[3], d[4]))
print()
ok = (dp[0] == pre[1] and dp[1] == tail[2]
      and d[1] == pre[3] and d[2] == tail[0]
      and d[3] == tail[1] and d[4] == tail[3])
print('  全部一致:', ok)
