#!/system/bin/sh
# QQ 自杀诊断日志：只记录与 EXIT_SELF / 崩溃 / hook 相关的行
# 输出：/sdcard/qq_suicide_diag.log

OUT=/sdcard/qq_suicide_diag.log
PKG=com.tencent.mobileqq

[ -f "$OUT" ] || echo "#ts|tag|msg" > "$OUT"

echo "$(date +%s)|WATCHER_START|pid=$(pidof $PKG)" >> "$OUT"

# 只抓关键 tag，避免日志爆量
logcat -v time -s \
    ActivityManager:I \
    AndroidRuntime:E \
    DEBUG:E \
    libc:F \
    QQNativeBypass:V \
    QQEnhancedBypass:V \
    System.err:W \
    MSF:E \
    native:E \
    2>/dev/null | while IFS= read -r line; do

    TS=$(date +%s)

    case "$line" in
        # 进程死亡 / 退出信息（最关键）
        *"has died"*|*"EXIT_SELF"*|*"ApplicationExitInfo"*|*"handleAppExit"*)
            echo "$TS|EXIT|$line" >> "$OUT" ;;

        # QQ 自杀标志
        *"VM exiting"*|*"exit(255)"*|*"Runtime.exit"*|*"System.exit"*)
            echo "$TS|SELFKILL|$line" >> "$OUT" ;;

        # 崩溃信号
        *"Fatal signal"*|*"SIGSEGV"*|*"SIGABRT"*|*"SIGBUS"*|*"Fatal exception"*)
            echo "$TS|CRASH|$line" >> "$OUT" ;;

        # Java 异常
        *"FATAL EXCEPTION"*|*"Process: com.tencent.mobileqq"*)
            echo "$TS|JAVA_EXC|$line" >> "$OUT" ;;

        # hook 侧异常
        *"QQNativeBypass"*|*"QQEnhancedBypass"*)
            case "$line" in
                *"fail"*|*"Fail"*|*"error"*|*"Error"*|*"abort"*|*"abort"*)
                    echo "$TS|HOOKERR|$line" >> "$OUT" ;;
                *)
                    echo "$TS|HOOK|$line" >> "$OUT" ;;
            esac ;;

        # 原生层
        *"native"*|*"JNI"*|*"checkJNI"*|*"CheckJNI"*)
            echo "$TS|NATIVE|$line" >> "$OUT" ;;
    esac
done