#!/usr/bin/env python3
"""Prometheus 지표를 조회해서 시계열마다 요약(최소·최대·평균·마지막 값)을 출력한다.

표준 라이브러리만 쓴다. 읽기 전용 API(/api/v1/query, /api/v1/query_range)만 호출한다.

예:
  prom_query.py 'mysql_up'                                   # 지금 값
  prom_query.py --start 30m 'sum(hikaricp_connections_pending)'   # 최근 30분
  prom_query.py --start 2026-11-25T14:00 --end 2026-11-25T14:20 --raw '<PromQL>'

시간 형식: now, 30m / 2h / 1d (지금부터 그만큼 전), 2026-11-25T14:00 (이 PC의 현지 시각), 유닉스 초.
Prometheus 주소: --url, 없으면 환경 변수 PROM_URL, 없으면 http://localhost:9090
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime


def parse_time(value):
    value = value.strip()
    if value == "now":
        return time.time()
    m = re.fullmatch(r"-?(\d+)([smhd])", value)  # "30m"과 "-30m" 모두 30분 전
    if m:
        unit = {"s": 1, "m": 60, "h": 3600, "d": 86400}[m.group(2)]
        return time.time() - int(m.group(1)) * unit
    if re.fullmatch(r"\d+(\.\d+)?", value):
        return float(value)
    dt = datetime.fromisoformat(value)
    return dt.timestamp() if dt.tzinfo else dt.astimezone().timestamp()


def fmt_time(ts):
    return datetime.fromtimestamp(ts).strftime("%m-%d %H:%M:%S")


def fmt_num(v):
    if v != v:  # NaN
        return "NaN"
    if abs(v) >= 1000 or v == int(v):
        return f"{v:.0f}"
    return f"{v:.4g}"


def call(url, path, params):
    full = f"{url.rstrip('/')}{path}?{urllib.parse.urlencode(params)}"
    try:
        with urllib.request.urlopen(full, timeout=15) as res:
            body = json.load(res)
    except urllib.error.HTTPError as e:
        body = json.load(e) if e.headers.get_content_type() == "application/json" else {}
        sys.exit(f"Prometheus 오류 {e.code}: {body.get('error', e.reason)}")
    except urllib.error.URLError as e:
        sys.exit(f"Prometheus에 연결할 수 없음 ({url}): {e.reason}")
    if body.get("status") != "success":
        sys.exit(f"Prometheus 오류: {body.get('error')}")
    return body["data"]


def labels_of(series):
    metric = series.get("metric", {})
    return "{" + ", ".join(f'{k}="{v}"' for k, v in sorted(metric.items())) + "}" if metric else "{}"


def fixture(query):
    """eval 모드: 장애 당시 기록해 둔 결과를 돌려준다 (KIT_EVAL_FIXTURES/prom.json).
    각 항목의 match 문자열이 모두 쿼리에 들어 있으면 그 output을 출력한다."""
    path = os.path.join(os.environ["KIT_EVAL_FIXTURES"], "prom.json")
    try:
        with open(path, encoding="utf-8") as f:
            items = json.load(f)
    except OSError:
        items = []
    q = query.lower()
    for item in items:
        if all(m.lower() in q for m in item.get("match", [])):
            print(item["output"])
            return
    print("결과 없음 (지표 이름·라벨을 확인하거나, 이 시점에 수집된 데이터가 없음)")


def main():
    p = argparse.ArgumentParser(description="Prometheus 지표 조회 요약")
    p.add_argument("query", help="PromQL")
    p.add_argument("--start", help="시작 시각. 없으면 지금 값 하나만 조회")
    p.add_argument("--end", default="now", help="끝 시각 (기본 now)")
    p.add_argument("--step", help="간격(초). 기본은 구간을 약 60개로 나눔")
    p.add_argument("--raw", action="store_true", help="요약과 함께 모든 점을 출력")
    p.add_argument("--url", default=os.environ.get("PROM_URL", "http://localhost:9090"))
    args = p.parse_args()

    if os.environ.get("KIT_EVAL_FIXTURES"):
        fixture(args.query)
        return

    if not args.start:
        data = call(args.url, "/api/v1/query", {"query": args.query})
        result = data["result"]
        if not result:
            print("결과 없음 (지표 이름·라벨을 확인하거나, 앱이 실행 중인지 확인)")
            return
        for s in result:
            ts, val = s["value"]
            print(f"{labels_of(s)}  {fmt_num(float(val))}  @ {fmt_time(float(ts))}")
        return

    start, end = parse_time(args.start), parse_time(args.end)
    if end <= start:
        sys.exit("--end는 --start보다 뒤여야 함")
    step = float(args.step) if args.step else max(5.0, round((end - start) / 60))
    data = call(args.url, "/api/v1/query_range",
                {"query": args.query, "start": start, "end": end, "step": step})
    result = data["result"]
    print(f"구간 {fmt_time(start)} ~ {fmt_time(end)}, 간격 {step:.0f}초, 시계열 {len(result)}개")
    if not result:
        print("결과 없음 (이 구간에 데이터가 없거나 지표 이름·라벨이 틀림)")
        return
    for s in result:
        points = [(float(t), float(v)) for t, v in s["values"]]
        vals = [v for _, v in points if v == v]
        print(f"\n{labels_of(s)}")
        if not vals:
            print("  값 없음 (모두 NaN: 이 구간에 요청이 없었을 수 있음)")
            continue
        t_max, v_max = max(((t, v) for t, v in points if v == v), key=lambda x: x[1])
        print(f"  최소 {fmt_num(min(vals))}  최대 {fmt_num(v_max)} (@ {fmt_time(t_max)})  "
              f"평균 {fmt_num(sum(vals) / len(vals))}  마지막 {fmt_num(points[-1][1])}  점 {len(points)}개")
        if args.raw:
            for t, v in points:
                print(f"    {fmt_time(t)}  {fmt_num(v)}")


if __name__ == "__main__":
    main()
