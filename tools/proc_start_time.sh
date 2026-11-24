#!/system/bin/sh
# 精确计算进程启动时刻
PID=$1
[ -z "$PID" ] && PID=$(pidof com.tencent.mobileqq)
[ -z "$PID" ] && { echo "no pid"; exit 1; }

UPTIME=$(awk '{print int($1)}' /proc/uptime 2>/dev/null)
STARTTIME=$(awk '{print $22}' /proc/$PID/stat 2>/dev/null)
HZ=$(getconf CLK_TCK 2>/dev/null || echo 100)

if [ -z "$UPTIME" ] || [ -z "$STARTTIME" ]; then
  echo "read fail"
  exit 1
fi

# starttime 单位是 clock tick
START_SEC=$((STARTTIME / HZ))
BOOT_EPOCH=$(( $(date +%s) - UPTIME ))
PROC_START=$((BOOT_EPOCH + START_SEC))
ELAPSED=$(( $(date +%s) - PROC_START ))

echo "pid=$PID"
echo "boot_epoch=$(date -d @$BOOT_EPOCH '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo $BOOT_EPOCH)"
echo "proc_start=$(date -d @$PROC_START '+%H:%M:%S' 2>/dev/null || echo $PROC_START)"
echo "elapsed_sec=$ELAPSED"
echo "elapsed_h=$(awk "BEGIN{printf \"%.1f\", $ELAPSED/3600}")"