# 측정 조건 (전후 동일)
- 스크립트: k6/negotiation-cache-expiry.js (협의 목록 GET /api/negotiations, 100 VU, 120초, 캐시 워밍업 후 측정)
- 환경: 로컬 MacBook Pro (Intel i7, 16GB), 앱·MySQL·Prometheus 모두 같은 장비의 Docker
- 데이터: 협의 2,400건, 구매자 100명 (같은 데이터로 두 번 측정)
- 측정 횟수: 전후 각 1회
- 개선 전: 커밋 3f2a91c (TTL 60초 고정) / 개선 후: 커밋 8c41d07 (TTL 60초 + 0~15초 jitter)
