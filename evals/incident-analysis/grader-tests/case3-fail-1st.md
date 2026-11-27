요약
21:44~45 사이에 GET /api/buyer/orders 요청 55건이 평균 4.6~5초 걸렸고, 이때 슬로우 쿼리가 약 54건 늘어서 알림이 왔습니다. 원인은 JPA 파생 쿼리 findByBuyer_UserIdOrderByCreatedAtDesc(OrderRepository.java:18)입니다. 이 쿼리가 orders 테이블(97만 행)을 인덱스 없이 풀스캔하고 filesort까지 합니다.

원인 후보
1. 주문 목록 쿼리가 idx_orders_buyer_created를 못 탐 — 가능성 높음
만들어지는 SQL: from orders o1_0 left join users b1_0 on ... where b1_0.user_id=? order by o1_0.created_at desc
조건이 orders.buyer_id가 아니라 조인한 users.user_id에 걸려 있습니다. LIMIT도 없습니다.
EXPLAIN: o1_0이 type=ALL, possible_keys=NULL, rows 977,109로 나오고 Using filesort가 붙습니다(buyer 5, 601 둘 다 확인).
실행 통계 (prepared_statements_instances): 최대 6.93초이고, 최근 9회는 평균 4.13초에 회당 129만 행을 검사했습니다.
비교: WHERE buyer_id=?로 직접 조회하면 0.002초, 4,562행입니다(21:40:39 digest).
반대 근거: 21:41~42의 145건은 평균 411~449ms로 빨랐습니다. 동시 실행 수가 달라서인지 실행 계획이 달라서인지는 아직 확인하지 못했습니다.
2. 직접 실행한 실험 쿼리 — 일부만 해당
21:40:39에 IGNORE INDEX 쿼리(3.15초), 21:35에 order_items 집계(1.26초)가 root 계정으로 실행됐습니다.

확인한 것 / 못 한 것
확인: 지표 ○, DB(performance_schema, EXPLAIN) ○, 로그(logs/app.log) ○, 최근 변경 ○
오늘 커밋은 문서 커밋뿐이라 관련 변경은 없습니다.

개선 방안
즉시: 없습니다. 인덱스는 이미 있으니 쿼리만 고치면 됩니다.
근본 해결:
findByBuyer_UserId... 대신 @Query("... where o.buyer.userId = :userId")처럼 orders.buyer_id에 직접 조건을 걸도록 바꿉니다.
구매자 한 명당 주문이 약 4,500건이라 페이징(Pageable/LIMIT)도 같이 넣는 게 좋습니다.

리포지토리 쿼리를 buyer_id 조건으로 고칠까요?
