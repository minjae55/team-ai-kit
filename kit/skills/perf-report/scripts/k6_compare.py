#!/usr/bin/env python3
"""k6 결과 두 개(--summary-export JSON)를 비교해 전후 표를 Markdown으로 출력한다. 계산은 이 스크립트가 하고 AI는 옮겨 적기만 한다.

사용:
  python3 k6_compare.py before.json after.json                       # 전체 http_req_duration
  python3 k6_compare.py before.json after.json --name order_create   # 태그 name:order_create 요청만
"""
import argparse
import json
import sys


def metric(summary, base, name):
    m = summary.get("metrics", {})
    if name:
        for key in (f"{base}{{name:{name}}}", f"{base}{{name: {name}}}"):
            if key in m:
                return m[key]
        return None
    return m.get(base)


def num(d, *keys):
    if not d:
        return None
    for k in keys:
        if k in d and isinstance(d[k], (int, float)):
            return float(d[k])
    return None


def fmt(v, unit="", decimals=1):
    if v is None:
        return "-"
    return f"{v:,.{decimals}f}" + unit


def change(b, a, lower_is_better=True):
    if b is None or a is None:
        return "-"
    if b == 0:
        return "변화 없음" if a == 0 else "새로 생김"
    pct = (a - b) / b * 100
    if round(pct, 1) == 0:
        return "변화 없음"
    better = (pct < 0) == lower_is_better
    mark = "개선" if better and pct != 0 else ("악화" if pct != 0 else "")
    return f"{pct:+.1f}% {mark}".strip()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("before")
    p.add_argument("after")
    p.add_argument("--name", help="tags name 값 (예: order_create)")
    a = p.parse_args()
    b_sum, a_sum = (json.load(open(f, encoding="utf-8")) for f in (a.before, a.after))

    rows = []
    bd, ad = metric(b_sum, "http_req_duration", a.name), metric(a_sum, "http_req_duration", a.name)
    if bd is None or ad is None:
        sys.exit(f"http_req_duration{('{name:' + a.name + '}') if a.name else ''} 지표가 없습니다. "
                 f"있는 지표: {', '.join(k for k in b_sum.get('metrics', {}) if k.startswith('http_req_duration'))}")
    for label, keys in (("평균", ("avg",)), ("중앙값", ("med",)), ("p90", ("p(90)",)), ("p95", ("p(95)",)),
                        ("p99", ("p(99)",)), ("최대", ("max",))):
        bv, av = num(bd, *keys), num(ad, *keys)
        if bv is not None or av is not None:
            rows.append((f"응답 시간 {label}", fmt(bv, " ms"), fmt(av, " ms"), change(bv, av)))
    br, ar = metric(b_sum, "http_reqs", a.name) or metric(b_sum, "http_reqs", None), \
        metric(a_sum, "http_reqs", a.name) or metric(a_sum, "http_reqs", None)
    rows.append(("요청 수", fmt(num(br, "count"), decimals=0), fmt(num(ar, "count"), decimals=0), change(num(br, "count"), num(ar, "count"), False)))
    rows.append(("처리량", fmt(num(br, "rate"), " req/s"), fmt(num(ar, "rate"), " req/s"),
                 change(num(br, "rate"), num(ar, "rate"), False)))
    bf, af = metric(b_sum, "http_req_failed", None), metric(a_sum, "http_req_failed", None)
    bfv, afv = num(bf, "rate", "value"), num(af, "rate", "value")
    rows.append(("실패율", fmt(None if bfv is None else bfv * 100, " %"), fmt(None if afv is None else afv * 100, " %"),
                 change(bfv, afv)))
    bc, ac = metric(b_sum, "checks", None), metric(a_sum, "checks", None)
    if bc or ac:
        bcv, acv = num(bc, "rate", "value"), num(ac, "rate", "value")
        rows.append(("check 통과율", fmt(None if bcv is None else bcv * 100, " %"), fmt(None if acv is None else acv * 100, " %"),
                     change(bcv, acv, False)))

    target = f"`name:{a.name}` 요청" if a.name else "전체 요청"
    print(f"대상: {target}\n")
    print("| 지표 | 개선 전 | 개선 후 | 변화 |\n|---|---|---|---|")
    for r in rows:
        print("| " + " | ".join(r) + " |")


if __name__ == "__main__":
    main()
