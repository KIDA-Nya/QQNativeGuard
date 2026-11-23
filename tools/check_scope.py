#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查新模块的 scope 配置"""
import sqlite3, sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

DB = r'D:\app\QQNativeGuard\tmp_modules.db'
con = sqlite3.connect(DB)
cur = con.cursor()

# 找新模块 mid
cur.execute("SELECT mid, module_pkg_name, apk_path, enabled FROM modules WHERE module_pkg_name LIKE '%qqnativeguard%' OR module_pkg_name LIKE '%qqenhanced%'")
rows = cur.fetchall()
print('=== 我们的模块 ===')
for r in rows:
    print('  mid=%s pkg=%s enabled=%s' % (r[0], r[1], r[3]))
    print('    apk_path: %s' % r[2])
    mid = r[0]
    cur.execute("SELECT app_pkg_name, user_id FROM scope WHERE mid=?", (mid,))
    scopes = cur.fetchall()
    print('    scope (%d 个):' % len(scopes))
    for s in scopes:
        print('      - %s (user %s)' % (s[0], s[1]))

print()
print('=== 对比：qfun 的 scope ===')
cur.execute("SELECT mid FROM modules WHERE module_pkg_name='me.yxp.qfun'")
r = cur.fetchone()
if r:
    cur.execute("SELECT app_pkg_name FROM scope WHERE mid=?", (r[0],))
    for s in cur.fetchall():
        print('  -', s[0])

print()
print('=== 确认 QQ 是否在任何 scope 里 ===')
cur.execute("SELECT DISTINCT mid, app_pkg_name FROM scope WHERE app_pkg_name='com.tencent.mobileqq'")
for r in cur.fetchall():
    print('  mid=%s -> %s' % (r[0], r[1]))

con.close()