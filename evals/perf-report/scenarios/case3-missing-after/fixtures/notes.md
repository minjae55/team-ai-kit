# 메모
- 개선 전 측정: 2026-11-27 14:00, k6/order-create-stages.js, 50 VU 60초
- 원인: 주문 생성 중 카트 조회가 carts 전체 스캔 (EXPLAIN type=ALL, 41만 행)
- 변경: carts(user_id, cart_type) 복합 인덱스 추가 (커밋 참고)
- 개선 후 측정: 아직 안 함 (다음 주 같은 조건으로 예정)
