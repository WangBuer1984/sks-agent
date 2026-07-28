# sks-agent 三仓拆分 + 镜像化 — 设计文档

> 日期：2026-07-28
> 状态：已通过 brainstorming（修订版），待用户复核后进 writing-plans
> 修订：从"只拆 sks-ai + 独立 compose + external 网络"改为"三仓对称 + 镜像 + GHCR + 单 compose"，更简更干净。

## 1. 目标与动机

把当前 monorepo 拆成**三个对称仓库**，sks-server 与 sks-ai 各自**独立发版**（不同版本号 / 各自 CI / 各自 git tag / 各自构建镜像），且都能**独立部署**（bump 镜像 tag、单独重启，不连带对方）。deploy 仓集中持有 compose + .env + 前端 + 部署文档。

**已排除的替代方案**：
- 不拆仓、monorepo + per-service CI（用户明确要仓库级分离 + 各自 CI/tag）。
- 只拆 sks-ai、原仓保留 sks-server+deploy（非对称）——用户选三仓对称，sks-server 也独立发版。
- 拆仓 + 各自独立 compose + external 共享网络（方案 A）——引入跨 compose 网络复杂度，且 .env 跨仓同步负担。镜像 + 单 compose 方案更简。

## 2. 关键决策（brainstorming 已定）

| 决策 | 选择 | 理由 |
|---|---|---|
| 拆仓范围 | **三仓对称**：sks-server / sks-ai / sks-agent-deploy | 两服务各自独立发版，对称最干净 |
| 部署模型 | **镜像化**：各服务仓 CI 构建+推镜像到 GHCR，deploy 仓 compose 按镜像引用 | 单 compose 单网络，DNS 名天然互通，无 external 网络复杂度；独立部署=bump tag+`up -d <服务>` |
| 镜像 registry | **GHCR（ghcr.io）** | 免费、与 GitHub 仓库集成、solo 零成本 |
| Postgres | 连同一个 pg 实例（deploy 仓 compose 管） | 保留「单库三合一」+ `analyze_task`/`kb_card` 共享表读写；拆库破坏架构，不取 |
| Git 历史 | 保留（`git subtree split` 抽离各子目录） | 追溯性好 |
| `.env` 归属 | **单份，住 deploy 仓** | compose `env_file` 运行时注入两服务镜像；跨仓 .env 同步负担整个消失 |
| 网络 | deploy 仓单 compose 单默认网络 | `postgres`/`sks-ai`/`sks-server` DNS 名天然互通，无需 external |

## 3. 仓库结构与文件归属（三仓）

### 3.1 `sks-server` 仓（Java 服务）

```
sks-server/
├── src/                      ← 原样
├── Dockerfile                ← 原样
├── pom.xml, mvnw, .mvn/      ← 原样
├── .github/workflows/ci.yml  ← 新增：test → build → push 镜像到 GHCR
├── .env.example              ← 新增：本地 dev 用（运行时由 deploy 仓 compose 注入，此文件仅本地跑参考）
├── .gitignore
└── README.md                 ← 新增：本地跑 + 镜像构建说明
```
独立发版：git tag → CI `./mvnw test` 绿 → build `ghcr.io/wangbuer1984/sks-server:<tag>` → push。
镜像构建用 `-DskipTests`（Dockerfile 已是），测试在 CI 前置 gate 跑。

### 3.2 `sks-ai` 仓（Python 服务）

```
sks-ai/
├── app/, tests/              ← 原样
├── Dockerfile, pyproject.toml, uv.lock  ← 原样
├── .github/workflows/ci.yml  ← 新增：test → build → push 镜像到 GHCR
├── .env.example              ← 新增：本地 dev 用（同上，运行时由 deploy 仓注入）
├── .gitignore
├── README.md                 ← 新增：本地跑 + 镜像构建
└── docs/
    └── API_CONTRACT.md       ← 新增：/ai/* 端点契约 + 共享表契约（sks-ai 是服务提供方，契约归它拥有）
```
独立发版：git tag → CI `uv run pytest`（带 dev 依赖）绿 → build `ghcr.io/wangbuer1984/sks-ai:<tag>`（Dockerfile `uv sync --no-dev` 不含 pytest，故测试在 CI 前置 gate 跑）→ push。
清理：`app/config.py` 删 `ALIYUN_SMS_SIGN` 字段（Python 无一处用，§4）。

### 3.3 `sks-agent-deploy` 仓（部署/编排/前端/文档）

```
sks-agent-deploy/
├── docker-compose.yml        ← 改：sks-server/sks-ai 按镜像引用，postgres/nginx 本地 build
├── .env.example              ← 单份 env 契约（运行时 .env 住这里）
├── sks-web/                  ← 前端原样（nginx 镜像 build 时用其 dist）
├── deploy/
│   ├── nginx/                ← nginx 镜像 + nginx.conf
│   ├── OPS.md, GO_LIVE_CHECKLIST.md, backup/
├── docs/                     ← PRD、tech-design、MVP plan、学习文档、本 spec
├── CLAUDE.md                 ← 更新：三仓架构说明
└── README.md                 ← 新增：如何用本仓部署全栈（compose up + env 配置 + 启动顺序）
```
原仓库改造而来：删 `sks-ai/`、`sks-server/` 目录，保留其余。GitHub 仓库名可改 `sks-agent-deploy`（或保留原名）。

## 4. .env 契约（单份住 deploy 仓，无跨仓同步）

**关键简化**：镜像 + 单 compose 方案下，`.env` 只住 **deploy 仓**一份。deploy 仓 `docker-compose.yml` 用 `env_file: .env` 把整文件注入 sks-server / sks-ai 两个容器。**不再有两份 .env 跨仓同步 SERVICE_TOKEN 的负担**——这是相对方案 A 最大的简化。

### deploy 仓 `.env.example`（单份，全量）

包含所有 key（运行时注入两服务）：

| key | 注入到 | 说明 |
|---|---|---|
| `POSTGRES_DB/USER/PASSWORD` | postgres + sks-server + sks-ai | pg 建库 + 两服务连库 |
| `DATABASE_URL` | compose 构造（不放进 deploy .env）| deploy compose `environment` 块从 `POSTGRES_USER/PASSWORD/DB` 拼出，覆盖 env_file；仅服务仓本地 `.env.example` 含（指向 `localhost:5432`）|
| `SPRING_DATASOURCE_*` | sks-server | Java 连 pg（deploy 仓 compose 的 environment 块注入，指向 `postgres:5432`）|
| `JWT_SECRET_USER/ADMIN` | sks-server | Java JWT 签发 |
| `SERVICE_TOKEN` | sks-server + sks-ai | **单份，两边一致天然保证**（同一个 .env）|
| `ADMIN_SEED_*`、`TRIAL_CREDIT` | sks-server | Java admin 种子 + 赠额度 |
| `ALIYUN_ACCESS_KEY_ID/SECRET` | sks-server + sks-ai | 同一阿里云账号（SMS/ASR/内容安全）|
| `ALIYUN_SMS_*`（sign/template）| sks-server | Java DYPNS 短信 |
| `ALIYUN_ASR_KEY`、`ALIYUN_ASR_APP_KEY` | sks-ai | Python ASR |
| `ZHIPU_API_KEY`、`TIKHUB_API_KEY` | sks-ai | Python LLM/数据 |
| `ZHIPU_BASE_URL`、`TIKHUB_BASE_URL`、`ALIYUN_CONTENT_SAFETY_ENDPOINT` | sks-ai | config.py 有默认值，`.env.example` 注释「默认 xxx，一般不改」|
| `SPRING_MAIL_*`、`SKS_ALERT_ADMIN_EMAIL` | sks-server | Java 邮件告警 |

### 服务仓的 `.env.example`（仅本地 dev 参考）

sks-server / sks-ai 仓各放一份**精简** `.env.example`，只列自己 dev 本地跑需要的 key（带注释「运行时由 deploy 仓 compose 注入，本文件仅本地调试参考」）。这是本地 `uv run uvicorn` / `./mvnw spring-boot:run` 时 `source .env` 用。

**`ALIYUN_SMS_SIGN` 清理**：Python `config.py` 声明了它但全代码库无一处使用（SMS 是 Java 的事）。拆分时从 `config.py` 字段 + deploy 仓 compose `sks-ai` 块 `environment` 传参里一并删除。

## 5. 网络 + deploy 仓的镜像化 docker-compose.yml

### 5.1 网络（单 compose 单网络，无 external 舞蹈）

deploy 仓**单 compose 管四服务**（postgres / sks-server / sks-ai / nginx），默认一个网络。`postgres`、`sks-ai`、`sks-server` DNS 名天然互通，**无需 external 共享网络**（这是镜像方案相对方案 A 的核心简化）。sks-ai `expose: 8000` 不发宿主端口，硬约束「Python 不暴露公网」原样保住，sks-server 经 compose 内网 reach。

### 5.2 deploy 仓 docker-compose.yml（镜像化）

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg16
    # ...（同现状，env 建库）

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

  nginx:
    build: { context: ., dockerfile: deploy/nginx/Dockerfile }   # 仍本地 build（含前端 dist）
    ports: ["80:80"]
    depends_on: [sks-server]
```

### 5.3 独立部署怎么操作（镜像 tag 策略）

- **deploy 仓 compose 钉具体 tag**（如 `ghcr.io/.../sks-ai:v1.2`），不用 `:latest`——`:latest` 有"本地缓存不更新"风险（§8）。
- **sks-ai 独立发版**：sks-ai 仓 git tag v1.2 → CI test 通过 → build 推 `ghcr.io/.../sks-ai:v1.2`。
- **sks-ai 独立部署**：deploy 仓改 `sks-ai.image` 的 tag 为 v1.2 → `docker compose up -d sks-ai` 只拉新镜像只重启 sks-ai（不碰 sks-server/pg/nginx；`--no-deps` 可避免顺带重启依赖）。sks-server 同理。`:latest` 仅本地 dev 临时用。
- **本地调试不受影响**：Java 用 IDEA、Python 用 PyCharm/`uv run uvicorn`、前端 `npm run dev`，全在宿主进程跑，不依赖 compose。compose 是部署用。

### 5.4 启动顺序（compose dependency 保证，见 §8 风险）

compose dependency 链：`postgres` → `sks-server`(depends postgres, Flyway 在它启动时跑) → `sks-ai`(depends sks-server healthy，即 Spring Boot 起完=Flyway 跑完=表已建) → `nginx`(depends sks-server)。单 compose `up` 自动按此序。若手动分批起，README 钉死同序。checkpointer 无懒重试的风险见 §8。

## 6. 文档归属

| 文档 | 去向 |
|---|---|
| `随口说PRD .md`、tech-design、MVP plan、`deploy/OPS.md`、`GO_LIVE_CHECKLIST.md`、学习文档、本 spec | **deploy 仓** |
| `docs/API_CONTRACT.md` | **sks-ai 仓**（服务提供方拥有）|
| `README.md`（sks-ai 仓）| 怎么本地跑（`uv sync`/`uv run uvicorn`）、镜像构建、`DATABASE_URL`/`.env` 契约、健康检查 |
| `README.md`（sks-server 仓）| 怎么本地跑（`./mvnw spring-boot:run` + `application-local.yml`/local profile）、镜像构建 |
| `README.md`（deploy 仓）| 如何用本仓部署全栈（`docker network` 不需要、`compose up` + `.env` 配置 + 启动顺序 §5.4 + 镜像 tag 更新流程）|
| `CLAUDE.md`（deploy 仓）| 架构图改成三仓：sks-server 仓 + sks-ai 仓（各自镜像发版）+ deploy 仓（compose 编排）；Java↔Python 跨仓 HTTP+X-Service-Token；删「Python packages」节、build commands 分仓 |
| `deploy/GO_LIVE_CHECKLIST.md`（deploy 仓）| 点名改「4 容器全 healthy」为「4 容器（postgres/sks-server/sks-ai/nginx）全 healthy，其中 sks-server/sks-ai 为 GHCR 镜像」 |

**API_CONTRACT.md 两个契约面**：

1. **HTTP 端点契约**：`/ai/*` 端点形状、`X-Service-Token`/`X-Request-Id` 头、请求/响应体、§5.3 超时链（Python LLM 120s×≤2 ≈ 250s < Java AiClient 270s < nginx 300s）。从现有 `AiClient.java` 注释 + pydantic models 抽取。拆仓后 Java record 与 Python model 字段漂移是第一类腐化风险，此文档是字段契约真相。

2. **共享表契约**（更隐蔽，必须单列一节）：sks-ai 直接读写 `kb_card`、`analyze_task`（外加自建 LangGraph 检查点表 `checkpoint_*`）。这些表的 schema 迁移 `V*.sql` 由 **sks-server 仓拥有**（在 `sks-server/src/main/resources/db/migration/`，被 Dockerfile `COPY src` + `package` 打进 jar、打进镜像）——**deploy 仓不含任何 Flyway 文件**，只是运行 sks-server 镜像时 Spring Boot 自动执行 Flyway。拆仓后这是第二类契约面。该节列：
   - sks-ai 依赖的表/列清单 + 语义（如 `analyze_task.progress`「已完成条数比例」语义、`kb_card.embedding` 固定 `vector(1024)` 维——之前专门钉死过的口径）。
   - 声明 schema 归属：**迁移归 sks-server 仓**（打进镜像），部署时 deploy compose 启动 sks-server 容器 → Spring Boot Flyway 执行到 V≥N（当前 V3）；**sks-ai 仓不做迁移**（含 kb_card/analyze_task 的表也由 sks-server 仓 Flyway 建）。
   - LangGraph `checkpoint_*` 表是例外（sks-ai 自己 `setup()` 建，归 sks-ai 仓管）。
   - 启动顺序约束：sks-ai 必须在 sks-server（Flyway）跑完后才服务 RAG（compose `depends_on: sks-server healthy` 保证，§5.4）。

## 7. 迁移步骤

### 7.1 抽离 sks-server 与 sks-ai 到独立仓库（带历史）
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
```

### 7.2 给两服务仓补 CI（test→build→push）+ 文档
- sks-ai 仓：加 `.github/workflows/ci.yml`（`uv run pytest` 带 dev 依赖 → 绿 → `docker build` → push GHCR；workflow 配 `packages: write`）、`.env.example`（精简，本地 dev 参考）、`.gitignore`、`README.md`、`docs/API_CONTRACT.md`；清理 `app/config.py` 删 `ALIYUN_SMS_SIGN`。
- sks-server 仓：加 `.github/workflows/ci.yml`（`./mvnw test` → 绿 → `docker build` → push GHCR）、`.env.example`（精简）、`.gitignore`、`README.md`（含 `application-local.yml` + local profile 的本地调试说明，见既有记忆 `local-idea-run-java-env`；**注意 spring.config.import 用相对路径 `optional:file:.env[.properties]` + 工作目录=仓库根，别写死老 monorepo 绝对路径**）。
- 各仓逐个 commit。

### 7.3 建两 GitHub 远程 + push
```bash
# sks-ai
cd /Users/rick/work/sks-ai
git remote add origin git@github.com:WangBuer1984/sks-ai.git
git branch -M main && git push -u origin main
# sks-server
cd /Users/rick/work/sks-server
git remote add origin git@github.com:WangBuer1984/sks-server.git
git branch -M main && git push -u origin main
```
触发 CI build 镜像到 GHCR（默认 `GITHUB_TOKEN` 即可推本仓命名空间，workflow 需配 `packages: write` 权限，见 §8）。

### 7.4 原仓库改造为 deploy 仓（**必须等 7.3 镜像 published 后**）
> gate：先确认两服务仓 CI green、`ghcr.io/wangbuer1984/sks-server:<tag>` 与 `sks-ai:<tag>` 已 published（`docker pull` 能拉到），再做本步——否则 deploy compose `up` 会 pull 失败。

```bash
cd /Users/rick/work/sks-agent
git rm -r sks-ai sks-server
# 编辑 docker-compose.yml：sks-server/sks-ai 改 image: ghcr.io/.../<svc>:<tag>（钉具体 tag，不用 :latest）
#   sks-ai depends_on 改成 sks-server healthy（§5.2/5.4，保证 Flyway 先跑）
# 编辑 .env.example：补全为单份全量（§4）
# 编辑 CLAUDE.md：三仓架构说明
# 编辑 deploy/GO_LIVE_CHECKLIST.md：「4 容器」描述更新（§6，sks-server/sks-ai 为 GHCR 镜像）
# 可选：GitHub 仓库名改 sks-agent-deploy
git commit -m "chore: 三仓拆分——本仓变为 deploy 仓（sks-server/sks-ai 见各自仓 + GHCR 镜像）"
git push
```

### 7.4.1 部署运行（写进 deploy 仓 README）
```bash
# 无需 docker network create（单 compose 单默认网络）
docker compose pull          # 拉两服务镜像
docker compose up -d        # 按 depends_on 顺序起：pg → sks-server(Flyway) → sks-ai → nginx
```

### 7.5 清理临时分支
```bash
cd /Users/rick/work/sks-agent && git branch -D split-sks-ai split-sks-server
```

## 8. 风险与回滚

- **不可逆但可恢复**：原仓 `git rm -r sks-ai sks-server` 后，历史里仍在（`git log -- sks-ai/` 可查、可 `git revert` 或从历史 checkout）。两新仓有完整历史。拆错能从 git 恢复，不丢代码。
- **执行顺序**：先抽两新仓并 push 成功（7.1-7.3），再动原仓（7.4）。抽离出问题时原仓未动，安全。
- **.env 真值不进 git**：deploy 仓 `.env` gitignored；服务仓 `.env`（本地 dev）也 gitignored。运行时 deploy 仓 `.env` 是唯一真值源。
- **跨仓契约漂移**：`AiClient` record（sks-server 仓）与 pydantic model（sks-ai 仓）字段变更 + 共享表 schema 变更（deploy 仓 Flyway）靠 `docs/API_CONTRACT.md`（sks-ai 仓）两个契约面约束。镜像方案下 `.env` 单份，无跨仓密钥同步负担。
- **启动顺序（两处风险，单列）**：
  1. **sks-ai 依赖 sks-server 的 Flyway 表**：sks-ai RAG 读 `kb_card`/`analyze_task`，这些表由 sks-server 镜像启动时 Spring Boot Flyway 建。如果 sks-ai 先于 sks-server 起来服务请求，表不存在 → RAG 报错。**缓解**：compose `sks-ai depends_on: sks-server healthy`（healthcheck `/api/health` UP = Spring Boot 已起完 = Flyway 已跑完，表已建，§5.4）；独立重启 sks-ai 用 `docker compose up -d --no-deps sks-ai` 或 `restart sks-ai`（不触发依赖重启）。
  2. **checkpointer 无懒重试**：`/health 仍 UP` 兜底**只覆盖 asyncpg 池**（有懒重试）。`checkpointer`（`_init_checkpointer`）只在启动时初始化一次、无懒重试。若 sks-ai 先于 pg 起来，interview 端点会一直坏，而 `/health` 显示 UP、`restart: unless-stopped` 不救（进程没死）。**缓解**：compose `depends_on` 保证 pg 先健康；给 checkpointer 补懒重试标为 out-of-scope（本次不做，但风险写明，别让「/health 仍 UP」读起来像全兜住了）。
- **镜像 tag 漂移**：deploy 仓 compose 用 `:latest` 有"本地缓存不更新"风险，故钉具体 tag（§5.3）。CI 推 GHCR 用默认 `GITHUB_TOKEN`（workflow 配 `packages: write` 权限，推本仓命名空间，无需 PAT）。GHCR 命名空间自动 lowercase owner：仓库 URL `WangBuer1984/sks-ai`（mixed）但镜像路径 `ghcr.io/wangbuer1984/sks-ai`（lowercase），两者都对，勿混淆。

## 9. 不在本次范围

- 不拆 sks-web（前端仍留 deploy 仓，nginx 镜像 build 时用其 dist）。
- 不改 sks-server 的 AiClient 逻辑、不改 sks-ai 的端点逻辑（只搬+文档化契约）。
- 不拆 Postgres（保留共享单库，deploy 仓 compose 管）。
- 不补 checkpointer 懒重试（标 out-of-scope，风险写进 §8）。
- 不做 deploy 仓的自动化 tag→镜像更新（手动 bump compose 里 image tag；后续可加 Renovate/watch 之类自动化）。
