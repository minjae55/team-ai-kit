# team-ai-kit

팀이 만든 AI 스킬과 공통 지식을 **구성원 모두의 AI 도구에 자동으로 설치하고 항상 최신으로 유지**하는 키트.
웹에서 프롬프트를 복사해 붙여넣는 대신, 평소처럼 자연어로 요청하면 AI가 팀의 스킬을 꺼내 쓴다.

## 현재 상태 (2026-11-25)
| 단계 | 내용 | 상태 |
|---|---|---|
| 0 | `incident-analysis` 스킬 작성·검증 (eval 3건) | 완료 |
| 0.5 | 분석 → 승인 → 수정 → 전후 증거 → PR 흐름 시험 | 완료 |
| 1 | 레포 정리, 공통 지식, 스킬 형식 검사 CI | 완료 |
| 2 | 배포 CLI `kit install / sync / doctor` | 다음 |

## 스킬
| 스킬 | 하는 일 |
|---|---|
| [`incident-analysis`](kit/skills/incident-analysis/SKILL.md) | 장애·지표 이상의 원인을 지표 → DB → 로그 → 최근 변경 순서로 근거를 모아 분석 (읽기 전용) |

## 설치 (배포 CLI, 개발 중)
Java 8 이상과 git, 키트 레포 접근 권한(SSH)이 필요하다.
```bash
cd cli && ./gradlew fatJar      # 처음 한 번 빌드
./kit install                    # 감지된 도구(Claude Code, Codex)에 스킬과 공통 지식 설치
./kit doctor                     # 설치 상태 점검 (아무것도 바꾸지 않음)
./kit sync                       # 최신 버전으로 맞추기
./kit uninstall                  # 키트가 설치한 것만 제거
```
- 스킬은 `~/.claude/skills/`, `~/.agents/skills/`에 링크로, 공통 지식은 `~/.claude/CLAUDE.md`(import), `~/.codex/AGENTS.md`(복사)의 자동 관리 구역에 들어간다
- 키트가 만든 것만 건드린다. 같은 이름의 개인 스킬과 직접 쓴 지침은 그대로 둔다
- 설계: [docs/cli-design.md](docs/cli-design.md)

## 도구
- `tools/validate_skills.py`: 스킬 형식 검사
- `tools/perf-evidence/`: 부하 테스트 중 JFR·스레드 덤프·Grafana 캡처 수집 (현재 stylehub 경로 고정)
- `tools/dashboard/`: 개인 AI 작업 대시보드 (`python3 tools/dashboard/build.py --open`, 내 PC 전용)

자세한 배경과 로드맵: [docs/HANDOFF.md](docs/HANDOFF.md)
