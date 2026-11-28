# 스킬 추가·수정 안내

팀 키트에는 **팀원 누구나 쓸 만하고, 시나리오로 검증된 스킬**만 들어간다. main은 PR로만 바뀌고, 검사를 통과해야 병합된다.

## 1. 스킬이 될 만한가
- 두 명 이상이 반복하는 작업인가 (`kit ui`의 "스킬로 만들면 좋을 반복 작업"이 근거가 된다)
- 프로젝트가 달라도 같은 절차로 할 수 있는가 (프로젝트별 값은 각 프로젝트 `AGENTS.md`로)
- 한 사람만 쓰는 업무라면 팀 키트가 아니라 개인 스킬(`~/.claude/skills/`)로 둔다

## 2. 만들기
```
kit/skills/<이름>/
  SKILL.md        # frontmatter는 name, description만. 500줄 이하
  scripts/        # 판정·계산은 스크립트로 (AI가 눈으로 판정하지 않게). 읽기 전용이 기본
  references/     # 긴 설명, 양식, 체크리스트
```
- `description`에 "무엇을, 언제" 쓰는지와 실제 요청 예시("…해줘")를 넣는다. AI가 이걸 보고 스킬을 고른다
- 쓰기·변경·발송처럼 되돌리기 어려운 동작은 사람 승인 절차를 적는다

## 3. 시나리오 (스킬마다 최소 1개, 권장 3개)
```
evals/<이름>/scenarios/<시나리오>/
  case.json       # 요청(prompt), 정답 설명(answer), 확인 항목(checks)
  fixtures/       # 그때의 데이터·코드·로그 (가짜 데이터. 실제 개인정보·비밀값 금지)
```
- 시나리오마다 **함정 하나**를 넣는다 (예: 없는 숫자를 만들게 유도, 명세와 다른 구현, 이미 보낸 사람)
- 확인 항목 종류는 `AGENTS.md`의 "검증" 절 참고 (답변 검사, 명령 검사, 파일 검사, 스크립트 검사)
- AI가 실제로 틀렸던 답변은 `evals/<이름>/grader-tests/`에 남겨 채점기가 계속 잡는지 확인한다

## 4. 검증
```bash
python3 tools/validate_skills.py            # 형식
python3 tools/eval/test_grader.py           # 채점기·시나리오 무결성
python3 tools/eval/run.py --skill <이름>    # AI eval (claude CLI, 구독 사용량 사용)
```
- AI eval은 내 PC에 설치된 같은 이름 스킬을 시험 동안 자동으로 끄고, 끝나면 켠다
- 결과는 `evals/<이름>/verified.json`에 기록된다. 이 파일을 PR에 함께 올리면 팀원의 `kit list`·`kit ui`에 "검증 N/M"으로 보인다

## 5. PR → 병합 → 배포
- PR을 올리면 CI(형식 검사, 채점기·시나리오 검사, CLI 테스트)가 돈다. 모두 통과하고 관리자가 승인하면 병합된다
- 관리자가 `CHANGELOG.md` 새 버전 절 → `tools/release.sh 0.x.y` → 태그 푸시로 배포한다. 팀원은 자동 업데이트로 받는다
