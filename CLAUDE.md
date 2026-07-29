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
- **deploy 仓**（本仓）：`docker-compose.yml`（5 服务按镜像引用 + gateway 本地 build + postgres）、`.env`（单份全量，gitignored）、`deploy/nginx/`（gateway Dockerfile+nginx.conf+50x.html）、`docs/`（PRD/tech-design/MVP plan/学习文档/拆分 spec+plan）。

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

- **gateway 不镜像化**：本地 build（`nginx:alpine + 两文件` nginx.conf + 50x.html），无 CI/registry。改 gateway = `compose build nginx && up -d nginx`。
- **gateway nginx.conf 编辑基于现文件，勿整体覆盖**——保留三条承重注释（不加 internal+curl 验收 / 超时链后果「假 AI_FAILED→误退款」/ `{{CONTACT_WECHAT}}` 替换指引）。见拆分 spec §3.4。
- **443 注释块陷阱**：启用 TLS 时 443 块 `location /` 必须是 `proxy_pass http://sks-web:80`（不可保留旧 try_files，gateway 无 dist → `/` 直接 404 全站黑）；server 级 root/index 删。见 GO_LIVE_CHECKLIST certbot 项 + 拆分 spec §3.4。
- **gateway healthcheck 探 `/50x.html`**（不探 `/`——`/` 反代 sks-web 会耦合 sks-web 健康状态，违背独立部署）。
- **镜像 tag 钉具体版本**：compose 不用 `:latest`（本地缓存不更新），钉 `v0.1.0` 等。
- **单 named 网络 `sks-net`**（compose 自动创建，无需 `docker network create`）；顶层 `volumes: sks-pgdata` 必须声明。
- **`.env` 单份全量住本仓**，compose `env_file: .env` 注入 sks-server 与 sks-ai（sks-web 无 env）。`.env` 真值 gitignored 不进 git；模板见 `.env.example`（按拆分 spec §4 枚举，漏配 SMS/MAIL 走 stub 静默不发，靠枚举兜底）。
- **GHCR private**：部署机 `docker login ghcr.io`（PAT `read:packages`）或把三 package 设 public。GHCR 国内可达性见 OPS.md「部署机初始化」预验。

## 文档

- 拆分 spec：`docs/superpowers/specs/2026-07-28-sks-ai-split-design.md`
- 拆分实施计划：`docs/superpowers/plans/2026-07-29-sks-agent-split.md`
- 产品/技术设计：`随口说PRD .md`、`docs/superpowers/specs/2026-07-19-suikoushuo-tech-design.md`、`docs/superpowers/plans/2026-07-19-suikoushuo-mvp.md`（路径按拆分前 monorepo 布局，现分属三服务仓）
