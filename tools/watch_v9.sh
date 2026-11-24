#!/system/bin/sh
# QsecEst/V9 会话监控：记录存活、上报、风险信号
LOG=/sdcard/qq_v9_watch.log
PKG=com.tencent.mobileqq

[ -f "$LOG" ] || echo "#ts|event|detail" > "$LOG"

START=$(date +%s)
echo "$START|SESSION_START|login" >> "$LOG"

while true; do
  NOW=$(date +%s)
  ELAPSED=$((NOW - START))
  PID=$(pidof $PKG)

  if [ -z "$PID" ]; then
    echo "$NOW|PROCESS_GONE|elapsed=${ELAPSED}s" >> "$LOG"
    am start -n $PKG/.activity.SplashActivity > /dev/null 2>&1
    START=$NOW
    sleep 20
    continue
  fi

  # 踢线检测
  K=$(logcat -d -t 120 2>/dev/null | grep -c "KICK_TO_LOGIN")
  if [ "$K" -gt 0 ]; then
    echo "$NOW|KICK|elapsed=${ELAPSED}s" >> "$LOG"
  fi

  # 风险等级观测
  R=$(logcat -d -s QQEnhancedBypass:V 2>/dev/null | grep "RISK-OBSERVE" | tail -1)
  if [ -n "$R" ]; then
    echo "$NOW|RISK_LEVEL|$R" >> "$LOG"
  fi

  # QsecEst 计数
  QC=$(grep -ac 'QsecEst.d' /storage/emulated/0/Android/data/com.tencent.mobileqq/wup_dump.log 2>/dev/null)
  MH=$((ELAPSED / 1800))
  if [ $((ELAPSED % 1800)) -lt 60 ]; then
    echo "$NOW|ALIVE|elapsed=${ELAPSED}s qsec=$QC" >> "$LOG"
  fi

  sleep 60
done