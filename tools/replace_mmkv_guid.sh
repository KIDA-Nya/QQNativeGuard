#!/system/bin/sh
# 等长替换 MMKV 中的 GUID
# 注意：MMKV 二进制格式，16 字节 → 16 字节（32 hex chars）等长替换安全

PKG=com.tencent.mobileqq
MMKV=/data/user/0/$PKG/files/mmkv/wtlogin_mmkv_file

OLD="7a7b60913d36d97303f7d28f13edcc0d"
NEW="deadbeef00112233445566778899aabb"

echo "=== 替换前统计 ==="
echo "  old 出现次数: $(grep -ac "$OLD" "$MMKV" 2>/dev/null || echo 0)"

# 用 dd 做精确偏移替换（偏移 95，从前面实验得知）
OFFSET=95
echo "  偏移: $OFFSET"

# 方法：用 head + new + tail 重建文件
if [ -f "$MMKV" ]; then
  TMP=/data/local/tmp/mmkv_new.tmp
  # 前 95 字节
  dd if="$MMKV" of="$TMP" bs=1 count=$OFFSET 2>/dev/null
  # 新值（32 hex chars = 32 bytes as ASCII）
  printf '%s' "$NEW" >> "$TMP"
  # 剩余部分（跳过 95+32=127）
  dd if="$MMKV" bs=1 skip=127 2>/dev/null >> "$TMP"
  
  # 验证大小不变
  OLD_SIZE=$(stat -c %s "$MMKV" 2>/dev/null)
  NEW_SIZE=$(stat -c %s "$TMP" 2>/dev/null)
  echo "  原大小: $OLD_SIZE  新大小: $NEW_SIZE"
  
  if [ "$OLD_SIZE" = "$NEW_SIZE" ]; then
    cp "$TMP" "$MMKV"
    echo "  ✓ 替换完成（大小一致）"
    echo "  验证: $(grep -ac "$NEW" "$MMKV" 2>/dev/null || echo 0) 次命中新值"
    echo "  旧值剩余: $(grep -ac "$OLD" "$MMKV" 2>/dev/null || echo 0) 次"
  else
    echo "  ✗ 大小不一致，放弃（避免破坏 MMKV）"
  fi
  rm -f "$TMP"
fi