요약
21:56:15~21:57:30 사이에 슬로우 쿼리가 약 130건 생겼습니다(30초당 최대 36건). 원인은 그 직전인 21:55:54에 `orders.idx_orders_buyer_created` 인덱스가 INVISIBLE로 바뀐 것입니다. 이 때문에 구매자 주문 목록 조회(`GET /api/buyer/orders`)가 약 98만 행을 전부 훑었고, 한 번에 2~3초씩 걸렸습니다.

원인 후보
1. 인덱스 INVISIBLE로 전체 스캔 — 가능성 높음
* DDL 기록: `ALTER INDEX idx_orders_buyer_created INVISIBLE`가 마지막으로 21:55:54에 실행됐습니다. 지금 상태는 `visible=NO`입니다.
* 실행 계획: orders를 `type=ALL`로 읽습니다. 사용 가능한 인덱스가 없고(`possible_keys=NULL`), 예상 rows는 977,109건이며, `Using filesort`로 정렬까지 따로 합니다.
* 쿼리 통계: 이 SELECT는 105회 실행에 평균 2,394ms였고, 총 9,859만 행을 읽었습니다.
* 같은 API의 정상 구간과 비교: 인덱스를 끄기 전인 21:42:00에는 약 400ms였는데, 21:57:08에는 약 3,280ms였습니다.
* 반대 근거: 없습니다.

확인한 것 / 못 한 것
* 확인: 지표 ○, DB ○(DDL·인덱스·EXPLAIN), 로그 ○(logs/app.log), 최근 변경 ○
* 확인하지 못한 것: 누가 ALTER를 실행했는지는 DB 기록만으로는 알 수 없습니다.

개선 방안
* 즉시 (사람 승인 필요): 아래 SQL을 실행합니다. 쓰기 권한이 필요하고, 저는 분석만 해서 실행하지 않았습니다.
ALTER TABLE orders ALTER INDEX idx_orders_buyer_created VISIBLE;
* 근본 해결:
   * 쿼리 자체도 손볼 여지가 있습니다. 지금은 쓸모없는 `LEFT JOIN users` 뒤에 `WHERE b1_0.user_id=?`로 거르는데, `o.buyer_id=?`로 바로 거르면 더 단순해집니다.
   * 페이지 처리가 없어 구매자의 주문을 전부 가져옵니다.
