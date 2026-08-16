#!/usr/bin/env bash
# 每日备份 sks 数据库（额度账本等业务表）。只写本机，不做异地 OSS。
#
# 宿主 crontab（上线后安装，见 SERVER_INIT.md §7 / OPS.md §2）：
#   0 3 * * * /opt/sks/deploy/backup/pg_backup.sh >> /var/log/sks-pg-backup.log 2>&1
#
# 用法：
#   bash pg_backup.sh                         # 备份 + 本地保留 RETAIN_DAYS 天
#
# 上线前必须跑一次 pg_restore_verify.sh 验证可恢复。
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/backup}"
# compose 里是 POSTGRES_USER；文档 crontab 写过 SPRING_DATASOURCE_USERNAME。两者默认都是 sks。
DB_USER="${POSTGRES_USER:-${SPRING_DATASOURCE_USERNAME:-sks}}"
DB_NAME="${POSTGRES_DB:-${PG_DB_NAME:-sks}}"
COMPOSE_DIR="${COMPOSE_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
# 与 deploy.sh 一致带上 prod override。prod 只改 nginx，postgres 服务名不变；
# 本地 80-only 起的栈也能 exec 到同一项目里的 postgres。
COMPOSE_FILES=(-f "$COMPOSE_DIR/docker-compose.yml" -f "$COMPOSE_DIR/docker-compose.prod.yml")
dc() { docker compose "${COMPOSE_FILES[@]}" --project-directory "$COMPOSE_DIR" "$@"; }
RETAIN_DAYS="${RETAIN_DAYS:-30}"

mkdir -p "$BACKUP_DIR"

stamp=$(date +%F)
file="$BACKUP_DIR/sks-$stamp.sql.gz"

# dump 中途失败时删掉半成品，避免留下「有文件名、其实空/坏」的当日备份。
cleanup_partial() {
  rm -f "$file"
}
trap cleanup_partial ERR

if ! dc exec -T postgres pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null; then
  echo "[pg_backup] ERROR: postgres 未就绪（容器未起？）" >&2
  exit 1
fi

echo "[pg_backup] dumping to $file ..."
# 不要把 pg_dump 的 stderr 吞掉——crontab 日志里要能看见失败原因。
dc exec -T postgres pg_dump -U "$DB_USER" "$DB_NAME" | gzip > "$file"

if [ ! -s "$file" ]; then
  echo "[pg_backup] ERROR: $file 为空——pg_dump 失败" >&2
  exit 1
fi
if ! gzip -t "$file"; then
  echo "[pg_backup] ERROR: $file 不是合法 gzip" >&2
  exit 1
fi
trap - ERR

echo "[pg_backup] dump ok: $file ($(du -h "$file" | cut -f1))"

find "$BACKUP_DIR" -name 'sks-*.sql.gz' -mtime +"$RETAIN_DAYS" -print -delete \
  | sed 's/^/[pg_backup] purged local: /' || true
echo "[pg_backup] local retention: kept files <= ${RETAIN_DAYS} days old"

echo "[pg_backup] done"
