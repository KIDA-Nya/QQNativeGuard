#!/system/bin/sh
# 会话观测 v4：用 logcat -T 按时间戳过滤，只统计本次会话的事件
OUT=/sdcard/qq_obs_v4.log
PKG=com.tencent.mobileqq
WUP=/storage/emulated/0/Android/data/com.tencent.mobileqq/wup_dump.log

[ -f "$OUT" ] || echo "#ts|pid|elapsed|qsec|kick|risk|guard|note" > "$OUT"

START=$(date +%s)
PREV_PID=$(pidof $PKG)
# 记录会话起始时间戳（用于 logcat -T 过滤）
START_TS=$(date +"%m-%d %H:%M:%S.000")

echo "$(date +%s)|$PREV_PID|0|0|0|0|0|SESSION_START from=$START_TS" >> "$OUT"

while true; do
    NOW=$(date +%s)
    EL=$((NOW - START))
    PID=$(pidof $PKG)
    T=$(date +%H:%M:%S)

    if [ -z "$PID" ]; then
        EXITI=$(dumpsys activity exit-info $PKG 2>/dev/null | grep -E 'reason=' | head -1)
        echo "$NOW|-|$EL|0|0|0|0|PROC_GONE $EXITI" >> "$OUT"
        break
    fi

    if [ "$PID" != "$PREV_PID" ]; then
        echo "$NOW|$PID|$EL|0|0|0|0|PID_CHANGED from=$PREV_PID" >> "$OUT"
        PREV_PID=$PID
        START_TS=$(date +"%m-%d %H:%M:%S.000")
    fi

    # 关键：只统计本次会话之后的事件
    KICK=$(logcat -d -T "$START_TS" 2>/dev/null | grep -c "KICK_TO_LOGIN")
    RISK=$(logcat -d -T "$START_TS" -s QQEnhancedBypass:V 2>/dev/null | grep -c "RISK-OBSERVE")
    GUARD=$(logcat -d -T "$START_TS" -s QQEnhancedBypass:V 2>/dev/null | grep -c "GUARD-EXIT")
    QSEC=$(grep -ac 'QsecEst.d' "$WUP" 2>/dev/null || echo 0)

    if [ $((EL % 120)) -lt 62 ]; then
        echo "$NOW|$PID|$EL|$QSEC|$KICK|$RISK|$GUARD|HB $T" >> "$OUT"
    fi

    # 有事件立即记录
    if [ "$KICK" -gt 0 ]; then
        echo "$NOW|$PID|$EL|$QSEC|$KICK|$RISK|$GUARD|★KICK_THIS_SESSION $T" >> "$OUT"
    fi
    if [ "$GUARD" -gt 0 ]; then
        echo "$NOW|$PID|$EL|$QSEC|$KICK|$RISK|$GUARD|★GUARD_EXIT_THIS_SESSION $T" >> "$OUT"
    fi

    sleep 60
done