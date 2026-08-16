#!/usr/bin/env bash
# standalone 续期：先停 sks-nginx 让出 80，签完再拉起（证书只读挂载，start 即重读）。
# 不要用 certbot --nginx（网关在容器里）。
#
#   sudo ./deploy/renew-cert.sh --dry-run
#   sudo ./deploy/renew-cert.sh --quiet          # crontab 用这个
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null)" || REPO_DIR="$SCRIPT_DIR/.."
cd "$REPO_DIR"

if ! command -v certbot >/dev/null; then
  echo "未装 certbot。Aliyun Linux：sudo dnf install -y certbot" >&2
  exit 1
fi

dc() { docker compose -f docker-compose.yml -f docker-compose.prod.yml "$@"; }

nginx_was_up=0
if docker inspect -f '{{.State.Running}}' sks-nginx 2>/dev/null | grep -qx true; then
  nginx_was_up=1
fi

restore() {
  if (( nginx_was_up )); then
    dc start nginx 2>/dev/null || dc up -d --no-deps nginx
  fi
}
trap restore EXIT

dc stop nginx 2>/dev/null || true
systemctl stop nginx 2>/dev/null || true

certbot renew "$@"
