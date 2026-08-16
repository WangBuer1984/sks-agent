#!/usr/bin/env bash
# 每日 03:00 备份 sks 数据库（额度账本等业务表，PRD §11.4 / 设计 §5.3）。
#
# 宿主 crontab（上线后安装）：
#   0 3 * * * /path/to/sks-agent/deploy/backup/pg_backup.sh
#
# 用法：
#   bash pg_backup.sh                    # 备份 + 本地保留 30 天
#   OSS_BUCKET=my-bucket bash pg_backup.sh   # 同时上传对象存储（联调后配置 CLI）
#
# 额度账本不可丢——上线前必须跑一次 pg_restore_verify.sh 验证可恢复（见同目录）。
set -euo pipefail

# ---- 配置（env 可覆盖）----
BACKUP_DIR="${BACKUP_DIR:-/backup}"
DB_USER="${SPRING_DATASOURCE_USERNAME:-sks}"
DB_NAME="${PG_DB_NAME:-sks}"
# docker-compose.yml 所在目录（-T 非交互 exec 用）
COMPOSE_DIR="${COMPOSE_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
# 与 deploy.sh 一致恒走 prod compose（-f prod override）。exec 按项目名 + 服务名找运行中的
# postgres 容器——prod override 只改 nginx（build arg + letsencrypt 卷 + 443），postgres 服务
# 定义与项目名都不变，故本地联调（即使没用 prod override 起栈）也能命中。
COMPOSE_FILES=(-f "$COMPOSE_DIR/docker-compose.yml" -f "$COMPOSE_DIR/docker-compose.prod.yml")
dc() { docker compose "${COMPOSE_FILES[@]}" --project-directory "$COMPOSE_DIR" "$@"; }
OSS_BUCKET="${OSS_BUCKET:-}"              # 联调：未设 OSS_BUCKET → 跳过远端上传，仅留本地
RETAIN_DAYS="${RETAIN_DAYS:-30}"

mkdir -p "$BACKUP_DIR"

stamp=$(date +%F)
file="$BACKUP_DIR/sks-$stamp.sql.gz"

echo "[pg_backup] dumping to $file ..."
# -T: disable TTY (non-interactive)；pg_dump 输出经 gzip 落盘
dc exec -T postgres \
    pg_dump -U "$DB_USER" "$DB_NAME" 2>/dev/null | gzip > "$file"

# sanity：文件非空（pg_dump 失败 / 容器未起 → 空文件）
if [ ! -s "$file" ]; then
    echo "[pg_backup] ERROR: $file 为空——pg_dump 失败（容器未起？凭证错？）" >&2
    exit 1
fi
echo "[pg_backup] dump ok: $file ($(du -h "$file" | cut -f1))"

# ---- 上传对象存储（联调 stub）----
# OSS_BUCKET 未设时跳过远端上传，仅留本地备份——联调时配置 aliyun-oss / cos CLI 后生效。
if [ -z "$OSS_BUCKET" ]; then
    echo "[pg_backup] OSS_BUCKET 未设，跳过远端上传（联调时配置 aliyun-oss/cos CLI + OSS_BUCKET 后生效）"
else
    # 联调 TODO: 替换为真实 CLI，例如：
    #   aliyun oss cp "$file" "oss://$OSS_BUCKET/sks-$stamp.sql.gz" --force
    #   coscli cp "$file" "cos://$OSS_BUCKET/sks-$stamp.sql.gz"
    echo "[pg_backup] TODO: oss cp $file oss://$OSS_BUCKET/sks-$stamp.sql.gz (联调替换真实 CLI)"
    # 联调 TODO（远端保留 30 天）：
    #   aliyun oss ls oss://$OSS_BUCKET/sks-*.sql.gz | awk ... | xargs -r aliyun oss rm
fi

# ---- 本地保留 30 天 ----
find "$BACKUP_DIR" -name 'sks-*.sql.gz' -mtime +"$RETAIN_DAYS" -print -delete \
    | sed 's/^/[pg_backup] purged local: /' || true
echo "[pg_backup] local retention: kept files <= ${RETAIN_DAYS} days old"

echo "[pg_backup] done"
