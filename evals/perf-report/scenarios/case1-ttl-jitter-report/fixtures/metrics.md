# Prometheus 기록 (측정 구간, collect.sh 가 저장)
| 지표 | 개선 전 | 개선 후 |
|---|---|---|
| hikaricp_connections_pending 최대 | 88 | 0 |
| hikaricp_connections_active 최대 | 10 (풀 크기 10, 포화) | 4 |
| 커넥션 획득 평균 시간 최대 | 1,412 ms | 0.3 ms |
| mysql 초당 쿼리 최대 | 1,950 | 310 |

# 스레드 덤프 (캐시 만료 시점 t+60.0s)
- 개선 전: http-nio 스레드 96개 중 88개가 HikariPool.getConnection 에서 대기 (WAITING)
- 개선 후: 대기 스레드 0개
