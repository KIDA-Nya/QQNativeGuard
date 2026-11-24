#!/system/bin/sh
# 备份 sp_imei 相关文件，准备修改
PKG=com.tencent.mobileqq
TS=$(date +%Y%m%d_%H%M%S)

echo "=== 1. QQ 进程状态 ==="
PID=$(pidof $PKG)
echo "  pid=$PID"
if [ -n "$PID" ]; then
  echo "  ⚠️ QQ 正在运行，需先停止才能安全修改 MMKV"
fi

echo
echo "=== 2. 备份关键文件 ==="
mkdir -p /data/local/tmp/guid_fix_backup_$TS
cp /data/user/0/$PKG/files/wlogin_device.dat /data/local/tmp/guid_fix_backup_$TS/ 2>/dev/null
cp /data/user/0/$PKG/files/mmkv/wtlogin_mmkv_file /data/local/tmp/guid_fix_backup_$TS/ 2>/dev/null
cp /data/user/0/$PKG/files/mmkv/msf_mmkv_file /data/local/tmp/guid_fix_backup_$TS/ 2>/dev/null
ls -la /data/local/tmp/guid_fix_backup_$TS/

echo
echo "=== 3. 当前 sp_imei 值 ==="
grep -aoE 'MSF\.C\.Util_@Stri_sp_imei.{0,22}' \
  /data/user/0/$PKG/files/mmkv/msf_mmkv_file 2>/dev/null | head -1

echo
echo "=== 4. 当前 FAKE_IMEI 配置 ==="
grep 'FAKE_IMEI' /data/local/tmp/qqbypass.conf 2>/dev/null

echo
echo "=== 5. sp_imei 是否进入上报载荷（检查 wup_dump）==="
WUP=/storage/emulated/0/Android/data/$PKG/wup_dump.log
if [ -f "$WUP" ]; then
  # 检查 WLOGIN_JSON 里有没有 imei 字段
  grep -ac 'imei' "$WUP" 2>/dev/null
  echo "  (以上是含 imei 的行数)"
fi