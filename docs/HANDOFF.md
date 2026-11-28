# 인수인계: 팀 AI 키트 (team-ai-kit)

> 작성일 2026-11-25 · 새 레포 루트에 `docs/HANDOFF.md`로 복사해서 쓴다.
> 다음 작업자(사람이든 AI든)는 이 문서만 읽고 바로 이어서 시작할 수 있어야 한다.

---

## 0. 진행 상황 (2026-11-27 갱신)
- **0단계 완료**: `incident-analysis` 스킬을 stylehub 로컬 성능 환경에서 eval 3건(커넥션 풀 고갈, 행 락 경합, 인덱스 숨김)으로 검증. 케이스 3은 1차 불통과 → 스킬 보완(DB 스키마 변경 확인, 정상 구간 비교) → 재시험 통과. 기록: `evals/incident-analysis/cases.md`
- **0.5단계 완료 (로드맵에 추가)**: 분석 → 수정안 → 사람 승인 → 수정 → 같은 시나리오로 전후 측정 → PR까지 한 바퀴. stylehub 협의 목록 캐시 동시 만료를 TTL jitter로 해결(커넥션 대기 88 → 0). stylehub PR #4, 증거 `docs/evidence/2026-11-25-negotiation-cache-ttl-jitter/`
- **개인 대시보드 v1**: `tools/dashboard/build.py`. 서버·배포 없이 내 PC 기록으로 HTML 생성. API 목표 현황, 개선 사례, 알림 → 원인 파악 시간, AI 제안 검증, 로그. 실용성은 "누가, 언제, 보고 나서 무엇을 하나" 기준으로 재설계 필요
- **1단계 완료**: 레포 규칙(AGENTS.md), 팀 공통 지식(`kit/context/AGENTS.md`), 스킬 형식 검사(`tools/validate_skills.py` + CI). GitHub `minjae55/team-ai-kit`(private, SSH)에 푸시, CI에서 `skills-ref validate` 통과. 빈 저장소 첫 푸시에서는 경로 필터 때문에 CI가 돌지 않아 `workflow_dispatch`를 추가함
- **2단계 완료 (v0.1.x)**: Java CLI `kit install / sync / doctor / list / uninstall`. `install.sh` 한 줄로 설치, Claude(`~/.claude/skills`, CLAUDE.md import)와 Codex(`~/.agents/skills`, AGENTS.md 복사) 지원. 설계 `docs/cli-design.md`
- **2.5단계 완료 (v0.2~0.4)**: `kit ui` 내 PC 전용 브라우저 화면. 업데이트, 점검, 스킬 켜기·끄기, 활동 기록, 스킬 후보 추천, 👍/👎 평가
- **3단계 완료 (v0.5.0)**: 자동 sync(SessionStart 훅), kit 자기 갱신, pin/rollback, 스킬 사용 기록
- **4단계 완료 (v0.6.0 + 6fc7cf8)**: 고정 데이터 시나리오, 채점기(과거 실제 답변 5개와 판정 일치), 실행기, CI. 확인 방식은 분석형(답변·명령 검사)과 생성형(`files`·`script` 검사)을 지원하고, 시나리오 없는 스킬은 CI에서 막는다. 혼자 쓰는 지금은 AI eval을 내 PC에서 구독 계정으로 돌린다(CI의 AI eval·병합 강제는 막을 대상이 없어 5단계로 미룸)
- 배운 점
  - 무엇을 확인할지는 사람이 시나리오에 정한다. AI가 채점 기준까지 정하면 검증이 아니다
  - 스킬의 가치는 "틀린 걸 한 번 고치면 다음부터 안 틀린다"에 있다 (케이스 3 재시험)
  - eval 정답도 틀릴 수 있다. 케이스 1 정답을 처음엔 "스탬피드"로 적었다가 "동시 만료"로 정정함
  - stylehub 쿼리 통계는 `useServerPrepStmts=true` 때문에 `performance_schema.prepared_statements_instances`에 있다
  - 로컬 환경 함정: `gradlew` 실행 권한 없음(`sh gradlew`), `.my.cnf` CRLF 줄 끝, zsh는 변수를 공백으로 나누지 않음

## 1. 한 줄 정의
팀이 만든 **AI 스킬과 공통 지식을 구성원 모두의 AI 도구(Claude Code · Codex · Gemini)에 자동으로 설치하고 항상 최신으로 유지**하는 배포 체계. 이를 통해 전사의 AI 실행력을 높인다.

## 2. 핵심 방향 (확정, 선배 피드백)
- ❌ 웹페이지에서 클릭해 실행하는 방식은 실효성이 없다. 회사에는 이미 회사 도구가 있다.
- ✅ 핵심은 만들어 둔 스킬과 지식이 AI를 쓰는 구성원에게 **자동으로 전파**되는 것이다.
- ✅ 한 사람의 AI 고도화가 아니라 **전사의 AI 실행력**을 높이는 것이 목표다.
- 지금 사용자는 1명이지만 최종적으로는 전원이 쓴다. 그래서 처음부터 배포를 전제로 설계한다.
- Claude Code, Codex, Gemini를 **모두 지원**하는 것을 전제로 한다. 단, 구현은 한 도구씩 순서대로 한다.

## 3. 실행 방식 (대시보드 없음)
- **사람이 실행**: 팀원이 평소 쓰는 AI 도구에서 자연어로 요청하거나(AI가 스킬을 자동 선택) 직접 호출한다(`/incident-analysis`, Codex는 `$incident-analysis`).
- **자동 실행**: 알림 webhook → 사람 없이 실행(`claude -p "/incident-analysis ..."`) → 결과를 Slack 등 회사 채널로 보낸다.
- **위험한 조치**(재시작, 코드 수정)는 반드시 사람이 승인한 뒤 실행한다.

## 4. 레포 역할 분담

| 레포 | 역할 |
|---|---|
| **team-ai-kit** (새 레포) | 공통 스킬 원본, 배포 CLI, 공통 AGENTS.md, 스킬 평가(eval), 도구별 어댑터 |
| **stylehub** (기존, 모니터링 구축됨) | stylehub 전용 지식(`AGENTS.md`: 서비스 구조, 주요 지표와 쿼리, 대시보드 위치), 알림 연동. **첫 스킬을 실제로 검증하는 곳** |
| team-prompt-builder (기존) | 보류. 나중에 메트릭 화면 후보 |

## 5. 기술 결정 (추천안, 미확정 항목 포함)

| 주제 | 결정 / 추천 |
|---|---|
| 주입 방식 | **CLI + 스킬(중심) + MCP(보조, 회사 도구에 실시간 접근할 때만)**. 프로젝트 스킬만으로는 전사 배포가 안 된다 |
| CLI 언어 | **Java 17 + Gradle + picocli로 확정** (작업자가 Java 개발자이고 팀도 유지보수하기 쉬움). 배포는 jar 또는 GraalVM native-image(방식 미확정). **Gradle 프로젝트는 2단계에서 `cli/` 폴더 안에만 만든다.** 0~1단계는 빌드 도구 없이 Markdown 스킬과 셸 스크립트만으로 진행한다 |
| 레포 형식 | IDE 템플릿이나 Gradle 없이 빈 폴더 + `git init`으로 시작한다 |
| 스킬 형식 | Agent Skills 오픈 표준(`SKILL.md`)을 따르고 원본은 하나로 관리한다 |
| 최신화 | Git 버전 태그를 붙이고, `kit sync`를 자동 실행(세션 시작 훅 또는 주기 실행)한다. 설치된 버전은 lock 파일에 기록한다 |
| 평가 | 스킬마다 eval 세트(입력 예시와 확인 항목)를 둔다. 스킬을 바꾸는 PR은 CI에서 사람 없이 실행해 검증하고, 통과해야만 배포한다 |
| 메트릭 | 1순위 도구 훅, 2순위 OpenTelemetry. 훅이 없는 도구는 스킬 안에서 기록 스크립트를 호출해 보완한다 |

## 6. 도구별 차이 (공식 문서로 확인됨)

| | Codex | Claude Code | Gemini CLI |
|---|---|---|---|
| 지침 파일 | `AGENTS.md` (기본, 32 KiB 제한) | `CLAUDE.md`. `@AGENTS.md`로 가져오는 방식 권장 | `GEMINI.md`. `settings.json`의 `context.fileName`에 AGENTS.md 지정 가능 |
| 사용자 스킬 경로 | `~/.agents/skills/` | `~/.claude/skills/` | `~/.gemini/skills/` 또는 `~/.agents/skills/` |
| 프로젝트 스킬 경로 | `.agents/skills/` | `.claude/skills/` | `.gemini/skills/` 또는 `.agents/skills/` |
| 호출 | `$name`, 자동 선택 | `/name`, 자동 선택 | 자동 선택(`activate_skill`, **사용자 확인 프롬프트**가 뜸) |
| 사람 없이 실행 | `codex exec --json --output-schema` | `claude -p --output-format json` (`/name` 확장됨) | `gemini -p --output-format …` |
| MCP 설정 | `~/.codex/config.toml` | Claude 설정 / `.mcp.json` | `~/.gemini/settings.json` |

→ 어댑터 역할은 하나의 원본(스킬, AGENTS.md, MCP 정의)을 **도구별 경로와 설정 형식으로 변환해 설치**하는 것이다.

## 7. 로드맵

| 단계 | 내용 | 완료 기준 |
|---|---|---|
| **0** ✅ | `incident-analysis` 스킬과 데이터 수집 스크립트를 작성하고, stylehub에서 Claude Code로 검증 | 실제 장애나 지표 이상 3건에서 쓸 만한 분석 결과가 나옴 (로컬 환경이라 k6로 만든 이상 상황 3건으로 대체) |
| 0.5 ✅ | 분석 → 승인 → 수정 → 전후 증거 → PR 흐름 시험 | 같은 시나리오 전후 수치로 개선 증명 |
| 1 ✅ | team-ai-kit 레포 구조와 공통 AGENTS.md 작성, 스킬 형식 검증 CI | `skills-ref validate` 통과 (2026-11-25 CI #1) |
| 2 ✅ | **CLI MVP**: `kit install / sync / doctor`. Claude와 Codex 지원 | 새 PC에서 명령 하나로 스킬·지침이 설치됨 (2026-11-26, `install.sh`로 실제 PC 설치 → 다른 폴더의 새 세션에서 스킬 인식 확인. MCP 설정은 3단계 이후로 미룸) |
| 3 ✅ | 자동 sync, 버전 고정과 롤백, 훅 기반 사용 추적 | 스킬을 수정하면 팀원 환경에 자동 반영되고 사용 기록이 남음 (2026-11-27 v0.5.0: Claude SessionStart 훅으로 6시간마다 sync, kit 명령 자기 갱신, pin/rollback, PostToolUse(Skill)·UserPromptExpansion 훅으로 ~/.kit/usage.jsonl 기록. 팀 단위 집계와 Codex 훅은 미구현: Codex만 쓰는 팀원은 수동 sync) |
| 4 ✅ | eval 파이프라인 | 스킬 PR이 eval을 통과해야만 배포됨 (2026-11-27 v0.6.0: 장애 당시 기록을 고정 데이터로 만든 시나리오 3개, 스킬 스크립트의 KIT_EVAL_FIXTURES 모드, 채점기와 과거 실제 답변 5개로 채점기 검증, CI evals.yml. 6fc7cf8: 생성형 스킬용 `files`·`script` 검사, 시나리오별 허용 도구, 시나리오 없는 스킬은 CI 실패. 지금은 키 없이 도는 검사(형식, 채점기, 시나리오 필수)만 CI에서 돌고, AI eval은 내 PC에서 실행) |
| 5 | 팀원 온보딩과 팀 단위 운영 | 2명 이상이 실제로 사용. ① 회사 GitHub 조직(비공개)으로 이전, CODEOWNERS로 스킬 승인자 지정, CI에 회사 계정 키 등록(AI eval) + 브랜치 보호(PR 필수, eval 통과 필수). 팀원이 고친 스킬도 시험을 거쳐야만 배포되게 하는 것이 목적 ② 설치 간소화(빌드된 jar 배포, 팀원 PC에 JDK 불필요: tools/release.sh + dist/kit.jar 준비됨, 첫 적용은 다음 배포 v0.7.0) ③ 성능 개선 스킬 묶음(query-analysis, k6-scenario-generator, perf-report. 시나리오와 함께) ④ 팀 단위 사용·평가 집계 ⑤ Gemini 어댑터, Codex 훅 |
| 6 | 자동 실행 | 알림 webhook → 사람 없이 `claude -p`로 분석 → 회사 채널로 결과 전송 (3절 자동 실행). 회사 계정 인증·라이선스 확인(8절 2번)이 먼저 |
| 7 | 전 직군 확대 | 프론트엔드를 포함한 모든 개발자와 비개발자가 사용. 비개발자는 터미널 없이 Claude 앱 조직 배포(회사 플랜 지원 여부 확인 필요)로 같은 스킬 원본을 받음 |

## 8. 미확인 항목 (POC가 필요하며, 지원된다고 단정하지 않음)
1. Codex와 Gemini의 **훅 형식**과 텔레메트리(OpenTelemetry) 지원 여부
2. 회사 계정으로 사람 없이 서버나 자동 실행을 해도 되는지(인증·라이선스)
3. 회사 플랜에서 Gemini CLI를 쓸 수 있는지. 공식 문서에 따르면 2026-06-18부터 무료 티어와 Google One 사용자는 Antigravity CLI로 대체되었다
4. Gemini를 사람 없이 실행할 때 `activate_skill` 확인 프롬프트가 어떻게 처리되는지
5. Codex `exec`에서 `$name`으로 명시 호출이 되는지
6. ~~Claude Code가 심링크로 연결한 스킬 폴더를 인식하는지~~ → **확인됨**: 프로젝트 `.claude/skills/`(2026-11-25)와 사용자 `~/.claude/skills/`(2026-11-26) 모두 심링크 스킬을 인식. `~/.claude/CLAUDE.md`의 `@~/...` import도 동작. Codex는 문서상 `~/.agents/skills/` 링크 지원, AGENTS.md import는 미지원 (`docs/cli-design.md` 8절)
7. 도구 전용 frontmatter 필드를 다른 도구가 오류 없이 무시하는지

## 9. 권장 구조
0~1단계에는 `kit/`, `docs/`, `evals/`만 있으면 된다. `cli/`(Gradle)는 2단계에서 추가한다.
```
team-ai-kit/
├── AGENTS.md                         # 이 레포에서 작업할 때의 규칙
├── CLAUDE.md                         # @AGENTS.md
├── docs/HANDOFF.md                   # 이 문서
├── kit/
│   ├── context/AGENTS.md             # 팀원에게 배포할 공통 지식
│   ├── skills/incident-analysis/
│   │   ├── SKILL.md
│   │   ├── scripts/                  # 메트릭·로그 수집
│   │   └── references/
│   └── mcp/servers.yaml              # 도구에 상관없는 MCP 정의(원본)
├── cli/                              # (2단계) Java 17 + Gradle + picocli: kit install/sync/doctor + adapters/{claude,codex,gemini}
└── evals/incident-analysis/          # 입력 예시와 확인 항목
```

## 10. 다음 작업 (2026-11-28 갱신)
1. 사장님이 먼저 실제로 써 보기 (kit sync → 성능·커밋·리뷰·테스트 스킬을 평소 작업에) → 쓸 만하면 팀원 1명에게 권함
2. 저장소 공개 전환 (HTTPS 설치, 한 줄 설치·말로 설치가 동작하려면 필요)
3. Codex·Gemini CLI 실사용 검증 (유료 계정이 있는 팀원 온보딩 때)
4. 팀 단위 사용·평가 집계, 회사 조직 이전 (사용자가 생긴 뒤)
5. 블로그 글 이어 쓰기
6. 보류: stylehub PR #4, B 트랙. 개인 업무 스킬(contact-db-check, mail-campaign, company-research)을 팀 키트에서 뺄지는 나중에 결정

## 11. 참고 자료
- 도구별 상세 조사(비교표, 로딩 원리, 어댑터 범위): team-prompt-builder 레포의 `docs/01-research-and-mvp.md`. 단, 이 문서의 대시보드 중심 MVP 부분은 **폐기된 방향**이다.

## 출처
- Agent Skills 명세: https://agentskills.io/specification
- Claude Code: https://code.claude.com/docs/en/skills · /memory · /headless
- Codex: https://learn.chatgpt.com/docs/build-skills · /agent-configuration/agents-md · /non-interactive-mode
- Gemini CLI: https://geminicli.com/docs/cli/skills/ · /gemini-md/ · /headless
