#!/usr/bin/env python3
"""실제로 보낸 뒤 발송 기록에 남긴다. 사람이 "보냈다"고 확인한 뒤에만 실행한다 (--confirm 필수).
다음 발송 때 build_sendlist.py가 이 기록으로 같은 사람에게 두 번 보내지 않게 막는다.

사용: python3 log_sent.py outbox/<campaign>/list.csv --campaign <campaign> --sent-log data/sent_log.csv --confirm [--only 1,2,5]
"""
import argparse
import csv
import datetime as dt
import os
import sys


def main():
    p = argparse.ArgumentParser()
    p.add_argument("list_csv")
    p.add_argument("--campaign", required=True)
    p.add_argument("--sent-log", required=True)
    p.add_argument("--only", help="실제로 보낸 번호만 (쉼표). 생략하면 목록 전체")
    p.add_argument("--confirm", action="store_true", help="실제로 보냈음을 사람이 확인함")
    a = p.parse_args()
    if not a.confirm:
        sys.exit("실제로 보낸 뒤 사람이 확인했을 때만 --confirm 을 붙여 실행하세요")
    only = {s.strip() for s in a.only.split(",")} if a.only else None
    with open(a.list_csv, encoding="utf-8-sig", newline="") as f:
        rows = [r for r in csv.DictReader(f) if only is None or r["번호"] in only]
    new = not os.path.exists(a.sent_log)
    now = dt.datetime.now().astimezone().isoformat(timespec="seconds")
    with open(a.sent_log, "a", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        if new:
            w.writerow(["campaign", "email", "company", "sent_at"])
        for r in rows:
            w.writerow([a.campaign, r["이메일"], r["회사명"], now])
    print(f"발송 기록 {len(rows)}건 추가: {a.sent_log}")


if __name__ == "__main__":
    main()
