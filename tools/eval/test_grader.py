#!/usr/bin/env python3
"""채점기 검증 (AI를 부르지 않으므로 API 키 없이 CI에서 항상 돈다)
  1. 시나리오 필수: kit/skills/ 의 스킬마다 evals/<스킬>/scenarios/ 에 시나리오가 하나 이상 있어야 한다
  2. 시나리오 형식: 필수 항목, 확인 항목 형식, 고정 데이터 파일이 빠짐없는지
  3. files·script 검사 자체 시험: 임시 폴더에서 통과·불통과를 제대로 가르는지
  4. 과거 실제 답변(evals/<스킬>/grader-tests)을 채점해서 사람의 판정과 같은지

사용: python3 tools/eval/test_grader.py
"""
import glob
import json
import os
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from grade import SCOPES, grade  # noqa: E402


def check_scenarios(skill_dir):
    problems = []
    for case_file in glob.glob(os.path.join(skill_dir, "scenarios", "*", "case.json")):
        d = os.path.dirname(case_file)
        case = json.load(open(case_file, encoding="utf-8"))
        for key in ("id", "title", "prompt", "now", "checks"):
            if key not in case:
                problems.append(f"{case_file}: {key} 없음")
        for chk in case.get("checks", []):
            scope = chk.get("scope", "answer")
            if "name" not in chk or scope not in SCOPES:
                problems.append(f"{case_file}: 확인 항목 형식 오류 {chk} (scope는 {', '.join(SCOPES)})")
            elif scope == "script" and not chk.get("run"):
                problems.append(f"{case_file}: script 검사 '{chk['name']}'에 run 없음")
            elif scope == "files" and not chk.get("path"):
                problems.append(f"{case_file}: files 검사 '{chk['name']}'에 path 없음")
            elif scope not in ("script", "files") and "any" not in chk and "none" not in chk:
                problems.append(f"{case_file}: '{chk['name']}'에 any나 none이 없음")
        for rel, content in {**case.get("files", {}), **case.get("staged", {}), **case.get("unstaged", {})}.items():
            if content.startswith("@fixtures/") and not os.path.exists(os.path.join(d, "fixtures", content[10:])):
                problems.append(f"{case_file}: {content} 파일 없음")
        rules = os.path.join(d, "fixtures", "db.tsv")
        if os.path.exists(rules):
            for line in open(rules, encoding="utf-8"):
                if "\t" in line and not os.path.exists(os.path.join(d, "fixtures", line.strip().split("\t")[1])):
                    problems.append(f"{rules}: {line.strip()} 의 파일 없음")
        prom = os.path.join(d, "fixtures", "prom.json")
        if os.path.exists(prom):
            json.load(open(prom, encoding="utf-8"))
    return problems


def missing_scenarios():
    """배포할 스킬인데 시나리오가 없는 것. 시나리오 없이 추가하면 AI eval을 아예 거치지 않으므로 막는다."""
    missing = []
    for skill in sorted(os.listdir(os.path.join(ROOT, "kit", "skills"))):
        if not os.path.isfile(os.path.join(ROOT, "kit", "skills", skill, "SKILL.md")):
            continue
        if not glob.glob(os.path.join(ROOT, "evals", skill, "scenarios", "*", "case.json")):
            missing.append(skill)
    return missing


def self_test():
    """files·script 검사가 통과·불통과를 제대로 가르는지 임시 폴더로 확인"""
    work = tempfile.mkdtemp(prefix="kit-grader-test-")
    try:
        os.makedirs(os.path.join(work, "src", "test"))
        open(os.path.join(work, "src", "test", "FooTest.java"), "w").write("class FooTest { @Test void ok() {} }")
        case = {"id": "self", "checks": [
            {"name": "script 성공", "scope": "script", "run": "test -f src/test/FooTest.java"},
            {"name": "script 실패", "scope": "script", "run": "exit 3"},
            {"name": "files 내용 있음", "scope": "files", "path": "src/**/*Test.java", "any": ["@test"]},
            {"name": "files 금지어", "scope": "files", "path": "src/**/*Test.java", "none": ["@test"]},
            {"name": "files 파일 없음", "scope": "files", "path": "src/**/*.kt"},
            {"name": "script 시간 초과", "scope": "script", "run": "sleep 5", "timeout": 1},
        ]}
        got = [c["ok"] for c in grade(case, "", [], workdir=work)["checks"]]
        want = [True, False, True, False, False, False]
        no_dir = [c["ok"] for c in grade(case, "")["checks"]]  # 작업 폴더 없으면 확인 불가로 통과
        return [f"{c['name']}: 기대 {w}, 실제 {g}" for c, g, w in zip(case["checks"], got, want) if g != w] + \
               ([] if all(no_dir) else ["작업 폴더 없을 때 확인 불가 처리 실패"])
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main():
    failed = 0
    total = 0
    for skill in missing_scenarios():
        print(f"FAIL 시나리오 필수: '{skill}' 스킬에 evals/{skill}/scenarios/<시나리오>/case.json 이 없습니다")
        failed += 1
    for p in self_test():
        print("FAIL 채점기 자체 시험:", p)
        failed += 1
    for skill_dir in sorted(d for d in glob.glob(os.path.join(ROOT, "evals", "*")) if os.path.isdir(d)):
        for p in check_scenarios(skill_dir):
            print("FAIL 시나리오:", p)
            failed += 1
        exp_file = os.path.join(skill_dir, "grader-tests", "expected.json")
        if not os.path.exists(exp_file):
            continue
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
    print(f"\n채점기 검증: 과거 답변 {total}개 채점 · 실패 {failed}건")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
