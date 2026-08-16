#!/usr/bin/env bash
# ── 随口说 · 后续每次部署脚本 ──────────────────────────────────────────────────
# 用法（在云服务器 sks-agent 仓根执行）：
#   ./deploy/deploy.sh                 # 默认 all：拉全部镜像 + 起栈（含 nginx 重建）
#   ./deploy/deploy.sh sks-server       # 只更新 sks-server（pull + up --no-deps，不连带重启依赖）
#   ./deploy/deploy.sh sks-ai
#   ./deploy/deploy.sh sks-web
#   ./deploy/deploy.sh nginx            # 只重建网关（改了 nginx.https.conf / 50x.html 后）
#   ./deploy/deploy.sh --no-pull all    # 跳过镜像拉取，只 up
#
# 前提（首次部署前已完成，见 deploy/SERVER_INIT.md + deploy/ALIYUN_DEPLOYMENT.md）：
#   - 服务器已装 docker + compose v2.22+、已 docker login ACR
#   - 已 git clone 本仓到 COMPOSE_DIR，.env 已配实值（chmod 600）
#   - certbot 已签发证书到 /etc/letsencrypt/live/suikoushuo.com/（含 www.suikoushuo.com）
#
# 本脚本恒走生产 compose（docker-compose.yml + docker-compose.prod.yml）——
# prod override 让 nginx 用 nginx.https.conf 构建 + 挂 letsencrypt 卷 + 开 443。
# 本地联调别用本脚本（本地走 80-only 的 docker-compose.yml + .override）。
#
# 镜像 tag 更新流程（每次发版）：先在 deploy 仓改 <svc>.image 的新 tag 并 git push →
# 服务器 `git pull` 拿到新 compose → 再跑本脚本。本脚本不替你 git pull（避免脏树冲突），
# 请自行 `git pull` 后再执行。

set -euo pipefail

# ── 定位仓库根（脚本可在 deploy/ 下，靠 git rev-parse 找仓根）────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null)" || REPO_DIR="$SCRIPT_DIR/.."
cd "$REPO_DIR"
COMPOSE_DIR="$REPO_DIR"

COMPOSE_FILES=(-f docker-compose.yml -f docker-compose.prod.yml)
IMAGE_SERVICES=(sks-server sks-ai sks-web)   # 走 ACR（SKS_IMAGE_REGISTRY），pull 可用
ALL_SERVICES=(postgres sks-server sks-ai sks-web nginx)

# ── 颜色 / 日志 ───────────────────────────────────────────────────────────────
if [[ -t 1 ]]; then
  C_G=$'\033[32m'; C_Y=$'\033[33m'; C_R=$'\033[31m'; C_B=$'\033[34m'; C_N=$'\033[0m'
else
  C_G=''; C_Y=''; C_R=''; C_B=''; C_N=''
fi
log()  { printf '%s▶%s %s\n' "$C_B" "$C_N" "$*"; }
ok()   { printf '%s✓%s %s\n' "$C_G" "$C_N" "$*"; }
warn() { printf '%s!%s %s\n' "$C_Y" "$C_N" "$*" >&2; }
die()  { printf '%s✗%s %s\n' "$C_R" "$C_N" "$*" >&2; exit 1; }

# ── 参数解析 ───────────────────────────────────────────────────────────────────
DO_PULL=1
TARGET=""
for a in "$@"; do
  case "$a" in
    --no-pull) DO_PULL=0 ;;
    -h|--help)
      sed -n '2,28p' "$0"; exit 0 ;;
    *) TARGET="$a" ;;
  esac
done
[[ -z "$TARGET" ]] && TARGET="all"

# ── preflight ──────────────────────────────────────────────────────────────────
log "preflight: 检查部署前置"
command -v docker >/dev/null || die "未装 docker（见 deploy/SERVER_INIT.md）"
docker compose version >/dev/null 2>&1 || die "未装 compose 插件（硬依赖 v2.22+，见 SERVER_INIT.md）"
[[ -f .env ]] || die "缺 .env（从 deploy/.env.prod.example 拷贝并填实值；见 ALIYUN_DEPLOYMENT.md）"
[[ -f docker-compose.yml ]] || die "不在 sks-agent 仓根？缺 docker-compose.yml"

# 证书存在性（nginx https 起来的硬前提）。live/ 下常有 README，不能只看目录非空。
CERT=/etc/letsencrypt/live/suikoushuo.com/fullchain.pem
if [[ ! -f "$CERT" ]] && { [[ "$TARGET" == "all" ]] || [[ "$TARGET" == "nginx" ]]; }; then
  die "缺 $CERT ——nginx 起不来。先 sudo ./deploy/issue-cert.sh（80 须空闲，见 ALIYUN_DEPLOYMENT.md §2）"
fi

# ── 定义 compose 命令前缀 ─────────────────────────────────────────────────────
dc() { docker compose "${COMPOSE_FILES[@]}" "$@"; }

# ── 执行 ──────────────────────────────────────────────────────────────────────
case "$TARGET" in
  all)
    log "全量更新：拉镜像（--ignore-buildable 跳过 nginx，它本地 build）"
    if (( DO_PULL )); then
      dc pull --ignore-buildable || die "拉镜像失败——查 .env SKS_IMAGE_REGISTRY / ACR login / DaoCloud（pgvector、nginx）"
    else
      warn "--no-pull：跳过拉镜像"
    fi
    log "重建 nginx 网关（nginx.https.conf + 50x.html 有改动时必须 build）"
    dc build nginx || die "build nginx 失败——查 nginx.https.conf 语法（docker run ... nginx -t）"
    log "起栈（按 depends_on：pg → sks-server/sks-web → sks-ai → nginx）"
    dc up -d
    ;;
  nginx)
    log "只重建网关"
    dc build nginx || die "build nginx 失败"
    dc up -d --no-deps nginx || die "up nginx 失败"
    ;;
  sks-server|sks-ai|sks-web)
    log "单服务更新：$TARGET"
    if (( DO_PULL )); then
      dc pull "$TARGET" || die "pull $TARGET 失败——查 tag 是否 bump、ACR login、本机是否已 acr-sync"
    fi
    # --no-deps：不连带重启依赖（pg/sks-server 等），三服务各自独立部署互不连带。
    # nginx 的 resolver 127.0.0.11 valid=10s 会让换 IP 后 10s 内自愈，无需连带重启网关。
    dc up -d --no-deps "$TARGET" || die "up $TARGET 失败"
    ;;
  *)
    die "未知目标 '$TARGET'。可用：all | sks-server | sks-ai | sks-web | nginx"
    ;;
esac

# ── 健康自检 ──────────────────────────────────────────────────────────────────
# 给容器一个 settle 窗口（nginx 取决于上游 healthy；sks-server 首次 Flyway 有 30s start_period）。
# 不做脆弱的「轮询到全 healthy」——真相交给下面 curl 探针，它通即网关 + sks-server 通。
log "settle 12s（容器健康收敛 + nginx resolver 10s 自愈窗口）"
sleep 12

echo
ok "部署完成。当前容器状态："
dc ps --format 'table {{.Name}}\t{{.Status}}' || true

# 外部拨测（经 nginx，仅 all/nginx/sks-server 后有意义；单 sks-ai/web 也能探）
echo
log "拨测网关（本地 127.0.0.1，不涉外网/域名）"
if curl -fsS -o /dev/null -w '  /api/health → %{http_code}\n' http://127.0.0.1/api/health 2>/dev/null; then
  :
else
  warn "/api/health 未通——可能 sks-server 还在启动期（start_period 30s）；稍后 curl -s localhost/api/health 复查"
fi
curl -s -o /dev/null -w '  /50x.html   → %{http_code}\n' http://127.0.0.1/50x.html 2>/dev/null || true

echo
log "回滚提示：deploy 仓把 <svc>.image tag 改回上一版 → git pull → ./deploy/deploy.sh <svc>"
