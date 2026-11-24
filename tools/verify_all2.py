#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""端到端验证 v2：正确区分"系统侧"（应为真值）和"QQ 进程内"（应为伪造值）"""

import sys, subprocess, os, json

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

R = []


def sh(cmd, timeout=90):
    try:
        r = subprocess.run(['adb', 'shell', cmd], capture_output=True, timeout=timeout)
        return r.stdout.decode('utf-8', 'replace').strip()
    except Exception as e:
        return 'ERR:' + str(e)[:60]


def check(name, ok, detail='', cat=''):
    R.append({'name': name, 'ok': ok, 'detail': detail[:70], 'cat': cat})
    print('  %-44s %s %s' % (name, 'PASS' if ok else 'FAIL', detail[:52]))


print('=== 0. 环境 ===')
pid = sh('pidof com.tencent.mobileqq')
if not pid:
    sh('am start -n com.tencent.mobileqq/.activity.SplashActivity')
    subprocess.run(['adb', 'shell', 'sleep', '22'], capture_output=True)
    pid = sh('pidof com.tencent.mobileqq')
print('QQ pid:', pid or '(未运行)')
print()

logs = sh('logcat -d -s QQNativeBypass:V QQEnhancedBypass:V')

print('=== 1. 模块加载 ===')
check('Native 库加载', 'Native Bypass Library Loaded' in logs, cat='module')
check('Native hook 全装', 'All Native Hooks Installed Successfully' in logs, cat='module')
check('JNI 无缺失', 'No implementation found' not in logs, cat='module')
print()

print('=== 2. Java hook ===')
lazy = int(sh('logcat -d -s QQEnhancedBypass:V | grep -c "LAZY-HOOK. attached"') or 0)
check('懒加载 hook >=4', lazy >= 4, 'attached=%d' % lazy, cat='java')
pand = int(sh('logcat -d -s QQEnhancedBypass:V | grep -c "Hooked all methods: com.tencent.qmethod.pandoraex.monitor.DeviceInfoMonitor"') or 0)
check('Pandora >=20 方法', pand >= 20, 'hooked=%d' % pand, cat='java')
check('camerasdk isDeviceRooted', 'DeviceInfo.isDeviceRooted' in logs, cat='java')
check('gathererga 完整路径', 'internal.provider.impl.UserInfoImpl' in logs, cat='java')
check('GProGuildSafetyRisk', 'GProGuildSafetyRisk' in logs, cat='java')
print()

print('=== 3. QQ 进程内设备身份（关键，来自模块自检日志）===')
# 这些是 QQ 进程内真实读到值，必须是伪造值
selftest_expect = [
    ('ro.board.platform', 'kalama'),
    ('ro.soc.model', 'SM8650'),
    ('soc_serial', '<soc>'),
    ('verifiedbootstate', 'green'),
    ('serialno', '<serial>'),
]
st_lines = [l for l in logs.split('\n') if 'Self-test' in l]
for key, want in selftest_expect:
    hit = [l for l in st_lines if key in l]
    if hit:
        line = hit[0]
        ok = want in line
        val = line.split("'")[1] if "'" in line else '?'
        check('QQ内 ' + key, ok, '= %s (want %s)' % (val[:24], want), cat='qq')
    else:
        check('QQ内 ' + key, False, '无自检行', cat='qq')
print()

print('=== 4. 系统侧（应为真值 —— 验证 hook 只影响 QQ 进程）===')
for prop, expect_real in [
    ('ro.board.platform', 'lahaina'),
    ('ro.soc.model', 'SM8350'),
    ('ro.boot.verifiedbootstate', 'orange'),
]:
    v = sh('getprop %s' % prop)
    check('系统侧 %s 仍为真值' % prop, v == expect_real, '= %s' % v[:30], cat='sys')
print()

print('=== 5. 稳定性 ===')
died = int(sh('logcat -d | grep -c "Process com.tencent.mobileqq.*has died"') or 0)
check('无崩溃', died == 0, 'died=%d' % died, cat='stable')
check('进程存活', bool(pid), 'pid=%s' % pid, cat='stable')
print()

print('=== 6. 硬约束包 ===')
for p in ['bin.mt.plus.canary', 'com.netease.uuremote', 'me.yxp.qfun']:
    r = sh('pm path %s' % p)
    check('包完好 ' + p.split('.')[-1], 'package:' in r, cat='hard')
blk = int(sh('logcat -d -s QQEnhancedBypass:V | grep "Blocked PackageManager" | grep -cE "mt.plus|netease"') or 0)
check('硬约束包未被拦', blk == 0, 'blocked=%d' % blk, cat='hard')
print()

print('=== 7. SELinux 隔离验证 ===')
uni = int(sh('run-as com.tencent.mobileqq cat /proc/net/unix 2>&1 | grep -c lspd') or 0)
check('QQ 读不到 lspd', uni == 0, 'count=%d' % uni, cat='selinux')
print()

print('=' * 60)
ok = sum(1 for r in R if r['ok'])
print('%d/%d PASS' % (ok, len(R)))
bad = [r for r in R if not r['ok']]
if bad:
    print('\n失败项:')
    for r in bad:
        print('  [%s] %s : %s' % (r['cat'], r['name'], r['detail']))

json.dump(R, open(r'D:\app\QQNativeGuard\tmp\verify_result.json', 'w', encoding='utf-8'),
          indent=1, ensure_ascii=False)
print('\n结果已保存 tmp/verify_result.json')