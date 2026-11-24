#!/system/bin/sh
# 实验 2：验证 MMKV 是 generateGuid 的真正源头
# 上次实验证明改 wlogin_device.dat 无效，这次改 MMKV

PKG=com.tencent.mobileqq
MMKV=/data/user/0/$PKG/files/mmkv/wtlogin_mmkv_file
DAT=/data/user/0/$PKG/files/wlogin_device.dat

echo "=== 先恢复 dat 文件（上次实验改坏了）==="
cp /data/local/tmp/wlogin_device.dat.exp_backup "$DAT" 2>/dev/null
echo "  恢复后:"
od -A n -t x1 "$DAT" 2>/dev/null

echo
echo "=== 检查 MMKV 文件结构 ==="
ls -la "$MMKV" 2>/dev/null

echo
echo "=== MMKV 中 GUID 的精确偏移 ==="
GUID="7a7b60913d36d97303f7d28f13edcc0d"
OFFSET=$(grep -abo "$GUID" "$MMKV" 2>/dev/null | head -1 | cut -d: -f1)
echo "  第一次出现偏移: ${OFFSET:-未找到}"

if [ -n "$OFFSET" ]; then
  echo
  echo "=== 该位置前后 80 字节（含键名）==="
  START=$((OFFSET - 40))
  [ $START -lt 0 ] && START=0
  dd if="$MMKV" bs=1 skip=$START count=90 2>/dev/null | od -A n -c | head -8
fi

echo
echo "=== 是否有多个出现位置 ==="
grep -abo "$GUID" "$MMKV" 2>/dev/null | head -5

echo
echo "=== 实验设计 ==="
echo "  A. 停 QQ"
echo "  B. 在 MMKV 中用 sed/perl 替换 GUID → 测试值"
echo "  C. 启动 QQ，看 generateGuid() 返回什么"
echo "  D. 若跟着变 → MMKV 是源头（可用此法轮换）"
echo "  E. 若不变 → 还有第三层来源"
echo
echo "  注意：MMKV 是二进制格式，直接字节替换可能破坏长度前缀。"
echo "  安全做法：用等长替换（16 字节 → 16 字节，长度不变）"