#!/bin/bash
# ScrcpyCast 持续性压力监控
# 持续采集两端性能指标，直到 Ctrl+C 生成报告

DJI="9YXCN7C0013ZL6"
RCV="192.168.1.210:5555"
INTERVAL=10
OUTDIR="/tmp/scrcpycast_monitor_$(date +%Y%m%d_%H%M%S)"
RAWLOG="$OUTDIR/raw.log"
REPORT="$OUTDIR/report.txt"
mkdir -p "$OUTDIR"

echo "======================================"
echo " ScrcpyCast 持续监控"
echo " 输出: $OUTDIR"
echo " 采集间隔: ${INTERVAL}s"
echo " 提示: Ctrl+C 结束并生成报告"
echo "======================================"

adb -s $DJI shell echo OK 2>/dev/null || { echo "DJI端连接失败"; exit 1; }
adb -s $RCV shell echo OK 2>/dev/null || { echo "接收端连接失败"; exit 1; }
adb -s $DJI logcat -c 2>/dev/null || true
adb -s $RCV logcat -c 2>/dev/null || true

echo "START: $(date '+%Y-%m-%d %H:%M:%S')" > "$RAWLOG"
echo "DJI_DEVICE: DJI RC Plus 2 (7.9GB)" >> "$RAWLOG"
echo "RCV_DEVICE: Amlogic S905X-SM (3.7GB)" >> "$RAWLOG"
echo "DJI_MEM_TOTAL: $(adb -s $DJI shell 'cat /proc/meminfo | grep MemTotal' | awk '{print $2}') kB" >> "$RAWLOG"
echo "RCV_MEM_TOTAL: $(adb -s $RCV shell 'cat /proc/meminfo | grep MemTotal' | awk '{print $2}') kB" >> "$RAWLOG"
echo "" >> "$RAWLOG"

SAMPLE=0
START_TS=$(date +%s)

cleanup() {
    echo ""
    echo ""
    echo "======================================"
    echo " 监控结束，生成报告..."
    echo "======================================"
    TOTAL_SAMPLES=$(grep -c '^=== SAMPLE=' "$RAWLOG" || echo 0)
    {
        echo "ScrcpyCast 压力测试报告"
        echo "生成时间: $(date '+%Y-%m-%d %H:%M:%S')"
        echo ""
        echo "--- 设备信息 ---"
        head -5 "$RAWLOG"
        echo ""
        echo "监控样本: ${TOTAL_SAMPLES} 个 (${INTERVAL}s间隔)"
        echo ""

        echo "--- DJI端 编码器 (c2.qti.avc.encoder) ---"

        echo "[帧率 (fps)]"
        grep 'realFR' "$RAWLOG" | sed 's/.*realFR //' | awk '{print $1}' | sort -n | awk '
            {v[NR]=$1}
            END{
                if(NR>0){
                    s=0; for(i=1;i<=NR;i++) s+=v[i]
                    print "  样本: " NR
                    printf "  平均: %.1f\n", s/NR
                    print "  最低: " v[1]
                    print "  最高: " v[NR]
                } else print "  (无数据)"
            }'

        echo "[编码延迟 (us)]"
        grep 'latency' "$RAWLOG" | sed 's/.*latency max //' | awk '{print $1}' | sed 's/us,*//' | sort -n | awk '
            {v[NR]=$1}
            END{
                if(NR>0){
                    s=0; for(i=1;i<=NR;i++) s+=v[i]
                    print "  样本: " NR
                    printf "  平均: %.0f\n", s/NR
                    print "  最低: " v[1]
                    print "  最高: " v[NR]
                } else print "  (无数据)"
            }'

        TOTAL_FRAMES=$(grep 'Sent frame #' "$RAWLOG" | tail -1 | sed 's/.*#//;s/ .*//' || echo 0)
        echo "[总发送帧数] $TOTAL_FRAMES"

        echo "[帧大小 (bytes)]"
        grep 'Sent frame' "$RAWLOG" | sed 's/.*size=//;s/ .*//' | sort -n | awk '
            {v[NR]=$1}
            END{
                if(NR>0){
                    s=0; for(i=1;i<=NR;i++) s+=v[i]
                    print "  样本: " NR
                    printf "  平均: %.0f\n", s/NR
                    print "  最小: " v[1]
                    print "  最大: " v[NR]
                } else print "  (无数据)"
            }'

        echo "[关键帧 (I-frame) 大小]"
        grep 'Sent frame.*keyFrame=true' "$RAWLOG" | sed 's/.*size=//;s/ .*//' | sort -n | awk '
            {v[NR]=$1}
            END{
                if(NR>0){
                    s=0; for(i=1;i<=NR;i++) s+=v[i]
                    print "  数量: " NR
                    printf "  平均: %.0f bytes\n", s/NR
                    print "  最小: " v[1]
                    print "  最大: " v[NR]
                } else print "  无"
            }'

        echo "[DJI端 内存 RSS (MB)]"
        grep 'MEM_VmRSS' "$RAWLOG" | grep '\[DJI\]' | sed 's/.*VmRSS: *//;s/ .*//' | sort -n | awk '
            {v[NR]=$1/1024}
            END{
                if(NR>0){
                    s=0; for(i=1;i<=NR;i++) s+=v[i]
                    print "  样本: " NR
                    printf "  平均: %.1f\n", s/NR
                    printf "  最低: %.1f\n", v[1]
                    printf "  最高: %.1f\n", v[NR]
                } else print "  (无数据)"
            }'

        ERR_COUNT=$(grep -c 'Casting error\|错误' "$RAWLOG" || echo 0)
        echo "[DJI端 编码错误] $ERR_COUNT"
        echo ""

        echo "--- 接收端 解码器 (Amlogic VDA) ---"

        echo "[VDA 解码统计 (末次)]"
        grep 'INs=' "$RAWLOG" | tail -3 | sed 's/.*/  &/'

        PW_SUM=$(grep 'PW_ERRORS=' "$RAWLOG" | sed 's/.*PW_ERRORS=//' | awk '{s+=$1} END{print s+0}')
        echo "[PipelineWatcher 错误] $PW_SUM"

        echo "[接收端 内存 RSS (MB)]"
        grep 'MEM_VmRSS' "$RAWLOG" | grep '\[RCV\]' | sed 's/.*VmRSS: *//;s/ .*//' | sort -n | awk '
            {v[NR]=$1/1024}
            END{
                if(NR>0){
                    s=0; for(i=1;i<=NR;i++) s+=v[i]
                    print "  样本: " NR
                    printf "  平均: %.1f\n", s/NR
                    printf "  最低: %.1f\n", v[1]
                    printf "  最高: %.1f\n", v[NR]
                } else print "  (无数据)"
            }'

        echo ""
        echo "--- 原始数据 ---"
        echo "  $RAWLOG"
    } > "$REPORT"

    cat "$REPORT"
    echo ""
    echo "报告已保存: $REPORT"
    exit 0
}
trap cleanup SIGINT SIGTERM

echo "请在 DJI 端操作..."
echo ""

while true; do
    SAMPLE=$((SAMPLE + 1))
    NOW=$(date '+%H:%M:%S')
    ELAPSED=$(( $(date +%s) - START_TS ))
    echo -ne "\r[${ELAPSED}s] 采样 #$SAMPLE"

    {
        echo "=== SAMPLE=$SAMPLE TIME=$NOW ELAPSED=${ELAPSED}s ==="

        echo "[DJI]"
        DJI_PID=$(adb -s $DJI shell 'pgrep -f com.scrcpycast | head -1' 2>/dev/null | tr -d '\r')
        echo "PID=$DJI_PID"
        if [ -n "$DJI_PID" ]; then
            adb -s $DJI shell "cat /proc/$DJI_PID/status 2>/dev/null" | grep -E 'VmRSS|Threads' | awk '{print "MEM_"$1"="$2}'
            adb -s $DJI shell logcat -d -v time 2>/dev/null | grep -E 'ServerService.*Sent frame' | tail -1
            adb -s $DJI shell logcat -d -v time 2>/dev/null | grep -E "MediaCodec.*com\.scrcpycast.*AVC" | tail -1
            adb -s $DJI shell logcat -d -v time 2>/dev/null | grep -E 'CCodecBufferChannel.*encoder.*DEBUG' | tail -1
            adb -s $DJI shell logcat -d -v time 2>/dev/null | grep -E 'ServerService.*Casting error|ServerService.*错误' | tail -1
        else
            echo "STATUS=not_running"
        fi

        echo "[RCV]"
        RCV_PID=$(adb -s $RCV shell 'pgrep -f com.scrcpycast.receiver | head -1' 2>/dev/null | tr -d '\r')
        echo "PID=$RCV_PID"
        if [ -n "$RCV_PID" ]; then
            adb -s $RCV shell "cat /proc/$RCV_PID/status 2>/dev/null" | grep -E 'VmRSS|Threads' | awk '{print "MEM_"$1"="$2}'
            adb -s $RCV shell logcat -d -v time 2>/dev/null | grep -E 'VDA.*ServiceDeviceTask' | tail -1
            PW_ERR=$(adb -s $RCV shell logcat -d -v time 2>/dev/null | grep -c 'PipelineWatcher.*frameIndex not found' || echo 0)
            echo "PW_ERRORS=$PW_ERR"
            adb -s $RCV shell logcat -d -v time 2>/dev/null | grep -E 'Decoder output format' | tail -1
        else
            echo "STATUS=not_running"
        fi
        echo "---"
    } >> "$RAWLOG"

    sleep $INTERVAL
done
