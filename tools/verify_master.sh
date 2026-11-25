#!/system/bin/sh
# 核查 MASTER.md 中记录的环境值是否与设备实际一致

PKG=com.tencent.mobileqq
CONF=/data/local/tmp/qqbypass.conf

echo "=== 1. 配置文件的值 ==="
grep -E '^(ENV_MODEL|ENV_DEVICE|ENV_BUILD_ID|ENV_FINGERPRINT|FAKE_WLOGIN_GUID|FAKE_SERIAL|FAKE_SOC_SERIAL|FAKE_ANDROID_ID|FAKE_IMEI|FAKE_QIMEI36)=' "$CONF" 2>/dev/null

echo
echo "=== 2. 系统属性真实值 ==="
echo "  ro.product.model      = $(getprop ro.product.model)"
echo "  ro.product.board      = $(getprop ro.product.board)"
echo "  ro.board.platform     = $(getprop ro.board.platform)"
echo "  ro.serialno           = $(getprop ro.serialno)"
echo "  Settings.ANDROID_ID   = $(settings get secure android_id 2>/dev/null)"

echo
echo "=== 3. QQ 进程内（经 hook）==="
echo "  从模块自检日志读取："
logcat -d -s QQNativeBypass:V 2>/dev/null | grep -aE 'Self-test ro.product.board|Self-test soc_serial|Self-test ro.serialno' | tail -3

echo
echo "=== 4. GUID 状态 ==="
echo "  配置文件 FAKE_WLOGIN_GUID = $(grep FAKE_WLOGIN_GUID "$CONF" 2>/dev/null | cut -d= -f2)"
echo "  generateGuid 实际返回："
logcat -d -s QQEnhancedBypass:V 2>/dev/null | grep -aE 'generateGuid\(\) ->' | tail -1

echo
echo "=== 5. 模块与作用域 ==="
pm path io.github.kida.qqnativeguard 2>/dev/null | head -1
/data/adb/lspd/cli scope ls io.github.kida.qqnativeguard 2>/dev/null | tail -2

echo
echo "=== 6. 硬约束包 ==="
for p in bin.mt.plus.canary com.netease.uuremote me.yxp.qfun; do
  R=$(pm path $p 2>/dev/null)
  case "$R" in
    package:*) echo "  ✓ $p" ;;
    *) echo "  ✗ $p 缺失！" ;;
  esac
done

echo
echo "=== 7. QQ 运行状态 ==="
echo "  pid = $(pidof $PKG)"
echo "  died = $(logcat -d 2>/dev/null | grep -c 'Process com.tencent.mobileqq.*has died')"
echo "  KICK = $(logcat -d 2>/dev/null | grep -c KICK_TO_LOGIN)"