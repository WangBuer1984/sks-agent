#!/usr/bin/env bash
# 恢复验证：上线前必做——额度账本不可丢，必须验证备份可恢复。
#
# 用法：
#   bash pg_restore_verify.sh <sks-YYYY-MM-DD.sql.gz>
#
# 流程：解压 → 导入临时库 sks_verify → 关键表计数 → 删临时库。
# 不碰生产库 sks。
set -euo pipefail

file="${1:-}"
if [ -z "$file" ] || [ ! -f "$file" ]; then
  echo "用法: bash pg_restore_verify.sh <sks-YYYY-MM-DD.sql.gz>" >&2
  exit 2
fi
if ! gzip -t "$file"; then
  echo "[pg_restore_verify] ERROR: $file 不是合法 gzip" >&2
  exit 2
fi

COMPOSE_DIR="${COMPOSE_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
COMPOSE_FILES=(-f "$COMPOSE_DIR/docker-compose.yml" -f "$COMPOSE_DIR/docker-compose.prod.yml")
dc() { docker compose "${COMPOSE_FILES[@]}" --project-directory "$COMPOSE_DIR" "$@"; }
DB_USER="${POSTGRES_USER:-${SPRING_DATASOURCE_USERNAME:-sks}}"
PROD_DB="${POSTGRES_DB:-${PG_DB_NAME:-sks}}"
VERIFY_DB="${VERIFY_DB:-sks_verify}"

if ! [[ "$VERIFY_DB" =~ ^[a-z][a-z0-9_]*$ ]] || [ "$VERIFY_DB" = "$PROD_DB" ] || [ "$VERIFY_DB" = "postgres" ]; then
  echo "[pg_restore_verify] ERROR: VERIFY_DB='$VERIFY_DB' 非法或等于生产库，拒绝执行" >&2
  exit 2
fi

# 金丝雀：缺一张即视为备份不可用。不是全量表清单。
TABLES=(app_user credit_account credit_ledger recharge_order script kb_card topic analyze_task)

psql_postgres() {
  dc exec -T postgres psql -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 "$@"
}

drop_verify_db() {
  # PG16 支持 FORCE，避免上次失败残留连接导致 DROP 卡住。
  psql_postgres -c "DROP DATABASE IF EXISTS ${VERIFY_DB} WITH (FORCE);" >/dev/null 2>&1 || true
}

if ! dc exec -T postgres pg_isready -U "$DB_USER" >/dev/null; then
  echo "[pg_restore_verify] ERROR: postgres 未就绪" >&2
  exit 1
fi

trap drop_verify_db EXIT

echo "[pg_restore_verify] creating temp db $VERIFY_DB (drop if exists) ..."
drop_verify_db
psql_postgres -c "CREATE DATABASE ${VERIFY_DB};" >/dev/null

echo "[pg_restore_verify] restoring $file -> $VERIFY_DB ..."
gunzip -c "$file" | dc exec -T postgres \
  psql -U "$DB_USER" -d "$VERIFY_DB" -v ON_ERROR_STOP=1 >/dev/null

echo "[pg_restore_verify] sanity counts (空表合法，缺表不合法):"
fail=0
for tbl in "${TABLES[@]}"; do
  if ! cnt=$(dc exec -T postgres \
      psql -U "$DB_USER" -d "$VERIFY_DB" -v ON_ERROR_STOP=1 -tAc "SELECT count(*) FROM ${tbl};"); then
    printf "  %-16s ERR\n" "$tbl"
    fail=1
    continue
  fi
  printf "  %-16s %s\n" "$tbl" "$cnt"
done

if [ "$fail" -eq 1 ]; then
  echo "[pg_restore_verify] FAILED: 某些关键表缺失——备份不可用" >&2
  exit 1
fi
echo "[pg_restore_verify] done: 备份可恢复，关键表齐全"
