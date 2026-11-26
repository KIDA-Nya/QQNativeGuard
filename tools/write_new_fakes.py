#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把新一批伪造值写入设备上的 qqbypass.conf"""
import subprocess, sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

NEW = {
    'FAKE_WLOGIN_GUID': '2a828fa04b4343f12de4f51dc784071b',
    'FAKE_IMEI':        '861361825484209',
    'FAKE_ANDROID_ID':  'ad1a616d9b789112',
    'FAKE_SERIAL':      'C2669259',
    'FAKE_SOC_SERIAL':  '9916868093',
    'FAKE_QIMEI36':     '15af1f0ce308ef7947b01482eae5d4f56782',
    'FAKE_OAID':        'ccc37c6d-91a5-45fd-8efc-fa31c1978671',
    'FAKE_WIFI_SSID':   'TP-LINK_A7C2',
}

CFG = '/data/local/tmp/qqbypass.conf'

# 读当前配置
r = subprocess.run(['adb', 'shell', 'cat ' + CFG], capture_output=True)
text = r.stdout.decode('utf-8', 'replace')
lines = text.split('\n')

out = []
changed = []
for line in lines:
    s = line.strip()
    if '=' in s and not s.startswith('#'):
        key = s.split('=', 1)[0].strip()
        if key in NEW:
            old = s.split('=', 1)[1]
            out.append('%s=%s' % (key, NEW[key]))
            changed.append((key, old, NEW[key]))
            continue
    out.append(line)

# 检查是否所有 key 都替换了
found = {k for k, _, _ in changed}
missing = set(NEW) - found
if missing:
    # 缺失的追加
    for k in missing:
        out.append('%s=%s' % (k, NEW[k]))
        changed.append((k, '(新增)', NEW[k]))

new_text = '\n'.join(out)

# 写回：用 base64 传输避免 shell 转义问题
import base64
b64 = base64.b64encode(new_text.encode('utf-8')).decode('ascii')
cmd = 'echo %s | base64 -d > %s' % (b64, CFG)
r2 = subprocess.run(['adb', 'shell', cmd], capture_output=True)
if r2.returncode != 0:
    print('写入失败:', r2.stderr.decode('utf-8', 'replace')[:200])
    sys.exit(1)

subprocess.run(['adb', 'shell', 'chmod 644 ' + CFG], capture_output=True)

print('=== 已更新 %d 项 ===' % len(changed))
for k, old, new in changed:
    print('  %-20s %s  ->  %s' % (k, old[:36] if old else '(空)', new))
