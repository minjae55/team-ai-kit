# EXPLAIN 읽는 법과 흔한 원인 (MySQL 8 기준)

## 주요 칸
| 칸 | 나쁜 신호 | 뜻 |
|---|---|---|
| type | `ALL` | 전체 스캔 (인덱스 안 씀) |
| type | `index` | 인덱스 전체를 훑음 (조건이 아니라 커버링·정렬 때문에 고른 경우가 많음) |
| type | `range`, `ref`, `eq_ref`, `const` | 인덱스로 범위·같다 조건을 찾음 (좋은 쪽) |
| key | `NULL` | 쓸 인덱스가 없거나 옵티마이저가 안 고름. `possible_keys`와 비교 |
| rows × filtered | 돌려주는 행보다 훨씬 큼 | 읽고 버리는 행이 많다 |
| Extra | `Using filesort` | 인덱스 순서로 정렬하지 못해 따로 정렬. LIMIT이 있어도 조건에 맞는 행을 전부 읽고 정렬한다 |
| Extra | `Using temporary` | GROUP BY·DISTINCT·UNION 처리에 임시 테이블 |
| Extra | `Using index` | 커버링 인덱스 (테이블을 안 읽음, 좋은 쪽) |
| Extra | `Using index condition` | 인덱스 조건 푸시다운 (보통 괜찮음) |

## 흔한 원인과 처방
1. **조건은 인덱스를 타지만 정렬이 안 됨** (`ref` + `Using filesort`)
   - 예: `WHERE buyer_id = ? ORDER BY created_at DESC LIMIT 20`, 인덱스는 `(buyer_id)`만 있음
   - 처방: `(buyer_id, created_at)`. 같다 조건이 더 있으면 그 컬럼들을 앞에: `WHERE buyer_id=? AND status=? ORDER BY created_at` → `(buyer_id, status, created_at)`
2. **선택도가 낮은 단일 인덱스** (예: `status`만 있는 인덱스. 값이 몇 개뿐이라 거의 다 읽음)
   - 처방: 선택도 높은 컬럼과 묶은 복합 인덱스. 단독 인덱스는 삭제 후보로 검토
3. **컬럼 가공**: `WHERE DATE(created_at) = ?`, `WHERE LOWER(email) = ?` → 범위 조건으로 다시 쓰거나 함수 인덱스
4. **앞에 %가 붙은 LIKE**: `LIKE '%abc'` → 인덱스 불가. 전문 검색·검색 엔진 검토
5. **타입 불일치·암묵 변환**: 문자열 컬럼에 숫자 비교 → 전체 스캔
6. **큰 OFFSET 페이지**: `LIMIT 20 OFFSET 100000` → 앞의 10만 건을 읽고 버림. 커서(마지막 id) 방식
7. **인덱스가 있는데 안 탐**: `IS_VISIBLE = NO`(숨김), 통계가 낡음, 조건이 OR로 묶임
8. **N+1**: 쿼리 하나는 빠른데 요청당 수십~수백 번 실행 → 실행 횟수(COUNT_STAR)와 로그로 확인, fetch join·IN 일괄 조회

## 인덱스 제안 시 같이 적을 것
- 예상 실행 계획 변화 (type, key, filesort 유무)
- 쓰기 비용(INSERT/UPDATE마다 인덱스 갱신), 크기
- 생성 방법과 부하: MySQL 8 InnoDB는 대부분 온라인 DDL(`ALGORITHM=INPLACE, LOCK=NONE`)이지만 큰 테이블은 시간이 걸리고 디스크·복제 지연이 생길 수 있다
- 되돌리기: `ALTER INDEX … INVISIBLE`로 숨겨 효과를 비교하고, 필요 없으면 `DROP INDEX`
