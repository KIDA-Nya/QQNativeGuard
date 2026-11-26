#!/system/bin/sh
# 登录后一键端到端验证
# 用法：用户登录 QQ 后运行本脚本
PKG=com.tencent.mobileqq
WUP=/storage/emulated/0/Android/data/$PKG/wup_dump.log
ST=/storage/emulated/0/Android/data/$PKG/files/qqng_selftest.log
TRACE=/storage/emulated/0/Android/data/$PKG/files/qqng_exit_trace.log

echo "=========================================="
echo " 登录后端到端验证 @ $(date +'%H:%M:%S')"
echo "=========================================="

echo ""
echo "=== 1. 会话状态 ==="
PID=$(pidof $PKG)
echo "  pid: ${PID:-未运行}"
dumpsys activity activities 2>/dev/null | grep -oE 'com.tencent.mobileqq/.activity.[A-Za-z]+' | head -1

echo ""
echo "=== 2. 服务端是否接受（关键判据）==="
KICK=$(logcat -d 2>/dev/null | grep -c 'KICK_TO_LOGIN')
echo "  KICK_TO_LOGIN: $KICK  (0=未被踢)"
RISK=$(logcat -d -s QQEnhancedBypass:V 2>/dev/null | grep -c 'RISK-OBSERVE')
echo "  RISK-OBSERVE: $RISK  (0=服务端未下发风险等级)"
echo "  --- 若有踢线，看原因 ---"
logcat -d 2>/dev/null | grep -aE 'KICK_TO_LOGIN|ACCOUNT_EXPIRED|ACCOUNT_KICKED' | tail -2

echo ""
echo "=== 3. QsecEst 上报次数（登录应触发新上报）==="
grep -ac 'QsecEst.d' "$WUP" 2>/dev/null
echo "  (对比登录前的数值，应该增加)"

echo ""
echo "=== 4. 归因日志：是否有新的 act=real 矛盾 ==="
echo "  --- 全部 act=real 属性 ---"
logcat -d -s QQNativeBypass:V 2>/dev/null \
  | grep -a 'act=real prop=' \
  | grep -aoE 'mod=[A-Za-z0-9_.-]+.*prop=[a-z0-9._]+' \
  | sed 's/ d=[0-9]*//' | sort -u | head -12
echo "  (应只有 qcom / arm64-v8a 这类不矛盾的项)"

echo ""
echo "=== 5. 关键属性当前状态（应为伪造值）==="
logcat -d -s QQNativeBypass:V 2>/dev/null \
  | grep -a 'FKCALL prop' \
  | grep -aoE 'act=[a-z]+ prop=(ro\.product\.board|ro\.product\.model|ro\.soc\.model|ro\.product\.first_api_level)' \
  | sort -u

echo ""
echo "=== 6. SelfTest（最近的落盘）==="
if [ -f "$ST" ]; then
  echo "  文件时间: $(ls -la $ST | awk '{print $6,$7,$8}')"
  grep -E 'LEAKED|MISSING' "$ST" 2>/dev/null | head -5
  echo "  (上面为空 = 无泄露)"
  echo "  --- 关键字段 ---"
  grep -E 'ro\.(product\.board|product\.model|soc\.model|serialno)' "$ST" 2>/dev/null
else
  echo "  (无 SelfTest 结果)"
fi

echo ""
echo "=== 7. 自杀/退出追踪 ==="
if [ -f "$TRACE" ]; then
  echo "  EXIT PRIMITIVE 次数: $(grep -ac 'EXIT PRIMITIVE' $TRACE 2>/dev/null)"
  echo "  最近的 exit 记录:"
  grep -aA2 'EXIT PRIMITIVE' "$TRACE" 2>/dev/null | tail -6
else
  echo "  (无追踪日志)"
fi

echo ""
echo "=== 8. 三层反检测探针是否仍在 ==="
echo "  DLIP 调用总数: $(logcat -d -s QQNativeBypass:V 2>/dev/null | grep -ac 'DLIP call')"
echo "  REAL_SYSCALL 记录: $(logcat -d -s QQNativeBypass:V 2>/dev/null | grep -ac 'REAL_SYSCALL')"
echo "  from_turing=1: $(logcat -d -s QQNativeBypass:V 2>/dev/null | grep -ac 'from_turing=1')"

echo ""
echo "=========================================="
echo " 判定标准："
echo "  ✓ KICK=0 且 RISK=0        → 服务端接受"
echo "  ✓ act=real 只有不矛盾项    → 指纹自洽"
echo "  ✓ SelfTest 无 LEAKED      → hook 正常"
echo "  ✓ from_turing=1 = 0       → Turing 仍休眠"
echo "=========================================="