#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""确保 NetworkReportHook.java 正确移除 wtlogin.log_report"""
import sys, re, os

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

P = r'D:\app\QQNativeGuard\app\src\main\java\io\github\kida\qqnativeguard\hooks\NetworkReportHook.java'
s = open(P, encoding='utf-8', errors='replace').read()

# 找 BLOCK_COMMANDS 数组块
m = re.search(r'BLOCK_COMMANDS\s*=\s*\{', s)
if not m:
    print('BLOCK_COMMANDS 未找到')
    sys.exit(1)

start = s.find('{', m.start())
d = 0; j = start
while j < len(s):
    if s[j] == '{': d += 1
    elif s[j] == '}':
        d -= 1
        if d == 0: break
    j += 1
old = s[m.start():j+1]
print('=== 当前 BLOCK_COMMANDS ===')
print(old)

NEW = '''BLOCK_COMMANDS = {
        "trpc.o3.report",
        "trpc.o3.mobile_security",
        "trpc.ilive_cdn.report",
        "OidbSvc.0xd79",  // Device report
        // 【移除】wtlogin.device_lock —— 登录校验类命令，绝不能拦。
        // 外部实证（WeChatTablet README changelog）：
        //   误拦此命令会导致 "身份验证失败 (w21)"
        // "wtlogin.device_lock",
        "turing",
        "riskCheckWup",
        "DeviceTokenV3",
        // 【移除】wtlogin.log_report —— 纯登录日志，拦截无收益且可能引发异常
        // "wtlogin.log_report",
        "QQStranger.login_svr.SsoLoginInfoReport",
    }'''

s = s[:m.start()] + NEW + s[j+1:]
open(P, 'w', encoding='utf-8', newline='\n').write(s)

print()
print('=== 修复后 ===')
for line in NEW.split('\n'):
    print(line)