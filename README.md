# team-ai-kit

팀이 만든 AI 스킬과 공통 지식을 **구성원 모두의 AI 도구에 자동으로 설치하고 항상 최신으로 유지**하는 키트.
웹에서 프롬프트를 복사해 붙여넣는 대신, 평소처럼 자연어로 요청하면 AI가 팀의 스킬을 꺼내 쓴다.

## 현재 상태 (2026-11-27)
| 단계 | 내용 | 상태 |
|---|---|---|
| 0 | `incident-analysis` 스킬 작성·검증 (eval 3건) | 완료 |
| 0.5 | 분석 → 승인 → 수정 → 전후 증거 → PR 흐름 시험 | 완료 |
| 1 | 레포 정리, 공통 지식, 스킬 형식 검사 CI | 완료 |
| 2 | 배포 CLI `kit install / sync / doctor` | 완료 (v0.1.x) |
| 2.5 | `kit ui` 화면 (설치·업데이트·스킬 후보 추천·평가·시스템 현황) | 완료 (v0.2~0.4) |
| 3 | 자동 sync, 버전 고정·롤백, 사용 기록 | 완료 (v0.5.0, 내 PC 기준) |
| 4 | eval 자동화 (시나리오 + 채점기 + CI, 분석형·생성형 검사, 시나리오 필수) | 완료 (v0.6.0. AI eval은 지금 내 PC에서, CI 실행·병합 강제는 5단계) |
| 5 | 팀 온보딩: 회사 조직 이전(CI AI eval·브랜치 보호), jar 배포, 성능 스킬 묶음, 팀 단위 집계, Gemini | 진행 중 |
| 6 | 자동 실행: 알림 → 사람 없이 분석 → 회사 채널로 결과 | 예정 |
| 7 | 전 직군 확대: 프론트엔드 포함 모든 개발자, 비개발자(Claude 앱 조직 배포) | 예정 |

## 스킬
| 스킬 | 하는 일 |
|---|---|
| [`incident-analysis`](kit/skills/incident-analysis/SKILL.md) | 장애·지표 이상의 원인을 지표 → DB → 로그 → 최근 변경 순서로 근거를 모아 분석 (읽기 전용) |
| [`query-analysis`](kit/skills/query-analysis/SKILL.md) | 느린 쿼리의 원인을 실행 계획·인덱스·테이블 통계로 분석하고 인덱스·쿼리 개선안 제안 (읽기 전용) |
| [`k6-scenario-generator`](kit/skills/k6-scenario-generator/SKILL.md) | 프로젝트의 기존 k6 관례대로 부하 테스트 스크립트 작성 (실행은 사람이) |
| [`perf-report`](kit/skills/perf-report/SKILL.md) | 전후 측정 기록으로 "문제 → 원인 → 변경 → 결과 → 한계" 보고서 작성 |

## 설치
Java 8 이상(실행만, JDK·빌드 도구 불필요)과 git이 필요하다. 공개 저장소라 SSH 키나 로그인 없이 받는다. 처음 한 번만:
```bash
git clone https://github.com/minjae55/team-ai-kit.git ~/.kit/repo && ~/.kit/repo/install.sh
```
`install.sh`가 kit 실행 파일을 `~/.kit/bin`에 넣고, PATH 등록 여부를 물은 뒤, `kit install`까지 실행한다. 그다음부터는 아무 폴더에서:
```bash
kit ui          # 브라우저 화면: 업데이트, 점검, 스킬 켜기·끄기, 활동 기록 (내 PC에서만 열림)
kit sync        # 최신 버전으로 맞추기
kit doctor      # 설치 상태 점검 (아무것도 바꾸지 않음)
kit list        # 설치된 버전과 스킬
kit uninstall   # 키트가 설치한 것만 제거
kit auto on     # 자동 업데이트 + 스킬 사용 기록 켜기 (Claude Code 훅)
kit rollback    # 업데이트 후 문제가 생기면 직전 버전으로 되돌리고 고정 (해제: kit unpin)
```
- 스킬은 `~/.claude/skills/`, `~/.agents/skills/`에 링크로, 공통 지식은 `~/.claude/CLAUDE.md`(import), `~/.codex/AGENTS.md`(복사)의 자동 관리 구역에 들어간다
- 키트가 만든 것만 건드린다. 같은 이름의 개인 스킬과 직접 쓴 지침은 그대로 둔다
- 설계: [docs/cli-design.md](docs/cli-design.md)

## 도구
- `tools/validate_skills.py`: 스킬 형식 검사
- `tools/eval/`: 스킬 eval 실행기(`run.py`), 채점기(`grade.py`), 채점기 검증(`test_grader.py`)
- `tools/perf-evidence/`: 부하 테스트 중 JFR·스레드 덤프·Grafana 캡처 수집 (현재 stylehub 경로 고정)
- `tools/dashboard/`: 개인 AI 작업 대시보드 (`python3 tools/dashboard/build.py --open`, 내 PC 전용)

자세한 배경과 로드맵: [docs/HANDOFF.md](docs/HANDOFF.md)
