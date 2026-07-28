# sks-agent 四仓拆分 + 镜像化 — 设计文档

> 日期：2026-07-28
> 状态：已通过 brainstorming（多轮 review 修订，含四仓重写后逐节复核），待用户复核后进 writing-plans
> 演进：2仓独立compose+external网络 → 3仓镜像 → **4仓镜像（sks-web 独立 + nginx 拆静态/网关两职）**

## 1. 目标与动机

把当前 monorepo 拆成**四个仓库**：三个服务仓（sks-server / sks-ai / sks-web）各自**独立发版**（不同版本号 / 各自 CI / 各自 git tag / 各自构建镜像到 GHCR），且都能**独立部署**（bump 镜像 tag、单独重启，不连带对方）。第四个 deploy 仓集中持有 compose + gateway nginx + .env + 部署文档。

**nginx 拆两职**：静态服务容器（sks-web 镜像，serve SPA + try_files fallback + 资源缓存）+ 网关容器（deploy 仓本地 build，做 `/api/` 反代 + `/` 反代 sks-web + 超时链 + TLS + 50x 兜底页）。

**已排除的替代方案**：
- 不拆仓、monorepo + per-service CI（用户明确要仓库级分离 + 各自 CI/tag）。
- 三仓（不拆 sks-web，nginx 兼静态+网关）：前端发版要连带 gateway rebuild，且 gateway 依赖 node 阶段（生产机出网拉 npm）。拆 sks-web 后 gateway 变 `nginx:alpine + 两文件`秒级 build、零 npm 依赖，前端与另两服务同款发版 ergonomics。
- 拆仓 + 各自独立 compose + external 共享网络：跨 compose 网络复杂度 + .env 跨仓同步负担。镜像 + 单 compose 更简。

## 2. 关键决策（brainstorming 已定）

| 决策 | 选择 | 理由 |
|---|---|---|
| 拆仓范围 | **四仓对称**：sks-server / sks-ai / sks-web / sks-agent-deploy | 三服务各自独立发版，sks-web 也独立（前端镜像环境无关，CI 零 secret）|
| nginx 两职 | **拆**：sks-web 镜像管静态服务（SPA fallback + 缓存），gateway（deploy 仓本地 build）管 `/api/` 反代 + `/` 反代 sks-web + TLS + 50x | 前端发版不连带 gateway rebuild；gateway 去掉 node 阶段秒级 build 零 npm 依赖 |
| 部署模型 | **镜像化**：三服务仓 CI 构建+推镜像到 GHCR，deploy 仓 compose 按镜像引用；gateway 本地 build | 单 compose 单网络，DNS 名天然互通，无 external；gateway 太简不镜像化（无 CI/registry）|
| 镜像 registry | **GHCR（ghcr.io）** | 免费、与 GitHub 仓库集成、solo 零成本 |
| Postgres | 连同一个 pg 实例（deploy 仓 compose 管） | 保留「单库三合一」+ `analyze_task`/`kb_card` 共享表读写；拆库破坏架构，不取 |
| Git 历史 | 保留（`git subtree split` 抽离各子目录） | 追溯性好；sks-web 是顶层目录，三次 split 完全同款 |
| `.env` 归属 | **单份，住 deploy 仓** | compose `env_file` 运行时注入三服务镜像；跨仓 .env 同步负担整个消失 |
| sks-web env | **无任何运行期/构建期 env** | axios 用相对基址（`/api`+`/api/admin`），全代码库无 `VITE_` 变量，镜像环境无关，CI 零 secret |
| 网络 | deploy 仓单 compose 单 named 网络 `sks-net`（5 服务，同现状保留） | DNS 名天然互通，compose 自动创建，无需 external / `docker network create` |

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
├── .dockerignore             ← node_modules/.env/prototypes/ 护栏（prototypes/ 14MB+ 排除出 build context，§3.3 Dockerfile 是 COPY . .）
├── CLAUDE.md                 ← 新增：scoped，承载前端硬约束（§6）
├── prototypes/               ← 新增：两份原型 HTML（C 端 + admin），视觉基准只读不改（§6 归属，§7.2 从原仓根 cp 来）
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
│   ├── nginx/                ← 重写：删 node 阶段（静态已迁 sks-web）；nginx.conf 改 /api/ 反代 + / 反代 sks-web + TLS + 50x；Dockerfile 改 FROM nginx:alpine + 两文件
│   ├── OPS.md, GO_LIVE_CHECKLIST.md, backup/
├── docs/                     ← PRD、tech-design、MVP plan、学习文档、本 spec（顶部加注「路径按拆分前 monorepo 布局」）
├── CLAUDE.md                 ← 更新：四仓架构总览 + 指向三服务仓 scoped CLAUDE.md
└── README.md                 ← 新增：如何用本仓部署全栈
```
原仓库改造而来：删 `sks-ai/`、`sks-server/`、`sks-web/` 目录，保留其余。**gateway 仍本地 build**（`nginx:alpine + 两文件`，秒级，无 npm，无 CI/registry）。

> **50x.html 必须留在 gateway 本地 serve**（`error_page 50x → 本地 50x.html`）——它是"sks-web 挂了也要能渲染"的兜底页，代理到 sks-web 就失去意义（sks-web 挂时 50x 也拿不到）。这是 nginx 拆两职最容易写漏的细节：`/` 静态 + SPA fallback 迁 sks-web，但 `50x.html` 留 gateway。支撑论据：`50x.html` 内含 `{{CONTACT_WECHAT}}` 占位符，靠 OPS.md 记录的部署期 sed/envsubst 替换，这本身就是 deploy 仓关切，跟着 gateway 走天然正确。

**gateway nginx.conf**（网关职——整个改动里最容易写错的文件，下面是**骨架**，示意「改完后该长成什么样」；实际操作是**基于现文件编辑**，不是拿此块整体覆盖，见 §7.4）：
```nginx
server {
    listen 80;
    server_name _;
    # 兜底页：gateway 本地 serve，sks-web/Java 全挂仍能渲染
    error_page 500 502 503 504 /50x.html;
    location = /50x.html { root /usr/share/nginx/html; }
    location /api/ {
        proxy_pass http://sks-server:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;   # 超时链外环：nginx 300s > Java 270s > Python 240s（120s × 2，口径见 §7.4）
        proxy_send_timeout 300s;
    }
    # 静态不再本地 serve —— 反代 sks-web 容器（try_files fallback 在 sks-web 内）
    location / {
        proxy_pass http://sks-web:80;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```
> **勿整体覆盖——三条承重注释必须随编辑保留**：上面骨架把现 `deploy/nginx/nginx.conf` 的注释压成了行内短注，拿它盖掉原文件会丢三条承重注释，后来人"顺手规范化"即踩雷：(1) 第 13 行 `# 不加 internal：brief 验收要求 curl https://域名/50x.html 可直访兜底页`——清单第 15/117 行都在 curl 它验收，丢了注释后来人加个 `internal` 验收当场红；(2) 第 19-21 行超时链完整推导 + 后果半句「nginx 不可短于 Java，否则会先掐断仍在跑的 Python 调用 → 假 AI_FAILED → 误退款」——整个文件里最跟钱相关的一条注释，骨架只留了数值没留后果；(3) 第 11 行 `{{CONTACT_WECHAT}}` 上线前 sed/envsubst 替换指引——清单第 73 行依赖它。故 §7.4 的操作是**基于现文件编辑**（删 server 级 root/index、把 `location /` 的 try_files 换成 proxy_pass），不是 overwrite。

> **二阶陷阱——443 注释块必须同步改写**：现有 nginx.conf 末尾那个注释掉的 443 server 块，把 `root` / `index` / `location / { try_files ... }` 完整复制了一份。§7.4 只说「TLS 注释」保留——若上线签证书时照着取消注释，会在 gateway 里复活静态服务，而 gateway 镜像里根本没有 dist → `/` 直接 404，且只在开 HTTPS 那天才炸。**取消注释时 443 块的 `location /` 必须改成 `proxy_pass http://sks-web:80`**（与 80 块一致），不可保留旧静态 root；443 块的 server 级 `root`/`index`（现第 53-54 行）也一并删——gateway 不再 serve 静态，`location = /50x.html` 自带 root 不依赖 server 级。§7.4 要点名这件事。

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
| `ALIYUN_SMS_SIGN` + `ALIYUN_SMS_TEMPLATE_LOGIN/VERIFY_OLD/BIND_NEW` | sks-server | Java DYPNS 短信。**四键均无默认、必填**（application.yml `sks.sms.*` 块，现 .env.example 只有 SIGN 一行，三个 template 是缺项）；`ALIYUN_SMS_ENDPOINT` 有默认 `dypnsapi.aliyuncs.com` 可不入 .env。注意漏配**不报错**——DYPNS 客户端 key/模板空时走 stub 静默不发，缺键藏得住，故必须靠此表枚举兜底 |
| `ALIYUN_ASR_KEY`、`ALIYUN_ASR_APP_KEY` | sks-ai | Python ASR |
| `ZHIPU_API_KEY`、`TIKHUB_API_KEY` | sks-ai | Python LLM/数据 |
| `ZHIPU_BASE_URL`、`TIKHUB_BASE_URL`、`ALIYUN_CONTENT_SAFETY_ENDPOINT` | sks-ai | config.py 有默认值，注释「默认 xxx，一般不改」|
| `SPRING_MAIL_HOST/USERNAME/PASSWORD` + `SKS_ALERT_ADMIN_EMAIL` | sks-server | Java 邮件告警。与 SMS 同款**静默 stub 特性**：HOST 或收件人空 → MailAlertNotifier stub（log + no-op 不抛），漏配藏得住；`SPRING_MAIL_PORT` 有默认 465 可不入 .env（465 的 `ssl.enable=true` 已钉在 yaml，不走 env）。现 .env.example 此四键 + `ALIYUN_ASR_*` 均缺，§7.4 补全时按本表逐行核 |

**sks-web 无任何运行期/构建期 env**：axios 用相对基址（`baseURL: '/api'` + `/api/admin`），全代码库无 `VITE_` 构建期变量，镜像环境无关，CI 零 secret，一个镜像任何环境通用。

### 服务仓的 `.env.example`（仅本地 dev 参考）

sks-server / sks-ai 仓各放一份**精简** `.env.example`，只列自己 dev 本地跑需要的 key（带注释「运行时由 deploy 仓 compose 注入，本文件仅本地调试参考」）。这是本地 `uv run uvicorn` / `./mvnw spring-boot:run` 时 `source .env` 用。sks-web 不需要 `.env.example`（无 env）。

> **TODO（不阻塞，现状非回归）**：单 `.env` 全量 `env_file` 注入两容器，sks-ai 也能读到 `JWT_SECRET_*`/`ADMIN_SEED_PASSWORD`/`SPRING_MAIL_PASSWORD`（爆炸半径）。当前 monorepo 就有，不算拆分引入。后续要收紧可分 per-service env 文件，但别为它牺牲「单份 env」的简化。

## 5. 网络 + deploy 仓的镜像化 docker-compose.yml（5 服务）

### 5.1 网络（单 compose 单网络，无 external 舞蹈）

deploy 仓**单 compose 管五服务**（postgres / sks-server / sks-ai / sks-web / nginx（即 gateway，服务名 nginx）），单 **named 桥接网络 `sks-net`**——同现状保留：顶层 `networks: sks-net: {driver: bridge}` + 各服务 `networks: [sks-net]`，compose 自动创建，无需 `docker network create`。`postgres`、`sks-ai`、`sks-server`、`sks-web` DNS 名天然互通，**无需 external 共享网络**。sks-ai/sks-web `expose` 不发宿主端口，硬约束「Python 不暴露公网」原样保住。（现 compose 就是 named `sks-net` 而非 compose 默认网络——拆分不改网络拓扑，零改动贴合现状。）

### 5.2 deploy 仓 docker-compose.yml（镜像化，5 服务）

```yaml
services:
  # ⚠️ 骨架省略 ≠ 删除：现 compose 各服务的 networks: [sks-net]、container_name、restart: unless-stopped
  #   本骨架为省篇幅未逐一展示，一律同现状保留（实现是"基于现文件编辑"，与 §3.4 nginx.conf 同款原则）。
  #   sks-web 是新增服务，对齐兄弟服务补：networks: [sks-net] + container_name: sks-web + restart: unless-stopped。
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
      # ...（SERVICE_TOKEN / ZHIPU / TIKHUB / ALIYUN_ACCESS_KEY_* 同现状；**唯 ALIYUN_SMS_SIGN 行删除**——
      #   §3.2 已从 config.py 删该字段、§4 把 ALIYUN_SMS_* 归 sks-server only，三处口径对齐。
      #   即便 env_file 全量带进来 config.py 也不读，删行零行为变化，纯去 cruft）
    depends_on:
      sks-server: { condition: service_healthy }   # 等 sks-server 健康（/api/health UP = Spring Boot 已起 = Flyway 已跑完，保证 kb_card/analyze_task 表已建）
    expose: ["8000"]
    # ⚠️ 同现状必须保留 healthcheck（python urllib 探 /health）——§6 验收要「5 容器全 healthy」。

  sks-web:
    image: ghcr.io/wangbuer1984/sks-web:<tag>      # 按镜像引用，环境无关
    expose: ["80"]
    healthcheck:                                   # 新增服务无"同现状"可抄，字段给齐（口径沿用现 nginx healthcheck）——§6 验收要 5 容器全 healthy
      test: ["CMD-SHELL", "wget -q --spider http://localhost/ || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 3

  nginx:                                                         # 服务名 nginx（gateway 是角色名；命令语境一律用 nginx，勿敲 nginx-gateway → no such service）
    build: { context: ., dockerfile: deploy/nginx/Dockerfile }   # gateway 本地 build（nginx:alpine + 两文件，秒级，无 npm）
    ports: ["80:80"]                                             # gateway 是唯一 ports 暴露宿主的容器；现状的 8080:80 本地调试映射删除（§5.3 compose=部署用，本地调试走宿主进程）
    depends_on:
      sks-server: { condition: service_healthy }   # /api/ 反代 Java
      sks-web: { condition: service_healthy }      # / 反代静态
    # ⚠️ healthcheck 必须保留但探测目标要改——五个里唯独这条不能照抄现状：
    #   现 compose 探 http://127.0.0.1/（本地 serve 静态时对的），拆分后 / 反代 sks-web，探 / 穿透到 sks-web 容器；
    #   sks-web 独立重启窗口里 gateway 被误标 unhealthy（"5 容器全 healthy"误报 + 拨测告警误触），违背"独立部署互不连带"。
    #   改探 http://127.0.0.1/50x.html：gateway 本地文件、恒在、不穿透任何 upstream，顺带持续验证兜底页可达（同清单第 15/117 行 curl 验收）。
    # ...（healthcheck: wget -q --spider http://127.0.0.1/50x.html）

networks:
  sks-net:
    driver: bridge   # 同现状保留（§5.1）——各服务省略未展示的 networks: [sks-net] 行同样保留，勿因骨架未画而删

volumes:
  sks-pgdata:   # 顶层必须声明 named volume——§5.2 服务级 volumes 引用了它，缺顶层声明 compose 直接报 "service refers to undefined volume"，不是退化是起不来
```

### 5.3 独立部署怎么操作（镜像 tag 策略）

- **deploy 仓 compose 钉具体 tag**（如 `ghcr.io/.../sks-web:v1.2`），不用 `:latest`——`:latest` 有"本地缓存不更新"风险（§8）。
- **sks-web 独立发版**：sks-web 仓 git tag v1.2 → CI `npm ci && npm run build` 绿 → build 推 `ghcr.io/.../sks-web:v1.2`（零 secret）。
- **sks-web 独立部署**：deploy 仓改 `sks-web.image` tag 为 v1.2 → `docker compose up -d sks-web` 只拉新镜像只重启 sks-web（不碰 sks-server/sks-ai/pg/gateway；`--no-deps` 可避免顺带重启依赖）。sks-server/sks-ai 同理。
- **gateway 不镜像化**：本地 build，无 tag 流程；改 gateway 配置 = `docker compose build nginx && docker compose up -d nginx`。
- **本地调试不受影响**：Java 用 IDEA、Python 用 PyCharm/`uv run uvicorn`、前端 `npm run dev`，全在宿主进程跑，不依赖 compose。compose 是部署用。

### 5.4 启动顺序（compose dependency 保证，见 §8 风险）

compose dependency 链：`postgres` → `sks-server`(depends postgres, Flyway 在它启动时跑) → `sks-ai`(depends sks-server healthy，即 Spring Boot 起完=Flyway 跑完=表已建)；`sks-web` 独立（无 deps）；`nginx`(gateway，depends sks-server + sks-web healthy)。单 compose `up` 自动按此序。若手动分批起，README 钉死：**先 postgres，后 sks-server(Flyway)/sks-web（并行），后 sks-ai，最后 nginx**（`docker compose up -d nginx`——服务名是 nginx，不是 nginx-gateway，敲后者得 no such service）。checkpointer 无懒重试的风险见 §8。

## 6. 文档归属

| 文档 | 去向 |
|---|---|
| `随口说PRD .md`、tech-design、MVP plan、学习文档、本 spec | **deploy 仓**（顶部各加注「路径按拆分前 monorepo 布局，现分属三服务仓」，不逐条改写）|
| `deploy/OPS.md`、`GO_LIVE_CHECKLIST.md` | **deploy 仓**，且需**改写 --build 流程**（见下文「--build 心智模型改写」）|
| `docs/API_CONTRACT.md` | **sks-ai 仓**（/ai/* HTTP 端点 + 共享表契约，服务提供方拥有）|
| `docs/REST_CONTRACT.md` | **sks-server 仓，必需**（前端↔Java REST 契约跨仓）：ErrorCode 全表 + `ApiResponse` 形状 + 两套 token key 约定（`sks_token`/`sks_admin_token`）+ 401 行为 |
| `随口说原型-07191700.html`（C 端原型）+ `随口说后台管理原型-admin.html`（站长后台原型）| **sks-web 仓 `prototypes/` 子目录**（前端 visual reference——sks-web 仓需拿到自己的设计基准，与 scoped CLAUDE.md 同类文档局部性问题；按 §3.4「保留其余」它们本会留 deploy 仓，故需显式迁出）。两份对称处理：C 端原型覆盖 sks-web 绝大部分页面，admin 只同一 SPA 一小块，C 端更该跟走；只搬 admin 不搬 C 端会给出"C 端无视觉基准，自由发挥"的错误完整性信号，比都不给更糟。§7.2 从原仓根 `cp` 两份进 `sks-web/prototypes/`；deploy 仓 `git rm` 两份只留指针（§7.4），canonical 一份更干净（冻结文件无漂移风险，体积是唯一重复成本）|
| `README.md`（sks-ai 仓）| 怎么本地跑（`uv sync`/`uv run uvicorn`）、镜像构建、`DATABASE_URL`/`.env` 契约、健康检查、镜像只保证 linux/amd64 |
| `README.md`（sks-server 仓）| 怎么本地跑（`./mvnw spring-boot:run` + `application-local.yml`/local profile）、镜像构建、镜像只保证 linux/amd64 |
| `README.md`（sks-web 仓）| 怎么本地跑（`npm install`/`npm run dev`）、镜像构建（零 secret）、镜像只保证 linux/amd64 |
| `README.md`（deploy 仓）| 如何用本仓部署全栈：**部署机初始化前置**（装 docker-ce + compose plugin ≥ v2.22 + 加速器 + `docker login ghcr.io`，§7.4.1——现无文档覆盖，目标机实测无 Docker）+ `compose up` + `.env` 配置 + 启动顺序 §5.4 + 镜像 tag 更新流程 |
| `CLAUDE.md`（deploy 仓）| 改成**四仓总览**：架构图（四仓 + GHCR 镜像 + gateway 本地 build + compose 编排）、Java↔Python 跨仓 HTTP+X-Service-Token、指向三服务仓 scoped CLAUDE.md、原型指向 sks-web 仓 `prototypes/`（两份原型 `git rm` 出 deploy 仓，见 §7.4，避免死引用）；删「Python packages」节、build commands 分仓 |
| `CLAUDE.md`（**sks-server 仓，新增 scoped**）| **承载约束 sks-server 代码的硬不变量**：信用事务边界（扣额度原子条件更新 + 退款幂等 via credit_ledger）/ admin 隔离（独立 admin_user + 独立 SecurityFilterChain + 不同 JWT secret）/ Testcontainers pgvector:pg16 非 H2 / 复盘状态机无 AI 判态 / Java 唯一公网入口 / 不用 Redis/MQ；**+ 本仓构建测试命令**（`./mvnw test` / `./mvnw test -Dtest=Xxx` / `./mvnw spring-boot:run`）——原根 CLAUDE.md「Build/test/run commands」节随仓搬来 |
| `CLAUDE.md`（**sks-ai 仓，新增 scoped**）| **承载约束 sks-ai 代码的硬不变量**：无流式输出 + 先审后返（生成完→内容安全→返回 JSON）/ GLM 单厂商 + 型号只在 llm/ + embedding 1024 维绑 vector(1024) 列 / 不做迁移（checkpointer 例外，sks-ai 自己 setup）/ UGC 过内容安全审 / Python 不暴露公网只信 X-Service-Token；**+ 本仓构建测试命令**（`uv sync` / `uv run pytest tests/xxx.py -v` / `uv run uvicorn app.main:app --reload --port 8000`）|
| `CLAUDE.md`（**sks-web 仓，新增 scoped**）| **承载约束前端代码的硬不变量**：纸感色板（`#f4f1e9` base / `#8a5a2b` primary / `Noto Serif SC` serif，tailwind.config.js 主题变量）/ TanStack Query 管服务端态 + Zustand 管客户端态 / axios 双实例（`userClient` baseURL `/api` 注入 `sks_token`；`adminClient` baseURL `/api/admin` 注入 `sks_admin_token`，两套隔离）/ 401 清 token + 存回跳路径 `returnKey` + 跳对应登录页（C 端 `/login`、管理端 `/admin/login`；router 守卫 + axios 拦截器双保险）/ 无流式输出 → 用多阶段进度动画 mask 等待 / `prototypes/` 是前端视觉基准，只读不改（见 §6 归属）；**+ 本仓构建测试命令**（`npm install` / `npm run dev` / `npm run build`）；**+ 指针**：错误码全表与 `ApiResponse` 形状见 sks-server 仓 `docs/REST_CONTRACT.md`（消费方须知道契约位置，契约文档只有提供方知道等于没有）|
| `deploy/GO_LIVE_CHECKLIST.md`（deploy 仓）| 点名改「4 容器全 healthy」为「**5 容器**（postgres/sks-server/sks-ai/sks-web/nginx）全 healthy，其中 sks-server/sks-ai/sks-web 为 GHCR 镜像，gateway 本地 build」；**并改写 certbot/TLS 验收项（现第 70 行「nginx 443 server block 取消注释」）**——签证书那天操作者读的是这份清单不是拆分 spec，警告写在 §3.4 对当时的他不产生作用。须把「取消注释」扩写为「取消注释 + 443 块 `location /` 改 `proxy_pass http://sks-web:80`（同 80 块，见 gateway nginx.conf）+ 删 443 块 server 级 root/index」。坑比看起来深：现 nginx.conf 第 41-42 行启用 443 时把 80 块整个换成 301 跳转，启用 TLS 后唯一 serve `/` 和 `/api/` 的就只剩 443 块——443 块写错即全站黑，不是局部降级 |

> **sks-web 401 注记**（从上表 sks-web 行拆出，避免长警告夹在斜杠列表中间被扫读漏看）：当前实现**只存回跳路径 `returnKey`、未存表单内容**——PRD §11.6 要的"当前表单存 localStorage 后跳登录"是既有 gap，不在拆分 spec 顺手改；scoped CLAUDE.md **不得写成"401 保内容"**，否则误导后继 agent 以为已实现。

> **CLAUDE.md 分仓的必要性**：CLAUDE.md 承载的硬不变量恰恰约束服务仓代码。拆完之后，在三服务仓里干活的 agent 读不到任何 CLAUDE.md，这些约束当场失效——而这些仓恰恰是唯一会写业务代码的地方。所以三服务仓各放一份 scoped CLAUDE.md，deploy 仓那份改成总览 + 指向。

### --build 心智模型改写（运维文档重点改造）

镜像化后，`docker compose up -d --build` 的语义变了（三服务仓不 build 了）。OPS.md / GO_LIVE_CHECKLIST.md 现有的 `--build` 指引要改写为新流程：

| 场景 | 旧（monorepo） | 新（镜像化） |
|---|---|---|
| 新增 Flyway 迁移生效 | `up -d --build sks-server` | sks-server 仓发新 tag → CI 出镜像 → deploy 仓 bump `sks-server.image` tag → `compose pull sks-server && compose up -d sks-server` |
| 前端发版 | `up -d --build nginx`（连带 node 阶段）| sks-web 仓发新 tag → CI 出镜像 → deploy 仓 bump `sks-web.image` tag → `pull sks-web && up -d sks-web` |
| 重建/首次起栈 | `up -d --build` | `compose pull --ignore-buildable`（拉三镜像，gateway 仍本地 build）→ `compose up -d`。`--ignore-buildable` 需 Compose v2.22+，老版本 fallback `compose pull sks-server sks-ai sks-web` |
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

**sks-web 仓**：加 `.github/workflows/ci.yml`（`npm ci && npm run build` → 绿 → `docker build` → push GHCR，**零 secret**）、`.gitignore`、`.dockerignore`（**含 `prototypes/` 行**——Dockerfile `COPY . .` 会把两份原型 14MB+ 拉进 build context/build stage，最终镜像虽只 COPY dist 不受影响，但白白多传一遍）、`CLAUDE.md`（scoped）、`README.md`、新写 `Dockerfile` + `nginx.conf`（静态服务职，§3.3）。另：`mkdir -p /Users/rick/work/sks-web/prototypes && cp /Users/rick/work/sks-agent/随口说原型-07191700.html /Users/rick/work/sks-agent/随口说后台管理原型-admin.html /Users/rick/work/sks-web/prototypes/`——两份原型对称带进 sks-web 仓 `prototypes/` 子目录（§6 归属表；subtree split 只带 `sks-web/` 子目录，根级文件不会自动跟过去，须显式复制）。
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
> 2. **GHCR package 默认 private**（与仓库可见性独立）：要么部署机 `docker login ghcr.io`（PAT 带 `read:packages`）后 `docker pull` 三镜像成功；要么显式把三 package 设为 public。**不验证此步，首次 `compose pull` 必 401。**此 `docker pull` 必须在**部署机**上做，因为它同时验证第二种独立失败模式——**部署机对 ghcr.io 的网络可达性**（OPS.md §8 的阿里云 registry-mirrors 只镜像 docker.io，对 ghcr.io 无效；国内服务器 ghcr.io 可达性不稳，见 §8 风险与 fallback）。
> 3. 原仓 `git rm` 已确认要删三目录（见 §8 回滚）。

```bash
cd /Users/rick/work/sks-agent
git rm -r sks-ai sks-server sks-web
git rm 随口说原型-07191700.html 随口说后台管理原型-admin.html   # 两份原型已 cp 进 sks-web 仓 prototypes/（§7.2）；deploy 仓只留指针，canonical 一份。git rm 后历史里仍在、可 checkout，不丢东西（§8 同款逻辑）
# 编辑 docker-compose.yml：三服务改 image: ghcr.io/.../<svc>:<tag>（钉具体 tag，不用 :latest）；删 sks-server/sks-ai 现有的 build: 块（sks-web 是新增服务块，本无 build 可删）
#   networks（顶层 sks-net + 各服务挂载）/ container_name / restart: unless-stopped 一律不动——§5.2 骨架省略 ≠ 删除
#   sks-ai depends_on 改 sks-server healthy（§5.2/5.4，保证 Flyway 先跑）
#   sks-ai environment: 删 ALIYUN_SMS_SIGN 行（现第 60 行；§3.2 已从 config.py 删字段、§4 SMS_* 归 sks-server——即便 env_file 带进来 config.py 也不读，零行为变化，纯去 cruft）
#   新增 sks-web 块（image + expose:80 + 完整 healthcheck（§5.2 已给齐字段）+ networks: [sks-net] + container_name: sks-web + restart: unless-stopped，对齐兄弟服务）
#   nginx ports 删 8080:80 本地调试映射，仅留 80:80（现第 84 行；§5.3 compose=部署用，本地调试走宿主进程）
#   nginx depends_on 加 sks-web healthy；nginx 仍本地 build（gateway，§3.4）；**healthcheck 探测目标改 http://127.0.0.1/50x.html**（现探 /，拆分后 / 反代 sks-web 会耦合 sks-web 健康状态，见 §5.2）
# 重写 deploy/nginx/（基于现文件编辑，勿拿 §3.4 骨架整体覆盖——三条承重注释须保留，见 §3.4「勿整体覆盖」）：
#   Dockerfile：删 node 阶段（web-build 整段去掉）→ 改 FROM nginx:alpine + COPY nginx.conf + COPY 50x.html 两文件
#   nginx.conf 80 块：删 server 级 root/index（gateway 不再 serve 静态，location = /50x.html 自带 root）；
#     location / 从 try_files 改 proxy_pass http://sks-web:80（代理这一跳必须留——删掉则 gateway 对根路径无 handler，/ 返回 404 前端整个打不开）；保留 /api/ 反代 + 超时链注释 + error_page 50x
#   nginx.conf 443 注释块：取消注释启用时，location / 同样改 proxy_pass http://sks-web:80（不可保留旧 try_files）；并删 server 级 root/index（现第 53-54 行）——gateway 无 dist，留 root 也无意义
#   ⚠️ Dockerfile 的 COPY 路径保持 deploy/nginx/ 前缀不动（现 COPY deploy/nginx/nginx.conf、COPY deploy/nginx/50x.html，依赖 compose context: .）。
#     去掉 node 阶段后有人想收窄 context 到 deploy/nginx 加速 build——可以，但收窄 context 与去掉 COPY 路径前缀必须同时做，只改一边 build fail（构建期失败、不危险，但白费一次排查）。§5.2 保留 context: .，故 COPY 前缀不动
#   顺手统一 Python 侧超时口径：nginx.conf 第 19 行注释与 OPS.md 超时链表（现第 134 行）写「≈ 250s」、GO_LIVE_CHECKLIST 第 17/90 行写 240s——两套既有口径（拆分未引入）。重写时三处统一为 240s（120s × 2 推导值），不影响 300 > 270 > 240 结论
# 编辑 .env.example：补全为单份全量（§4）
# 编辑 deploy/OPS.md：
#   --build 流程改写（§6 场景表：迁移生效/前端发版/重建/回滚）
#   §8 镜像加速器节改写：部署机不再拉 node/python/temurin 三基础镜像（CI 在 GitHub runner 构建），但仍从 Docker Hub 拉 pgvector/pgvector:pg16 + nginx:alpine（阿里云加速器仍需要）；
#     三服务镜像改走 ghcr.io——registry-mirrors 只镜像 docker.io，对 ghcr.io 无效（GHCR 国内可达性风险与 fallback 见 §8）
#   源码路径引用改指向服务仓（现第 5 行 sks-server/.../QuotaWatchJob.java、第 134 行 sks-ai/app/llm/——拆分后在 deploy 仓是死路径，改成「见 sks-server/sks-ai 仓 xxx」或顶部加 §6 同款「路径按拆分前布局」注）
#   补「部署机初始化」节（现无任何文档覆盖装 Docker；2026-07-29 已在目标机实际走通一遍，按实录写）：装 docker-ce + compose plugin ≥ v2.22（阿里云 RHEL 系用 mirrors.aliyun.com/docker-ce 源；**须加 --setopt=install_weak_deps=False**——rootless-extras 弱依赖在镜像源下载失败会回滚整个事务，实测踩过）+ systemctl enable --now docker；顺带校正第 32 行 certbot 安装命令（现为 apt，目标机是 RHEL 系应为 dnf）
# 编辑 CLAUDE.md：四仓架构说明（总览 + 指向三服务仓 scoped CLAUDE.md + 原型指向 sks-web 仓 prototypes/，避免对已 git rm 的根级原型留死引用）
# 编辑 deploy/GO_LIVE_CHECKLIST.md：「5 容器」描述更新（§6，三服务为 GHCR 镜像，gateway 本地 build）；**另必改**——(a) certbot/TLS 验收项（现第 70 行）扩写为「取消注释 + 443 块 location / 改 proxy_pass http://sks-web:80 + 删 server 级 root/index」（§6 详，签证书那天操作者只读此清单）。清单第 17/90 行本就是 240s、不动；要改的是 nginx.conf 注释 ≈250 向 240 对齐（见上 nginx.conf 编辑项），别对清单做空编辑
# 新增 README.md：部署全栈说明（§6，含 docker login ghcr.io + 启动顺序 §5.4 + tag 更新流程）
# 可选：GitHub 仓库名改 sks-agent-deploy
git commit -m "chore: 四仓拆分——本仓变为 deploy 仓（sks-server/sks-ai/sks-web 见各自仓 + GHCR 镜像，nginx 拆静态/网关）"
git push
```

### 7.4.1 部署运行（写进 deploy 仓 README）
```bash
# 部署机前置（新服务器裸机——现有 OPS.md/清单全部假设 Docker 已装，此步之前无任何文档覆盖）：
#   1) 装 docker-ce + docker-compose-plugin（阿里云 RHEL 系用 mirrors.aliyun.com/docker-ce 源，勿走 download.docker.com）
#   2) docker compose version 确认 ≥ v2.22（下方 --ignore-buildable 依赖）
#   3) 配 Docker Hub 加速器（OPS.md §8，pgvector/nginx:alpine 仍走 docker.io）
#   4) docker login ghcr.io（package 为 private 时；PAT 带 read:packages）
# 无需 docker network create（单 compose 单 named 网络 sks-net，compose 自动创建，§5.1）
docker compose pull --ignore-buildable   # 拉三服务镜像；gateway 只有 build: 无 image:，--ignore-buildable 跳过它（up -d 仍自动 build gateway）
# ⚠️ --ignore-buildable 需 Compose v2.22+；老版本 fallback：docker compose pull sks-server sks-ai sks-web（显式列服务名，绕开只有 build: 的 gateway）
docker compose up -d                    # 按 depends_on 顺序起：pg → sks-server(Flyway)/sks-web → sks-ai → nginx（gateway）
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
  - `AiClient` record（sks-server 仓）与 pydantic model（sks-ai 仓）字段变更 + 共享表 schema 变更（**sks-server 仓 Flyway**，打进镜像）靠 `docs/API_CONTRACT.md`（sks-ai 仓，§6 归属表）约束。
  - 前端 axios 调用 ↔ Java REST（`ApiResponse` 形状、ErrorCode 全表、`sks_token`/`sks_admin_token` 两套 token key、401 行为）靠 `docs/REST_CONTRACT.md`（sks-server 仓，必需）约束。
  - 镜像方案下 `.env` 单份，无跨仓密钥同步负担。
- **启动顺序（两处风险，单列）**：
  1. **sks-ai 依赖 sks-server 的 Flyway 表**：sks-ai RAG 读 `kb_card`/`analyze_task`，由 sks-server 镜像启动时 Flyway 建。**缓解**：compose `sks-ai depends_on: sks-server healthy`（§5.4）；独立重启 sks-ai 用 `docker compose up -d --no-deps sks-ai` 或 `restart sks-ai`。
  2. **checkpointer 无懒重试**：`/health 仍 UP` 兜底只覆盖 asyncpg 池（有懒重试）。`checkpointer` 只启动时初始化一次、无懒重试。若 sks-ai 先于 pg 起来，interview 端点一直坏而 `/health` UP。**缓解**：compose `depends_on` 保证 pg 先健康；补懒重试标 out-of-scope（§9），但风险写明。
- **镜像 tag 漂移**：deploy compose 用 `:latest` 有"本地缓存不更新"风险，故钉具体 tag（§5.3）。CI 推 GHCR 用默认 `GITHUB_TOKEN`（workflow 配 `packages: write`）。GHCR 命名空间自动 lowercase owner：仓库 URL `WangBuer1984/sks-web`（mixed）但镜像路径 `ghcr.io/wangbuer1984/sks-web`（lowercase），两者都对，勿混淆。
- **GHCR 国内可达性（选 GHCR 时未纳入考量，此处补上）**：部署机若在中国大陆，ghcr.io 可达性不稳定，且**不吃 Docker Hub 加速器**——OPS.md §8 的阿里云 registry-mirrors 只对 docker.io 生效。CI 侧无此问题（GitHub runner 构建/推送都在 GitHub 网内）。注意 **ping 通 ≠ pull 通**：pull 的 manifest 请求走 `ghcr.io:443`，但下载镜像层会 302 跳 `pkg-containers.githubusercontent.com`（blob 后端），国内典型卡点是这一跳。**拆分动手前即可在部署机预验**（不必等 7.3 出镜像；✅ **2026-07-29 已在目标阿里云服务器三条全过**：`ghcr.io/v2/` 返回 401、blob 后端返回 400（均为可达的预期应用层响应），`docker pull ghcr.io/astral-sh/uv:latest` 实拉成功——整条链验证通过，**此风险退役**；gate 2 时只剩验 private package 认证。部署机 Docker 已装好：docker-ce 需 `--setopt=install_weak_deps=False` 跳过 rootless-extras 下载失败，此参数已写进 OPS.md 部署机初始化节的要求里）：
  ```bash
  curl -sS -o /dev/null -w '%{http_code}\n' --max-time 8 https://ghcr.io/v2/   # 返回 401 = 通（匿名未授权是预期）
  curl -sS -o /dev/null -w '%{http_code}\n' --max-time 8 https://pkg-containers.githubusercontent.com/   # blob 后端可达性
  docker pull ghcr.io/astral-sh/uv:latest   # 终极验证：实拉小型公共 GHCR 镜像，成功 = 整条链通
  ```
  预验通过后此风险退役，§7.4 gate 2 的实拉只剩验 401 认证（private package 的 PAT / public 设置）。若预验不可达，fallback 两条：镜像改推/双推**阿里云 ACR 个人版**（免费、国内直连，CI 加一个 push 目标 + compose 换 image 前缀），或 CI 产物 `docker save` → scp → 部署机 `docker load`（无 registry 依赖，牺牲 pull 的便利）。别等 7.4 删完三目录才发现拉不动镜像。
- **5 容器多一跳**：静态请求 `浏览器→gateway→sks-web` 多一跳（同机可忽略）；多维护 `nginx depends_on sks-web healthy` 一条。换得前端与另两服务同款发版 ergonomics。

## 9. 不在本次范围

- 不改三服务的业务逻辑（sks-server AiClient / sks-ai 端点 / 前端页面）——只搬 + 文档化契约 + Dockerfile/uv.lock 修复。
- 不补 checkpointer 懒重试（标 out-of-scope，风险写进 §8）。
- 不拆 Postgres（保留共享单库，deploy 仓 compose 管）。
- **gateway 不镜像化**（本地 build，`nginx:alpine + 两文件`秒级，无 npm 依赖，无 CI/registry）。
- 不做 deploy 仓的自动化 tag→镜像更新（手动 bump compose 里 image tag；后续可加 Renovate/watch）。
- **未来路径**：若前端要独立团队/CDN 托管，可再把 sks-web 静态托管迁出 gateway（直接 CDN/sks-web:80 对公网，gateway 只留 /api/ 反代+TLS）。
