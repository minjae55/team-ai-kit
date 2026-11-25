#!/usr/bin/env bash
# 읽기 전용 MySQL 조회. SELECT / SHOW / EXPLAIN / DESC / WITH 문 하나만 허용한다.
# 세션을 READ ONLY로 열고, SELECT는 10초 넘으면 끊는다.
#
# 사용: db_query.sh "SHOW FULL PROCESSLIST"
#
# 접속 설정 (환경 변수, 프로젝트 AGENTS.md에 적힌 값을 쓴다):
#   INCIDENT_DB_CNF        접속 정보 파일(.my.cnf 형식) 경로. 스크립트만 읽고 화면에 내용을 출력하지 않는다
#   INCIDENT_DB_CONTAINER  MySQL이 도는 docker 컨테이너 이름 (있으면 docker exec로 실행)
#   INCIDENT_DB_NAME       기본 DB 이름 (선택)
#   INCIDENT_DB_HOST/PORT  접속 호스트·포트 (선택). 계정이 특정 호스트에서만 허용될 때 쓴다
set -euo pipefail

sql="${1:-}"
[ -n "$sql" ] || { echo "사용: db_query.sh \"<SQL>\"" >&2; exit 2; }

# --- 안전 검사 ---
trimmed="$(printf '%s' "$sql" | sed -E 's/^[[:space:]]+//; s/[[:space:];]+$//')"
upper="$(printf '%s' "$trimmed" | tr '[:lower:]' '[:upper:]')"
first="$(printf '%s' "$upper" | awk '{print $1}')"
case "$first" in
  SELECT|SHOW|EXPLAIN|DESC|DESCRIBE|WITH) ;;
  *) echo "거부: 읽기 문(SELECT/SHOW/EXPLAIN/DESC/WITH)만 허용됩니다" >&2; exit 3 ;;
esac
if printf '%s' "$trimmed" | grep -q ';'; then
  echo "거부: 문장은 하나만 허용됩니다 (; 금지)" >&2; exit 3
fi
# 키워드 검사는 따옴표 안 문자열('ALTER%' 같은 검색어)을 빼고 한다
no_literals="$(printf '%s' "$upper" | sed -E "s/'[^']*'//g")"
if printf '%s' "$no_literals" | grep -Eqw 'INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|GRANT|REVOKE|RENAME|LOAD|HANDLER|CALL'; then
  echo "거부: 쓰기·변경 키워드가 들어 있습니다" >&2; exit 3
fi
if printf '%s' "$upper" | grep -Eq 'EXPLAIN[[:space:]]+ANALYZE|INTO[[:space:]]+(OUT|DUMP)FILE|FOR[[:space:]]+UPDATE|LOCK[[:space:]]+IN[[:space:]]+SHARE|SLEEP[[:space:]]*\(|BENCHMARK[[:space:]]*\('; then
  echo "거부: 쿼리를 실제 실행하거나 락을 잡거나 파일을 쓰는 구문은 허용되지 않습니다" >&2; exit 3
fi

# --- 접속 정보 ---
cnf="${INCIDENT_DB_CNF:-}"
[ -n "$cnf" ] && [ -f "$cnf" ] || { echo "INCIDENT_DB_CNF에 접속 정보 파일 경로를 지정하세요 (프로젝트 AGENTS.md 참고)" >&2; exit 4; }
# Windows 줄 끝(CRLF)의 \r을 제거하고 읽는다
get() { tr -d '\r' < "$cnf" | awk -F= -v k="$1" '$1~"^[[:space:]]*"k"[[:space:]]*$"{sub(/^[^=]*=[[:space:]]*/,""); gsub(/^["'\'']|["'\'']$/,""); print; exit}'; }
db_user="$(get user)"; db_pass="$(get password)"
[ -n "$db_user" ] || { echo "접속 정보 파일에 user가 없습니다" >&2; exit 4; }

full_sql="SET SESSION TRANSACTION READ ONLY; SET SESSION MAX_EXECUTION_TIME=10000; $trimmed;"
db_args=(--user="$db_user" --table)
[ -n "${INCIDENT_DB_NAME:-}" ] && db_args+=(--database="$INCIDENT_DB_NAME")
[ -n "${INCIDENT_DB_HOST:-}" ] && db_args+=(--host="$INCIDENT_DB_HOST" --port="${INCIDENT_DB_PORT:-3306}")

if [ -n "${INCIDENT_DB_CONTAINER:-}" ]; then
  # 비밀번호는 환경 변수로만 넘긴다 (명령줄·화면에 남기지 않음)
  MYSQL_PWD="$db_pass" docker exec -i -e MYSQL_PWD "$INCIDENT_DB_CONTAINER" \
    mysql "${db_args[@]}" -e "$full_sql" 2> >(grep -v 'Using a password' >&2)
elif command -v mysql >/dev/null; then
  mysql --defaults-extra-file="$cnf" --table ${INCIDENT_DB_NAME:+--database="$INCIDENT_DB_NAME"} -e "$full_sql"
else
  echo "mysql 클라이언트도, INCIDENT_DB_CONTAINER 설정도 없습니다" >&2; exit 4
fi
