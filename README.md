# sks-agent-deploy

部署/编排仓。四仓架构：sks-server/sks-ai/sks-web 各自 GHCR 镜像独立发版，本仓 gateway 本地 build + 单 compose 编排 + 单份 `.env`。

## 部署全栈

```bash
# 1. 配 .env（从 .env.example 拷贝，填真值；.env gitignored 不进 git）
cp .env.example .env && vim .env

# 2. 登录 GHCR（private package 需 PAT read:packages；或把三 package 设 public）
docker login ghcr.io

# 3. 拉镜像 + 起栈（单 named 网络 sks-net，compose 自动创建，无需 docker network create）
docker compose pull --ignore-buildable   # 需 Compose v2.22+；老版本 docker compose pull sks-server sks-ai sks-web
docker compose up -d                     # 按 depends_on 起：pg → sks-server(Flyway)/sks-web → sks-ai → nginx(gateway)
```

## 启动顺序（§5.4）

compose `depends_on` 自动保证：postgres → sks-server(等 pg healthy, Flyway 起时跑) → sks-ai(等 sks-server healthy, Flyway 跑完=表已建)；sks-web 独立；nginx（gateway，等 sks-server + sks-web healthy）。手动分批：先 postgres，后 sks-server/sks-web（并行），后 sks-ai，最后 nginx。

## 镜像 tag 更新流程（独立部署某服务）

deploy 仓改 `<svc>.image` tag 为新版本 → `docker compose pull <svc> && docker compose up -d <svc>`（`--no-deps` 可避免顺带重启依赖）。三服务各自独立重启互不连带。

## 验证

```bash
curl -s localhost/api/health                  # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}' localhost/   # 200（前端经 gateway→sks-web）
curl -s -o /dev/null -w '%{http_code}' localhost/50x.html  # 200（兜底页）
docker compose ps                              # 5 容器全 healthy
```

详见 `deploy/OPS.md`、`deploy/GO_LIVE_CHECKLIST.md`。

## 阿里云部署（服务器）

- 裸机一次性初始化：[`deploy/SERVER_INIT.md`](deploy/SERVER_INIT.md)
- 首次完整部署：[`deploy/ALIYUN_DEPLOYMENT.md`](deploy/ALIYUN_DEPLOYMENT.md)
- 后续每次发版部署：`deploy/deploy.sh`（用法 `./deploy/deploy.sh [all|sks-server|sks-ai|sks-web|nginx]`）
