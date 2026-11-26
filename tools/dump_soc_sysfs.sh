#!/system/bin/sh
# 列出 /sys/devices/soc0/ 所有可见文件的真机值
echo "=== /sys/devices/soc0/ 内容（真机 SoC 特征）==="
for f in chip_family chip_name family machine soc_id serial_number \
         raw_device_family raw_device_number raw_id platform_subtype \
         platform_subtype_id platform_version pmic_model pmic_die_revision \
         foundry_id hw_platform num_clusters revision vendor; do
  v=$(cat "/sys/devices/soc0/$f" 2>/dev/null)
  printf "  %-24s = %s\n" "$f" "${v:-（不可读）}"
done

echo ""
echo "=== CPU 相关 ==="
for f in /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq \
         /sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_max_freq; do
  v=$(cat "$f" 2>/dev/null)
  printf "  %-56s = %s\n" "$f" "${v:-（不可读）}"
done

echo ""
echo "=== 内存总量（机型差异指标）==="
grep -E '^MemTotal' /proc/meminfo 2>/dev/null