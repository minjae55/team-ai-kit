# team-ai-kit — 이 레포에서 작업할 때의 규칙

팀이 만든 AI 스킬과 공통 지식을 구성원 모두의 AI 도구(Claude Code · Codex · Gemini)에 자동으로 설치하고 최신으로 유지하는 배포 키트다.
배경과 로드맵은 `docs/HANDOFF.md`를 먼저 읽는다.

## 구조
| 경로 | 내용 |
|---|---|
| `kit/skills/<이름>/` | 팀원에게 배포할 스킬 원본 (`SKILL.md` + `scripts/`, `references/`) |
| `kit/context/AGENTS.md` | 팀원에게 배포할 공통 지식 (모든 프로젝트에서 AI가 읽음) |
| `evals/<스킬>/` | 스킬 검증 시나리오(`cases.md`), 결과(`results.json`), 재현 스크립트 |
| `evals/ai-corrections.json` | AI가 틀렸던 제안과 잡아낸 근거 |
| `tools/` | 개발용 도구: 스킬 형식 검사, 성능 증거 수집, 개인 대시보드 |
| `cli/` | (2단계) 배포 CLI. Java 17 + Gradle + picocli. 이 폴더 밖에는 빌드 도구를 두지 않는다 |

## 스킬 작성 규칙
- [Agent Skills 명세](https://agentskills.io/specification)를 따른다. frontmatter는 `name`, `description`만 쓰고 도구 전용 필드는 쓰지 않는다
- `name`은 소문자·숫자·하이픈, 64자 이하, 폴더 이름과 같아야 한다. `description`은 1024자 이하로 "무엇을, 언제" 쓰는지 적는다
- `SKILL.md`는 500줄 이하. 자세한 내용은 `references/`로 뺀다
- 프로젝트별 값(포트, 쿼리, 경로)은 스킬에 넣지 않는다. 각 프로젝트의 `AGENTS.md`에서 읽게 한다
- 스크립트는 읽기 전용이 기본이다. 쓰기·변경이 필요한 조치는 사람 승인 절차를 스킬에 적는다
- 스킬을 바꾸면 `python3 tools/validate_skills.py`가 통과해야 하고, 관련 eval 시나리오로 다시 검증한다

## 검증
```bash
python3 tools/validate_skills.py                       # 형식 검사
python3 tools/eval/test_grader.py                      # 채점기 검증 (과거 실제 답변, AI 호출 없음)
python3 tools/eval/run.py --skill <이름>               # AI eval (claude CLI 필요, 토큰 사용)
```
- PR마다 CI가 형식 검사(`skills-validate.yml`)와 eval(`evals.yml`)을 돌린다. AI eval은 저장소 비밀값 `ANTHROPIC_API_KEY`가 있을 때만 돈다
- 스킬을 바꾸면 eval 시나리오(`evals/<스킬>/scenarios/`)가 모두 통과해야 병합한다
- 새 시나리오는 `case.json`(요청, 정답, 확인 항목) + `fixtures/`(장애 당시 지표·DB·로그 기록)로 만든다. 실제로 AI가 틀렸던 답변은 `grader-tests/`에 남겨 채점기가 계속 잡아내는지 확인한다

## 배포 (버전 태그)
- 팀원에게 반영할 변경을 모았으면 `CHANGELOG.md` 맨 위에 새 버전 절을 쓰고, 같은 커밋에서 태그(`v0.x.y`)를 붙여 푸시한다
- `kit sync`가 그 절을 팀원에게 그대로 보여 주므로, 팀원이 알아야 할 것만 사람이 읽는 말로 쓴다

## 커밋
- 한글 Conventional Commits (`feat:`, `fix:`, `docs:`, `test:`, `chore:`), 본문은 불릿
- 커밋 전에 메시지와 대상 파일을 사람에게 먼저 보여 준다
- `out/`(개인 대시보드 결과)에는 요청 내용이 들어가므로 커밋하지 않는다
