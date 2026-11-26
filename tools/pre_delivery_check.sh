#!/system/bin/sh
# 交付前检查：硬约束 + hook 完整性 + 无回归
PKG=com.tencent.mobileqq
PID=$(pidof $PKG)

echo "=========================================="
echo " 交付前检查 @ $(date +'%H:%M:%S')"
echo "=========================================="

echo ""
echo "=== 1. 硬约束：必装包是否完好 ==="
for p in bin.mt.plus.canary com.netease.uuremote me.yxp.qfun; do
  R=$(pm path $p 2>/dev/null)
  case "$R" in
    package:*) echo "  ✓ $p" ;;
    *) echo "  ✗✗✗ $p 缺失！" ;;
  esac
done

echo ""
echo "=== 2. 硬约束：QQ 是否稳定（无自杀）==="
echo "  当前 pid: ${PID:-未运行}"
DIED=$(logcat -d 2>/dev/null | grep -c 'Process com.tencent.mobileqq.*has died')
echo "  进程死亡次数: $DIED"
EXITSELF=$(dumpsys activity exit-info $PKG 2>/dev/null | grep -c 'reason=1 (EXIT_SELF)')
echo "  EXIT_SELF 次数: $EXITSELF"
KICK=$(logcat -d 2>/dev/null | grep -c 'KICK_TO_LOGIN')
echo "  KICK_TO_LOGIN: $KICK"

echo ""
echo "=== 3. 模块与作用域 ==="
pm path io.github.kida.qqnativeguard 2>/dev/null | head -1
/data/adb/lspd/cli scope ls io.github.kida.qqnativeguard 2>/dev/null | tail -2

echo ""
echo "=== 4. Native hook 是否活跃 ==="
echo "  smaps 过滤次数: $(logcat -d -s QQNativeBypass:V 2>/dev/null | grep -c 'filtered.*smaps')"
echo "  FKCALL 归因次数: $(logcat -d -s QQNativeBypass:V 2>/dev/null | grep -c 'FKCALL')"
echo "  三层探针安装: $(logcat -d -s QQNativeBypass:V 2>/dev/null | grep -c 'hook real_syscall -> ok')"

echo ""
echo "=== 5. SelfTest 结果（最近一次落盘）==="
ST=/storage/emulated/0/Android/data/$PKG/files/qqng_selftest.log
if [ -f "$ST" ]; then
  ls -la "$ST" | awk '{print "  文件: " $5 " 字节, " $6 " " $7 " " $8}'
  echo "  --- 隐藏路径检查 ---"
  grep -E '^/data/adb|^/system/bin/su|^/data/adb/lspd' "$ST" 2>/dev/null | head -3
  echo "  --- 对照组（应为 visible）---"
  grep -E '^/system/(build.prop|etc/hosts)' "$ST" 2>/dev/null | head -2
  echo "  --- 关键字段 ---"
  grep -E 'ro\.(product\.board|product\.model|soc\.model|serialno)' "$ST" 2>/dev/null
else
  echo "  (未找到 SelfTest 结果)"
fi

echo ""
echo "=== 6. 当前会话状态 ==="
dumpsys activity activities 2>/dev/null | grep -oE 'com.tencent.mobileqq/.activity.[A-Za-z]+' | head -1
echo ""
echo "=========================================="