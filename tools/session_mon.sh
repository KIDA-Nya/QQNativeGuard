#!/system/bin/sh
# 会话监控：记录存活时长 + 自杀/踢线/风险信号 + 票据刷新
LOG=/sdcard/qq_session_v2.log
PKG=com.tencent.mobileqq
WUP=/storage/emulated/0/Android/data/com.tencent.mobileqq/wup_dump.log

[ -f "$LOG" ] || echo "#pid|elapsed|qsec|kick|risk|note" > "$LOG"

START=$(date +%s)
PID0=$(pidof $PKG)
echo "START|0|0|0|0|pid=$PID0 login=20:48" >> "$LOG"

while true; do
    NOW=$(date +%s)
    EL=$((NOW - START))
    PID=$(pidof $PKG)

    # 进程消失 = 自杀或被系统杀
    if [ -z "$PID" ]; then
        echo "PROC_GONE|$EL|0|0|0|detected_at=$(date +%H:%M:%S)" >> "$LOG"
        # 查 ApplicationExitInfo
        EXIT=$(dumpsys activity exit-info $PKG 2>/dev/null | head -12 | grep -oE 'reason=[0-9]+ \([A-Z_]+\)' | head -1)
        echo "EXITINFO|$EL|0|0|0|$EXIT" >> "$LOG"
        break
    fi

    # 踢线
    KICK=$(logcat -d -t 90 2>/dev/null | grep -c "KICK_TO_LOGIN")

    # 风险等级
    RISK=$(logcat -d -s QQEnhancedBypass:V 2>/dev/null | grep -c "RISK-OBSERVE")

    # QsecEst 计数
    QSEC=$(grep -ac 'QsecEst.d' "$WUP" 2>/dev/null)

    # 每 5 分钟记录一次
    if [ $((EL % 300)) -lt 65 ]; then
        echo "$PID|$EL|$QSEC|$KICK|$RISK|hb=$(date +%H:%M:%S)" >> "$LOG"
    fi

    # 检测到踢线立即记录
    if [ "$KICK" -gt 0 ]; then
        echo "$PID|$EL|$QSEC|$KICK|$RISK|KICKED" >> "$LOG"
        # 不做任何处置，仅记录
    fi

    if [ "$RISK" -gt 0 ]; then
        R=$(logcat -d -s QQEnhancedBypass:V 2>/dev/null | grep "RISK-OBSERVE" | tail -1)
        echo "$PID|$EL|$QSEC|$KICK|$RISK|$R" >> "$LOG"
    fi

    sleep 60
done