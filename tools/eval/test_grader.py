#!/usr/bin/env python3
"""채점기 검증: 과거 실제 답변(evals/<스킬>/grader-tests)을 채점해서 사람의 판정과 같은지 확인한다.
AI를 부르지 않으므로 API 키 없이 CI에서 항상 돌린다. 시나리오 고정 데이터 파일이 빠짐없는지도 검사한다.

사용: python3 tools/eval/test_grader.py
"""
import glob
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from grade import grade  # noqa: E402


def check_scenarios(skill_dir):
    problems = []
    for case_file in glob.glob(os.path.join(skill_dir, "scenarios", "*", "case.json")):
        d = os.path.dirname(case_file)
        case = json.load(open(case_file, encoding="utf-8"))
        for key in ("id", "title", "prompt", "now", "checks"):
            if key not in case:
                problems.append(f"{case_file}: {key} 없음")
        for rel, content in case.get("files", {}).items():
            if content.startswith("@fixtures/") and not os.path.exists(os.path.join(d, "fixtures", content[10:])):
                problems.append(f"{case_file}: {content} 파일 없음")
        rules = os.path.join(d, "fixtures", "db.tsv")
        if os.path.exists(rules):
            for line in open(rules, encoding="utf-8"):
                if "\t" in line and not os.path.exists(os.path.join(d, "fixtures", line.strip().split("\t")[1])):
                    problems.append(f"{rules}: {line.strip()} 의 파일 없음")
        json.load(open(os.path.join(d, "fixtures", "prom.json"), encoding="utf-8"))
    return problems


def main():
    failed = 0
    total = 0
    for skill_dir in sorted(glob.glob(os.path.join(ROOT, "evals", "*"))):
        exp_file = os.path.join(skill_dir, "grader-tests", "expected.json")
        if not os.path.exists(exp_file):
            continue
        for p in check_scenarios(skill_dir):
            print("FAIL 시나리오:", p)
            failed += 1
        for t in json.load(open(exp_file, encoding="utf-8"))["tests"]:
            total += 1
            case = json.load(open(os.path.join(skill_dir, "scenarios", t["case"], "case.json"), encoding="utf-8"))
            answer = open(os.path.join(skill_dir, "grader-tests", t["answer"]), encoding="utf-8").read()
            r = grade(case, answer)
            ok = r["passed"] == t["expect"]
            failed += 0 if ok else 1
            print(f"{'OK  ' if ok else 'FAIL'} {t['answer']}: 채점 {'통과' if r['passed'] else '불통과'}, 사람 판정 {'통과' if t['expect'] else '불통과'}")
            if not ok or not r["passed"]:
                for c in r["checks"]:
                    if not c["ok"]:
                        print(f"       - {c['name']}: {c['detail']}")
    print(f"\n채점기 검증: {total - failed}/{total} 일치" if total else "검증할 답변이 없습니다")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
