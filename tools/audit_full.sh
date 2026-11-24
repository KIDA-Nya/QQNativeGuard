#!/system/bin/sh
# 完整排查：QQ 能用来判定 root/注入/篡改的所有信息源
# 分类：属性 / 文件 / 进程 / 系统调用 / 内嵌数据
PKG=com.tencent.mobileqq
PID=$(pidof $PKG)

echo "=== QQ pid: $PID ==="
echo

echo "########## A. 系统属性（QQ 通过 __system_property_get）##########"
echo "--- 解锁/引导状态 ---"
for p in ro.boot.verifiedbootstate ro.boot.flash.locked ro.boot.vbmeta.device_state \
         ro.boot.veritymode ro.boot.warranty_bit ro.warranty_bit ro.vendor.warranty_bit \
         ro.boot.secboot ro.boot.rpmb_enable ro.secboot; do
  v=$(getprop $p 2>/dev/null)
  [ -n "$v" ] && echo "  $p = $v"
done
echo
echo "--- 调试/构建类型 ---"
for p in ro.debuggable ro.secure ro.build.type ro.build.tags ro.build.selinux \
         ro.adb.secure service.adb.root persist.sys.usb.config; do
  v=$(getprop $p 2>/dev/null)
  [ -n "$v" ] && echo "  $p = $v"
done
echo
echo "--- 平台/机型真值 ---"
for p in ro.board.platform ro.soc.model ro.chipname ro.hardware \
         ro.product.model ro.product.device ro.product.name; do
  v=$(getprop $p 2>/dev/null)
  [ -n "$v" ] && echo "  $p = $v"
done
echo

echo "########## B. 文件系统（QQ 通过 fopen/stat）##########"
echo "--- root 框架目录 ---"
for d in /data/adb /data/adb/modules /data/adb/ksu /data/adb/magisk /sbin/.magisk /debug_ramdisk; do
  [ -e "$d" ] && echo "  存在: $d"
done
echo
echo "--- su / 提权二进制 ---"
for f in /system/bin/su /system/xbin/su /sbin/su /su/bin/su /data/adb/ksu/bin/su \
         /system/bin/failsafe/su /system/sd/xbin/su; do
  [ -e "$f" ] && echo "  存在: $f"
done
echo
echo "--- 模块列表（Zygisk/LSPosed）---"
ls /data/adb/modules/ 2>/dev/null | head -10
echo
echo "--- LSPosed 痕迹 ---"
for d in /data/adb/lspd /data/misc/lspd /data/local/tmp/lspd; do
  [ -e "$d" ] && echo "  存在: $d"
done
echo

echo "########## C. /proc 接口 ##########"
echo "--- /proc/cmdline 关键 token ---"
cat /proc/cmdline 2>/dev/null | tr ' ' '\n' | grep -E 'verifiedboot|flash.locked|vbmeta|warranty|project_codename|platform_name|type=|bootreason'
echo
echo "--- /proc/self/status TracerPid ---"
grep -a TracerPid /proc/self/status 2>/dev/null
echo
echo "--- /proc/mounts 里的 magisk/overlay ---"
cat /proc/self/mounts 2>/dev/null | grep -icE 'magisk|ksu|overlay'
echo "  (命中行数)"
echo

echo "########## D. 进程 ##########"
echo "--- root 管理进程 ---"
ps -A 2>/dev/null | grep -icE 'magiskd|ksud|apd'
echo "  (命中行数)"
echo
echo "--- 调试相关进程 ---"
ps -A 2>/dev/null | grep -icE 'gdbserver|frida'
echo "  (命中行数)"
echo

echo "########## E. 包名 ##########"
echo "--- root/Xposed 管理包 ---"
pm list packages 2>/dev/null | grep -icE 'magisk|kernelsu|ksu|supersu|apatch|shamiko|lsposed|taichi|virtualxposed|xposed'
echo "  (命中行数，已隐藏则为 0)"
echo
echo "--- 我们的模块是否可见 ---"
pm list packages 2>/dev/null | grep -c 'qqnativeguard\|qqenhanced'
echo "  (1=可见，会被 QQ 的 Root 检测扫到)"
echo

echo "########## F. 内核信息 ##########"
echo "--- uname ---"
uname -a 2>/dev/null
echo
echo "--- /proc/version ---"
cat /proc/version 2>/dev/null
echo
echo "--- /proc/cpuinfo Hardware ---"
grep -i hardware /proc/cpuinfo 2>/dev/null
echo

echo "########## G. SELinux ##########"
getenforce 2>/dev/null
echo

echo "########## H. 存储挂载（sdcardfs/fuse）##########"
mount 2>/dev/null | grep -E '/storage|/sdcard' | head -3