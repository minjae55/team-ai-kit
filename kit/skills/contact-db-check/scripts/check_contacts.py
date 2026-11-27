#!/usr/bin/env python3
"""기업·담당자 DB(CSV) 점검: 중복, 같은 회사 다른 표기, 필수 칸 누락, 이메일·전화 형식, 출처 누락, 오래된 확인일.
원본은 바꾸지 않고 문제 목록만 출력한다. 판정은 이 스크립트가 하고 AI는 결과를 옮겨 적는다.

사용:
  python3 check_contacts.py data/contacts.csv                         # Markdown 보고서를 화면에
  python3 check_contacts.py data/contacts.csv --out reports/issues.csv  # 문제 목록 CSV도 저장
  옵션: --required 회사명,이메일  --stale-days 90  --today 2026-12-01
칸 이름은 한글·영어 모두 알아본다 (회사명/company, 담당자/name, 이메일/email, 전화/phone, 출처/source, 확인일/checked_at)
"""
import argparse
import csv
import datetime as dt
import os
import re
import sys
from collections import defaultdict

ALIASES = {
    "company": ["회사명", "기업명", "회사", "company", "company_name"],
    "dept": ["담당부서", "부서", "department", "dept"],
    "name": ["담당자", "담당자명", "이름", "name", "contact_name"],
    "email": ["이메일", "메일", "email", "e-mail"],
    "phone": ["전화", "전화번호", "유선전화", "연락처", "phone", "tel"],
    "source": ["출처", "출처url", "source", "source_url", "url"],
    "checked": ["확인일", "조사일", "checked_at", "checked", "updated_at"],
}
EMAIL = re.compile(r"^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}$")
# 지역번호(02, 031~064), 대표번호(15xx·16xx·18xx), 070, 휴대폰(010 등)
PHONE = re.compile(r"^(0(2|3[1-3]|4[1-4]|5[1-5]|6[1-4]|70)-?\d{3,4}-?\d{4}|1[5-8]\d{2}-?\d{4}|01[016789]-?\d{3,4}-?\d{4})$")
MOBILE = re.compile(r"^01[016789]")
CORP_WORDS = re.compile(r"\(주\)|㈜|주식회사|\(유\)|유한회사|co\.?,?\s*ltd\.?|inc\.?|corp\.?|corporation|\s+", re.I)


def find_columns(header):
    cols = {}
    low = {h.strip().lower().replace(" ", ""): h for h in header}
    for key, names in ALIASES.items():
        for n in names:
            if n.lower().replace(" ", "") in low:
                cols[key] = low[n.lower().replace(" ", "")]
                break
    return cols


def norm_company(s):
    return CORP_WORDS.sub("", s or "").lower()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("csv")
    p.add_argument("--out")
    p.add_argument("--required", default="company,email,source", help="필수 칸 (키 이름이나 실제 칸 이름, 쉼표로)")
    p.add_argument("--stale-days", type=int, default=90)
    p.add_argument("--today")
    a = p.parse_args()

    with open(a.csv, encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        sys.exit("데이터가 없습니다")
    cols = find_columns(rows[0].keys())
    today = dt.date.fromisoformat(a.today) if a.today else dt.date.today()
    issues = []  # (행 번호, 종류, 내용)

    def val(r, key):
        return (r.get(cols[key]) or "").strip() if key in cols else ""

    required = []
    for k in a.required.split(","):
        k = k.strip()
        required.append(k if k in ALIASES else next((kk for kk, c in cols.items() if c == k), k))
    for k in required:
        if k not in cols:
            issues.append((0, "칸 없음", f"필수 칸 '{k}'이(가) 파일에 없습니다 (있는 칸: {', '.join(rows[0].keys())})"))

    by_email, by_company, names = defaultdict(list), defaultdict(list), defaultdict(set)
    for i, r in enumerate(rows, start=2):  # 1행은 머리글
        for k in required:
            if k in cols and not val(r, k):
                issues.append((i, "누락", f"{cols[k]} 비어 있음"))
        email = val(r, "email")
        if email:
            if not EMAIL.match(email):
                issues.append((i, "이메일 형식", email))
            by_email[email.lower()].append(i)
        phone = val(r, "phone")
        if phone:
            digits = re.sub(r"[\s().]", "", phone)
            if not PHONE.match(digits):
                issues.append((i, "전화 형식", phone))
            elif MOBILE.match(digits):
                issues.append((i, "개인 휴대폰 의심", f"{phone} (공개된 업무용 연락처인지 출처 확인)"))
        company = val(r, "company")
        if company:
            key = norm_company(company)
            by_company[key].append(i)
            names[key].add(company)
        checked = val(r, "checked")
        if checked:
            try:
                d = dt.date.fromisoformat(checked[:10])
                if (today - d).days > a.stale_days:
                    issues.append((i, "오래된 정보", f"확인일 {checked[:10]} ({(today - d).days}일 전)"))
            except ValueError:
                issues.append((i, "날짜 형식", checked))
        src = val(r, "source")
        if src and not re.match(r"^https?://", src):
            issues.append((i, "출처 형식", f"{src} (URL이 아님)"))

    for email, lines in by_email.items():
        if len(lines) > 1:
            issues.append((lines[0], "중복 이메일", f"{email}: {', '.join(map(str, lines))}행"))
    for key, variants in names.items():
        if len(variants) > 1:
            issues.append((by_company[key][0], "같은 회사 다른 표기", " / ".join(sorted(variants)) + f": {', '.join(map(str, by_company[key]))}행"))

    issues.sort(key=lambda x: (x[0], x[1]))
    counts = defaultdict(int)
    for _, kind, _ in issues:
        counts[kind] += 1
    print(f"# 연락처 DB 점검: {os.path.basename(a.csv)}\n")
    print(f"- 행 {len(rows)}개, 문제 {len(issues)}건 (기준일 {today}, 오래된 정보 기준 {a.stale_days}일)")
    print(f"- 알아본 칸: " + ", ".join(f"{k}={v}" for k, v in cols.items()) + "\n")
    if counts:
        print("| 종류 | 건수 |\n|---|---|")
        for k, c in sorted(counts.items(), key=lambda x: -x[1]):
            print(f"| {k} | {c} |")
        print("\n| 행 | 종류 | 내용 |\n|---|---|---|")
        for line, kind, detail in issues:
            print(f"| {line or '-'} | {kind} | {detail.replace('|', '/')} |")
    else:
        print("문제 없음")
    if a.out:
        os.makedirs(os.path.dirname(a.out) or ".", exist_ok=True)
        with open(a.out, "w", encoding="utf-8-sig", newline="") as f:
            w = csv.writer(f)
            w.writerow(["행", "종류", "내용"])
            w.writerows(issues)
        print(f"\n문제 목록 저장: {a.out}")


if __name__ == "__main__":
    main()
