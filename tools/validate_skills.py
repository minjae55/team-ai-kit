#!/usr/bin/env python3
"""kit/skills/ 아래 스킬이 Agent Skills 명세(https://agentskills.io/specification)를 지키는지 검사한다.

표준 라이브러리만 쓴다. 오류가 하나라도 있으면 종료 코드 1.
사용: python3 tools/validate_skills.py [스킬 폴더 ...]   (인자 없으면 kit/skills/* 전부)
"""
import os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ALLOWED = {"name", "description", "license", "compatibility", "metadata", "allowed-tools"}
NAME_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
REF_RE = re.compile(r"(?<![\w/.-])((?:scripts|references|assets)/[\w./-]+)")


def parse_frontmatter(text):
    """맨 위 --- 사이의 YAML에서 최상위 키만 읽는다 (여러 줄 값은 이어 붙임)."""
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        return None, "파일이 '---' frontmatter로 시작하지 않음"
    try:
        end = next(i for i in range(1, len(lines)) if lines[i].strip() == "---")
    except StopIteration:
        return None, "frontmatter를 닫는 '---'가 없음"
    fields, key = {}, None
    for line in lines[1:end]:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        m = re.match(r"^([A-Za-z0-9_-]+):\s*(.*)$", line)
        if m and not line[0].isspace():
            key, val = m.group(1), m.group(2).strip()
            fields[key] = "" if val in ("|", ">", "|-", ">-") else val
        elif key:
            fields[key] = (fields[key] + " " + line.strip()).strip()
    for k, v in fields.items():
        if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
            fields[k] = v[1:-1]
    return (fields, end), None


def check(skill_dir):
    errors, warnings = [], []
    name_dir = os.path.basename(os.path.normpath(skill_dir))
    path = os.path.join(skill_dir, "SKILL.md")
    if not os.path.isfile(path):
        return [f"SKILL.md가 없음"], []
    text = open(path, encoding="utf-8").read()
    parsed, err = parse_frontmatter(text)
    if err:
        return [err], []
    fields, _ = parsed

    name = fields.get("name", "")
    if not name:
        errors.append("name이 없음")
    else:
        if len(name) > 64:
            errors.append(f"name이 64자를 넘음 ({len(name)}자)")
        if not NAME_RE.match(name):
            errors.append(f"name '{name}'은 소문자·숫자·하이픈만, 하이픈으로 시작·끝·연속 불가")
        if name != name_dir:
            errors.append(f"name '{name}'이 폴더 이름 '{name_dir}'과 다름")

    desc = fields.get("description", "")
    if not desc:
        errors.append("description이 없음")
    elif len(desc) > 1024:
        errors.append(f"description이 1024자를 넘음 ({len(desc)}자)")

    if "compatibility" in fields and not (1 <= len(fields["compatibility"]) <= 500):
        errors.append("compatibility는 1~500자")

    extra = sorted(set(fields) - ALLOWED)
    if extra:
        warnings.append(f"명세에 없는 frontmatter 필드: {', '.join(extra)} (다른 도구에서 무시되거나 오류가 날 수 있음)")

    n_lines = text.count("\n") + 1
    if n_lines > 500:
        warnings.append(f"SKILL.md가 500줄을 넘음 ({n_lines}줄). 자세한 내용은 references/로 뺄 것")

    for ref in sorted(set(REF_RE.findall(text))):
        ref = ref.rstrip(".")
        if not os.path.exists(os.path.join(skill_dir, ref)):
            errors.append(f"SKILL.md가 가리키는 파일이 없음: {ref}")

    scripts = os.path.join(skill_dir, "scripts")
    if os.path.isdir(scripts):
        for f in sorted(os.listdir(scripts)):
            p = os.path.join(scripts, f)
            if os.path.isfile(p) and not os.access(p, os.X_OK):
                warnings.append(f"실행 권한이 없는 스크립트: scripts/{f}")
    return errors, warnings


def main():
    dirs = sys.argv[1:] or sorted(
        os.path.join(ROOT, "kit", "skills", d) for d in os.listdir(os.path.join(ROOT, "kit", "skills"))
        if os.path.isdir(os.path.join(ROOT, "kit", "skills", d)))
    failed = 0
    for d in dirs:
        errors, warnings = check(d)
        label = os.path.relpath(d, ROOT)
        print(f"{'FAIL' if errors else 'OK  '} {label}")
        for e in errors:
            print(f"     오류: {e}")
        for w in warnings:
            print(f"     경고: {w}")
        failed += bool(errors)
    print(f"\n스킬 {len(dirs)}개 중 {failed}개 실패")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
