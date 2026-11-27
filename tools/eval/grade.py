#!/usr/bin/env python3
"""eval 채점기: AI의 최종 답변(과 실행한 명령)이 시나리오의 확인 항목을 통과하는지 기계적으로 검사한다.

확인 항목(case.json의 checks) 범위:
  first_cause  답변의 1순위 원인 부분 ("원인 후보" 아래 첫 번째 항목)
  answer       답변 전체
  commands     AI가 실행한 셸 명령 (실행기가 기록한 것. 없으면 확인 불가로 통과 처리)
  files        AI가 작업 폴더에 만든·고친 파일 내용. "path"(glob, ** 가능)에 맞는 파일이 하나도 없으면 불통과
  script       작업 폴더에서 "run" 명령을 실행해 종료 코드 0이면 통과 (예: ./gradlew test, k6 inspect x.js)
               "timeout"(초, 기본 300). 생성형 스킬 검증용
  any  하나라도 들어 있어야 통과 / none  하나도 없어야 통과 (대소문자 무시)
  files·script 는 작업 폴더가 있어야 하므로, 과거 답변만으로 채점할 때(test_grader)는 확인 불가로 통과 처리

사용: grade.py <case.json> <답변.md> [명령.txt]
"""
import glob
import json
import os
import re
import subprocess
import sys

SCOPES = ("first_cause", "answer", "commands", "files", "script")

FIRST = re.compile(r"^\s*(?:#+\s*)?(?:\*\*)?\s*(?:1[.)]|1순위|①)")
SECOND = re.compile(r"^\s*(?:#+\s*)?(?:\*\*)?\s*(?:2[.)]|2순위|②)")
H2 = re.compile(r"^\s*#{1,2}\s")


def first_cause(answer: str) -> str:
    lines = answer.splitlines()
    start = 0
    for i, line in enumerate(lines):
        if "원인 후보" in line:
            start = i + 1
            break
    begin = None
    for i in range(start, len(lines)):
        if FIRST.match(lines[i]):
            begin = i
            break
    if begin is None:
        # 번호가 없으면 "원인 후보" 아래 첫 단락, 그것도 없으면 답변 앞부분
        chunk = lines[start:start + 12] if start else lines[:12]
        return "\n".join(chunk)
    end = len(lines)
    for i in range(begin + 1, len(lines)):
        if SECOND.match(lines[i]) or (H2.match(lines[i]) and i > begin + 1):
            end = i
            break
    return "\n".join(lines[begin:end])


def run_script(chk: dict, workdir: str):
    try:
        p = subprocess.run(["bash", "-c", chk["run"]], cwd=workdir, capture_output=True, text=True,
                           timeout=chk.get("timeout", 300))
    except subprocess.TimeoutExpired:
        return False, f"시간 초과 ({chk.get('timeout', 300)}초): {chk['run']}"
    if p.returncode == 0:
        return True, f"성공: {chk['run']}"
    return False, f"종료 코드 {p.returncode}: {chk['run']}\n" + (p.stdout + p.stderr)[-400:]


def read_files(pattern: str, workdir: str):
    paths = sorted(f for f in glob.glob(os.path.join(workdir, pattern), recursive=True) if os.path.isfile(f))
    text = "\n".join(open(f, encoding="utf-8", errors="replace").read() for f in paths)
    return paths, text.lower()


def grade(case: dict, answer: str, commands=None, workdir=None) -> dict:
    scopes = {"first_cause": first_cause(answer).lower(), "answer": answer.lower()}
    results = []
    for chk in case.get("checks", []):
        scope = chk.get("scope", "answer")
        if scope in ("files", "script") and workdir is None:
            results.append({"name": chk["name"], "scope": scope, "ok": True, "detail": "작업 폴더 없음 (확인 불가)"})
            continue
        if scope == "script":
            ok, detail = run_script(chk, workdir)
            results.append({"name": chk["name"], "scope": scope, "ok": ok, "detail": detail})
            continue
        if scope == "files":
            paths, text = read_files(chk["path"], workdir)
            if not paths:
                results.append({"name": chk["name"], "scope": scope, "ok": False, "detail": "파일이 없음: " + chk["path"]})
                continue
        elif scope == "commands":
            if commands is None:
                results.append({"name": chk["name"], "ok": True, "detail": "실행 명령 기록 없음 (확인 불가)"})
                continue
            text = "\n".join(commands).lower()
        else:
            text = scopes[scope]
        ok, detail = True, ""
        if "any" in chk:
            hits = [w for w in chk["any"] if w.lower() in text]
            ok = bool(hits)
            detail = ("찾음: " + ", ".join(hits)) if hits else ("없음: " + " / ".join(chk["any"]))
        if ok and "none" in chk:
            bad = [w for w in chk["none"] if w.lower() in text]
            if bad:
                ok, detail = False, "들어 있으면 안 됨: " + ", ".join(bad)
        results.append({"name": chk["name"], "scope": scope, "ok": ok, "detail": detail})
    return {"case": case.get("id"), "passed": all(r["ok"] for r in results), "checks": results,
            "first_cause": first_cause(answer)[:400]}


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    case = json.load(open(sys.argv[1], encoding="utf-8"))
    answer = open(sys.argv[2], encoding="utf-8").read()
    commands = open(sys.argv[3], encoding="utf-8").read().splitlines() if len(sys.argv) > 3 else None
    r = grade(case, answer, commands)
    for c in r["checks"]:
        print(("PASS " if c["ok"] else "FAIL ") + c["name"] + (f"  ({c['detail']})" if c["detail"] else ""))
    print("\n결과:", "통과" if r["passed"] else "불통과")
    sys.exit(0 if r["passed"] else 1)


if __name__ == "__main__":
    main()
