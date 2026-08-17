#!/usr/bin/env bash
# 把 GHCR 上的三服务镜像同步到阿里云个人版 ACR（北京），供国内 ECS 拉取。
#
# 不要在 ECS 上跑本脚本（GHCR 只有十几 kB/s）。在能较快访问 GitHub 的机器上：
#   docker login --username=dingtalk_bakexx \
#     crpi-7eu3mopdi4xg4ext.cn-beijing.personal.cr.aliyuncs.com
#   ./deploy/acr-sync.sh v0.1.2 sks-web
# GHCR login 仅匿名 pull 401 时需要：docker login ghcr.io -u WangBuer1984
#
# 本机 push 走公网地址；ECS pull 走 VPC 地址（见 .env.prod.example SKS_IMAGE_REGISTRY）。
set -euo pipefail

GHCR="${GHCR_REGISTRY:-ghcr.io/wangbuer1984}"
# 个人版 ACR 公网（Mac / 非 VPC 推送）
ACR_PUSH="${ACR_PUSH_REGISTRY:-crpi-7eu3mopdi4xg4ext.cn-beijing.personal.cr.aliyuncs.com/suikoushuo}"
# ECS 同地域 pull 用这个（写入服务器 .env）
ACR_PULL_VPC="crpi-7eu3mopdi4xg4ext-vpc.cn-beijing.personal.cr.aliyuncs.com/suikoushuo"
PLATFORM="${DOCKER_DEFAULT_PLATFORM:-linux/amd64}"
TAG="${1:-v0.1.1}"
TARGET="${2:-all}"

case "$TARGET" in
  all) SERVICES=(sks-server sks-ai sks-web) ;;
  sks-server|sks-ai|sks-web) SERVICES=("$TARGET") ;;
  *) echo "未知目标 '$TARGET'。可用：all | sks-server | sks-ai | sks-web" >&2; exit 1 ;;
esac

echo "GHCR     $GHCR"
echo "ACR push $ACR_PUSH"
echo "tag      $TAG"
echo "platform $PLATFORM"
echo

for svc in "${SERVICES[@]}"; do
  src="$GHCR/$svc:$TAG"
  dst="$ACR_PUSH/$svc:$TAG"
  echo "── $src  →  $dst"
  docker pull --platform "$PLATFORM" "$src"
  docker tag "$src" "$dst"
  docker push "$dst"
done

echo
echo "同步完成。push 时若出现 Not all multiplatform-content is present：可忽略（GHCR 本就只有 linux/amd64）。"
echo "ECS .env："
echo "  SKS_IMAGE_REGISTRY=$ACR_PULL_VPC"
echo "ECS 登录（VPC，不计公网流量）："
echo "  docker login --username=dingtalk_bakexx crpi-7eu3mopdi4xg4ext-vpc.cn-beijing.personal.cr.aliyuncs.com"
if [[ "$TARGET" == "all" ]]; then
  echo "然后：sks-agent 把 compose 三处 tag 都改成 $TAG 并 push；ECS：./deploy/deploy.sh all"
else
  echo "然后：sks-agent 只 bump docker-compose.yml 里 $TARGET 的 tag 为 $TAG 并 push"
  echo "ECS：cd /opt/sks && git pull && ./deploy/deploy.sh $TARGET"
  echo "不要跑 ./deploy/deploy.sh all（除非三服务都发了 $TAG）。"
fi
