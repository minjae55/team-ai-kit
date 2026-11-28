#!/usr/bin/env python3
"""eval 실행기: 스킬을 고정 데이터 시나리오에서 사람 없이(claude -p) 돌리고 채점한다.

시나리오마다:
  1. 임시 폴더에 가짜 프로젝트(AGENTS.md, 로그, 코드, git 기록)를 만들고, 시험할 스킬을 .claude/skills/ 에 복사
  2. KIT_EVAL_FIXTURES 를 켜서 스킬 스크립트가 실제 Prometheus·DB 대신 장애 당시 기록을 돌려주게 함
  3. claude -p 로 시나리오의 요청을 보내고, 실행한 명령과 최종 답변을 받음 (읽기·스크립트 실행만 허용)
  4. grade.py 로 채점 (files·script 검사는 AI가 작업을 마친 그 폴더에서), 결과를 out/evals/ 에 저장. 하나라도 불통과면 종료 코드 1

사용:
  python3 tools/eval/run.py --skill incident-analysis                 # 시나리오 전부
  python3 tools/eval/run.py --skill incident-analysis --case case3-index-invisible
  python3 tools/eval/run.py --skill incident-analysis --isolated      # 내 ~/.claude 설정·설치 스킬을 섞지 않음 (권장)
      로그인: 구독이면 `claude setup-token`으로 받은 토큰을 CLAUDE_CODE_OAUTH_TOKEN 에, API면 ANTHROPIC_API_KEY 에 넣어 둔다
      격리하지 않으면 ~/.claude/skills 의 설치본(main 버전)이 불려서 PR 버전을 시험하지 못할 수 있다
  python3 tools/eval/run.py --skill incident-analysis --dry-run       # 명령만 보여 주기
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from grade import grade  # noqa: E402

ALLOWED = ("Read,Grep,Glob,Skill,Bash(cd *),Bash(python3 *),Bash(bash *),Bash(git log *),Bash(git diff *),Bash(git show *),"
           "Bash(cat *),Bash(grep *),Bash(head *),Bash(tail *),Bash(ls *),Bash(date *)")
DENIED = "Edit,Write,NotebookEdit,WebFetch,WebSearch"


def build_workdir(case_dir: str, case: dict, skill: str) -> str:
    work = tempfile.mkdtemp(prefix="kit-eval-")
    fixtures = os.path.join(case_dir, "fixtures")
    for rel, content in case.get("files", {}).items():
        dst = os.path.join(work, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if content.startswith("@fixtures/"):
            src = os.path.join(fixtures, content[len("@fixtures/"):])
            if os.path.isdir(src):  # 폴더째 복사 (예: 생성형 스킬이 작업할 작은 Gradle 프로젝트)
                shutil.copytree(src, dst, dirs_exist_ok=True)
            else:
                shutil.copy(src, dst)
        else:
            open(dst, "w", encoding="utf-8").write(content)
    if os.path.exists(os.path.join(work, "AGENTS.md")) and not os.path.exists(os.path.join(work, "CLAUDE.md")):
        open(os.path.join(work, "CLAUDE.md"), "w", encoding="utf-8").write("@AGENTS.md\n")  # 실제 프로젝트와 같은 연결
    shutil.copytree(os.path.join(ROOT, "kit", "skills", skill), os.path.join(work, ".claude", "skills", skill))
    env = dict(os.environ, GIT_AUTHOR_NAME="dev", GIT_AUTHOR_EMAIL="dev@example.com",
               GIT_COMMITTER_NAME="dev", GIT_COMMITTER_EMAIL="dev@example.com")
    subprocess.run(["git", "init", "-q", "-b", "main"], cwd=work, check=True)
    subprocess.run(["git", "add", "-A"], cwd=work, check=True)
    commits = case.get("commits") or [{"date": case.get("now"), "message": "init"}]
    for i, c in enumerate(commits):
        e = dict(env, GIT_AUTHOR_DATE=c["date"], GIT_COMMITTER_DATE=c["date"])
        subprocess.run(["git", "commit", "-q", "--allow-empty", "-m", c["message"]], cwd=work, env=e, check=True)
    # 커밋하지 않은 변경 (커밋 메시지·코드 리뷰 시나리오): "staged"는 git add까지, "unstaged"는 파일만 바꿈
    for key, add in (("staged", True), ("unstaged", False)):
        for rel, content in case.get(key, {}).items():
            dst = os.path.join(work, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            if content.startswith("@fixtures/"):
                shutil.copy(os.path.join(fixtures, content[len("@fixtures/"):]), dst)
            else:
                open(dst, "w", encoding="utf-8").write(content)
            if add:
                subprocess.run(["git", "add", rel], cwd=work, check=True)
    return work


def outside_skill_paths(commands, skill: str, work: str):
    """명령에 나온 절대 경로 중 작업 폴더 밖의 스킬 경로 (예: ~/.claude/skills/<스킬> 설치본). 상대 경로는 작업 폴더 안이다."""
    roots = {os.path.realpath(work), work}
    pat = re.compile(r"(?:^|(?<=[\s'\"=(]))(~?/[^\s'\"]*?)/skills/" + re.escape(skill) + r"\b")
    found = set()
    for c in commands:
        for m in pat.finditer(c):
            path = os.path.expanduser(m.group(1))
            if not any(path == r or path.startswith(r + "/") for r in roots):
                found.add(c)
    return sorted(found)


def tools_for(case: dict):
    """시나리오의 "allow_tools"(예: ["Edit", "Write", "Bash(./gradlew *)"])를 기본 허용 목록에 더하고 금지 목록에서 뺀다."""
    extra = case.get("allow_tools", [])
    allowed = ALLOWED.split(",") + [t for t in extra if t not in ALLOWED.split(",")]
    denied = [t for t in DENIED.split(",") if t not in extra]
    return ",".join(allowed), ",".join(denied)


class InstalledSkillOff:
    """격리하지 않고 돌릴 때, 같은 이름의 설치본(~/.claude/skills/<스킬>)이 있으면 시험 동안만 kit으로 끄고 끝나면 다시 켠다.
    설치본이 먼저 불리면 시험용 복사본(PR 버전)을 시험하지 못하기 때문이다. 끄는 동안에는 내 다른 Claude 세션에서도 그 스킬이 빠진다."""

    def __init__(self, skill: str, enabled: bool):
        self.skill = skill
        self.kit = os.path.expanduser("~/.kit/bin/kit")
        link = os.path.expanduser(f"~/.claude/skills/{skill}")
        self.active = enabled and os.path.lexists(link) and os.path.exists(self.kit)

    def __enter__(self):
        if self.active:
            print(f"  (설치된 {self.skill} 스킬을 시험 동안 잠시 끕니다. 끝나면 다시 켭니다)", flush=True)
            subprocess.run([self.kit, "skill", "disable", self.skill], capture_output=True, text=True)
        return self

    def __exit__(self, *exc):
        if self.active:
            r = subprocess.run([self.kit, "skill", "enable", self.skill], capture_output=True, text=True)
            if r.returncode != 0:
                print(f"  [경고] {self.skill} 스킬을 다시 켜지 못했습니다. 직접 실행하세요: kit skill enable {self.skill}")
        return False


def run_case(case_dir: str, skill: str, args) -> dict:
    case = json.load(open(os.path.join(case_dir, "case.json"), encoding="utf-8"))
    work = build_workdir(case_dir, case, skill)
    prompt = f"(현재 시각: {case['now']})\n\n{case['prompt']}"
    allowed, denied = tools_for(case)
    cmd = [args.claude, "-p", prompt, "--output-format", "stream-json", "--verbose",
           "--max-turns", str(args.max_turns), "--permission-mode", "dontAsk",
           "--allowedTools", allowed, "--disallowedTools", denied]
    if args.model:
        cmd += ["--model", args.model]
    env = dict(os.environ, KIT_EVAL_FIXTURES=os.path.join(case_dir, "fixtures"), **case.get("env", {}))
    config_dir = None
    if args.isolated:
        config_dir = tempfile.mkdtemp(prefix="kit-eval-config-")
        env["CLAUDE_CONFIG_DIR"] = config_dir
    if args.dry_run:
        print("작업 폴더:", work)
        print("명령:", " ".join(repr(c) if " " in c else c for c in cmd))
        return {"case": case["id"], "passed": True, "dry_run": True}

    started = time.time()
    with InstalledSkillOff(skill, enabled=not args.isolated and not args.keep_installed):
        proc = subprocess.run(cmd, cwd=work, env=env, capture_output=True, text=True, timeout=args.timeout)
    answer, commands, skills_used, meta = "", [], [], {}
    denials = []
    for line in proc.stdout.splitlines():
        try:
            ev = json.loads(line)
        except ValueError:
            continue
        if ev.get("type") == "assistant":
            for b in ev.get("message", {}).get("content", []):
                if b.get("type") == "tool_use":
                    if b.get("name") == "Bash":
                        commands.append(b.get("input", {}).get("command", ""))
                    elif b.get("name") == "Skill":
                        skills_used.append(b.get("input", {}).get("skill", ""))
        elif ev.get("type") == "result":
            answer = ev.get("result") or ""
            meta = {k: ev.get(k) for k in ("subtype", "is_error", "num_turns", "total_cost_usd", "duration_ms")}
            denials = ev.get("permission_denials") or []
    result = grade(case, answer, commands, workdir=work)  # files·script 검사는 AI가 작업한 폴더에서
    # 환경 검사: 실패하면 스킬이 틀린 게 아니라 시험 환경 문제다 (결과를 믿을 수 없으므로 불통과 처리)
    outside = outside_skill_paths(commands, skill, work)
    call_failed = bool(meta.get("is_error")) or bool(re.search(r"hit your .*limit|usage limit|rate limit|overloaded", answer, re.I))
    env_checks = [
        {"name": "환경: AI 호출 성공", "scope": "env", "ok": not call_failed,
         "detail": ("AI 호출 실패 (사용량 한도·오류): " + answer[:120]) if call_failed else ""},
        {"name": "환경: 시험용 스킬 복사본을 사용함", "scope": "env", "ok": not outside,
         "detail": "작업 폴더 밖의 설치본을 불렀음 (--isolated 로 실행하세요): " + outside[0][:150] if outside else ""},
    ]
    if denials:
        # 거부돼도 AI가 다른 방법으로 다시 해서 답을 맞히는 경우가 많다. 그래서 판정에는 넣지 않고 기록만 한다.
        # 거부 때문에 근거를 못 모았다면 내용 검사가 불통과하고, 이 기록이 그 이유를 설명한다.
        env_checks.append({"name": "참고: 권한 거부", "scope": "env", "ok": True,
                           "detail": f"{len(denials)}건 (판정에는 영향 없음): " + "; ".join(
                               str((d.get('tool_input') or {}).get('command') or d.get('tool_name'))[:100] for d in denials[:3])})
    result["checks"] += env_checks
    result["env_problem"] = not all(c["ok"] for c in env_checks)
    result["passed"] = result["passed"] and not result["env_problem"]
    if not answer:
        result["passed"] = False
        result["checks"].append({"name": "최종 답변을 받음", "ok": False,
                                 "detail": (proc.stderr or proc.stdout)[-500:]})
    if skill not in [s.split(":")[-1] for s in skills_used] and not any(skill in c for c in commands):
        result["checks"].append({"name": "스킬을 사용함 (참고)", "ok": True, "detail": "스킬 사용 흔적이 없음: 스킬 없이 답했을 수 있음"})
    result.update({"title": case["title"], "skills_used": skills_used, "commands": commands, "answer": answer,
                   "permission_denials": denials,
                   "meta": meta, "seconds": round(time.time() - started)})
    shutil.rmtree(work, ignore_errors=True)
    if config_dir:
        shutil.rmtree(config_dir, ignore_errors=True)
    return result


def record_verified(skill: str, results: list, model):
    """시나리오별 최근 결과를 evals/<스킬>/verified.json 에 남긴다. PR에 함께 올리면 팀원 kit list·ui에 "검증 N/M"으로 보인다."""
    path = os.path.join(ROOT, "evals", skill, "verified.json")
    data = json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {"skill": skill, "cases": {}}
    today = time.strftime("%Y-%m-%d")
    for r in results:
        if r.get("env_problem"):
            continue   # 시험 환경 문제(사용량 한도, 설치본 사용 등)는 스킬 검증 결과로 남기지 않는다
        data["cases"][r["case"]] = {"passed": bool(r["passed"]), "date": today, "model": model or "기본",
                                    "env_problem": bool(r.get("env_problem"))}
    data["cases"] = dict(sorted(data["cases"].items()))
    json.dump(data, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    open(path, "a", encoding="utf-8").write("\n")


def main():
    p = argparse.ArgumentParser(description="스킬 eval 실행기")
    p.add_argument("--skill", required=True)
    p.add_argument("--case", help="시나리오 id (생략하면 전부)")
    p.add_argument("--model", help="예: sonnet, opus 또는 전체 모델 ID")
    p.add_argument("--max-turns", type=int, default=40)
    p.add_argument("--timeout", type=int, default=900, help="시나리오당 최대 초")
    p.add_argument("--isolated", action="store_true",
                   help="빈 CLAUDE_CONFIG_DIR로 실행 (CLAUDE_CODE_OAUTH_TOKEN 또는 ANTHROPIC_API_KEY 필요)")
    p.add_argument("--keep-installed", action="store_true", help="설치된 같은 이름 스킬을 끄지 않음 (환경 검사에서 불통과할 수 있음)")
    p.add_argument("--claude", default=os.environ.get("CLAUDE_BIN", "claude"))
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args()

    base = os.path.join(ROOT, "evals", args.skill, "scenarios")
    cases = sorted(d for d in os.listdir(base) if os.path.isfile(os.path.join(base, d, "case.json")))
    if args.case:
        cases = [c for c in cases if c == args.case]
    if not cases:
        sys.exit("시나리오가 없습니다: " + base)
    if not args.dry_run and shutil.which(args.claude) is None:
        sys.exit(f"'{args.claude}' 명령이 없습니다. Claude Code CLI를 설치하세요 (npm install -g @anthropic-ai/claude-code)")
    if args.isolated and not (os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("CLAUDE_CODE_OAUTH_TOKEN")) \
            and not args.dry_run:
        sys.exit("--isolated 는 로그인 정보가 필요합니다: 구독은 `claude setup-token` 으로 받은 토큰을 "
                 "CLAUDE_CODE_OAUTH_TOKEN 에, API는 ANTHROPIC_API_KEY 에 넣으세요 (채팅·파일에 붙여 넣지 말 것)")

    out_dir = os.path.join(ROOT, "out", "evals")
    os.makedirs(out_dir, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    results = []
    for c in cases:
        print(f"== {c}", flush=True)
        r = run_case(os.path.join(base, c), args.skill, args)
        results.append(r)
        if r.get("dry_run"):
            continue
        for chk in r["checks"]:
            print(("  PASS " if chk["ok"] else "  FAIL ") + chk["name"] + (f"  ({chk['detail']})" if chk.get("detail") else ""))
        cost = (r.get("meta") or {}).get("total_cost_usd")
        if r.get("env_problem"):
            print("  ⚠ 시험 환경 문제: 스킬이 틀렸다고 단정할 수 없음")
        print(f"  → {'통과' if r['passed'] else '불통과'}  ({r.get('seconds')}초" + (f", ${cost:.3f}" if cost else "") + ")")
    if args.dry_run:
        return
    record_verified(args.skill, results, args.model)
    report = os.path.join(out_dir, f"{stamp}-{args.skill}.json")
    json.dump({"skill": args.skill, "time": stamp, "results": results}, open(report, "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    passed = sum(1 for r in results if r["passed"])
    print(f"\n{args.skill}: {passed}/{len(results)} 통과 · 자세한 결과 {os.path.relpath(report, ROOT)}")
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
