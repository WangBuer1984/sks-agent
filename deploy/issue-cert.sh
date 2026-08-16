#!/usr/bin/env bash
# 在 ECS 上签发 Let's Encrypt 证书（standalone，占用 80）。
# 网关在容器里，不要用 certbot --nginx。
#
# 用法（服务器上，nginx 未占 80）：
#   sudo ./deploy/issue-cert.sh
#
# 已起栈时先停网关：
#   docker compose -f docker-compose.yml -f docker-compose.prod.yml stop nginx
set -euo pipefail

DOMAIN=suikoushuo.com
WWW=www.suikoushuo.com
EMAIL=15169128616@163.com

if ! command -v certbot >/dev/null; then
  echo "未装 certbot。Aliyun Linux：sudo dnf install -y certbot" >&2
  exit 1
fi

if ss -lnt 2>/dev/null | grep -q ':80 '; then
  echo "80 端口已被占用。若是本仓 nginx，先：" >&2
  echo "  docker compose -f docker-compose.yml -f docker-compose.prod.yml stop nginx" >&2
  exit 1
fi

# --expand：若已签过裸域，把 www 并进同一张证（live 目录仍是裸域名）
certbot certonly --standalone --expand \
  -d "$DOMAIN" \
  -d "$WWW" \
  --non-interactive --agree-tos -m "$EMAIL"

echo "证书目录："
ls -l "/etc/letsencrypt/live/${DOMAIN}/"
