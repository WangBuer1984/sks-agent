# sks-agent 四仓拆分 + 镜像化 — 设计文档

> 日期：2026-07-28
> 状态：已通过 brainstorming（五轮 review 修订），待用户复核后进 writing-plans
> 演进：2仓独立compose+external网络 → 3仓镜像 → **4仓镜像（sks-web 独立 + nginx 拆静态/网关两职）**

## 1. 目标与动机

把当前 monorepo 拆成**四个仓库**：三个服务仓（sks-server / sks-ai / sks-web）各自**独立发版**（不同版本号 / 各自 CI / 各自 git tag / 各自构建镜像到 GHCR），且都能**独立部署**（bump 镜像 tag、单独重启，不连带对方）。第四个 deploy 仓集中持有 compose + gateway nginx + .env + 部署文档。

**nginx 拆两职**：静态服务容器（sks-web 镜像，serve SPA + try_files fallback + 资源缓存）+ 网关容器（deploy 仓本地 build，只做 `/api/` 反代 + 超时链 + TLS + 50x 兜底页）。

**已排除的替代方案**：
- 不拆仓、monorepo + per-service CI（用户明确要仓库级分离 + 各自 CI/tag）。
- 三仓（不拆 sks-web，nginx 兼静态+网关）：前端发版要连带 gateway rebuild，且 gateway 依赖 node 阶段（生产机出网拉 npm）。拆 sks-web 后 gateway 变 `nginx:alpine + 两文件`秒级 build、零 npm 依赖，前端与另两服务同款发版 ergonomics。
- 拆仓 + 各自独立 compose + external 共享网络：跨 compose 网络复杂度 + .env 跨仓同步负担。镜像 + 单 compose 更简。

## 2. 关键决策（brainstorming 已定）

| 决策 | 选择 | 理由 |
|---|---|---|
| 拆仓范围 | **四仓对称**：sks-server / sks-ai / sks-web / sks-agent-deploy | 三服务各自独立发版，sks-web 也独立（前端镜像环境无关，CI 零 secret）|
| nginx 两职 | **拆**：sks-web 镜像管静态服务（SPA fallback + 缓存），gateway（deploy 仓本地 build）管 `/api/` 反代 + TLS + 50x | 前端发版不连带 gateway rebuild；gateway 去掉 node 阶段秒级 build 零 npm 依赖 |
| 部署模型 | **镜像化**：三服务仓 CI 构建+推镜像到 GHCR，deploy 仓 compose 按镜像引用；gateway 本地 build | 单 compose 单网络，DNS 名天然互通，无 external；gateway 太简不镜像化（无 CI/registry）|
| 镜像 registry | **GHCR（ghcr.io）** | 免费、与 GitHub 仓库集成、solo 零成本 |
| Postgres | 连同一个 pg 实例（deploy 仓 compose 管） | 保留「单库三合一」+ `analyze_task`/`kb_card` 共享表读写；拆库破坏架构，不取 |
| Git 历史 | 保留（`git subtree split` 抽离各子目录） | 追溯性好；sks-web 是顶层目录，三次 split 完全同款 |
| `.env` 归属 | **单份，住 deploy 仓** | compose `env_file` 运行时注入三服务镜像；跨仓 .env 同步负担整个消失 |
| sks-web env | **无任何运行期/构建期 env** | axios 用相对基址（`/api`+`/api/admin`），全代码库无 `VITE_` 变量，镜像环境无关，CI 零 secret |
| 网络 | deploy 仓单 compose 单默认网络（5 服务） | DNS 名天然互通，无需 external |

## 3. 仓库结构与文件归属（四仓）

### 3.1 `sks-server` 仓（Java 服务）

```
sks-server/
├── src/                      ← 原样
├── Dockerfile                ← 原样
├── pom.xml, mvnw, .mvn/      ← 原样
├── .github/workflows/ci.yml  ← 新增：test → build → push 镜像到 GHCR
├── .env.example              ← 新增：本地 dev 用（运行时由 deploy 仓 compose 注入，此文件仅本地跑参考）
├── .gitignore
├── .dockerignore             ← 新增：护栏（.env、.git），防密钥打进镜像（见下文白名单说明）
├── CLAUDE.md                 ← 新增：scoped，承载 sks-server 硬不变量 + 本仓构建/测试命令（§6）
└── README.md                 ← 新增：本地跑 + 镜像构建说明
```
独立发版：git tag → CI `./mvnw test` 绿 → build `ghcr.io/wangbuer1984/sks-server:<tag>` → push。
镜像构建用 `-DskipTests`（Dockerfile 已是），测试在 CI 前置 gate 跑。

### 3.2 `sks-ai` 仓（Python 服务）

```
sks-ai/
├── app/, tests/              ← 原样
├── Dockerfile, pyproject.toml, uv.lock  ← Dockerfile 改（加 COPY uv.lock + --frozen）
├── .github/workflows/ci.yml  ← 新增：test → build → push 镜像到 GHCR
├── .env.example              ← 新增：本地 dev 用（同上，运行时由 deploy 仓注入）
├── .gitignore
├── .dockerignore             ← 新增：护栏（.env、.git）
├── CLAUDE.md                 ← 新增：scoped，承载 sks-ai 硬不变量 + 本仓构建/测试命令（§6）
├── README.md                 ← 新增：本地跑 + 镜像构建
└── docs/
    └── API_CONTRACT.md       ← 新增：/ai/* 端点契约 + 共享表契约（sks-ai 是服务提供方，契约归它拥有）
```
独立发版：git tag → CI `uv run pytest`（带 dev 依赖）绿 → build `ghcr.io/wangbuer1984/sks-ai:<tag>`（Dockerfile `uv sync --no-dev` 不含 pytest，故测试在 CI 前置 gate 跑）→ push。
清理（两处，和删 ALIYUN_SMS_SIGN 同级，不碰业务代码）：
- `app/config.py` 删 `ALIYUN_SMS_SIGN` 字段（Python 无一处用，§4）。
- **`Dockerfile` 加 `COPY uv.lock ./` + `uv sync --no-dev --frozen`**——原 Dockerfile 只 COPY pyproject.toml，镜像里装的是构建当天重新解析的版本，和 CI `uv run pytest`（按 uv.lock）验的锁定版本可能不一致（假绿）。加 --frozen 后镜像严格按 lock 装，gate 才真。

> **.dockerignore 护栏说明**：三个服务仓 Dockerfile 都是白名单式 COPY，`.env` 不会进镜像，现状安全。但拆分后服务仓根放了 `.env`（在 build context 内），加两行 `.dockerignore`（`.env`、`.git`）当护栏成本极低——将来谁把 Dockerfile 改成 `COPY . .` 就不会踩雷。

### 3.3 `sks-web` 仓（前端 + 静态服务，新增第四仓）

```
sks-web/
├── src/, index.html, vite.config.ts, tailwind.config.js, tsconfig.json, postcss.config.js
├── package.json, package-lock.json
├── Dockerfile                ← 新写：node build → nginx:alpine serve dist（含精简 nginx.conf）
├── nginx.conf                ← 新写：静态服务职（try_files SPA fallback + /assets/* 长缓存 + index.html no-cache）
├── .github/workflows/ci.yml  ← 新增：test(build) → push 镜像到 GHCR（零 secret）
├── .gitignore
├── .dockerignore             ← node_modules/.env 护栏
├── CLAUDE.md                 ← 新增：scoped，承载前端硬约束（§6）
└── README.md                 ← 新增：本地跑（npm run dev）+ 镜像构建
```
**sks-web Dockerfile**（新写，多阶段）：
```dockerfile
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build                    # 产物带 hash → /assets/* 可长缓存

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```
**sks-web nginx.conf**（静态服务职，新写）：
```nginx
server {
    listen 80;
    root /usr/share/nginx/html;
    index index.html;
    # SPA fallback（只有 sks-web 知道文件布局）
    location / { try_files $uri $uri/ /index.html; }
    # vite 产物带 hash → 长缓存
    location /assets/ { expires 1y; add_header Cache-Control "public, immutable"; }
    # index.html 必须每次取最新（否则发版后用户拿旧壳）
    location = /index.html { add_header Cache-Control "no-cache"; }
}
```
独立发版：git tag → CI `npm ci && npm run build` 绿 → build `ghcr.io/wangbuer1984/sks-web:<tag>` → push。**CI 零 secret**（镜像环境无关）。

### 3.4 `sks-agent-deploy` 仓（部署/编排/gateway/文档）

```
sks-agent-deploy/
├── docker-compose.yml        ← 改：三服务按镜像引用，gateway 本地 build，postgres 官方镜像（5 服务）
├── .env.example              ← 单份 env 契约（运行时 .env 住这里）
├── deploy/
│   ├── nginx/                ← 重写：删 node 阶段（静态已迁 sks-web）；nginx.conf 只留 /api/ 反代+TLS+50x；Dockerfile 改 FROM nginx:alpine + 两文件
│   ├── OPS.md, GO_LIVE_CHECKLIST.md, backup/
├── docs/                     ← PRD、tech-design、MVP plan、学习文档、本 spec（顶部加注「路径按拆分前 monorepo 布局」）
├── CLAUDE.md                 ← 更新：四仓架构总览 + 指向三服务仓 scoped CLAUDE.md
└── README.md                 ← 新增：如何用本仓部署全栈
```
原仓库改造而来：删 `sks-ai/`、`sks-server/`、`sks-web/` 目录，保留其余。**gateway 仍本地 build**（`nginx:alpine + 两文件`，秒级，无 npm，无 CI/registry）。

> **50x.html 必须留在 gateway 本地 serve**（`error_page 50x → 本地 50x.html`）——它是"sks-web 挂了也要能渲染"的兜底页，代理到 sks-web 就失去意义（sks-web 挂时 50x 也拿不到）。这是 nginx 拆两职最容易写漏的细节：`/` 静态 + SPA fallback 迁 sks-web，但 `50x.html` 留 gateway。

## 4. .env 契约（单份住 deploy 仓，无跨仓同步）

**关键简化**：镜像 + 单 compose 方案下，`.env` 只住 **deploy 仓**一份。deploy 仓 `docker-compose.yml` 用 `env_file: .env` 把整文件注入 sks-server / sks-ai 两个容器（sks-web 无 env）。**不再有跨仓 .env 同步 SERVICE_TOKEN 的负担**。

### deploy 仓 `.env.example`（单份，全量）

| key | 注入到 | 说明 |
|---|---|---|
| `POSTGRES_DB/USER/PASSWORD` | postgres + sks-server + sks-ai | pg 建库 + 两服务连库 |
| `DATABASE_URL` | compose 构造（不放进 deploy .env）| deploy compose `environment` 块从 `POSTGRES_USER/PASSWORD/DB` 拼出，覆盖 env_file；仅服务仓本地 `.env.example` 含（指向 `localhost:5432`）|
| `SPRING_DATASOURCE_*` | sks-server | Java 连 pg（deploy compose environment 块注入，指向 `postgres:5432`）|
| `JWT_SECRET_USER/ADMIN` | sks-server | Java JWT 签发 |
| `SERVICE_TOKEN` | sks-server + sks-ai | **单份，两边一致天然保证**（同一个 .env）|
| `ADMIN_SEED_*`、`TRIAL_CREDIT` | sks-server | Java admin 种子 + 赠额度 |
| `ALIYUN_ACCESS_KEY_ID/SECRET` | sks-server + sks-ai | 同一阿里云账号（SMS/ASR/内容安全）|
| `ALIYUN_SMS_*`（sign/template）| sks-server | Java DYPNS 短信 |
| `ALIYUN_ASR_KEY`、`ALIYUN_ASR_APP_KEY` | sks-ai | Python ASR |
| `ZHIPU_API_KEY`、`TIKHUB_API_KEY` | sks-ai | Python LLM/数据 |
| `ZHIPU_BASE_URL`、`TIKHUB_BASE_URL`、`ALIYUN_CONTENT_SAFETY_ENDPOINT` | sks-ai | config.py 有默认值，注释「默认 xxx，一般不改」|
| `SPRING_MAIL_*`、`SKS_ALERT_ADMIN_EMAIL` | sks-server | Java 邮件告警 |

**sks-web 无任何运行期/构建期 env**：axios 用相对基址（`baseURL: '/api'` + `/api/admin`），全代码库无 `VITE_` 构建期变量，镜像环境无关，CI 零 secret，一个镜像任何环境通用。

### 服务仓的 `.env.example`（仅本地 dev 参考）

sks-server / sks-ai 仓各放一份**精简** `.env.example`，只列自己 dev 本地跑需要的 key（带注释「运行时由 deploy 仓 compose 注入，本文件仅本地调试参考」）。这是本地 `uv run uvicorn` / `./mvnw spring-boot:run` 时 `source .env` 用。sks-web 不需要 `.env.example`（无 env）。

> **TODO（不阻塞，现状非回归）**：单 `.env` 全量 `env_file` 注入两容器，sks-ai 也能读到 `JWT_SECRET_*`/`ADMIN_SEED_PASSWORD`/`SPRING_MAIL_PASSWORD`（爆炸半径）。当前 monorepo 就有，不算拆分引入。后续要收紧可分 per-service env 文件，但别为它牺牲「单份 env」的简化。

## 5. 网络 + deploy 仓的镜像化 docker-compose.yml（5 服务）

### 5.1 网络（单 compose 单网络，无 external 舞蹈）

deploy 仓**单 compose 管五服务**（postgres / sks-server / sks-ai / sks-web / nginx-gateway），默认一个网络。`postgres`、`sks-ai`、`sks-server`、`sks-web` DNS 名天然互通，**无需 external 共享网络**。sks-ai/sks-web `expose` 不发宿主端口，硬约束「Python 不暴露公网」原样保住。

### 5.2 deploy 仓 docker-compose.yml（镜像化，5 服务）

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg16
    # ⚠️ 同现状必须保留：environment(POSTGRES_DB/USER/PASSWORD)、
    #   volumes: sks-pgdata:/var/lib/postgresql/data、healthcheck(pg_isready)。
    #   pg healthcheck 是整条 depends_on service_healthy 链的前提，丢了退化成"只等启动"。
    # ...（env 建库 + volumes + healthcheck，同现状）

  sks-server:
    image: ghcr.io/wangbuer1984/sks-server:<tag>   # 按镜像引用，不 build（§5.3：钉具体 tag，不用 :latest）
    env_file: .env
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB}
      # ...（同现状）
    depends_on:
      postgres: { condition: service_healthy }      # 等 pg 健康后再起，否则 Flyway 连库失败
    expose: ["8080"]
    healthcheck:                                    # 必须定义：sks-ai 的 depends_on condition: service_healthy 依赖此
      test: ["CMD-SHELL", "curl -fsS http://localhost:8080/api/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s

  sks-ai:
    image: ghcr.io/wangbuer1984/sks-ai:<tag>        # 按镜像引用（§5.3：钉具体 tag，不用 :latest）
    env_file: .env
    environment:
      DATABASE_URL: postgresql://${POSTGRES_USER}:${POSTGRES_PASSWORD}@postgres:5432/${POSTGRES_DB}
    depends_on:
      sks-server: { condition: service_healthy }   # 等 sks-server 健康（/api/health UP = Spring Boot 已起 = Flyway 已跑完，保证 kb_card/analyze_task 表已建）
    expose: ["8000"]
    # ⚠️ 同现状必须保留 healthcheck（python urllib 探 /health）——§6 验收要「5 容器全 healthy」。

  sks-web:
    image: ghcr.io/wangbuer1984/sks-web:<tag>      # 按镜像引用，环境无关
    expose: ["80"]
    # ⚠️ 必须定义 healthcheck（wget --spider http://localhost/）——§6 验收要 5 容器全 healthy。
    # ...（healthcheck）

  nginx:
    build: { context: ., dockerfile: deploy/nginx/Dockerfile }   # gateway 本地 build（nginx:alpine + 两文件，秒级，无 npm）
    ports: ["80:80"]                                             # gateway 是唯一 ports 暴露宿主的容器
    depends_on:
      sks-server: { condition: service_healthy }   # /api/ 反代 Java
      sks-web: { condition: service_healthy }      # / 反代静态
    # ⚠️ 同现状必须保留 healthcheck（wget --spider）。
    # ...（healthcheck）
```

### 5.3 独立部署怎么操作（镜像 tag 策略）

- **deploy 仓 compose 钉具体 tag**（如 `ghcr.io/.../sks-web:v1.2`），不用 `:latest`——`:latest` 有"本地缓存不更新"风险（§8）。
- **sks-web 独立发版**：sks-web 仓 git tag v1.2 → CI `npm ci && npm run build` 绿 → build 推 `ghcr.io/.../sks-web:v1.2`（零 secret）。
- **sks-web 独立部署**：deploy 仓改 `sks-web.image` tag 为 v1.2 → `docker compose up -d sks-web` 只拉新镜像只重启 sks-web（不碰 sks-server/sks-ai/pg/gateway；`--no-deps` 可避免顺带重启依赖）。sks-server/sks-ai 同理。
- **gateway 不镜像化**：本地 build，无 tag 流程；改 gateway 配置 = `docker compose build nginx && up -d nginx`。
- **本地调试不受影响**：Java 用 IDEA、Python 用 PyCharm/`uv run uvicorn`、前端 `npm run dev`，全在宿主进程跑，不依赖 compose。compose 是部署用。

### 5.4 启动顺序（compose dependency 保证，见 §8 风险）

compose dependency 链：`postgres` → `sks-server`(depends postgres, Flyway 在它启动时跑) → `sks-ai`(depends sks-server healthy，即 Spring Boot 起完=Flyway 跑完=表已建)；`sks-web` 独立（无 deps）；`nginx-gateway`(depends sks-server + sks-web healthy)。单 compose `up` 自动按此序。若手动分批起，README 钉死：**先 postgres，后 sks-server(Flyway)/sks-web（并行），后 sks-ai，最后 nginx-gateway**。checkpointer 无懒重试的风险见 §8。

## 6. 文档归属

| 文档 | 去向 |
|---|---|
| `随口说PRD .md`、tech-design、MVP plan、学习文档、本 spec | **deploy 仓**（顶部各加注「路径按拆分前 monorepo 布局，现分属三服务仓」，不逐条改写）|
| `deploy/OPS.md`、`GO_LIVE_CHECKLIST.md` | **deploy 仓**，且需**改写 --build 流程**（见下文「--build 心智模型改写」）|
| `docs/API_CONTRACT.md` | **sks-ai 仓**（/ai/* HTTP 端点 + 共享表契约，服务提供方拥有）|
| `docs/REST_CONTRACT.md` | **sks-server 仓，必需**（前端↔Java REST 契约跨仓）：ErrorCode 全表 + `ApiResponse` 形状 + 两套 token key 约定（`sks_token`/`sks_admin_token`）+ 401 行为 |
| `README.md`（sks-ai 仓）| 怎么本地跑（`uv sync`/`uv run uvicorn`）、镜像构建、`DATABASE_URL`/`.env` 契约、健康检查、镜像只保证 linux/amd64 |
| `README.md`（sks-server 仓）| 怎么本地跑（`./mvnw spring-boot:run` + `application-local.yml`/local profile）、镜像构建、镜像只保证 linux/amd64 |
| `README.md`（sks-web 仓）| 怎么本地跑（`npm install`/`npm run dev`）、镜像构建（零 secret）、镜像只保证 linux/amd64 |
| `README.md`（deploy 仓）| 如何用本仓部署全栈（`compose up` + `.env` 配置 + 启动顺序 §5.4 + 镜像 tag 更新流程 + `docker login ghcr.io`）|
| `CLAUDE.md`（deploy 仓）| 改成**四仓总览**：架构图（四仓 + GHCR 镜像 + gateway 本地 build + compose 编排）、Java↔Python 跨仓 HTTP+X-Service-Token、指向三服务仓 scoped CLAUDE.md；删「Python packages」节、build commands 分仓 |
| `CLAUDE.md`（**sks-server 仓，新增 scoped**）| **承载约束 sks-server 代码的硬不变量**：信用事务边界（扣额度原子条件更新 + 退款幂等 via credit_ledger）/ admin 隔离（独立 admin_user + 独立 SecurityFilterChain + 不同 JWT secret）/ Testcontainers pgvector:pg16 非 H2 / 复盘状态机无 AI 判态 / Java 唯一公网入口 / 不用 Redis/MQ；**+ 本仓构建测试命令**（`./mvnw test` / `./mvnw test -Dtest=Xxx` / `./mvnw spring-boot:run`）——原根 CLAUDE.md「Build/test/run commands」节随仓搬来 |
| `CLAUDE.md`（**sks-ai 仓，新增 scoped**）| **承载约束 sks-ai 代码的硬不变量**：无流式输出 + 先审后返（生成完→内容安全→返回 JSON）/ GLM 单厂商 + 型号只在 llm/ + embedding 1024 维绑 vector(1024) 列 / 不做迁移（checkpointer 例外，sks-ai 自己 setup）/ UGC 过内容安全审 / Python 不暴露公网只信 X-Service-Token；**+ 本仓构建测试命令**（`uv sync` / `uv run pytest tests/xxx.py -v` / `uv run uvicorn app.main:app --reload --port 8000`）|
| `CLAUDE.md`（**sks-web 仓，新增 scoped**）| **承载约束前端代码的硬不变量**：纸感色板（`#f4f1e9` base / `#8a5a2b` primary / `Noto Serif SC` serif，tailwind.config.js 主题变量）/ TanStack Query 管服务端态 + Zustand 管客户端态 / axios 双实例（`userClient` baseURL `/api` 注入 `sks_token`；`adminClient` baseURL `/api/admin` 注入 `sks_admin_token`，两套隔离）/ 401 清 token + 跳登录（router 守卫 + axios 拦截器双保险）/ 无流式输出 → 用多阶段进度动画 mask 等待；**+ 本仓构建测试命令**（`npm install` / `npm run dev` / `npm run build`）|
| `deploy/GO_LIVE_CHECKLIST.md`（deploy 仓）| 点名改「4 容器全 healthy」为「**5 容器**（postgres/sks-server/sks-ai/sks-web/nginx）全 healthy，其中 sks-server/sks-ai/sks-web 为 GHCR 镜像，gateway 本地 build」|

> **CLAUDE.md 分仓的必要性**：CLAUDE.md 承载的硬不变量恰恰约束服务仓代码。拆完之后，在三服务仓里干活的 agent 读不到任何 CLAUDE.md，这些约束当场失效——而这些仓恰恰是唯一会写业务代码的地方。所以三服务仓各放一份 scoped CLAUDE.md，deploy 仓那份改成总览 + 指向。

### --build 心智模型改写（运维文档重点改造）

镜像化后，`docker compose up -d --build` 的语义变了（三服务仓不 build 了）。OPS.md / GO_LIVE_CHECKLIST.md 现有的 `--build` 指引要改写为新流程：

| 场景 | 旧（monorepo） | 新（镜像化） |
|---|---|---|
| 新增 Flyway 迁移生效 | `up -d --build sks-server` | sks-server 仓发新 tag → CI 出镜像 → deploy 仓 bump `sks-server.image` tag → `compose pull sks-server && compose up -d sks-server` |
| 前端发版 | `up -d --build nginx`（连带 node 阶段）| sks-web 仓发新 tag → CI 出镜像 → deploy 仓 bump `sks-web.image` tag → `pull sks-web && up -d sks-web` |
| 重建/首次起栈 | `up -d --build` | `compose pull --ignore-buildable`（拉三镜像，gateway 仍本地 build）→ `compose up -d` |
| 回滚部署 | `rebuild` 旧代码 | deploy 仓把 `<svc>.image` tag 改回上一版 → `pull && up -d`（比 build 快且确定，镜像化收益）|

§7.4 改造清单必须显式列入 OPS.md 这几段，不能只点「5 容器 healthy」。

## 7. 迁移步骤

### 7.1 抽离三服务仓到独立仓库（带历史，三次对称 split）
> 前置：`subtree split` 只带**已提交**的历史，三子目录里任何未提交改动不会跟到新仓。split 前先 `git status` 确认干净（当前仓库有未提交的 `.claude/settings.local.json`，虽在目录外，但 split 前务必确认三子目录无未提交改动，防真丢东西）。

```bash
cd /Users/rick/work/sks-agent

# 抽 sks-ai
git subtree split --prefix=sks-ai -b split-sks-ai
mkdir /Users/rick/work/sks-ai && cd /Users/rick/work/sks-ai
git init && git pull /Users/rick/work/sks-agent split-sks-ai

# 抽 sks-server
cd /Users/rick/work/sks-agent
git subtree split --prefix=sks-server -b split-sks-server
mkdir /Users/rick/work/sks-server && cd /Users/rick/work/sks-server
git init && git pull /Users/rick/work/sks-agent split-sks-server

# 抽 sks-web（与上两次完全同款，sks-web 是顶层目录）
cd /Users/rick/work/sks-agent
git subtree split --prefix=sks-web -b split-sks-web
mkdir /Users/rick/work/sks-web && cd /Users/rick/work/sks-web
git init && git pull /Users/rick/work/sks-agent split-sks-web
```

### 7.2 给三服务仓补 CI（test→build→push）+ 文档

**CI 公共参数**（三仓 workflow 都要）：
- **trigger**：`on: push: tags: ['v*']` 才 build+push（tag 名 = 镜像 tag，取 `github.ref_name`）；`push: branches: [main]` 与 `pull_request` 只跑 test（不推镜像）。
- **platforms**：固定 `linux/amd64`（服务器是 amd64）；README 注明镜像只保证 amd64。Mac arm64 本地 `compose up` 拉 amd64 走模拟（能跑但慢），与 §5.3「本地调试走宿主进程不用 compose」自洽。
- **权限**：`packages: write`（推 GHCR 本仓命名空间）。

**sks-ai 仓**：加 `.github/workflows/ci.yml`（`uv run pytest` 带 dev 依赖 → 绿 → `docker build` → push GHCR）、`.env.example`、`.gitignore`、`.dockerignore`、`CLAUDE.md`（scoped）、`README.md`、`docs/API_CONTRACT.md`；清理 `app/config.py` 删 `ALIYUN_SMS_SIGN` + Dockerfile 加 `COPY uv.lock` + `--frozen`（§3.2 假绿修复）。
- ⚠️ Python CI 留意：`tests/test_retrieve.py`、`test_account_analyze.py`、`test_video_analyze.py` 可能引 asyncpg/DATABASE_URL。实现期先确认是否全 mock；若有跑真库的用例，workflow 需加 `services: postgres`（pgvector 镜像）。

**sks-server 仓**：加 `.github/workflows/ci.yml`（`./mvnw test` → 绿 → `docker build` → push GHCR）、`.env.example`、`.gitignore`、`.dockerignore`、`CLAUDE.md`（scoped）、`README.md`、`docs/REST_CONTRACT.md`（前端↔Java REST 契约，§6）。
- 含 `application-local.yml` + local profile 的本地调试说明（见既有记忆 `local-idea-run-java-env`；**spring.config.import 用相对路径 `optional:file:.env[.properties]` + 工作目录=仓库根，别写死老 monorepo 绝对路径**）。
- ⚠️ Java CI 留意：Testcontainers 在 ubuntu runner 上可行；`actions/setup-java` 带 `cache: maven`。

**sks-web 仓**：加 `.github/workflows/ci.yml`（`npm ci && npm run build` → 绿 → `docker build` → push GHCR，**零 secret**）、`.gitignore`、`.dockerignore`、`CLAUDE.md`（scoped）、`README.md`、新写 `Dockerfile` + `nginx.conf`（静态服务职，§3.3）。
- ⚠️ sks-web 不需要 `.env.example`（无 env）。

各仓逐个 commit。

### 7.3 建三 GitHub 远程 + push + 打首个 tag 触发镜像构建
```bash
# sks-ai
cd /Users/rick/work/sks-ai
git remote add origin git@github.com:WangBuer1984/sks-ai.git
git branch -M main && git push -u origin main
git tag v0.1.0 && git push origin v0.1.0      # ⚠️ 必须打 tag 才触发 build+push（§7.2：push main 只跑 test 不产镜像）
# sks-server
cd /Users/rick/work/sks-server
git remote add origin git@github.com:WangBuer1984/sks-server.git
git branch -M main && git push -u origin main
git tag v0.1.0 && git push origin v0.1.0
# sks-web
cd /Users/rick/work/sks-web
git remote add origin git@github.com:WangBuer1984/sks-web.git
git branch -M main && git push -u origin main
git tag v0.1.0 && git push origin v0.1.0
```
> ⚠️ §7.2 定的 trigger 规则：`push: branches: [main]` 只跑 test 不推镜像，`push: tags: ['v*']` 才 build+push。所以 `git push -u origin main` **不会**产出镜像——必须紧跟 `git tag v0.1.0 && git push origin v0.1.0`（tag 名 = 镜像 tag，取 `github.ref_name`）。三仓各打首个 tag 后，CI 才 build 推 `ghcr.io/wangbuer1984/<svc>:v0.1.0`。默认 `GITHUB_TOKEN` 推本仓命名空间（workflow 配 `packages: write`，见 §8）。

### 7.4 原仓库改造为 deploy 仓（**必须等 7.3 三镜像 published 且可拉取后**）
> gate（三步都过才动原仓）：
> 1. 三服务仓 CI green、镜像 published（GitHub → Packages 页可见 `ghcr.io/wangbuer1984/sks-server:v0.1.0`、`sks-ai:v0.1.0`、`sks-web:v0.1.0`）。
> 2. **GHCR package 默认 private**（与仓库可见性独立）：要么部署机 `docker login ghcr.io`（PAT 带 `read:packages`）后 `docker pull` 三镜像成功；要么显式把三 package 设为 public。**不验证此步，首次 `compose pull` 必 401。**
> 3. 原仓 `git rm` 已确认要删三目录（见 §8 回滚）。

```bash
cd /Users/rick/work/sks-agent
git rm -r sks-ai sks-server sks-web
# 编辑 docker-compose.yml：三服务改 image: ghcr.io/.../<svc>:<tag>（钉具体 tag，不用 :latest）
#   sks-ai depends_on 改 sks-server healthy（§5.2/5.4，保证 Flyway 先跑）
#   新增 sks-web 块（image + expose:80 + healthcheck）
#   nginx depends_on 加 sks-web healthy；nginx 仍本地 build（gateway，§3.4）
# 重写 deploy/nginx/：Dockerfile 删 node 阶段（改 FROM nginx:alpine + COPY 两文件）；nginx.conf 删 / 静态块（迁 sks-web），只留 /api/ 反代 + 超时链 + error_page 50x + TLS 注释
# 编辑 .env.example：补全为单份全量（§4）
# 编辑 deploy/OPS.md：--build 流程改写（§6 场景表：迁移生效/前端发版/重建/回滚）
# 编辑 CLAUDE.md：四仓架构说明（总览 + 指向三服务仓 scoped CLAUDE.md）
# 编辑 deploy/GO_LIVE_CHECKLIST.md：「5 容器」描述更新（§6，三服务为 GHCR 镜像，gateway 本地 build）
# 新增 README.md：部署全栈说明（§6，含 docker login ghcr.io + 启动顺序 §5.4 + tag 更新流程）
# 可选：GitHub 仓库名改 sks-agent-deploy
git commit -m "chore: 四仓拆分——本仓变为 deploy 仓（sks-server/sks-ai/sks-web 见各自仓 + GHCR 镜像，nginx 拆静态/网关）"
git push
```

### 7.4.1 部署运行（写进 deploy 仓 README）
```bash
# 无需 docker network create（单 compose 单默认网络）
docker compose pull --ignore-buildable   # 拉三服务镜像；gateway 只有 build: 无 image:，--ignore-buildable 跳过它（up -d 仍自动 build gateway）
docker compose up -d                    # 按 depends_on 顺序起：pg → sks-server(Flyway)/sks-web → sks-ai → nginx-gateway
```

### 7.5 清理临时分支
```bash
cd /Users/rick/work/sks-agent && git branch -D split-sks-ai split-sks-server split-sks-web
```

## 8. 风险与回滚

- **不可逆但可恢复**：原仓 `git rm -r sks-ai sks-server sks-web` 后，历史里仍在（`git log -- sks-web/` 可查、可 `git revert` 或从历史 checkout）。三新仓有完整历史。拆错能从 git 恢复，不丢代码。
- **执行顺序**：先抽三新仓并 push + 打 tag 成功（7.1-7.3），等镜像 published 可拉取后，再动原仓（7.4）。抽离出问题时原仓未动，安全。
- **.env 真值不进 git**：deploy 仓 `.env` gitignored；服务仓 `.env`（本地 dev）也 gitignored。运行时 deploy 仓 `.env` 是唯一真值源。
- **跨仓契约漂移**（两份契约文档约束）：
  - `AiClient` record（sks-server 仓）与 pydantic model（sks-ai 仓）字段变更 + 共享表 schema 变更（**sks-server 仓 Flyway**，打进镜像，§6 契约面 2）靠 `docs/API_CONTRACT.md`（sks-ai 仓）约束。
  - 前端 axios 调用 ↔ Java REST（`ApiResponse` 形状、ErrorCode 全表、`sks_token`/`sks_admin_token` 两套 token key、401 行为）靠 `docs/REST_CONTRACT.md`（sks-server 仓，必需）约束。
  - 镜像方案下 `.env` 单份，无跨仓密钥同步负担。
- **启动顺序（两处风险，单列）**：
  1. **sks-ai 依赖 sks-server 的 Flyway 表**：sks-ai RAG 读 `kb_card`/`analyze_task`，由 sks-server 镜像启动时 Flyway 建。**缓解**：compose `sks-ai depends_on: sks-server healthy`（§5.4）；独立重启 sks-ai 用 `docker compose up -d --no-deps sks-ai` 或 `restart sks-ai`。
  2. **checkpointer 无懒重试**：`/health 仍 UP` 兜底只覆盖 asyncpg 池（有懒重试）。`checkpointer` 只启动时初始化一次、无懒重试。若 sks-ai 先于 pg 起来，interview 端点一直坏而 `/health` UP。**缓解**：compose `depends_on` 保证 pg 先健康；补懒重试标 out-of-scope（§9），但风险写明。
- **镜像 tag 漂移**：deploy compose 用 `:latest` 有"本地缓存不更新"风险，故钉具体 tag（§5.3）。CI 推 GHCR 用默认 `GITHUB_TOKEN`（workflow 配 `packages: write`）。GHCR 命名空间自动 lowercase owner：仓库 URL `WangBuer1984/sks-web`（mixed）但镜像路径 `ghcr.io/wangbuer1984/sks-web`（lowercase），两者都对，勿混淆。
- **5 容器多一跳**：静态请求 `浏览器→gateway→sks-web` 多一跳（同机可忽略）；多维护 `nginx depends_on sks-web healthy` 一条。换得前端与另两服务同款发版 ergonomics。

## 9. 不在本次范围

- 不改三服务的业务逻辑（sks-server AiClient / sks-ai 端点 / 前端页面）——只搬 + 文档化契约 + Dockerfile/uv.lock 修复。
- 不补 checkpointer 懒重试（标 out-of-scope，风险写进 §8）。
- 不拆 Postgres（保留共享单库，deploy 仓 compose 管）。
- **gateway 不镜像化**（本地 build，`nginx:alpine + 两文件`秒级，无 npm 依赖，无 CI/registry）。
- 不做 deploy 仓的自动化 tag→镜像更新（手动 bump compose 里 image tag；后续可加 Renovate/watch）。
- **未来路径**：若前端要独立团队/CDN 托管，可再把 sks-web 静态托管迁出 gateway（直接 CDN/sks-web:80 对公网，gateway 只留 /api/ 反代+TLS）。
