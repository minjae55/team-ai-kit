#!/usr/bin/env python3
"""증거 폴더를 읽어 metrics.json(대시보드가 읽을 데이터)과 요약을 만든다."""
import glob, json, os, re, subprocess, sys, urllib.parse, urllib.request

OUT, JFR = sys.argv[1], sys.argv[2]
PROM = "http://localhost:9090"
JDK = "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin"
t = json.load(open(f"{OUT}/times.json"))
U = 'uri="/api/negotiations",method="GET"'


def series(expr, start, end, step=1):
    q = urllib.parse.urlencode({"query": expr, "start": start, "end": end, "step": step})
    with urllib.request.urlopen(f"{PROM}/api/v1/query_range?{q}", timeout=30) as r:
        res = json.load(r)["data"]["result"]
    if not res:
        return []
    return [(float(a), float(b)) for a, b in res[0]["values"] if b not in ("NaN", "+Inf")]


start, end = t["warm"] + 2, t["end"]
exprs = {
    "p95_seconds": f"histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{{{U}}}[10s])))",
    "pool_pending": "sum(hikaricp_connections_pending)",
    "pool_active": "sum(hikaricp_connections_active)",
    "acquire_avg_seconds": "sum(rate(hikaricp_connections_acquire_seconds_sum[10s])) / sum(rate(hikaricp_connections_acquire_seconds_count[10s]))",
    "mysql_qps": "rate(mysql_global_status_queries[10s])",
    "rps": f"sum(rate(http_server_requests_seconds_count{{{U}}}[10s]))",
}
metrics = {}
for name, e in exprs.items():
    pts = series(e, start, end, 5)
    vals = [v for _, v in pts]
    metrics[name] = {
        "max": max(vals) if vals else None,
        "max_at": max(pts, key=lambda p: p[1])[0] if pts else None,
        "avg": sum(vals) / len(vals) if vals else None,
        "points": [[round(a), round(b, 4)] for a, b in pts],
    }

k6 = json.load(open(f"{OUT}/k6-summary.json"))
dur = k6["metrics"].get("http_req_duration{name:negotiation_list}") or k6["metrics"]["http_req_duration"]
failed = k6["metrics"]["http_req_failed"]

# 스레드 덤프: 톰캣 요청 스레드가 어디서 대기하는지
dumps = {}
for f in sorted(glob.glob(f"{OUT}/threads/*.txt")):
    text = open(f).read()
    blocks = [b for b in text.split("\n\n") if b.startswith('"http-nio')]
    waiting_conn = sum(1 for b in blocks if "HikariPool.getConnection" in b or "ConcurrentBag.borrow" in b)
    in_jdbc = sum(1 for b in blocks if "com.mysql.cj" in b and "RUNNABLE" in b)
    dumps[os.path.basename(f)[:-4]] = {"http_threads": len(blocks), "waiting_connection": waiting_conn, "in_jdbc_runnable": in_jdbc}

# JFR 요약 (대기 이벤트 상위)
jfr_summary = {}
try:
    out = subprocess.run([f"{JDK}/jfr", "summary", JFR], capture_output=True, text=True, timeout=120).stdout
    for line in out.splitlines():
        m = re.match(r"\s*(jdk\.\w+)\s+(\d+)\s+(\d+)", line)
        if m and m.group(1) in ("jdk.ThreadPark", "jdk.JavaMonitorEnter", "jdk.SocketRead", "jdk.ExecutionSample", "jdk.GarbageCollection"):
            jfr_summary[m.group(1)] = int(m.group(2))
    open(f"{OUT}/jfr-summary.txt", "w").write(out)
except Exception as e:
    jfr_summary["error"] = str(e)

result = {
    "label": t["label"],
    "times": t,
    "k6": {
        "p95_ms": round(dur["p(95)"], 1), "p90_ms": round(dur["p(90)"], 1), "avg_ms": round(dur["avg"], 1),
        "max_ms": round(dur["max"], 1), "error_rate": failed.get("value", failed.get("rate")),
        "requests": k6["metrics"]["http_reqs"]["count"],
    },
    "prometheus": metrics,
    "thread_dumps": dumps,
    "jfr_event_counts": jfr_summary,
}
json.dump(result, open(f"{OUT}/metrics.json", "w"), ensure_ascii=False, indent=2)

print(f"[{t['label']}] k6 p95 {result['k6']['p95_ms']}ms, max {result['k6']['max_ms']}ms, 에러율 {result['k6']['error_rate']}")
for k in ("p95_seconds", "pool_pending", "acquire_avg_seconds", "mysql_qps"):
    print(f"  {k}: 최대 {metrics[k]['max']}")
peak = max(dumps.items(), key=lambda kv: kv[1]["waiting_connection"]) if dumps else None
if peak:
    print(f"  스레드 덤프 최대 커넥션 대기: {peak[1]['waiting_connection']}/{peak[1]['http_threads']} ({peak[0]})")
print(f"  JFR: {jfr_summary}")
