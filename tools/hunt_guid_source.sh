#!/system/bin/sh
# 穷尽搜索：GUID 7a7b6091... 到底存在哪些文件里
# 已试过：wlogin_device.dat（无效）、wtlogin_mmkv_file 的 android_id 键（无效）

PKG=com.tencent.mobileqq
GUID="7a7b60913d36d97303f7d28f13edcc0d"

echo "=== 全盘搜索该 GUID（排除已知的 2 处）==="
grep -ral "$GUID" /data/user/0/$PKG/ 2>/dev/null | while read f; do
  case "$f" in
    *wlogin_device.dat) echo "  [已知无效] $f" ;;
    *wtlogin_mmkv_file) echo "  [已知无效] $f" ;;
    *) echo "  ★ [候选] $f" ;;
  esac
done

echo
echo "=== 检查 msf_mmkv_file（MSF 也有同名 guid）==="
grep -ac "$GUID" /data/user/0/$PKG/files/mmkv/msf_mmkv_file 2>/dev/null
echo "  (1=存在)"

echo
echo "=== last_android_id / last_guid 的实际值 ==="
for key in "WLOGIN_DEVICE_INFO_@Stri_last_android_id" "WLOGIN_DEVICE_INFO_@Stri_last_guid"; do
  V=$(grep -aoE "${key}.{1,40}" /data/user/0/$PKG/files/mmkv/wtlogin_mmkv_file 2>/dev/null | head -1)
  echo "  $V"
done

echo
echo "=== 其他可能的位置 ==="
for p in \
  /data/user/0/$PKG/files/wlogin_* \
  /data/user/0/$PKG/app_wtlogin* \
  /data/user/0/$PKG/shared_prefs/*wlogin* ; do
  [ -e "$p" ] && echo "  存在: $p"
done

echo
echo "=== 检查 app_wtlogin 目录 ==="
ls -la /data/user/0/$PKG/app_wtlogin 2>/dev/null | head -10