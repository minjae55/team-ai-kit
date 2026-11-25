#!/usr/bin/env bash
# 성능 증거 수집: k6 부하 + JFR + 만료 시점 스레드 덤프 + Prometheus 지표 JSON + Grafana 캡처
# 사용: collect.sh <label> <증거 폴더> <JFR 폴더>
set -u
LABEL="$1"; OUT="$2"; JFRDIR="$3"
REPO=/Users/ming/study/stylehub-b2b-perf
JDK=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT" "$JFRDIR"
PID=$($JDK/jcmd -l | awk '/BackendApplication/{print $1}')
[ -n "$PID" ] || { echo "앱 프로세스를 찾지 못함"; exit 1; }

START=$(date +%s)
$JDK/jcmd "$PID" JFR.start name=ev settings=profile duration=240s filename="$JFRDIR/$LABEL.jfr" > /dev/null

cd "$REPO"
k6 run --summary-export "$OUT/k6-summary.json" k6/negotiation-cache-expiry.js > "$OUT/k6-output.txt" 2>&1 &
K6=$!

until grep -q 'WARM_DONE' "$OUT/k6-output.txt" 2>/dev/null; do sleep 1; done
WARM=$(grep -o 'WARM_DONE [0-9]*' "$OUT/k6-output.txt" | head -1 | awk '{printf "%.3f", $2/1000}')
echo "warm at $WARM"

# 캐시를 채운 지 60초 전후(만료 시점)에 0.5초 간격으로 스레드 덤프
mkdir -p "$OUT/threads"
for off in 59.0 59.5 60.0 60.5 61.0 61.5 62.0 63.0 65.0; do
  python3 -c "import time; t=$WARM+$off-time.time(); time.sleep(max(0,t))"
  $JDK/jcmd "$PID" Thread.print > "$OUT/threads/t+${off}s.txt" 2>&1
done

wait $K6
END=$(date +%s)
$JDK/jcmd "$PID" JFR.stop name=ev > /dev/null 2>&1
printf '{"label":"%s","start":%s,"warm":%s,"end":%s}\n' "$LABEL" "$START" "$WARM" "$END" > "$OUT/times.json"

python3 "$HERE/summarize.py" "$OUT" "$JFRDIR/$LABEL.jfr"

FROM=$(( ${WARM%.*} * 1000 - 5000 )); TO=$(( END * 1000 + 5000 ))
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --disable-gpu --hide-scrollbars \
  --window-size=1600,1000 --virtual-time-budget=15000 --user-data-dir="$HERE/chrome-prof" \
  --screenshot="$OUT/grafana.png" \
  "http://127.0.0.1:3000/d/stylehub-negotiation-list?orgId=1&from=$FROM&to=$TO&kiosk&theme=light" > /dev/null 2>&1
echo "done: $OUT"
