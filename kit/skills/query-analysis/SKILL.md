---
name: query-analysis
description: 느린 SQL 쿼리의 원인을 실행 계획(EXPLAIN), 인덱스, 테이블 통계로 분석하고 인덱스·쿼리 개선안을 제안한다. "이 쿼리 왜 느려", "EXPLAIN 봐줘", "인덱스 추천해줘", "슬로우 쿼리 분석", "full scan", "filesort" 같은 요청이나 특정 쿼리·리포지토리 메서드의 성능을 물을 때 사용한다. DB에는 읽기만 하고 인덱스 생성 같은 변경은 사람 승인 절차로 제안만 한다.
---

# 느린 쿼리 분석

## 절대 규칙
- **읽기만 한다.** 인덱스 생성·삭제, `ANALYZE TABLE`, 설정 변경, 데이터 수정은 하지 않는다. 필요하면 "사람 승인 필요"로 제안한다.
- **`EXPLAIN ANALYZE`는 쿼리를 실제로 실행한다.** 큰 테이블에서 부하를 줄 수 있어 스크립트가 막는다. 꼭 필요하면 사람에게 요청한다.
- **추측으로 인덱스를 권하지 않는다.** 실행 계획과 현재 인덱스 목록을 본 뒤, 무엇이 바뀌는지 근거를 붙여 제안한다.
- 비밀값 파일(.env, 접속 정보 파일)은 열지 않는다.

## 1. 쿼리 확보
- 사람이 SQL을 줬으면 그대로 쓴다.
- 리포지토리 메서드·API만 줬으면 코드에서 실제 SQL을 찾는다 (JPA 메서드 이름, `@Query`, MyBatis XML). 바인딩 값은 대표값으로 채운다.
- 프로젝트의 `AGENTS.md`(또는 `CLAUDE.md`)에서 DB 접근 방법(접속 설정 환경 변수)을 읽는다. 이 스킬은 프로젝트별 값을 갖고 있지 않다.

## 2. 근거 수집
스크립트는 이 SKILL.md가 있는 폴더의 `scripts/` 안에 있다. **전체 경로로 하나씩 직접** 호출한다 (`bash /…/scripts/db_query.sh "…"`). `sh`로 실행하거나 명령을 변수에 담지 않는다.

1. **실행 계획**: `EXPLAIN <쿼리>` (가능하면 `EXPLAIN FORMAT=TREE`도)
2. **현재 인덱스**: `SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME, CARDINALITY, IS_VISIBLE FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '<테이블>' ORDER BY INDEX_NAME, SEQ_IN_INDEX`
3. **테이블 크기**: `SELECT TABLE_NAME, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '<테이블>'`
4. **실제 실행 통계**(있으면): `performance_schema.events_statements_summary_by_digest`의 평균 시간, `SUM_ROWS_EXAMINED / COUNT_STAR`(실행당 검사한 행), `SUM_ROWS_SENT / COUNT_STAR`. 앱이 서버 측 prepared statement를 쓰면 `prepared_statements_instances`를 본다 (프로젝트 AGENTS.md 참고)
5. 조건 컬럼의 분포가 의심되면 `SELECT <컬럼>, COUNT(*) … GROUP BY … ORDER BY 2 DESC LIMIT 10`처럼 가볍게 본다 (큰 테이블에서는 LIMIT·인덱스 확인 후)

실행 계획 읽는 법과 흔한 원인은 `references/explain-guide.md`를 따른다.

## 3. 판단
- **검사한 행 수 vs 돌려준 행 수**: 수만 건을 읽고 20건을 돌려준다면 인덱스가 조건·정렬을 다 못 덮는 것이다.
- `type`, `key`, `rows`, `filtered`, `Extra`(Using filesort, Using temporary, Using index)를 근거로 원인을 하나씩 적는다.
- 인덱스를 제안할 때: **같다(=) 조건 컬럼 → 범위 조건 또는 정렬 컬럼** 순서로 묶고, 기존 인덱스와 겹치면 대체(기존 것 삭제 후보)인지 추가인지 밝힌다.
- 인덱스 말고 쿼리를 고치는 편이 나은 경우(컬럼에 함수 적용, 앞에 %가 붙은 LIKE, 타입 불일치, 큰 OFFSET, N+1)를 먼저 확인한다.

## 4. 결과 출력 (이 형식 그대로)

```markdown
## 요약
(한두 문장: 어떤 쿼리가, 왜 느리고, 무엇을 바꾸면 되는지)

## 원인 (가능성 높은 순)
1. <원인> — 근거: <EXPLAIN의 type/key/rows/Extra, 인덱스 목록, 실행당 검사 행 수>
2. ...

## 개선안
- 제안 (사람 승인 필요): <DDL 또는 쿼리 변경>
  - 바뀌는 것: <예상 실행 계획: type, key, filesort 사라짐 등>
  - 비용·위험: <인덱스 크기, 쓰기 비용, 생성 중 부하, 기존 인덱스와 중복>
  - 되돌리기: <예: ALTER TABLE … ALTER INDEX … INVISIBLE 로 먼저 효과 확인, 문제 시 DROP>
- 검증 방법: 적용 전후 같은 조건으로 EXPLAIN과 응답 시간(또는 부하 테스트)을 비교한다

## 확인한 것 / 못 한 것
- 확인: 실행 계획 ○, 인덱스 ○, 테이블 크기 ○, 실행 통계 ○/×
- 추가 확인 필요: <무엇을, 어떻게>
```
