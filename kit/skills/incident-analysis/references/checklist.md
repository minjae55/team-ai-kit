# 증상별 확인 항목

지표 이름은 Spring Boot(Micrometer) + mysqld-exporter 기준이다. 프로젝트 AGENTS.md에 다른 이름이 있으면 그것을 따른다.
`<URI>`, `<METHOD>`는 대상 API로 바꾼다.

## 공통: 먼저 이 셋
| 확인 | PromQL |
|---|---|
| p95 응답 시간 | `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri="<URI>",method="<METHOD>"}[30s])))` |
| 처리량 | `sum(rate(http_server_requests_seconds_count{uri="<URI>",method="<METHOD>"}[30s]))` |
| 상태 코드별 비율 | `sum by (status) (rate(http_server_requests_seconds_count{uri="<URI>"}[30s]))` |

처리량이 늘면서 느려졌다 → 부하로 인한 포화를 의심(아래 커넥션·DB).
처리량이 그대로인데 느려졌다 → 특정 쿼리·코드 변경·외부 호출을 의심.

## 느림 / 타임아웃
1. **커넥션 풀 포화**: `hikaricp_connections_pending > 0`이 계속되고, `hikaricp_connections_active`가 `hikaricp_connections_max`에 붙어 있다.
   - 획득 대기: `rate(hikaricp_connections_acquire_seconds_sum[30s]) / rate(hikaricp_connections_acquire_seconds_count[30s])`
   - 풀이 꽉 찼다면, 원인은 보통 풀 크기보다 **커넥션을 오래 잡는 쿼리·트랜잭션**이다 → DB 확인으로 넘어간다.
2. **DB가 바쁨**: `mysql_global_status_threads_running` 상승, `rate(mysql_global_status_queries[1m])`.
3. **행 락 경합**: `rate(mysql_global_status_innodb_row_lock_waits[1m])` 증가, `rate(mysql_global_status_innodb_row_lock_time[1m])`.
4. **슬로우 쿼리**: `increase(mysql_global_status_slow_queries[5m]) > 0`.
5. **단계별 시간**(앱이 제공할 때): 어느 단계가 늘었는지 본다. 예: `order_create_step_seconds`.
6. **JVM**: GC 정지 `rate(jvm_gc_pause_seconds_sum[1m])`, 스레드 `jvm_threads_live_threads`, 톰캣 스레드 `tomcat_threads_busy_threads`(설정에 따라 없을 수 있음).

## 에러 증가
- 상태 코드별 비율에서 4xx인지 5xx인지 먼저 나눈다. 4xx 급증은 대개 클라이언트·인증·검증 문제다.
- 예외 종류: `sum by (exception) (rate(http_server_requests_seconds_count{uri="<URI>",status=~"5.."}[1m]))`
- 커넥션 획득 타임아웃은 5xx와 함께 `hikaricp_connections_timeout_total` 증가로 나타난다.

## DB 조회 (db_query.sh)
| 목적 | SQL |
|---|---|
| 지금 실행 중인 쿼리 | `SHOW FULL PROCESSLIST` |
| 오래 걸리는 트랜잭션 | `SELECT trx_id, trx_state, trx_started, TIMESTAMPDIFF(SECOND, trx_started, NOW()) AS sec, trx_rows_locked, LEFT(trx_query, 200) AS q FROM information_schema.innodb_trx ORDER BY trx_started` |
| 락 대기 관계 | `SELECT w.REQUESTING_ENGINE_TRANSACTION_ID AS waiting_trx, w.BLOCKING_ENGINE_TRANSACTION_ID AS blocking_trx, l.OBJECT_NAME AS tbl, l.INDEX_NAME AS idx, l.LOCK_MODE FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.BLOCKING_ENGINE_LOCK_ID` (`sys.innodb_lock_waits`는 권한이 더 필요해서 쓰지 않음) |
| **앱 쿼리 통계 (서버 측 prepared statement)** | `SELECT LEFT(REPLACE(SQL_TEXT, '\n', ' '), 120) AS q, SUM(COUNT_EXECUTE) AS cnt, ROUND(SUM(SUM_TIMER_EXECUTE)/1e12, 2) AS total_s, ROUND(SUM(SUM_TIMER_EXECUTE)/GREATEST(SUM(COUNT_EXECUTE),1)/1e9, 2) AS avg_ms, SUM(SUM_ROWS_EXAMINED) AS rows_examined FROM performance_schema.prepared_statements_instances GROUP BY q ORDER BY total_s DESC LIMIT 10` — JDBC 설정에 `useServerPrepStmts=true`가 있으면 앱 쿼리는 아래 digest 테이블이 아니라 **여기에만** 쌓인다. 커넥션마다 한 줄이라 `GROUP BY`로 합친다 |
| 누적 상위 쿼리 (총 시간 순) | `SELECT LEFT(DIGEST_TEXT, 200) AS q, COUNT_STAR AS cnt, ROUND(SUM_TIMER_WAIT/1e12, 2) AS total_s, ROUND(AVG_TIMER_WAIT/1e9, 1) AS avg_ms, SUM_ROWS_EXAMINED AS rows_examined, SUM_NO_INDEX_USED AS no_index FROM performance_schema.events_statements_summary_by_digest ORDER BY SUM_TIMER_WAIT DESC LIMIT 10` |
| 인덱스 없이 도는 쿼리 | 위 쿼리에 `WHERE SUM_NO_INDEX_USED > 0` |
| 특정 테이블 쿼리만 | 위 쿼리에 `WHERE DIGEST_TEXT LIKE '%negotiations%'` (DIGEST_TEXT는 테이블 이름을 백틱으로 감싸고 값은 `?`로 바꿔 저장하므로, 테이블 이름 일부로만 검색한다) |
| 실행 계획 | `EXPLAIN <의심 SELECT>` — `type=ALL`(전체 스캔), 큰 `rows`, `Using filesort/temporary`를 본다 |
| 테이블 크기 | `SELECT table_name, table_rows, ROUND(data_length/1024/1024) AS data_mb FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY data_length DESC LIMIT 15` |

주의:
- `events_statements_summary_by_digest`는 서버가 켜진 뒤(또는 초기화 뒤) **누적값**이다. 특정 구간만 보려면 지표와 함께 해석한다.
- 권한이 없어 실패하면 "권한 부족으로 확인 못 함"으로 기록하고 넘어간다.

## 최근 변경
```bash
git log --since="<시작 2시간 전>" --until="<끝>" --stat --oneline
git log -5 --oneline -- <대상 API 관련 경로>
```
설정 파일(application*.yml/properties, 커넥션 풀, 캐시 설정) 변경은 코드 변경보다 먼저 의심한다.
