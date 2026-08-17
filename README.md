# sks-agent-deploy

部署/编排仓。四仓架构：sks-server/sks-ai/sks-web 各自 GHCR 独立发版，生产 ECS 从同地域 ACR 拉取；本仓 gateway 本地 build + 单 compose 编排 + 单份 `.env`。

## 部署全栈

```bash
# 1. 配 .env（从 .env.example 拷贝，填真值；.env gitignored 不进 git）
cp .env.example .env && vim .env

# 2. 登录镜像仓库
#    本地联调：docker login ghcr.io（PAT read:packages）
#    生产 ECS：docker login --username=dingtalk_bakexx crpi-7eu3mopdi4xg4ext-vpc.cn-beijing.personal.cr.aliyuncs.com

# 3. 拉镜像 + 起栈（单 named 网络 sks-net，compose 自动创建，无需 docker network create）
docker compose pull --ignore-buildable   # 需 Compose v2.22+；老版本 docker compose pull sks-server sks-ai sks-web
docker compose up -d                     # 按 depends_on 起：pg → sks-server(Flyway)/sks-web → sks-ai → nginx(gateway)
```

## 启动顺序

compose `depends_on` 自动保证：postgres → sks-server(等 pg healthy, Flyway 起时跑) → sks-ai(等 sks-server healthy, Flyway 跑完=表已建)；sks-web 独立；nginx（gateway，等 sks-server + sks-web healthy）。手动分批：先 postgres，后 sks-server/sks-web（并行），后 sks-ai，最后 nginx。

## 镜像 tag 更新流程（独立部署某服务）

日常逐步命令见 [`deploy/RELEASE.md`](deploy/RELEASE.md)。不要默认把 compose 三处 tag 一起改——只 bump 实际打了 tag、并已 `acr-sync` 的那个服务。

## 验证

```bash
curl -s localhost/api/health                  # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}' localhost/   # 200（前端经 gateway→sks-web）
curl -s -o /dev/null -w '%{http_code}' localhost/50x.html  # 200（兜底页）
docker compose ps                              # 5 容器全 healthy
```

## 文档

| 场景 | 看哪份 |
|---|---|
| 裸机一次性初始化 | [`deploy/SERVER_INIT.md`](deploy/SERVER_INIT.md) |
| 首次上云 | [`deploy/ALIYUN_DEPLOYMENT.md`](deploy/ALIYUN_DEPLOYMENT.md) |
| 后续发版 | [`deploy/RELEASE.md`](deploy/RELEASE.md) |
| 运维（HTTPS / 备份 / 告警） | [`deploy/OPS.md`](deploy/OPS.md) |
| 上线清单 | [`deploy/GO_LIVE_CHECKLIST.md`](deploy/GO_LIVE_CHECKLIST.md) |
| 学习工具链 / 本地调试 | [`docs/learning/`](docs/learning/) |
