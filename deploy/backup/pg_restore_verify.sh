#!/usr/bin/env bash
# 恢复验证（PRD §11.4 / 设计 §5.3：上线前必做——额度账本不可丢，必须验证备份可恢复）。
#
# 用法：
#   bash pg_restore_verify.sh <sks-YYYY-MM-DD.sql.gz>
#
# 流程：解压 → 导入临时库 sks_verify → 关键表计数 → 删临时库。
# 不碰生产库 sks；仅读备份文件验证完整性 + 关键业务表（额度账本 / 稿件 / 卡片等）可还原。
set -euo pipefail

file="${1:-}"
if [ -z "$file" ] || [ ! -f "$file" ]; then
    echo "用法: bash pg_restore_verify.sh <sks-YYYY-MM-DD.sql.gz>" >&2
    exit 2
fi

COMPOSE_DIR="${COMPOSE_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
# 与 deploy.sh 一致恒走 prod compose（见 pg_backup.sh 同款注释）。
COMPOSE_FILES=(-f "$COMPOSE_DIR/docker-compose.yml" -f "$COMPOSE_DIR/docker-compose.prod.yml")
dc() { docker compose "${COMPOSE_FILES[@]}" --project-directory "$COMPOSE_DIR" "$@"; }
DB_USER="${SPRING_DATASOURCE_USERNAME:-sks}"
VERIFY_DB="${VERIFY_DB:-sks_verify}"

# 关键业务表——若恢复后这些表不存在/计数异常，备份视为不可用。
TABLES=(app_user credit_account credit_ledger script kb_card topic analyze_task)

echo "[pg_restore_verify] creating temp db $VERIFY_DB (drop if exists) ..."
dc exec -T postgres \
    psql -U "$DB_USER" -d postgres -c "DROP DATABASE IF EXISTS $VERIFY_DB;" >/dev/null
dc exec -T postgres \
    psql -U "$DB_USER" -d postgres -c "CREATE DATABASE $VERIFY_DB;" >/dev/null

echo "[pg_restore_verify] restoring $file -> $VERIFY_DB ..."
gunzip -c "$file" | dc exec -T postgres \
    psql -U "$DB_USER" -d "$VERIFY_DB" -v ON_ERROR_STOP=1 >/dev/null

echo "[pg_restore_verify] sanity counts (空库也合法——只验证表结构存在 + 查询不报错):"
fail=0
for tbl in "${TABLES[@]}"; do
    cnt=$(dc exec -T postgres \
        psql -U "$DB_USER" -d "$VERIFY_DB" -tAc "SELECT count(*) FROM $tbl;" 2>/dev/null || echo "ERR")
    printf "  %-16s %s\n" "$tbl" "$cnt"
    [ "$cnt" = "ERR" ] && fail=1
done

echo "[pg_restore_verify] dropping temp db $VERIFY_DB ..."
dc exec -T postgres \
    psql -U "$DB_USER" -d postgres -c "DROP DATABASE $VERIFY_DB;" >/dev/null

if [ "$fail" -eq 1 ]; then
    echo "[pg_restore_verify] FAILED: 某些关键表缺失——备份不可用" >&2
    exit 1
fi
echo "[pg_restore_verify] done: 备份可恢复，关键表齐全"
