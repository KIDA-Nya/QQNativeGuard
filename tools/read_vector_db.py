#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""读取 Vector 的 modules_config.db，列出已登记模块与 scope"""
import sqlite3, sys, os

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

DB = r'D:\app\QQNativeGuard\tmp_modules.db'

if not os.path.exists(DB):
    print('DB not found')
    sys.exit(1)

con = sqlite3.connect(DB)
cur = con.cursor()

# 表结构
cur.execute("SELECT name FROM sqlite_master WHERE type='table'")
tables = [r[0] for r in cur.fetchall()]
print('=== 表 ===')
for t in tables:
    print(' ', t)

for t in tables:
    print()
    print('=== %s ===' % t)
    try:
        cur.execute('SELECT * FROM "%s" LIMIT 20' % t)
        cols = [d[0] for d in cur.description]
        print('  cols:', cols)
        for row in cur.fetchall():
            # 只打印可能是模块名的字段
            print('  ', row[:6])
    except Exception as e:
        print('  err', e)

con.close()