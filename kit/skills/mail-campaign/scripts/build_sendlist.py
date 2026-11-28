#!/usr/bin/env python3
"""안내 메일 발송 준비: 명단(CSV) + 템플릿 → 보낼 목록, 제외 목록(이유 포함), 개인별 초안. 메일을 보내지 않는다.

제외 기준: 이메일 형식 오류, 명단 안 중복, 같은 캠페인으로 이미 보냄(발송 기록), 수신 거부, 템플릿에 필요한 값 없음
사용:
  python3 build_sendlist.py --contacts data/contacts.csv --template templates/x.md --campaign 2026-12-ai-campus \
      [--sent-log data/sent_log.csv] [--optout data/optout.txt] [--out outbox]
템플릿: 첫 줄 "제목: ..." 다음 빈 줄, 그 아래 본문. {{칸이름}}은 명단의 칸 값으로, {{칸이름|기본값}}은 값이 없으면 기본값으로 바꾼다.
결과: <out>/<campaign>/list.csv, skipped.csv, drafts/NN-<회사>.md, for_sheet.csv(구글 시트 발송용: 번호·이메일·회사명·제목·본문)
"""
import argparse
import csv
import os
import re
import sys

EMAIL = re.compile(r"^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}$")
PLACE = re.compile(r"\{\{\s*([^}|]+?)\s*(?:\|\s*([^}]*?)\s*)?\}\}")
EMAIL_COLS = ["이메일", "메일", "email", "e-mail"]
COMPANY_COLS = ["회사명", "기업명", "company"]
OPTOUT_COLS = ["수신거부", "수신 거부", "optout", "opt_out", "unsubscribed"]
YES = {"y", "yes", "예", "o", "1", "true", "거부"}


def pick(header, names):
    low = {h.strip().lower(): h for h in header}
    for n in names:
        if n.lower() in low:
            return low[n.lower()]
    return None


def read_csv(path):
    with open(path, encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def safe(s):
    return re.sub(r"[^\w가-힣-]+", "_", s).strip("_")[:40] or "noname"


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--contacts", required=True)
    p.add_argument("--template", required=True)
    p.add_argument("--campaign", required=True)
    p.add_argument("--sent-log")
    p.add_argument("--optout", help="수신 거부 이메일 목록 파일 (한 줄에 하나)")
    p.add_argument("--out", default="outbox")
    a = p.parse_args()

    rows = read_csv(a.contacts)
    if not rows:
        sys.exit("명단이 비었습니다")
    header = list(rows[0].keys())
    ecol, ccol, ocol = pick(header, EMAIL_COLS), pick(header, COMPANY_COLS), pick(header, OPTOUT_COLS)
    if not ecol:
        sys.exit(f"이메일 칸을 찾지 못했습니다 (칸: {', '.join(header)})")
    text = open(a.template, encoding="utf-8").read()
    first, _, body = text.partition("\n")
    if not first.startswith("제목:"):
        sys.exit("템플릿 첫 줄은 '제목: ...' 이어야 합니다")
    subject_t, body_t = first[len("제목:"):].strip(), body.lstrip("\n")

    sent = set()
    if a.sent_log and os.path.exists(a.sent_log):
        for r in read_csv(a.sent_log):
            if (r.get("campaign") or "").strip() == a.campaign:
                sent.add((r.get("email") or "").strip().lower())
    optout = set()
    if a.optout and os.path.exists(a.optout):
        optout = {l.strip().lower() for l in open(a.optout, encoding="utf-8") if l.strip()}

    def fill(t, r):
        missing = []

        def rep(m):
            col, default = m.group(1), m.group(2)
            v = (r.get(col) or "").strip()
            if v:
                return v
            if default is not None:
                return default
            missing.append(col)
            return m.group(0)
        return PLACE.sub(rep, t), missing

    out_dir = os.path.join(a.out, a.campaign)
    os.makedirs(os.path.join(out_dir, "drafts"), exist_ok=True)
    send, skip, seen = [], [], set()
    for i, r in enumerate(rows, start=2):
        email = (r.get(ecol) or "").strip()
        company = (r.get(ccol) or "").strip() if ccol else ""
        key = email.lower()
        reason = None
        if not email:
            reason = "이메일 없음"
        elif not EMAIL.match(email):
            reason = "이메일 형식 오류"
        elif key in seen:
            reason = "명단 안 중복"
        elif key in sent:
            reason = "이미 보냄 (같은 캠페인)"
        elif key in optout or (ocol and (r.get(ocol) or "").strip().lower() in YES):
            reason = "수신 거부"
        subject = body_out = None
        if not reason:
            subject, m1 = fill(subject_t, r)
            body_out, m2 = fill(body_t, r)
            if m1 or m2:
                reason = "템플릿 값 없음: " + ", ".join(sorted(set(m1 + m2)))
        if reason:
            skip.append({"행": i, "이메일": email, "회사명": company, "이유": reason})
            continue
        seen.add(key)
        n = len(send) + 1
        draft = os.path.join(out_dir, "drafts", f"{n:02d}-{safe(company or email)}.md")
        with open(draft, "w", encoding="utf-8") as f:
            f.write(f"받는 사람: {email}\n제목: {subject}\n\n{body_out}")
        send.append({"번호": n, "이메일": email, "회사명": company, "제목": subject, "초안": os.path.relpath(draft, out_dir),
                     "본문": body_out})

    for name, data, cols in (("list.csv", send, ["번호", "이메일", "회사명", "제목", "초안"]),
                             ("for_sheet.csv", send, ["번호", "이메일", "회사명", "제목", "본문"]),
                             ("skipped.csv", skip, ["행", "이메일", "회사명", "이유"])):
        with open(os.path.join(out_dir, name), "w", encoding="utf-8-sig", newline="") as f:
            w = csv.DictWriter(f, fieldnames=cols, extrasaction="ignore")
            w.writeheader()
            w.writerows(data)
    print(f"캠페인 {a.campaign}: 명단 {len(rows)}명 → 보낼 대상 {len(send)}명, 제외 {len(skip)}명")
    print(f"결과: {out_dir}/list.csv, skipped.csv, drafts/, for_sheet.csv (메일은 보내지 않았습니다)")
    if skip:
        print("\n| 행 | 이메일 | 회사명 | 제외 이유 |\n|---|---|---|---|")
        for s in skip:
            print(f"| {s['행']} | {s['이메일'] or '-'} | {s['회사명'] or '-'} | {s['이유']} |")


if __name__ == "__main__":
    main()
