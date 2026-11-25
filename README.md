# team-ai-kit

팀이 만든 AI 스킬과 공통 지식을 **구성원 모두의 AI 도구에 자동으로 설치하고 항상 최신으로 유지**하는 키트.
웹에서 프롬프트를 복사해 붙여넣는 대신, 평소처럼 자연어로 요청하면 AI가 팀의 스킬을 꺼내 쓴다.

## 현재 상태 (2026-11-25)
| 단계 | 내용 | 상태 |
|---|---|---|
| 0 | `incident-analysis` 스킬 작성·검증 (eval 3건) | 완료 |
| 0.5 | 분석 → 승인 → 수정 → 전후 증거 → PR 흐름 시험 | 완료 |
| 1 | 레포 정리, 공통 지식, 스킬 형식 검사 CI | 진행 중 |
| 2 | 배포 CLI `kit install / sync / doctor` | 예정 |

## 스킬
| 스킬 | 하는 일 |
|---|---|
| [`incident-analysis`](kit/skills/incident-analysis/SKILL.md) | 장애·지표 이상의 원인을 지표 → DB → 로그 → 최근 변경 순서로 근거를 모아 분석 (읽기 전용) |

## 지금 쓰는 방법 (CLI 전)
스킬 폴더를 프로젝트의 `.claude/skills/`에 링크한다. 2단계 CLI가 나오면 `kit install` 하나로 대체된다.
```bash
ln -s "$PWD/kit/skills/incident-analysis" <프로젝트>/.claude/skills/incident-analysis
```

## 도구
- `tools/validate_skills.py`: 스킬 형식 검사
- `tools/perf-evidence/`: 부하 테스트 중 JFR·스레드 덤프·Grafana 캡처 수집 (현재 stylehub 경로 고정)
- `tools/dashboard/`: 개인 AI 작업 대시보드 (`python3 tools/dashboard/build.py --open`, 내 PC 전용)

자세한 배경과 로드맵: [docs/HANDOFF.md](docs/HANDOFF.md)
