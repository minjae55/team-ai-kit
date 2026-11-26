# 배포 CLI 설계안 (2단계, 초안)

> 2026-11-26 갱신 · 상태: 결정 완료, POC 통과. 구현 시작 가능.

## 1. 목표
팀원이 명령 하나로 팀 스킬과 공통 지식을 자기 AI 도구에 설치하고, 이후 최신으로 맞출 수 있게 한다.

완료 기준 (인수인계 문서): **새 PC에서 명령 하나로 스킬·지침이 설치된다.** Claude Code와 Codex를 지원한다.

## 2. 범위
| 이번에 함 (2단계) | 나중에 함 |
|---|---|
| `install`, `sync`, `doctor`, `uninstall`, `list` | 자동 sync (3단계: 세션 시작 훅 또는 주기 실행) |
| Claude Code, Codex 어댑터 | Gemini 어댑터 (5단계) |
| 스킬 + 공통 지식 설치 | MCP 서버 설정 설치, 사용 기록(텔레메트리) |
| 버전 태그 고정, lock 파일 | 롤백 명령, 단일 실행 파일(GraalVM native-image) |

## 3. 사용 모습
```bash
kit install                 # 최신 태그를 받아 감지된 도구에 모두 설치
kit install --tools claude  # 특정 도구만
kit install --version v0.2.0
kit sync                    # lock에 적힌 것과 최신 태그를 비교해 맞춤
kit doctor                  # 점검만 (아무것도 바꾸지 않음)
kit list                    # 설치된 스킬과 버전
kit uninstall               # 키트가 설치한 것만 제거
```
모든 변경 명령에 `--dry-run`(무엇을 바꿀지 보여 주기만)을 둔다.

## 4. 동작

### 4-1. 키트 받기
- 키트 레포를 `~/.kit/repo`에 `git clone` 하고, 지정한 태그(기본: 최신 `v*` 태그)로 체크아웃한다
- `sync`는 `git fetch --tags` 후 새 태그가 있으면 체크아웃한다
- git은 시스템의 `git` 명령을 그대로 쓴다. 인증도 각자 설정(SSH 등)을 따른다

### 4-2. 도구 감지
| 도구 | 감지 기준 |
|---|---|
| Claude Code | `~/.claude/` 폴더가 있음 (데스크톱 앱만 써도 생김) |
| Codex | `~/.codex/` 폴더가 있거나 `codex` 명령이 있음 |

### 4-3. 스킬 설치
| 도구 | 설치 위치 | 방식 |
|---|---|---|
| Claude Code | `~/.claude/skills/<스킬>` | `~/.kit/repo/kit/skills/<스킬>`로 심볼릭 링크 |
| Codex | `~/.agents/skills/<스킬>` | 심볼릭 링크 |

- 링크라서 `sync` 때 레포만 갱신하면 스킬도 함께 바뀐다
- 같은 이름의 폴더가 이미 있고 키트가 만든 것이 아니면 **건드리지 않고 경고**한다 (개인 스킬 보호)
- 키트가 만든 것인지는 lock 파일로 판단한다

### 4-4. 공통 지식 연결
| 도구 | 사용자 지침 파일 | 방식 |
|---|---|---|
| Claude Code | `~/.claude/CLAUDE.md` | 관리 구역 안에 `@~/.kit/repo/kit/context/AGENTS.md` 한 줄 (import) |
| Codex | `~/.codex/AGENTS.md` | 관리 구역 안에 내용 복사 (Codex 공식 문서에 import 기능이 없음. 전역 파일과 프로젝트 파일은 위에서 아래로 이어 붙임, 합계 기본 32 KiB 한도) |

관리 구역은 아래처럼 표시해서, 사용자가 직접 쓴 내용은 그대로 둔다.
```markdown
<!-- team-ai-kit:start (자동 관리 구역, 직접 수정하지 마세요) -->
@~/.kit/repo/kit/context/AGENTS.md
<!-- team-ai-kit:end -->
```
- 파일을 처음 바꾸기 전에 `<파일>.kit-backup`으로 백업한다

### 4-5. lock 파일 (`~/.kit/kit.lock.json`)
```json
{
  "version": "v0.2.0",
  "commit": "89d7e33",
  "installedAt": "2026-11-25T23:30:00+09:00",
  "tools": {
    "claude": {"skills": ["incident-analysis"], "context": "~/.claude/CLAUDE.md"},
    "codex":  {"skills": ["incident-analysis"], "context": "~/.codex/AGENTS.md"}
  }
}
```

### 4-6. doctor 점검 항목
- git 사용 가능, 키트 레포 존재, 현재 태그 vs 최신 태그
- 스킬 링크가 살아 있는지 (깨진 링크), lock에 있는데 없어진 스킬
- 지침 파일의 관리 구역이 그대로 있는지
- 결과는 항목별 `OK / 경고 / 오류`와 고치는 명령

## 5. 안전 원칙
- 키트가 만든 것(lock에 적힌 것)만 바꾸거나 지운다
- 지침 파일은 관리 구역 밖을 절대 건드리지 않고, 처음 바꿀 때 백업한다
- `doctor`와 `--dry-run`은 아무것도 바꾸지 않는다
- 네트워크 접근은 `git fetch/clone` 뿐이다

## 6. 코드 구조 (`cli/`)
```
cli/
├── build.gradle            # 개발은 JDK 17, 결과물은 --release 8 (Java 8 이상에서 실행), picocli, Jackson 2.x, JUnit5
└── src/main/java/kit/
    ├── KitCommand.java     # 최상위 명령 (picocli)
    ├── command/            # Install, Sync, Doctor, List, Uninstall
    ├── core/               # KitRepo(git), LockFile, ManagedBlock(관리 구역 편집)
    └── adapter/            # ToolAdapter 인터페이스 + ClaudeAdapter, CodexAdapter
```
- 새 도구 지원 = `ToolAdapter` 구현 하나 추가 (Gemini는 5단계)
- 테스트는 임시 폴더를 가짜 홈으로 써서 실제 홈 폴더를 건드리지 않는다

## 7. 결정 (2026-11-26)
1. **실행 방식**: jar + `kit` 실행 스크립트. 결과물은 **Java 8 기준**(`--release 8`)으로 빌드해 Java 8 이상 어디서나 실행. 스크립트가 먼저 `java -version`을 보고 8 미만이면 설치 안내. Java 9 이후 문법·API(`var`, `record`, `List.of()`, `Files.readString()`)는 쓰지 않는다. 자바가 없는 비개발자용 단일 실행 파일(GraalVM native-image)은 5단계
2. **설치 방식**: 심볼릭 링크 기본, `--copy` 옵션 제공
3. **키트 레포 접근**: 당분간 지금 private 저장소로 진행. 팀 도입 시점에 회사 GitHub 조직 이전과 승인 규칙(CODEOWNERS, 브랜치 보호)을 함께 결정
4. **버전 정책**: 스킬을 바꿀 때마다 태그(`v0.x.y`)를 붙여 배포. `install`·`sync`는 최신 태그를 기준으로 한다

## 8. POC 결과 (2026-11-26)
| 가정 | 결과 | 방법 |
|---|---|---|
| Claude Code가 `~/.claude/skills/`의 심볼릭 링크 스킬을 인식 | **확인** | 테스트 스킬을 링크하고, 다른 폴더의 새 세션에서 호출 |
| `~/.claude/CLAUDE.md`의 `@~/...` 홈 경로 import가 동작 | **확인** | 마커 파일을 import하고 새 세션에서 마커를 물어봄 |
| Codex 사용자 스킬 경로 `~/.agents/skills/`, 심볼릭 링크 지원 | 문서로 확인 | Codex 공식 문서 (설치 안 되어 있어 실행은 못 함) |
| Codex `AGENTS.md`의 import 지원 | 지원 안 함 (문서에 없음) | → 내용 복사 방식 |
| `codex exec`에서 `$스킬` 호출 | 미확인 | 문서에 없음. 3단계 자동 실행 때 확인 |

## 9. 스킬 변경 권한 (팀 도입 시)
- 개인 스킬은 키트 관리 밖. 키트는 덮어쓰지 않는 것만 지킨다
- 팀 스킬·공통 원칙은 누구나 PR로 제안하고, 처음에는 팀장이 승인한다. 스킬이 늘면 스킬별 담당자(CODEOWNERS)로 나눈다
- 사람 승인과 별개로 자동 검사(형식 검사, 4단계부터 eval)를 통과해야만 병합된다
- 병합 → 버전 태그 → `kit sync`로 전원 반영
