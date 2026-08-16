# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working in this repository.

## Repo status: deploy/编排仓（四仓拆分后）

本仓为 **deploy 仓**——四仓拆分后集中持有 docker-compose 编排 + gateway nginx + .env + 部署文档。三服务源码已分属各自仓库（sks-server / sks-ai / sks-web），各自独立发版到 GHCR，本仓按镜像引用。**本仓不写业务代码**——业务代码的硬不变量与构建/测试命令在各服务仓的 scoped CLAUDE.md。

## 四仓架构

```
浏览器 (React SPA) ──HTTPS──▶ nginx(gateway, 本仓本地 build) ──▶ /api/ → sks-server(image)
                                                                └─▶ /    → sks-web(image)
                                          sks-server ──内网 HTTP+X-Service-Token──▶ sks-ai(image)
                                          PostgreSQL 16 + pgvector ◀── (deploy 仓 compose 管，单库三合一)
```

- **三服务仓**（各自 GHCR 镜像独立发版，硬不变量见各自 scoped CLAUDE.md）：
  - sks-server（Java，唯一公网入口，鉴权/额度/CRUD/状态机）→ `ghcr.io/wangbuer1984/sks-server:<tag>`
  - sks-ai（Python FastAPI+LangGraph，内网 AI 服务，不暴露公网）→ `ghcr.io/wangbuer1984/sks-ai:<tag>`
  - sks-web（React SPA + nginx 静态服务）→ `ghcr.io/wangbuer1984/sks-web:<tag>`
- **deploy 仓**（本仓）：`docker-compose.yml`（5 服务按镜像引用 + gateway 本地 build + postgres）、`.env`（单份全量，gitignored）、`deploy/nginx/`（gateway Dockerfile+nginx.conf+50x.html）、`deploy/*.md`（运维）、`docs/learning/`（学习笔记）。

## 指向各服务仓 scoped CLAUDE.md

三服务仓各有一份 scoped CLAUDE.md 承载该仓的硬不变量 + 构建/测试命令。**在本仓干 deploy/编排活读不到那些约束；改动某服务代码时切到对应服务仓读其 CLAUDE.md。** 跨仓契约：Java↔Python 见 sks-ai 仓 `docs/API_CONTRACT.md`；前端↔Java 见 sks-server 仓 `docs/REST_CONTRACT.md`。

## 前端视觉基准

两份原型 HTML（C 端 + 站长后台）归 **sks-web 仓 `prototypes/`**（只读不改），不在本仓。

## 部署 / 编排命令（本仓本职）

- `docker compose pull --ignore-buildable`（拉三服务镜像；gateway 只有 build: 无 image:，跳过；需 Compose v2.22+，老版本 `docker compose pull sks-server sks-ai sks-web`）
- `docker compose up -d`（按 depends_on 起：pg → sks-server(Flyway)/sks-web → sks-ai → nginx-gateway）
- `docker compose build nginx && docker compose up -d nginx`（改 gateway 配置后）
- 独立部署某服务：deploy 仓改 `<svc>.image` tag → `docker compose pull <svc> && docker compose up -d <svc>`（`--no-deps` 避免顺带重启依赖）
- 健康检查：`curl localhost/api/health`（Java，经 nginx）→ `{"status":"UP"}`；`curl localhost/50x.html` → 200（gateway 兜底页）；Python `GET /health` → `{"status":"UP"}`

详见 `deploy/OPS.md`、`deploy/GO_LIVE_CHECKLIST.md`、`README.md`。

## 关键约束（deploy 仓侧）

- **gateway 不镜像化**：本地 build（`nginx:alpine` + `nginx.conf` / `nginx.https.conf` + `50x.html`），无 CI/registry。改网关 = `compose build nginx && up -d nginx`（生产带 `docker-compose.prod.yml`）。
- **gateway 配置基于现文件改，勿整体覆盖**——保留承重注释（`/50x.html` 可直访、超时链、resolver + 变量 `proxy_pass`、`{{CONTACT_WECHAT}}`）。
- **生产 TLS 走 `nginx.https.conf` + `docker-compose.prod.yml`**，不要在 `nginx.conf` 里取消 443 注释块（那块 80 纯 301 会让 healthcheck 探 `/50x.html` 失败）。`nginx.https.conf` 的 443 `location /` 必须反代 `sks-web:80`，不可 `try_files`、不可 server 级 root。详见 `deploy/ALIYUN_DEPLOYMENT.md`。
- **gateway healthcheck 探 `/50x.html`**（不探 `/`——`/` 反代 sks-web 会耦合 sks-web 健康状态，违背独立部署）。
- **镜像 tag 钉具体版本**：compose 不用 `:latest`（本地缓存不更新），钉 `v0.1.0` 等。
- **单 named 网络 `sks-net`**（compose 自动创建，无需 `docker network create`）；顶层 `volumes: sks-pgdata` 必须声明。
- **`.env` 单份全量住本仓**，compose `env_file: .env` 注入 sks-server 与 sks-ai（sks-web 无 env）。`.env` 真值 gitignored 不进 git；模板见 `.env.example`（漏配短信 AK 则只落库不发）。
- **GHCR private**：部署机 `docker login ghcr.io`（PAT `read:packages`）或把三 package 设 public。GHCR 国内可达性见 `deploy/SERVER_INIT.md`。

## 文档

日常部署看 `README.md` 与 `deploy/`：

| 场景 | 文档 |
|---|---|
| 裸机一次性初始化 | `deploy/SERVER_INIT.md` |
| 首次上云 | `deploy/ALIYUN_DEPLOYMENT.md` |
| 发版脚本 | `deploy/deploy.sh` |
| 运维（HTTPS / 备份 / 告警 / 镜像加速） | `deploy/OPS.md` |
| 上线清单 | `deploy/GO_LIVE_CHECKLIST.md` |

学习笔记在 `docs/learning/`（工具链与本地调试，不承载部署硬约束）。产品 PRD / 技术设计 / 契约在各服务仓，不在本仓。
