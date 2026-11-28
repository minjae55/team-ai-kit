# 측정 조건
- 스크립트: k6/product-detail.js (GET /api/products/{id}, name:product_detail)
- 개선 전 (2026-11-26 10:00): 50 VU, 60초, 상품 2,000개
- 개선 후 (2026-11-26 16:30): 100 VU, 120초, 상품 2,000개
- 환경: 로컬 MacBook Pro, Docker
- 측정 횟수: 각 1회
