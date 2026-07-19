# 随口说 MVP 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从零搭建「随口说」MVP——C 端手机号登录 + 额度账本、管理端人工开通后台、知识库 + RAG 文案创作、定位访谈、对标拆解（拆视频/拆账号）、发布复盘状态机——达到可上线运行、核心链路可测试。

**Architecture:** 双服务：Java（Spring Boot 3，唯一公网入口，负责鉴权/额度/CRUD/状态机/定时任务）+ Python（FastAPI + LangGraph，内网 AI 服务，无业务状态）。前端 React SPA。数据 PostgreSQL 16 + pgvector 单库承载业务表 + 向量 + LangGraph 检查点。无流式：生成完整稿 → 内容安全审核 → 一次性返回。

**Tech Stack:** Java 21 / Spring Boot 3.x / MyBatis-Plus / Spring Security(JWT)；Python 3.12 / FastAPI / LangGraph / langchain-openai；React 18 / Vite / TypeScript / Tailwind CSS；PostgreSQL 16 + pgvector；智谱 GLM + embedding-3；阿里云 SMS/ASR/内容安全；TikHub 数据 API；Docker Compose（nginx/java/python/postgres）。

**依据：** `docs/superpowers/specs/2026-07-19-suikoushuo-tech-design.md`（下称「设计文档」）、`随口说PRD .md`、`随口说原型-07191700.html`、`随口说后台管理原型-admin.html`。

## Global Constraints

以下为项目级约束，**每个任务都隐式包含**，实现时不得违背：

- **语言/框架版本**：Java 21、Spring Boot 3.2+、MyBatis-Plus 3.5+、Python 3.12、FastAPI 0.11x、React 18、Vite 5、PostgreSQL 16、pgvector 0.7+。
- **不引入**：Redis、消息队列、微服务、K8s。验证码/频控/异步任务全部用 Postgres 表 + Java `@Scheduled` 轮询解决。
- **无流式输出**：所有**展示给用户的 LLM 自然语言产出**（稿件、卡片、访谈问题与档案、拆解文本、归因文本）必须「生成完整结果 → 内容安全审核通过 → 一次性 JSON 返回」，前端用进度动画缓解等待。禁止 SSE / 打字机。
- **AI 栈单一厂商**：所有 LLM 调用走智谱 GLM（OpenAI 兼容协议），向量用智谱 embedding-3，**固定 1024 维**。模型型号只在 Python `llm/` 配置层出现，业务代码不硬编码型号。
- **Java 是唯一公网入口**：Python 服务不暴露公网，只接受带正确 `X-Service-Token` 的内网请求。每个 Java→Python 请求带 `X-Request-Id`（Java 生成）。
- **额度并发安全**：扣减一律用原子条件更新 `UPDATE credit_account SET balance = balance - :n WHERE user_id = :uid AND balance >= :n`，靠影响行数判断成败，禁止「先查后写」。
- **退款幂等**：`credit_ledger` 上 `(biz_id, biz_type, type)` 唯一约束保证重复退款不生效。
- **管理端隔离**：管理端用独立表 `admin_user`，接口统一 `/api/admin/**` 前缀 + 独立 Spring Security 过滤器链，JWT 与 C 端用不同签名密钥/claim，两侧 token 互不通用。管理端无注册入口，账号由数据库迁移种子写入（密码哈希取自环境变量）。
- **色彩/视觉**：前端沿用纸感规范 `#f4f1e9`（底）/ `#8a5a2b`（主色）/ `Noto Serif SC`（衬线标题），落为 Tailwind 主题变量。
- **密钥管理**：数据库密码、GLM key、TikHub key、阿里云 key、服务间共享密钥、JWT 密钥全部走 `.env` 注入，`.env` 不进 git（`.gitignore` 覆盖）。
- **测试重点**：`credit`（扣费/退款/幂等/并发）、复盘状态机、验证码频控必须有 JUnit 覆盖；Python 侧用 pytest 测编排逻辑（mock LLM）。
- **提交规范**：每个 Step 的 commit 用 Conventional Commits（`feat:`/`test:`/`chore:`/`fix:`）。频繁提交，一步一提。

---

## 文件结构总览（决定分包边界）

**单仓库（monorepo）布局：三工程 + 编排同处一个 Git 仓库。** 服务在运行时仍完全独立（各自容器、仅内网 REST 通信），但代码集中管理——跨服务改动一个提交搞定，一次 `docker compose up` 全量起，最适合一人全栈 + 单机冷启动：

```
sks-agent/
├── docker-compose.yml            # 四容器编排：nginx / sks-server / sks-ai / postgres
├── .env.example                  # 所有密钥占位（真实 .env 不进 git）
├── .gitignore
├── deploy/
│   └── nginx/                    # nginx.conf（HTTPS 终结 + 静态前端 + /api 反代 java）
│                                 # + Dockerfile（多阶段：node 构建 sks-web 产物 → nginx 镜像内嵌 dist）
├── sks-server/                   # Java 业务服务（Maven，含 mvnw wrapper）
│   ├── src/main/resources/
│   │   └── db/migration/         # Flyway SQL 迁移（V1__*.sql ...），Java 启动时执行
│   └── src/main/java/com/sks/
│       ├── SksServerApplication.java
│       ├── common/               # 全局异常、返回体、JWT 工具、@Scheduled 调度器、审计
│       ├── config/               # 两条 SecurityFilterChain、MyBatis-Plus、CORS
│       ├── auth/                  # C 端手机号+验证码登录
│       ├── admin/                 # 管理端账号密码登录 + 开通/补偿/统计
│       ├── user/                  # 个人中心资料
│       ├── credit/                # 额度账本（核心）
│       ├── profile/               # 定位档案
│       ├── kb/                    # 知识库 A/B/C 卡片
│       ├── topic/                 # 选题库
│       ├── analyze/               # 拆视频/拆账号编排
│       ├── script/                # 文案创作
│       ├── review/                # 发布复盘状态机
│       └── aiclient/              # 对 Python 的 HTTP 客户端（唯一出口）
├── sks-ai/                        # Python AI 服务（uv/poetry）
│   └── app/
│       ├── main.py                # FastAPI 入口 + X-Service-Token 中间件
│       ├── api/                   # 各 skill 路由
│       ├── skills/                # interview/script_gen/video_analyze/account_analyze/attribution/card_gen
│       ├── rag/                   # embedding + pgvector 检索
│       ├── llm/                   # 智谱 GLM 封装 + 档位配置
│       ├── safety/                # 阿里云内容安全封装
│       ├── datasource/            # TikHub 客户端 + ASR 转写管线
│       └── db.py                  # asyncpg 连接池（只用于 RAG/checkpointer/analyze_task）
└── sks-web/                       # React 前端（Vite）
    └── src/
        ├── api/                   # axios 实例 + 各接口封装
        ├── pages/                 # 登录/工作台/知识库/创作/拆解/复盘/管理端
        ├── components/
        └── store/                 # Zustand
```

**为什么 monorepo（而非拆多仓库）：** 服务独立 ≠ 仓库拆分——即便在一个仓库，三个服务仍是独立进程/容器、只走内网 REST、无源码依赖。对一人全栈 + 单机冷启动，monorepo 收益全中、代价不沾：跨服务改契约一个原子提交（不会出现 Java/Python 契约漂移）、一次 clone、一份 `docker-compose`（build context 直接是子目录，无需多仓库同级检出）、一份 `.env`、一套 CI；而多仓库的收益（独立团队/独立开源）本项目用不上。

**分包原则**：一起变更的代码放一起（按职责，不按技术层）；`credit` 是钱的核心，单独成包并配最厚的测试；`aiclient` 是 Java 调 Python 的唯一出口，统一超时/重试/错误码翻译。

---

# P0 · 项目骨架 + 登录 + 额度 + 人工开通后台

**阶段目标：** 四容器（nginx / Java / Python / Postgres）能起来（Python 本阶段只有健康检查，功能在 P1 起补）；C 端能注册登录看余额；管理端能登录并人工开通/补偿额度，额度加减正确落账、并发安全、可退款幂等。**这是钱的链路，测试最厚。**

### Task 0.1: 仓库骨架与容器编排

**Files:**
- Create: `docker-compose.yml`
- Create: `.env.example`
- Create: `.gitignore`
- Create: `deploy/nginx/nginx.conf`、`deploy/nginx/Dockerfile`（多阶段：构建前端 + nginx 运行时）
- Create: `sks-server/pom.xml`、`sks-server/mvnw`（Maven Wrapper）、`sks-server/Dockerfile`
- Create: `sks-server/src/main/resources/application.yml`
- Create: `sks-server/src/main/java/com/sks/SksServerApplication.java`
- Create: `sks-ai/pyproject.toml`、`sks-ai/Dockerfile`、`sks-ai/app/main.py`
- Create: `sks-web/package.json`、`sks-web/vite.config.ts`、`sks-web/index.html`、`sks-web/src/main.tsx`

**Interfaces:**
- Produces: 仓库根 `docker compose up` 起四容器；Java 暴露 `:8080`（内网），nginx 暴露 `:80/:443`；Python `:8000`（仅内网）；Postgres `:5432`（仅内网，带 pgvector）。Java 健康检查 `GET /api/health` → `{"status":"UP"}`；Python `GET /health` → `{"status":"UP"}`。

- [ ] **Step 1: 写 `.gitignore` 与 `.env.example`**

`.gitignore` 至少包含：`.env`、`**/target/`、`**/node_modules/`、`**/__pycache__/`、`**/dist/`、`*.log`。

`.env.example`（真实值填到 `.env`，不进 git）：

```dotenv
POSTGRES_DB=sks
POSTGRES_USER=sks
POSTGRES_PASSWORD=change_me
JWT_SECRET_USER=change_me_user_32bytes_min
JWT_SECRET_ADMIN=change_me_admin_32bytes_min
SERVICE_TOKEN=change_me_internal_shared_token
ADMIN_SEED_USERNAME=admin
ADMIN_SEED_PASSWORD=change_me_admin_login_pwd
TRIAL_CREDIT=3
ZHIPU_API_KEY=
TIKHUB_API_KEY=
ALIYUN_ACCESS_KEY_ID=
ALIYUN_ACCESS_KEY_SECRET=
ALIYUN_SMS_SIGN=
```

- [ ] **Step 2: 写 `docker-compose.yml`**

四服务：`postgres`（镜像 `pgvector/pgvector:pg16`，挂 volume，读 `POSTGRES_*`）、`sks-server`（build `./sks-server`，依赖 postgres，注入所有 `.env`）、`sks-ai`（build `./sks-ai`，依赖 postgres，注入 `ZHIPU/TIKHUB/ALIYUN/SERVICE_TOKEN` 与数据库 URL）、`nginx`（**build context 指向仓库根、dockerfile 用 `deploy/nginx/Dockerfile`**——多阶段构建：阶段一 node 镜像构建 `sks-web` 产物，阶段二 `nginx:alpine` COPY dist 与 `deploy/nginx/nginx.conf`；前端没有独立运行容器，产物在 nginx 镜像内。nginx 反代 `/api` → `sks-server:8080`、serve 静态前端）。`sks-server`/`sks-ai`/`postgres` 不映射公网端口，只有 `nginx` 映射 `80:80`（本地开发可加 `8080` 直连方便调试）。

- [ ] **Step 3: 生成三工程最小骨架**

Java：`pom.xml`（parent 用 `spring-boot-starter-parent`）引入 `spring-boot-starter-web`、`spring-boot-starter-security`、`spring-boot-starter-validation`、`mybatis-plus-spring-boot3-starter`、`postgresql`、`flyway-core` + `flyway-database-postgresql`、`jjwt`；测试依赖 `spring-boot-starter-test`、`org.testcontainers:junit-jupiter`、`org.testcontainers:postgresql`（后续所有 Java 测试用 Testcontainers 起 `pgvector/pgvector:pg16`，**不用 H2**，保证 SQL 方言一致）。附 Maven Wrapper（`mvn wrapper:wrapper` 生成 `mvnw`，本地与 Docker 构建都不依赖全局 mvn）。`application.yml` 配置数据源（读环境变量）、Flyway 开启、MyBatis-Plus。`SksServerApplication` 加 `@SpringBootApplication`。

Python：`pyproject.toml` 依赖 `fastapi`、`uvicorn[standard]`、`langgraph`、**`langgraph-checkpoint-postgres`（`PostgresSaver` 在此独立包，底层 psycopg，须与 `asyncpg` 并存——checkpointer 用 psycopg 连接，RAG/任务表读写用 asyncpg）**、`langchain-openai`、`asyncpg`、`psycopg[binary]`、`pgvector`、`httpx`、`pydantic-settings`。`app/main.py` 建 FastAPI 应用 + `/health`。

前端：`package.json` 装 `react`、`react-dom`、`react-router-dom`、`@tanstack/react-query`、`zustand`、`axios`、`tailwindcss`、`vite`、`typescript`。Tailwind 主题注入纸感色板。`main.tsx` 渲染一个占位路由。

- [ ] **Step 4: 写健康检查端点**

Java 建 `common/HealthController`：`@GetMapping("/api/health")` 返回 `{"status":"UP"}`。Python `main.py` 加 `@app.get("/health")` 返回 `{"status":"UP"}`。

- [ ] **Step 5: 启动验证**

Run: `docker compose --env-file .env up -d --build && sleep 20 && curl -s localhost/api/health`
Expected: 输出 `{"status":"UP"}`（经 nginx 反代，与 Step 2「仅 nginx 映射公网端口」一致）；`docker compose ps` 四容器均 `running/healthy`。

- [ ] **Step 6: Commit**

```bash
git add docker-compose.yml .env.example .gitignore deploy sks-server sks-ai sks-web
git commit -m "chore: scaffold monorepo with docker compose (java/python/web/postgres)"
```

### Task 0.2: 数据库迁移 —— 全部核心表

**Files:**
- Create: `sks-server/src/main/resources/db/migration/V1__core_schema.sql`
- Create: `sks-server/src/main/resources/db/migration/V2__seed_admin.sql`

> 迁移由 Java 用 Flyway 执行（放 `sks-server` 的 resources 下）。Python 通过 asyncpg 直连读写约定表（`kb_card`/`analyze_task`/LangGraph checkpointer 等），不做迁移。

**Interfaces:**
- Produces: 设计文档 §3.1 全部 15 张表 + pgvector 扩展 + 关键约束。后续所有任务的实体映射以这些列为准。关键：`credit_ledger` 上 `UNIQUE(biz_id, biz_type, type)`；`credit_account` 一行一用户；`recharge_order.admin_user_id` 可空。

- [ ] **Step 1: 写 `V1__core_schema.sql`（建扩展 + 全表）**

必须包含（列名将被后续任务引用，务必一致）：

```sql
CREATE EXTENSION IF NOT EXISTS vector;

-- 账号与额度
CREATE TABLE app_user (
  id BIGSERIAL PRIMARY KEY,
  phone VARCHAR(20) UNIQUE NOT NULL,
  nickname VARCHAR(50),
  industry VARCHAR(50), identity VARCHAR(50), style VARCHAR(50),
  weekly_goal INT,
  profile_completeness INT NOT NULL DEFAULT 0,
  token_version INT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE admin_user (
  id BIGSERIAL PRIMARY KEY,
  username VARCHAR(50) UNIQUE NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  name VARCHAR(50) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'active',
  last_login_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sms_code (
  id BIGSERIAL PRIMARY KEY,
  phone VARCHAR(20) NOT NULL,
  code VARCHAR(6) NOT NULL,
  expire_at TIMESTAMPTZ NOT NULL,
  err_count INT NOT NULL DEFAULT 0,
  used BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sms_phone_time ON sms_code(phone, created_at);

CREATE TABLE credit_account (
  user_id BIGINT PRIMARY KEY REFERENCES app_user(id),
  balance INT NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE credit_ledger (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES app_user(id),
  delta INT NOT NULL,
  biz_type VARCHAR(20) NOT NULL,   -- trial/recharge/bonus/compensate/generate/analyze_video/analyze_account（refund 是 type 维度，不在此列）
  biz_id VARCHAR(64) NOT NULL,
  type VARCHAR(10) NOT NULL,        -- debit/credit/refund
  memo VARCHAR(200),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (biz_id, biz_type, type)
);
CREATE INDEX idx_ledger_user ON credit_ledger(user_id, created_at);

CREATE TABLE recharge_order (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES app_user(id),
  order_type VARCHAR(20) NOT NULL DEFAULT 'recharge',  -- recharge（含注册 trial 单，开通后即充值单）/compensate；首充判定与统计不依赖 pkg 字符串
  pkg VARCHAR(20),                 -- p50/p150；免费体验单为空；补偿单形如 '补偿+5'
  amount INT NOT NULL DEFAULT 0,   -- 开通时回填 49/129，补偿单为 0
  phone_tail VARCHAR(6),
  status VARCHAR(20) NOT NULL DEFAULT 'trial',  -- trial/done
  is_first_charge BOOLEAN NOT NULL DEFAULT false,
  admin_user_id BIGINT REFERENCES admin_user(id),  -- 可空：注册自动建单时无操作人
  opened_at TIMESTAMPTZ,
  memo VARCHAR(200),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_status ON recharge_order(status, created_at);

-- 定位与知识库
CREATE TABLE positioning_profile (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES app_user(id),
  content JSONB NOT NULL,
  version INT NOT NULL DEFAULT 1,
  active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE kb_card (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES app_user(id),
  layer CHAR(1) NOT NULL,          -- A/B/C
  card_type VARCHAR(20) NOT NULL,
  title VARCHAR(100) NOT NULL,
  content JSONB NOT NULL,
  embedding vector(1024),          -- A/C 层可为空，B 层必填
  deleted BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_card_user_layer ON kb_card(user_id, layer) WHERE deleted = false;
CREATE INDEX idx_card_embedding ON kb_card USING hnsw (embedding vector_cosine_ops);

CREATE TABLE card_history (
  id BIGSERIAL PRIMARY KEY,
  card_id BIGINT NOT NULL REFERENCES kb_card(id),
  old_content JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE card_citation (
  id BIGSERIAL PRIMARY KEY,
  script_id BIGINT NOT NULL,
  card_id BIGINT NOT NULL REFERENCES kb_card(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_citation_card ON card_citation(card_id);

-- 选题、稿件与复盘
CREATE TABLE topic (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES app_user(id),
  source VARCHAR(10) NOT NULL,     -- hot/faq/benchmark/replay
  title VARCHAR(200) NOT NULL,
  rationale TEXT,
  pillar VARCHAR(50),
  status VARCHAR(20) NOT NULL DEFAULT 'open',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE script (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES app_user(id),
  topic_id BIGINT REFERENCES topic(id),
  hook JSONB, body JSONB, cta JSONB,
  platform VARCHAR(20) NOT NULL DEFAULT 'douyin',
  review_state VARCHAR(20) NOT NULL DEFAULT 'generating',  -- 生成期：generating/failed；复盘七态：draft/pending/tracking/hot/plain/flop/rejected（扣费前先插占位行拿 id，见 Task 1.4）
  publish_url VARCHAR(300),
  play_count INT,
  data_source VARCHAR(10) NOT NULL DEFAULT 'manual',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_script_user_state ON script(user_id, review_state);

CREATE TABLE analyze_task (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES app_user(id),
  task_type VARCHAR(10) NOT NULL,  -- account/video
  status VARCHAR(10) NOT NULL DEFAULT 'queued', -- queued/running/partial/done/failed
  progress INT NOT NULL DEFAULT 0,
  charged INT NOT NULL DEFAULT 0,
  input JSONB,
  result JSONB,
  error VARCHAR(300),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_task_status ON analyze_task(status, updated_at);

CREATE TABLE benchmark_video (
  id BIGSERIAL PRIMARY KEY,
  analyze_task_id BIGINT NOT NULL REFERENCES analyze_task(id),
  title VARCHAR(300),
  play_count BIGINT, fav_count BIGINT,
  transcript TEXT,
  structure JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_bench_task ON benchmark_video(analyze_task_id);

CREATE TABLE weekly_report (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES app_user(id),
  week_start DATE NOT NULL,
  content JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, week_start)     -- 周任务重跑不重复插行
);
```

- [ ] **Step 2: 写 `V2__seed_admin.sql`（种子管理员，密码占位后由应用回填）**

MVP 做法：迁移只插入一行占位行，密码哈希在应用启动时若为占位则用 `ADMIN_SEED_PASSWORD` 环境变量 BCrypt 后回填（见 Task 0.6 Step 5）。

```sql
INSERT INTO admin_user (username, password_hash, name, status)
VALUES ('__seed__', 'PLACEHOLDER', '站长本人', 'active')
ON CONFLICT (username) DO NOTHING;
```

- [ ] **Step 3: 迁移执行验证**

Run: `docker compose up -d --build sks-server && sleep 15 && docker compose exec -T postgres psql -U sks -d sks -c "\dt"`（restart 不重建镜像，新迁移文件进不去，必须 `--build`）
Expected: 列出 15 张业务表 + `flyway_schema_history`（P2 起 LangGraph checkpointer 还会自建检查点表，不必惊讶）；`\d credit_ledger` 显示 `UNIQUE (biz_id, biz_type, type)`。

- [ ] **Step 4: Commit**

```bash
git add sks-server/src/main/resources/db/migration
git commit -m "feat: core database schema (15 tables + pgvector) and admin seed"
```

### Task 0.3: 公共层 —— 统一返回体 / 全局异常 / JWT 工具

**Files:**
- Create: `sks-server/src/main/java/com/sks/common/ApiResponse.java`
- Create: `sks-server/src/main/java/com/sks/common/BizException.java`
- Create: `sks-server/src/main/java/com/sks/common/ErrorCode.java`
- Create: `sks-server/src/main/java/com/sks/common/GlobalExceptionHandler.java`
- Create: `sks-server/src/main/java/com/sks/common/JwtUtil.java`
- Test: `sks-server/src/test/java/com/sks/common/JwtUtilTest.java`

**Interfaces:**
- Produces:
  - `ApiResponse<T>{ int code; String message; T data; }`，静态 `ok(data)` / `fail(ErrorCode)`。
  - `BizException(ErrorCode code)` 运行时异常。
  - `ErrorCode` 枚举：`INSUFFICIENT_BALANCE(4001)`、`SMS_RATE_LIMIT(4002)`、`SMS_CODE_INVALID(4003)`、`AI_FAILED(5001)`、`CONTENT_BLOCKED(5002)`、`UNAUTHORIZED(4010)`、`ADMIN_UNAUTHORIZED(4011)` 等，各带 `int code` + `String msg`。
  - `JwtUtil`：`String issue(long subjectId, String audience, int tokenVersion)` / `Claims parse(String token, String audience)`。`audience` 取 `"user"` 或 `"admin"`，**用不同密钥签名**（`JWT_SECRET_USER` / `JWT_SECRET_ADMIN`），解析时校验 audience 匹配否则抛异常——这是 C 端/管理端 token 隔离的技术底座。

- [ ] **Step 1: 写 `JwtUtilTest`（先失败）**

```java
@Test
void userTokenCannotBeParsedAsAdmin() {
    JwtUtil util = new JwtUtil("user-secret-32bytes-xxxxxxxxxxxxx", "admin-secret-32bytes-xxxxxxxxxx");
    String userToken = util.issue(1L, "user", 0);
    assertThrows(RuntimeException.class, () -> util.parse(userToken, "admin"));
    assertEquals(1L, Long.parseLong(util.parse(userToken, "user").getSubject()));
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd sks-server && ./mvnw test -Dtest=JwtUtilTest`（后续 Run 命令均在 `sks-server/` 目录下执行）
Expected: 编译失败（`JwtUtil` 未定义）。

- [ ] **Step 3: 实现 `JwtUtil` 与其余公共类**

`JwtUtil` 用 jjwt，构造函数接收两个密钥，`issue` 把 `audience` 写入 claim 并用对应密钥签名，`parse` 用对应密钥验签且校验 `aud` 一致。其余类按 Interfaces 定义实现。`GlobalExceptionHandler` 用 `@RestControllerAdvice` 捕获 `BizException` → `ApiResponse.fail`，捕获校验异常 → 4000。

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw test -Dtest=JwtUtilTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add sks-server/src/main/java/com/sks/common sks-server/src/test/java/com/sks/common
git commit -m "feat: common layer (ApiResponse, error codes, JWT with audience isolation)"
```

### Task 0.4: C 端登录与个人资料 —— 手机号验证码 + 三级频控 + `/me`

**Files:**
- Create: `sks-server/src/main/java/com/sks/auth/{AuthController,AuthService,SmsCodeMapper,SmsCode}.java`
- Create: `sks-server/src/main/java/com/sks/user/{AppUser,AppUserMapper,UserController,UserService}.java`
- Test: `sks-server/src/test/java/com/sks/auth/AuthServiceTest.java`、`sks-server/src/test/java/com/sks/user/UserServiceTest.java`

**Interfaces:**
- Consumes: `JwtUtil`、`ErrorCode`、`ApiResponse`（Task 0.3）。
- Produces:
  - `POST /api/auth/send-code {phone}` → 发验证码，写 `sms_code`；频控：同手机号 1 分钟 1 条、1 小时 5 条、**1 天 10 条（PRD §11.1；达到日限当日不可再发，天然等效「锁定 24h」，无需额外锁定字段）**，超限抛 `SMS_RATE_LIMIT`。
  - `POST /api/auth/login {phone, code}` → 校验最近未使用且未过期验证码，错误 `err_count+1`；成功则 `app_user` 不存在即注册（并触发注册钩子，见 Task 0.7），签发 user JWT 返回 `{token, userId, isNew}`。
  - `AuthService.checkRateLimit(phone)`：按 `idx_sms_phone_time` 聚合 count 判断，纯 SQL 无 Redis。
  - `GET /api/user/me` → `{userId, phone, nickname, industry, identity, style, weeklyGoal, completeness, balance}`（Task 0.8 工作台消费此接口。`balance` 来自 `CreditService.balance`——该服务在 Task 0.5 才建，本任务先返回 0 占位，Task 0.5 完成后一行接线）。
  - `PUT /api/user/me {nickname?, industry?, identity?, style?, weeklyGoal?}` → 更新创作资料并重算 `profile_completeness`（已填字段数 / 5 × 100，取整）。

- [ ] **Step 1: 写 `AuthServiceTest` 与 `UserServiceTest`（先失败）**

```java
@Test
void rateLimitBlocksSecondSendWithinOneMinute() {
    authService.sendCode("13800000000");
    assertThrows(BizException.class, () -> authService.sendCode("13800000000"));
}
@Test
void loginWithWrongCodeIncrementsErrCount() {
    authService.sendCode("13800000001");
    assertThrows(BizException.class, () -> authService.login("13800000001", "000000"));
}
```

```java
@Test
void updatingProfileRecomputesCompleteness() {
    long uid = registerUser("13800000009");
    userService.update(uid, UpdateMe.of("装修老张", "全屋定制", null, null, null)); // 5 字段填 2
    assertEquals(40, userService.me(uid).completeness());
}
```

（测试统一用 `@SpringBootTest` + Testcontainers `pgvector/pgvector:pg16`——**不用 H2**，保证 SQL 方言与生产一致；依赖已在 Task 0.1 的 pom.xml 声明。）

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw test -Dtest="AuthServiceTest,UserServiceTest"`
Expected: FAIL（`AuthService`/`UserService` 未定义）。

- [ ] **Step 3: 实现 `AuthService` + `UserService` + Mapper + Controller**

`sendCode`：先 `checkRateLimit`，生成 6 位码，写 `sms_code`（`expire_at = now()+5min`），调阿里云 SMS（MVP 可先接口留桩打日志，联调时替换）。`login`：查最近一条未用未过期码比对，错误自增 `err_count` 抛 `SMS_CODE_INVALID`；成功标 `used=true`，`app_user` upsert，签发 JWT。`UserService.me/update`：按 Interfaces 实现，`completeness` 在 update 时重算落库。

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw test -Dtest="AuthServiceTest,UserServiceTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add sks-server/src/main/java/com/sks/auth sks-server/src/main/java/com/sks/user sks-server/src/test/java/com/sks/auth sks-server/src/test/java/com/sks/user
git commit -m "feat: phone+SMS login with rate limiting + user profile endpoints"
```

### Task 0.5: 额度账本（核心）—— 原子扣减 + 退款幂等

**Files:**
- Create: `sks-server/src/main/java/com/sks/credit/{CreditService,CreditAccountMapper,CreditLedgerMapper,CreditAccount,CreditLedger}.java`
- Test: `sks-server/src/test/java/com/sks/credit/CreditServiceTest.java`

**Interfaces:**
- Consumes: `ErrorCode.INSUFFICIENT_BALANCE`。
- Produces（后续 script/analyze 全依赖这些签名，务必一致）：
  - `boolean deduct(long userId, int n, String bizType, String bizId)`：原子扣减 + 写流水，余额不足抛 `BizException(INSUFFICIENT_BALANCE)`。成功 return true。
  - `void refund(long userId, int n, String bizType, String bizId)`：幂等退款，靠 `(biz_id, biz_type, type=refund)` 唯一约束，重复调用静默跳过（捕获唯一约束冲突）。
  - `void credit(long userId, int n, String bizType, String bizId, String memo)`：充值/赠送加额度（注册体验/开通/补偿用）。
  - `void ensureAccount(long userId)`：`credit_account` 不存在则插入 `balance=0` 一行（注册钩子用，`ON CONFLICT DO NOTHING`）。
  - `int balance(long userId)`。
  - 扣减 SQL：`UPDATE credit_account SET balance = balance - #{n}, updated_at = now() WHERE user_id = #{uid} AND balance >= #{n}`，`@Update` 返回影响行数；行数=0 即余额不足。**同一事务内先扣 account 成功、再插 ledger。**

- [ ] **Step 1: 写 `CreditServiceTest`（先失败，覆盖余额不足/退款幂等/并发）**

```java
@Test
void deductFailsWhenInsufficient() {
    creditService.credit(uid, 3, "recharge", "o1", null);
    assertThrows(BizException.class, () -> creditService.deduct(uid, 5, "generate", "s1"));
    assertEquals(3, creditService.balance(uid));
}

@Test
void refundIsIdempotent() {
    creditService.credit(uid, 10, "recharge", "o1", null);
    creditService.deduct(uid, 1, "generate", "s1");
    creditService.refund(uid, 1, "generate", "s1");
    creditService.refund(uid, 1, "generate", "s1"); // 重复不应多退
    assertEquals(10, creditService.balance(uid));
}

@Test
void concurrentDeductNeverOverspends() throws Exception {
    creditService.credit(uid, 10, "recharge", "o1", null);
    int threads = 20;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    AtomicInteger ok = new AtomicInteger();
    CountDownLatch latch = new CountDownLatch(threads);
    for (int i = 0; i < threads; i++) {
        int idx = i;
        pool.submit(() -> {
            try { creditService.deduct(uid, 1, "generate", "s" + idx); ok.incrementAndGet(); }
            catch (BizException ignored) {}
            finally { latch.countDown(); }
        });
    }
    latch.await();
    assertEquals(10, ok.get());          // 恰好 10 次成功
    assertEquals(0, creditService.balance(uid)); // 绝不超扣为负
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw test -Dtest=CreditServiceTest`
Expected: FAIL（`CreditService` 未定义）。

- [ ] **Step 3: 实现 `CreditService`（原子 SQL + 幂等退款）**

`deduct`：`@Transactional`，调 Mapper 原子 UPDATE，影响行数=0 抛 `INSUFFICIENT_BALANCE`；否则插 `credit_ledger(delta=-n, type='debit')`。`refund`：先按 account 加回 n，再插 `ledger(type='refund')`，用 try-catch 捕获 `DuplicateKeyException` 表示已退过则回滚加额度部分（或先查 ledger 是否存在该 refund 记录，存在则直接 return，不加额度——推荐后者，更清晰）。`credit`：加额度 + 插流水，`credit_account` 不存在则先 insert 一行 0 再更新（注册钩子已建账户，见 Task 0.7）。

> **实现要点**：`refund` 推荐「先查 `(biz_id,biz_type,type=refund)` 是否已存在，存在直接 return」，避免依赖异常控制流；唯一约束作为兜底防并发重复。

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw test -Dtest=CreditServiceTest`
Expected: PASS（含 20 线程并发用例）。

- [ ] **Step 5: Commit**

```bash
git add sks-server/src/main/java/com/sks/credit sks-server/src/test/java/com/sks/credit
git commit -m "feat: credit ledger with atomic deduction and idempotent refund"
```

### Task 0.6: 两条 Spring Security 过滤器链 + 管理端登录

**Files:**
- Create: `sks-server/src/main/java/com/sks/config/SecurityConfig.java`
- Create: `sks-server/src/main/java/com/sks/config/{UserJwtFilter,AdminJwtFilter}.java`
- Create: `sks-server/src/main/java/com/sks/admin/{AdminAuthController,AdminUserService,AdminUser,AdminUserMapper}.java`
- Create: `sks-server/src/main/java/com/sks/admin/AdminSeedRunner.java`
- Test: `sks-server/src/test/java/com/sks/admin/AdminAuthTest.java`

**Interfaces:**
- Consumes: `JwtUtil`（双密钥）。
- Produces:
  - 两条 `SecurityFilterChain`：链 A `securityMatcher("/api/admin/**")` 用 `AdminJwtFilter`（校验 admin audience）；链 B 匹配其余 `/api/**` 用 `UserJwtFilter`（校验 user audience）。`/api/auth/**`、`/api/admin/auth/login`、`/api/health` 放行。
  - `POST /api/admin/auth/login {username, password}` → BCrypt 校验 `admin_user`，成功签发 admin JWT + 更新 `last_login_at`，返回 `{token, adminId, name}`；失败抛 `ADMIN_UNAUTHORIZED`。
  - `AdminSeedRunner`（`ApplicationRunner`）：启动时若存在 `username='__seed__'` 或 password_hash 为占位，则用 `ADMIN_SEED_USERNAME`/`ADMIN_SEED_PASSWORD` 环境变量 BCrypt 后 upsert 真实站长账号。

- [ ] **Step 1: 写 `AdminAuthTest`（先失败）**

```java
@Test
void adminLoginSucceedsWithSeededCredential() {
    var resp = adminUserService.login(seedUsername, seedPassword);
    assertNotNull(resp.token());
}
@Test
void adminLoginFailsWithWrongPassword() {
    assertThrows(BizException.class, () -> adminUserService.login(seedUsername, "wrong"));
}
@Test
void userTokenRejectedOnAdminEndpoint() throws Exception {
    // MockMvc 带 user JWT 访问 /api/admin/orders → 期望 401/403
    mockMvc.perform(get("/api/admin/orders").header("Authorization", "Bearer " + userToken))
           .andExpect(status().isUnauthorized());
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw test -Dtest=AdminAuthTest`
Expected: FAIL。

- [ ] **Step 3: 实现两条链 + 管理端登录 + 种子回填**

`SecurityConfig` 定义两个 `@Bean SecurityFilterChain`（用 `@Order` 保证 admin 链先匹配）。`AdminJwtFilter`/`UserJwtFilter` 从 `Authorization: Bearer` 取 token，用 `JwtUtil.parse(token, "admin"|"user")` 校验，失败则不设置认证上下文（交由 entryPoint 返回 401）。**MVP 的过滤器不查库比对 `token_version`（避免每请求一次查询；claim 已写入版本号，V1.1 需即时失效时补一行比对即可，见设计文档 §6）**；C 端 token 过期 7 天、管理端 24h。`AdminUserService.login` 用 `BCryptPasswordEncoder.matches`。`AdminSeedRunner` 实现回填。

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw test -Dtest=AdminAuthTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add sks-server/src/main/java/com/sks/config sks-server/src/main/java/com/sks/admin sks-server/src/test/java/com/sks/admin
git commit -m "feat: dual security filter chains + admin login with seeded account"
```

### Task 0.7: 注册钩子 + 管理端人工开通（免费体验 → 已开通）+ 补偿额度

**Files:**
- Modify: `sks-server/src/main/java/com/sks/auth/AuthService.java`（注册钩子）
- Create: `sks-server/src/main/java/com/sks/admin/{AdminOrderController,RechargeOrderService,RechargeOrder,RechargeOrderMapper}.java`
- Test: `sks-server/src/test/java/com/sks/admin/RechargeOrderServiceTest.java`

**Interfaces:**
- Consumes: `CreditService.credit/ensureAccount`（Task 0.5）。
- Produces:
  - 注册钩子：`AuthService.login` 首次注册用户时，① `ensureAccount(uid)`；② 建 `recharge_order(order_type='recharge', status='trial')` 免费体验单；③ **送体验额度** `credit.credit(uid, trialCredit, biz_type=trial, biz_id=trial订单id)`——「免费体验」必须真能体验，开通前用户可实际生成几条感受价值。**条数可配置**：`application.yml` 里 `sks.trial-credit: ${TRIAL_CREDIT:3}`（环境变量注入，默认 3），业务代码用 `@Value`/`@ConfigurationProperties` 读取，不硬编码。
  - `GET /api/admin/users?phoneTail=` → 按手机尾号（后 4-6 位）模糊搜用户，返回 `[{userId, phoneMasked, balance, latestOrderStatus}]`——管理端原型的核心交互是「尾号搜索 → 多人时逐一确认 → 开通」，此接口是入口。
  - `GET /api/admin/orders?status=` → 订单列表（含 `userId`、用户手机尾号、套餐、状态、操作人）。
  - `POST /api/admin/orders/open {userId, pkg}` → 开通：套餐 `p50`→50 条 ¥49 / `p150`→150 条 ¥129（金额回填 `amount`）；调 `credit.credit(+N, biz_type=recharge, biz_id=orderId)`；**首充判定（唯一口径）**：该用户此前无 `status='done' AND order_type='recharge'` 的单即为首充，是首充则额外 `credit.credit(+10, biz_type=bonus, biz_id=同一 orderId)`（biz_type 不同，不撞唯一约束）并把本单 `is_first_charge=true`；把该用户的 trial 单转 `done` 并回填 `admin_user_id/opened_at`，复购则新建 done 单；发短信（留桩）。返回更新后余额。
  - `POST /api/admin/compensate {userId, n, memo}` → 补偿额度（服务不可用等场景，对齐管理端原型「补偿 +5」记录）：新建 `recharge_order(order_type='compensate', pkg='补偿+N', amount=0, status='done', admin_user_id, memo)` 留痕 → `credit.credit(+n, biz_type=compensate, biz_id=orderId)`。返回更新后余额。补偿单 `order_type='compensate'`，**不参与首充判定**。

- [ ] **Step 1: 写 `RechargeOrderServiceTest`（先失败）**

```java
@Test
void registrationGrantsTrialCredit() {
    long uid = registerUser("13800000001"); // 触发注册钩子
    assertEquals(3, creditService.balance(uid)); // 注册送体验额度（sks.trial-credit，测试环境按默认值 3 断言）
    assertEquals("trial", rechargeOrderService.latestOrder(uid).getStatus());
}
@Test
void firstChargeGrantsPackagePlusBonus() {
    long uid = registerUser("13800000002"); // 注册钩子：trial 单 + 3 条体验额度
    rechargeOrderService.open(uid, "p50", adminId);
    assertEquals(63, creditService.balance(uid)); // 3 体验 + 50 套餐 + 10 首充赠送
    assertEquals("done", rechargeOrderService.latestOrder(uid).getStatus());
}
@Test
void repeatChargeNoBonusAndNewOrder() {
    long uid = registerUser("13800000003");
    rechargeOrderService.open(uid, "p50", adminId);   // 首充 → 63
    rechargeOrderService.open(uid, "p150", adminId);  // 复购 +150
    assertEquals(63 + 150, creditService.balance(uid)); // 无第二次赠送
}
@Test
void compensationAddsCreditWithoutTriggeringFirstChargeBonus() {
    long uid = registerUser("13800000004"); // 注册 +3
    rechargeOrderService.compensate(uid, 5, "7/18 服务不可用补偿", adminId);
    assertEquals(8, creditService.balance(uid)); // 3 + 5，无首充赠送
    assertEquals("done", rechargeOrderService.latestOrder(uid).getStatus()); // 补偿单留痕
    rechargeOrderService.open(uid, "p50", adminId); // 补偿单不算首充
    assertEquals(8 + 50 + 10, creditService.balance(uid)); // 开通仍享首充 bonus
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw test -Dtest=RechargeOrderServiceTest`
Expected: FAIL。

- [ ] **Step 3: 实现注册钩子 + 开通服务**

在 `AuthService` 注册分支调 `creditService.ensureAccount(uid)` → `rechargeOrderService.createTrialOrder(uid)` → `creditService.credit(uid, trialCredit, "trial", trialOrderId, "注册体验")`（`trialCredit` 从 `sks.trial-credit` 配置读，默认 3）。`open`/`compensate` 按 Interfaces 逻辑实现，`@Transactional` 包住「加额度 + 改单/建单」（纯 DB 操作、无外部调用，可以同事务）。**首充判定唯一口径**：查用户名下是否已有 `status='done' AND order_type='recharge'` 的单，无则视为首充给 bonus 并把本单 `is_first_charge=true`（`order_type='compensate'` 的补偿单天然不算）。

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw test -Dtest=RechargeOrderServiceTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add sks-server/src/main/java/com/sks/admin sks-server/src/main/java/com/sks/auth sks-server/src/test/java/com/sks/admin
git commit -m "feat: trial order on registration + manual activation with first-charge bonus + compensation"
```

### Task 0.8: 前端骨架 —— 登录 / 工作台余额 / 管理端后台

**Files:**
- Create: `sks-web/src/api/{client.ts,auth.ts,admin.ts}`
- Create: `sks-web/src/store/auth.ts`（Zustand，存 token）
- Create: `sks-web/src/pages/{Login.tsx,Workbench.tsx}`
- Create: `sks-web/src/pages/admin/{AdminLogin.tsx,AdminConsole.tsx}`
- Create: `sks-web/src/router.tsx`

**Interfaces:**
- Consumes: 后端 `/api/auth/*`、`/api/admin/*`。
- Produces:
  - axios 实例：请求拦截器注入 `Authorization`；401 拦截器把当前表单存 localStorage 后跳登录（对齐 PRD §11.6）。C 端与管理端用不同 token key（`sks_token` / `sks_admin_token`）。
  - `/login` 手机号+验证码登录；`/` 工作台顶部显示余额。
  - `/admin/login` + `/admin` 管理端后台。**直接复用 `随口说后台管理原型-admin.html` 的视觉与交互**，改为 React 组件 + 接真实接口。

- [ ] **Step 1: axios 实例与 auth store**

`client.ts` 建两个 axios 实例（C 端 `/api`、管理端 `/api/admin`），各自注入对应 token；统一响应拦截解包 `ApiResponse.data`，`code!=0` 抛错。

- [ ] **Step 2: C 端登录 + 工作台余额**

`Login.tsx`：输入手机号 → 发码 → 输码登录 → 存 token 跳工作台。`Workbench.tsx`：用 TanStack Query 拉 `GET /api/user/me` + 余额，顶部展示。

- [ ] **Step 3: 管理端登录 + 后台（移植原型）**

把 `随口说后台管理原型-admin.html` 的登录页 + 开通页迁为 React：`AdminLogin.tsx` 调 `/api/admin/auth/login`；`AdminConsole.tsx` 复现原型核心链路——尾号搜索框调 `GET /api/admin/users?phoneTail=`（多人命中时逐一展示确认）→ 选中用户后展示其订单（`/api/admin/orders`）→ 开通按钮调 `/api/admin/orders/open`、补偿按钮调 `/api/admin/compensate`。保留原型的纸感样式（迁为 Tailwind class 或 CSS module）。

- [ ] **Step 4: 手动验证主链路**

Run: `docker compose up -d --build`，浏览器走：注册登录 → 工作台看到余额 3（注册体验）→ 管理端登录 → 尾号搜到该用户、看到 trial 单 → 开通 p50 → C 端刷新余额变 63（3+50+10）。
Expected: 全链路通，额度正确。

- [ ] **Step 5: Commit**

```bash
git add sks-web/src
git commit -m "feat: web skeleton - user login/workbench + admin console (from prototype)"
```

---

# P1 · 知识库 CRUD + RAG 检索 + 文案创作

**阶段目标：** 用户能建/改/删 A/B/C 层卡片（B 层即时算 embedding），能选一个选题一键生成带溯源的口播稿——完整跑通「Java 扣费 → 调 Python → 注入 A 层 + RAG 检索 B 层 → GLM 生成 → 内容安全审核 → 返回落库 → 前端展示引用卡片」。这是产品**核心价值闭环**。

**依赖：** P0（额度、登录、Python 服务骨架、`aiclient`）。

### Task 1.1: Python 服务基座 —— LLM 封装 / Embedding / 内容安全 / DB 池 / 服务鉴权

**Files:**
- Create: `sks-ai/app/config.py`（pydantic-settings 读环境变量）
- Create: `sks-ai/app/db.py`（asyncpg 池，注册 pgvector 类型）
- Create: `sks-ai/app/llm/client.py`（智谱 GLM，OpenAI 兼容）
- Create: `sks-ai/app/llm/models.py`（skill→档位映射）
- Create: `sks-ai/app/rag/embedding.py`（embedding-3，1024 维）
- Create: `sks-ai/app/safety/content_safety.py`（阿里云内容安全）
- Create: `sks-ai/app/api/deps.py`（`X-Service-Token` 校验依赖）
- Create: `sks-ai/app/api/embed.py`（`POST /ai/embed`——放本任务而非 Task 1.3：Task 1.2 的 Java KB 就要消费它，避免依赖倒挂）
- Test: `sks-ai/tests/test_llm_models.py`、`sks-ai/tests/test_safety.py`

**Interfaces:**
- Produces:
  - `llm/models.py`：`MODEL_FOR: dict[str,ModelSpec]`，key 为 skill 名（`script_gen`/`interview`/`card_gen`/`rewrite_sentence`/`video_analyze`/`account_analyze_item`/`account_analyze_summary`/`attribution`），value 含 `model`（如 `glm-4.7`/`glm-4.5-air`）+ `thinking:bool`。**唯一写模型型号的地方。**
  - `llm/client.py`：`async def chat(skill: str, messages: list[dict], json_schema: dict | None) -> dict`，按 skill 取档位调 GLM，支持结构化 JSON 输出。
  - `rag/embedding.py`：`async def embed(text: str) -> list[float]`（长度恒为 1024）。
  - `safety/content_safety.py`：`async def check(text: str) -> bool`（True=安全）。
  - `api/deps.py`：`verify_service_token(x_service_token: str = Header(...))`，不匹配 `SERVICE_TOKEN` 抛 403。
  - `POST /ai/embed {text}` → `{embedding: [1024 floats]}`（供 Java KB 写卡用，Task 1.2 消费）。

- [ ] **Step 1: 写 `test_llm_models.py`（先失败）**

```python
def test_every_skill_has_model_spec():
    from app.llm.models import MODEL_FOR
    required = {"script_gen","interview","card_gen","rewrite_sentence","video_analyze",
                "account_analyze_item","account_analyze_summary","attribution"}
    assert required <= set(MODEL_FOR.keys())
    for spec in MODEL_FOR.values():
        assert spec.model.startswith("glm-")
```

- [ ] **Step 2: 运行确认失败**

Run: `cd sks-ai && pytest tests/test_llm_models.py -v`
Expected: FAIL（`app.llm.models` 不存在）。

- [ ] **Step 3: 实现基座**

按 Interfaces 实现。`MODEL_FOR` 按设计文档模型选型表填：`script_gen/interview/video_analyze` = `glm-4.7` thinking 关；`card_gen`/`rewrite_sentence`/`account_analyze_item` = `glm-4.5-air`；`account_analyze_summary`/`attribution` = `glm-4.7` thinking 开。`chat` 用 langchain-openai `ChatOpenAI(base_url=智谱兼容端点, api_key=ZHIPU_API_KEY)`。`embed` 调智谱 embedding-3，断言返回 1024 维。

- [ ] **Step 4: 运行确认通过**

Run: `pytest tests/test_llm_models.py tests/test_safety.py -v`
Expected: PASS（safety 用 mock httpx）。

- [ ] **Step 5: Commit**

```bash
git add sks-ai/app sks-ai/tests
git commit -m "feat(ai): base layer - GLM client, embedding, content safety, service auth"
```

### Task 1.2: Java 知识库 CRUD + 卡片引用保护

**Files:**
- Create: `sks-server/src/main/java/com/sks/kb/{KbController,KbCardService,KbCard,KbCardMapper,CardHistoryMapper,CardCitationMapper}.java`
- Create: `sks-server/src/main/java/com/sks/aiclient/AiClient.java`（若 P0 未建则此处建）
- Create: `sks-web/src/pages/KB.tsx`（知识库管理页）
- Test: `sks-server/src/test/java/com/sks/kb/KbCardServiceTest.java`

**Interfaces:**
- Consumes: `AiClient.embed(text)`（调 Python `/ai/embed`，Task 1.1 已产出）。
- Produces:
  - `POST /api/kb/cards`、`PUT /api/kb/cards/{id}`、`DELETE /api/kb/cards/{id}`、`GET /api/kb/cards?layer=`。
  - B 层卡新建/编辑时同步调 `AiClient.embed` 写 `embedding` 列（PRD §7.4 立即生效）；A/C 层不算向量。
  - 删除保护：删除前查 `card_citation` 引用数，`>0` 时返回引用数要求二次确认（`?force=true` 才软删 `deleted=true`）；编辑 B 层内容时旧值写 `card_history`。

- [ ] **Step 1: 写 `KbCardServiceTest`（先失败）**

```java
@Test
void editingBLayerCardRecomputesEmbeddingAndArchivesOld() {
    long id = kbCardService.create(uid, "B", "产品", "老价格", contentV1);
    kbCardService.update(id, "新价格", contentV2);
    assertEquals(1, cardHistoryMapper.countByCard(id));   // 旧值归档
    verify(aiClient, times(2)).embed(any());              // 建+改各算一次
}
@Test
void deleteWithCitationsRequiresForce() {
    long id = kbCardService.create(uid, "B", "产品", "t", contentV1);
    cardCitationMapper.insert(new CardCitation(999L, id));
    assertThrows(BizException.class, () -> kbCardService.delete(id, false));
    kbCardService.delete(id, true); // force 通过
}
```

- [ ] **Step 2: 运行确认失败** — Run: `./mvnw test -Dtest=KbCardServiceTest` → FAIL。

- [ ] **Step 3: 实现 CRUD + embedding 同步 + 删除保护。** A/C 层 `embedding` 传 null；B 层调 `AiClient.embed` 后用 MyBatis 写 `vector` 列（自定义 TypeHandler 把 `float[]` 转 pgvector 字面量 `'[...]'`）。

- [ ] **Step 4: 运行确认通过** — Run: `./mvnw test -Dtest=KbCardServiceTest` → PASS。

- [ ] **Step 5: 前端知识库管理页**

`pages/KB.tsx`：A/B/C 三层 tab + 卡片列表（类型/标题/更新时间）+ 新建/编辑弹窗（B 层保存时提示「已重算向量，立即生效」）+ 删除时若返回引用数则弹二次确认（「有 N 篇稿件引用此卡」）。沿用纸感样式。

- [ ] **Step 6: Commit**

```bash
git add sks-server/src/main/java/com/sks/kb sks-server/src/main/java/com/sks/aiclient sks-server/src/test/java/com/sks/kb sks-web/src
git commit -m "feat: knowledge base CRUD (embedding sync, citation-guarded delete) + KB page"
```

### Task 1.3: Python RAG 检索 + 文案生成 skill

**Files:**
- Create: `sks-ai/app/rag/retrieve.py`
- Create: `sks-ai/app/skills/script_gen/__init__.py`、`sks-ai/app/skills/script_gen/graph.py`、`sks-ai/app/skills/script_gen/rewrite.py`（单句重写，复用 `llm.chat(skill="rewrite_sentence")` + `safety.check`）
- Create: `sks-ai/app/api/script_gen.py`（挂 `/ai/script_gen` 与 `/ai/rewrite_sentence` 两个路由）
- Modify: `sks-ai/app/main.py`（挂路由）
- Test: `sks-ai/tests/test_script_gen.py`、`sks-ai/tests/test_retrieve.py`

**Interfaces:**
- Consumes: `llm.chat`、`rag.embedding.embed`、`safety.check`、`db`。
- Produces:
  - `rag/retrieve.py`：`async def retrieve_b_cards(user_id, query, k=5, max_distance=0.25) -> list[Card]`，pgvector `ORDER BY embedding <=> $query_vec` 且 `WHERE (embedding <=> $query_vec) <= 0.25`。**注意 `<=>` 返回余弦「距离」不是相似度**：距离 ≤ 0.25 才等价于相似度 ≥ 0.75，阈值方向别写反（最常见的 RAG 翻车点）。
  - `POST /ai/script_gen {user_id, topic:{title,rationale}, profile:{...A层全量}, platform}` → 同步返回 `{hook, body, cta, cited_card_ids:[...]}` **或** `{blocked:true}`（内容安全命中且重写仍命中）。**三段每段是句数组 `{sentences:[{idx,text}]}`**——生成时就让 GLM 按 JSON schema 逐句输出（逐句编辑的数据基础，Java 原样落 JSONB）。流程：注入 A 层全量 + RAG 取 B 层 top5 → GLM 生成结构化三段 → `safety.check`，命中则重写一次再查，仍命中返回 blocked。**无流式。**
  - `POST /ai/rewrite_sentence {sentence, section, full_script, profile}` → `{text}` **或** `{blocked:true}`：单句「换个说法」——带上整稿与定位档案做上下文保持口吻连贯，走轻量档（skill=`rewrite_sentence`），产出过 `safety.check`。

- [ ] **Step 1: 写 `test_script_gen.py`（先失败，mock LLM 与 safety）**

```python
async def _unsafe(t): return False   # check 是 async def，桩必须也是协程，普通 lambda 会让 await 处 TypeError
async def _safe(t): return True

@pytest.mark.asyncio
async def test_blocked_content_returns_blocked_flag(monkeypatch):
    monkeypatch.setattr("app.skills.script_gen.graph.chat", fake_chat_returns_bad)
    monkeypatch.setattr("app.skills.script_gen.graph.check", _unsafe)  # 一直命中
    result = await generate_script(user_id=1, topic={"title":"x","rationale":"y"},
                                    profile={}, platform="douyin")
    assert result["blocked"] is True

@pytest.mark.asyncio
async def test_success_returns_three_sections_and_citations(monkeypatch):
    monkeypatch.setattr("app.skills.script_gen.graph.chat", fake_chat_ok)
    monkeypatch.setattr("app.skills.script_gen.graph.check", _safe)
    monkeypatch.setattr("app.skills.script_gen.graph.retrieve_b_cards", fake_retrieve_two_cards)
    result = await generate_script(user_id=1, topic={"title":"x","rationale":"y"},
                                   profile={}, platform="douyin")
    assert set(result) >= {"hook","body","cta","cited_card_ids"}
    assert result["cited_card_ids"] == [11, 22]
```

- [ ] **Step 2: 运行确认失败** — Run: `pytest tests/test_script_gen.py -v` → FAIL。

- [ ] **Step 3: 实现 retrieve + script_gen graph + 路由。** LangGraph 节点：`retrieve → generate → safety → (rewrite once) → done/blocked`。生成时 prompt 注入 A 层全量与 B 层命中卡，要求 GLM 按 `{hook,body,cta}` JSON schema 输出，`cited_card_ids` 取自 retrieve 命中的卡 id。

- [ ] **Step 4: 运行确认通过** — Run: `pytest tests/test_script_gen.py tests/test_retrieve.py -v` → PASS。

- [ ] **Step 5: Commit**

```bash
git add sks-ai/app sks-ai/tests
git commit -m "feat(ai): RAG retrieval + script generation (sentence-structured) + sentence rewrite skill"
```

### Task 1.4: Java 文案创作编排（额度事务 + 溯源落库）

**Files:**
- Create: `sks-server/src/main/java/com/sks/script/{ScriptController,ScriptService,Script,ScriptMapper}.java`
- Create: `sks-server/src/main/java/com/sks/topic/{TopicController,TopicService,Topic,TopicMapper}.java`
- Create: `sks-web/src/pages/Create.tsx`
- Modify: `sks-server/src/main/java/com/sks/aiclient/AiClient.java`（加 `scriptGen`、`rewriteSentence`）
- Test: `sks-server/src/test/java/com/sks/script/ScriptServiceTest.java`

**Interfaces:**
- Consumes: `CreditService.deduct/refund`（Task 0.5）、`AiClient.scriptGen`、`AiClient.rewriteSentence`（Task 1.3 `/ai/rewrite_sentence`）、`ProfileService.activeProfile`（P2 前先用空档案桩）。
- Produces:
  - `POST /api/scripts/generate {topicId, platform}` → 走设计文档 §4.1 事务链，**顺序解决「扣费 biz_id 从哪来」**：① 先插一行 `script(review_state='generating')` 拿到 `scriptId`（扣费流水的 biz_id 必须在扣费前就存在且稳定，退款幂等全靠它）；② 独立短事务 `deduct(uid,1,"generate",scriptId)`；③ **事务外**调 `AiClient.scriptGen`；④ 成功→回填 hook/body/cta、置 `draft`、写 `card_citation` 返回；失败（超时/异常/解析失败/blocked）→ 占位行置 `failed`、`refund(uid,1,"generate",scriptId)`、抛 `AI_FAILED`/`CONTENT_BLOCKED`。
  - **同选题免扣**：同 `topic_id` 已有**非 generating/failed** 的成功稿则不扣（PRD §4.2；失败占位行不算「成功生成过」）。
  - 三平台版本**按需生成**：默认主平台，切平台再生成、同选题不加扣。
  - 稿件管理（设计文档 §2.1 script 模块）：`GET /api/scripts?state=` 列表、`GET /api/scripts/{id}` 详情、`PUT /api/scripts/{id}/sentence {section, idx, text}` **单句手改**（改 JSONB 句数组中对应句后整列更新）。
  - `POST /api/scripts/{id}/rewrite-sentence {section, idx}` **单句 AI 重写**：取该句 + 整稿 + 定位档案调 `AiClient.rewriteSentence` → 返回新句给前端预览，用户确认后走上面的单句手改接口落库。**不扣额度**（轻量档成本可忽略；被刷再限流，V1.1）；`blocked` 翻译为 `CONTENT_BLOCKED`，原句保留。

- [ ] **Step 1: 写 `ScriptServiceTest`（先失败）**

```java
@Test
void generationFailureRefundsCredit() {
    creditService.credit(uid, 5, "recharge", "o1", null);
    when(aiClient.scriptGen(any())).thenThrow(new RuntimeException("timeout"));
    assertThrows(BizException.class, () -> scriptService.generate(uid, topicId, "douyin"));
    assertEquals(5, creditService.balance(uid)); // 已全额退回
}
@Test
void regenerateSameTopicNotCharged() {
    creditService.credit(uid, 5, "recharge", "o1", null);
    when(aiClient.scriptGen(any())).thenReturn(okScriptResult());
    scriptService.generate(uid, topicId, "douyin"); // 扣 1
    scriptService.generate(uid, topicId, "douyin"); // 同选题免扣
    assertEquals(4, creditService.balance(uid));
}
@Test
void sentenceRewriteIsFreeAndUpdatesNothingUntilConfirmed() {
    creditService.credit(uid, 5, "recharge", "o1", null);
    when(aiClient.scriptGen(any())).thenReturn(okScriptResult());
    long sid = scriptService.generate(uid, topicId, "douyin"); // 扣 1 → 4
    when(aiClient.rewriteSentence(any())).thenReturn("换了说法的新句子");
    String preview = scriptService.rewriteSentence(uid, sid, "body", 0);
    assertEquals(4, creditService.balance(uid));            // 单句重写不扣额度
    assertNotEquals(preview, scriptService.get(sid).bodySentence(0)); // 预览不落库，确认才写
}
```

- [ ] **Step 2: 运行确认失败** — Run: `./mvnw test -Dtest=ScriptServiceTest` → FAIL。

- [ ] **Step 3: 实现编排。** **事务边界（Spring 经典坑，照此写）**：`generate()` 方法本身**不加 `@Transactional`**；扣费与退款各用 `REQUIRES_NEW`（或 `TransactionTemplate`）独立短事务提交；30-60s 的 `AiClient.scriptGen` HTTP 调用必须在任何事务之外——否则长事务占住连接池连接，且失败时 `refund` 会在 rollback-only 事务里执行。用「该 topic 是否已有非 generating/failed 的 script」判免扣。blocked 结果翻译为 `CONTENT_BLOCKED`。

- [ ] **Step 4: 运行确认通过** — Run: `./mvnw test -Dtest=ScriptServiceTest` → PASS。

- [ ] **Step 5: 前端创作页 + 手动验证**

前端 `pages/Create.tsx`：选选题 → 点生成 → 展示多阶段进度动画（检索知识库 → 撰写 → 安全审核，对齐原型）→ 三段稿**逐句渲染**：悬浮/点选某句出现两个操作——「编辑」（就地改文字，保存调 `PUT .../sentence`）与「换个说法」（调 `POST .../rewrite-sentence`，新句预览、可采用/放弃/再换）+ 右栏引用卡片 + 历史稿件列表入口。手动验证扣费、溯源、单句手改与 AI 重写。

- [ ] **Step 6: Commit**

```bash
git add sks-server/src/main/java/com/sks/script sks-server/src/main/java/com/sks/topic sks-server/src/main/java/com/sks/aiclient sks-server/src/test sks-web/src
git commit -m "feat: script generation orchestration (credit tx + citation) + sentence-level editing UI"
```

### Task 1.5: 补卡（`card_gen` skill + 缺口/冲突检测）

**Files:**
- Create: `sks-ai/app/skills/card_gen/graph.py`、`sks-ai/app/api/card_gen.py`
- Modify: `sks-server/src/main/java/com/sks/kb/KbCardService.java`（补卡入口）
- Modify: `sks-server/src/main/java/com/sks/aiclient/AiClient.java`（加 `cardGen`，Task 4.2 爆款出 C 层卡也复用此方法）
- Test: `sks-ai/tests/test_card_gen.py`、`sks-server/src/test/java/com/sks/kb/CardGenTest.java`

**Interfaces:**
- Consumes: `llm.chat`（skill=`card_gen`）、`safety.check`（UGC 过审）、`KbCardService`、`CardHistoryMapper`。
- Produces:
  - `POST /ai/card_gen {user_id, raw_text, target_layer}` → `{cards:[{card_type,title,content}], gaps:[...], conflicts:[{card_id, reason}]}`：先对 `raw_text` 调 `safety.check`（**UGC 过审**，设计文档 §5.1，命中返回 `{blocked:true}`），再把大白话抽成结构化卡；缺口检测（缺哪类卡）；与现有卡冲突检测。
  - Java 侧 `AiClient.cardGen(userId, rawText, targetLayer)`：对应上述接口的客户端封装。
  - Java `POST /api/kb/supplement {rawText, layer}`：调 card_gen → 冲突项要求用户确认（确认覆盖时旧值写 `card_history`，PRD §11.4）→ 无冲突直接建卡（B 层同步算 embedding，复用 Task 1.2）。

- [ ] **Step 1: 写 `test_card_gen.py` + `CardGenTest`（先失败）** — Python 断言抽卡+缺口+冲突结构；Java 断言冲突覆盖时旧值归档 `card_history`。
- [ ] **Step 2: 运行确认失败** — Run: `pytest tests/test_card_gen.py -v` 与 `./mvnw test -Dtest=CardGenTest` → FAIL。
- [ ] **Step 3: 实现 card_gen skill + Java 补卡入口（冲突确认流）。**
- [ ] **Step 4: 运行确认通过** — 两侧测试 → PASS。
- [ ] **Step 5: Commit**

```bash
git add sks-ai/app/skills/card_gen sks-ai/app/api/card_gen.py sks-ai/tests/test_card_gen.py sks-server/src/main/java/com/sks/kb sks-server/src/main/java/com/sks/aiclient sks-server/src/test/java/com/sks/kb/CardGenTest.java
git commit -m "feat: card supplement (card_gen skill + gap/conflict detection)"
```

### Task 1.6: 查重（SimHash 本地，命中不阻断）

**Files:**
- Create: `sks-server/src/main/java/com/sks/script/DedupChecker.java`
- Modify: `sks-server/src/main/java/com/sks/script/ScriptService.java`（生成后查重）
- Test: `sks-server/src/test/java/com/sks/script/DedupCheckerTest.java`

**Interfaces:**
- Produces:
  - `DedupChecker.similarity(String a, String b) -> double`（SimHash 汉明距离或关键词 Jaccard，Java 本地零成本）。
  - `DedupChecker.findSimilar(long userId, String newBody, double threshold) -> Optional<Long>`：在同用户历史稿件内比对，返回最相似稿 id。
  - 生成结果返回体带 `dedupWarnScriptId`（命中则前端顶部黄条提示 + 「换角度」按钮，**不阻断**，PRD §11.2）。

- [ ] **Step 1: 写 `DedupCheckerTest`（先失败）** — 断言近乎相同文本相似度高于阈值、明显不同则低于阈值。
- [ ] **Step 2: 运行确认失败** — Run: `./mvnw test -Dtest=DedupCheckerTest` → FAIL。
- [ ] **Step 3: 实现 SimHash + 接入 ScriptService（命中仅告警）。**
- [ ] **Step 4: 运行确认通过** — Run: `./mvnw test -Dtest=DedupCheckerTest` → PASS。
- [ ] **Step 5: Commit**

```bash
git add sks-server/src/main/java/com/sks/script sks-server/src/test/java/com/sks/script/DedupCheckerTest.java
git commit -m "feat: local SimHash dedup warning (non-blocking) on generated scripts"
```

### Task 1.7: 选题库四路聚合

**Files:**
- Modify: `sks-server/src/main/java/com/sks/topic/TopicService.java`
- Create: `sks-server/src/main/java/com/sks/topic/HotTopicJob.java`（`@Scheduled`，依赖 P3 datasource）
- Test: `sks-server/src/test/java/com/sks/topic/TopicServiceTest.java`

**Interfaces:**
- Consumes: `AiClient.hotBoard`（P3 Task 3.2 产出 `GET /ai/hot_board`）、`AiClient.embed`（Task 1.1，热点匹配打分用）、`KbCardService`（faq 路取 FAQ 卡）、拆解结果（benchmark 路，Task 3.3 完成拆账号后把规律归纳中的选题建议写 `topic(source=benchmark)`）、复盘续集（replay 路，P4）。
- Produces:
  - `GET /api/topics?source=` 聚合四路：`hot`（TikHub 热点榜定时拉取 → 对热点标题调 `AiClient.embed` 算向量，Java 直接 SQL 在 `kb_card` B 层上做 pgvector 余弦匹配打分——**复用既有 embed 接口 + 数据库，不新增 Python 匹配端点**）、`faq`（用户 FAQ 卡转选题）、`benchmark`（拆解产出转选题）、`replay`（爆款续集）。
  - 按内容支柱配比排序（`pillar`）。
  - `HotTopicJob`：`@Scheduled` 定时拉热点榜入 `topic(source=hot)`。**此 Job 依赖 P3 的 `hot_board`，实现顺序上放 P3 之后接线，但选题聚合的其余三路不依赖 P3。**

- [ ] **Step 1: 写 `TopicServiceTest`（先失败）** — 断言四路来源都能查询、按 pillar 排序、hot 路 mock 热点榜后入库。
- [ ] **Step 2: 运行确认失败** — Run: `./mvnw test -Dtest=TopicServiceTest` → FAIL。
- [ ] **Step 3: 实现四路聚合 + HotTopicJob（hot 路接线待 P3 datasource 就绪）。**
- [ ] **Step 4: 运行确认通过** — Run: `./mvnw test -Dtest=TopicServiceTest` → PASS。
- [ ] **Step 5: Commit**

```bash
git add sks-server/src/main/java/com/sks/topic sks-server/src/test/java/com/sks/topic/TopicServiceTest.java
git commit -m "feat: topic library four-source aggregation (hot/faq/benchmark/replay)"
```

---

# P2 · 定位访谈（LangGraph 多轮）

**阶段目标：** 用户能走完校准访谈（猜人设 → 确认 → 5-8 问 → 出档案 → 确认生效），过程支持退出后断点续答；确认后产出定位档案（写 `positioning_profile`）+ 自动拆出的 A 层卡片（写 `kb_card`）。**校准不消耗额度**（PRD §4.2）。

**依赖：** P1（Python 基座、kb 写卡、LangGraph 已在依赖）。

### Task 2.1: Python 定位访谈 LangGraph 状态机 + Postgres checkpointer

**Files:**
- Create: `sks-ai/app/skills/interview/graph.py`、`sks-ai/app/skills/interview/state.py`
- Create: `sks-ai/app/api/interview.py`
- Create: `sks-ai/app/api/asr.py`（语音回答转文字：阿里云**一句话识别**，与 P3 拆解转写的「录音文件识别」是不同 API——短音频同步、长音频异步批量）
- Modify: `sks-ai/app/main.py`（挂路由 + 初始化 checkpointer）
- Test: `sks-ai/tests/test_interview.py`

**Interfaces:**
- Consumes: `llm.chat`（skill=`interview`）、LangGraph `PostgresSaver`（同一个库）。
- Produces:
  - LangGraph 状态机节点：`guess_persona → await_feedback → ask(5-8轮) → summarize → await_confirm`。用 `PostgresSaver` 持久化（**包为 `langgraph-checkpoint-postgres`，psycopg 连接**；`main.py` 启动时调 `checkpointer.setup()` 自建检查点表——这是「Python 不做迁移」的唯一例外，检查点表归 LangGraph 私有），`thread_id = f"{user_id}:{session_id}"`，天然支持断点续答（PRD §11.4）。
  - `POST /ai/interview/step {user_id, session_id, user_reply?}` → 同步返回 `{stage, question?, profile_draft?, done:bool}`。每轮一问一答，一次请求。`user_reply` 先过 `safety.check`（**UGC 过审**，设计文档 §5.1），命中返回 `{blocked:true}`，由 Java 提示用户修改后重答（不推进状态机）；LLM 生成的问题与档案文本返回前同样过 `safety.check`（全局约束：展示给用户的 LLM 产出必须过审）。
  - `GET /ai/interview/result?thread_id=` → **只读**从 checkpoint 取 `summarize` 产出（不推进状态机）——Java 的 `confirm` 靠它取数，避免「确认时再推一步状态机」的数据流断裂。
  - `POST /ai/asr`（multipart 音频，≤60s）→ `{text}`：调阿里云一句话识别同步转文字，供访谈/补卡的语音回答用（Task 2.2 消费）。识别失败返回错误码，由 Java 提示用户改用文字输入。
  - `summarize` 阶段产出 `{profile:{人设,人群,差异化,变现,红线,支柱配比}, a_cards:[{card_type,title,content}]}`。

- [ ] **Step 1: 写 `test_interview.py`（先失败，mock LLM + 内存 checkpointer）**

```python
@pytest.mark.asyncio
async def test_interview_resumes_from_checkpoint(monkeypatch):
    monkeypatch.setattr("app.skills.interview.graph.chat", scripted_chat)
    s = "sess-1"
    r1 = await interview_step(user_id=1, session_id=s, user_reply=None)   # 猜人设
    assert r1["stage"] == "await_feedback"
    r2 = await interview_step(user_id=1, session_id=s, user_reply="对")   # 进入提问
    assert r2["question"]
    # 用同一 thread_id 再次进入，状态应从 checkpoint 恢复而非重来
    r3 = await interview_step(user_id=1, session_id=s, user_reply="答")
    assert r3["stage"] in {"ask", "summarize"}
```

- [ ] **Step 2: 运行确认失败** — Run: `pytest tests/test_interview.py -v` → FAIL。

- [ ] **Step 3: 实现状态机 + checkpointer。** 测试用 `MemorySaver`，生产 `PostgresSaver` 连同一库。`summarize` 用 GLM 结构化输出档案 + A 层卡草稿。

- [ ] **Step 4: 运行确认通过** — Run: `pytest tests/test_interview.py -v` → PASS。

- [ ] **Step 5: Commit**

```bash
git add sks-ai/app sks-ai/tests
git commit -m "feat(ai): positioning interview LangGraph state machine with resumable checkpointer"
```

### Task 2.2: Java 校准编排 + 档案/ A 层卡落库 + 语音回答入口

**Files:**
- Create: `sks-server/src/main/java/com/sks/profile/{ProfileController,ProfileService,PositioningProfile,PositioningProfileMapper}.java`
- Create: `sks-web/src/pages/Calibrate.tsx`
- Modify: `sks-server/src/main/java/com/sks/aiclient/AiClient.java`（加 `interviewStep`、`interviewResult`、`asr`）
- Test: `sks-server/src/test/java/com/sks/profile/ProfileServiceTest.java`

**Interfaces:**
- Consumes: `AiClient.interviewStep`、`AiClient.interviewResult(threadId)`（Task 2.1 只读端点）、`AiClient.asr(audioBytes)`（Task 2.1 `/ai/asr`）、`KbCardService.create`（A 层卡）。
- Produces:
  - `POST /api/profile/interview {sessionId, reply?}` → 透传 Python，**不扣费**；返回当前问题/进度。Java 记录「校准进行中，第 X 步」供工作台横幅（PRD §11.4）。
  - `POST /api/profile/voice`（multipart 音频）→ `AiClient.asr` 转文字 → 文字作为该轮 reply 走 `interviewStep` 同一流程（转出文字先回显给用户确认再提交，识别错了可改）；ASR 失败提示改用文字输入，**不阻断访谈**。
  - `POST /api/profile/confirm {sessionId}` → 调 `AiClient.interviewResult` 从 checkpoint 只读取 summarize 产出（不再推状态机），`@Transactional` 写 `positioning_profile(active=true, version++)` + 批量建 A 层卡；旧 active 档案置 `active=false` 留历史。
  - `ProfileService.activeProfile(uid)`：供 P1 script_gen 注入 A 层全量（替换 P1 的空桩）。

- [ ] **Step 1: 写 `ProfileServiceTest`（先失败）**

```java
@Test
void confirmPersistsProfileAndACards() {
    when(aiClient.interviewResult(any())).thenReturn(summarizeResultWith2Cards());
    profileService.confirm(uid, "sess-1");
    assertTrue(profileService.activeProfile(uid).isPresent());
    assertEquals(2, kbCardService.countByLayer(uid, "A"));
}
@Test
void reCalibrationKeepsOldVersionInactive() {
    profileService.confirm(uid, "s1");  // v1
    profileService.confirm(uid, "s2");  // v2
    assertEquals(2, profileMapper.countByUser(uid));
    assertEquals(1, profileMapper.countActiveByUser(uid)); // 只有一条 active
}
```

- [ ] **Step 2: 运行确认失败** — Run: `./mvnw test -Dtest=ProfileServiceTest` → FAIL。

- [ ] **Step 3: 实现编排 + 落库 + 语音入口 + 用真实 `activeProfile` 替换 P1 空桩。**

- [ ] **Step 4: 运行确认通过** — Run: `./mvnw test -Dtest=ProfileServiceTest` → PASS。

- [ ] **Step 5: 前端校准页 + 手动验证**

`pages/Calibrate.tsx`：多轮对话式问答（文字输入 + 按住录音的语音输入，转出文字回显可修改后提交）→ 确认生效。手动走完（至少一轮用语音回答）并检查生成的稿件已注入定位档案（口吻更像本人）。

- [ ] **Step 6: Commit**

```bash
git add sks-server/src/main/java/com/sks/profile sks-server/src/main/java/com/sks/aiclient sks-server/src/test sks-web/src
git commit -m "feat: positioning calibration orchestration (profile + A-cards + voice input)"
```

---

# P3 · 拆视频 → 拆账号（异步任务 + TikHub/ASR）

**阶段目标：** 拆视频（粘文案→同步；粘链接→异步）与拆账号（异步）跑通，共用 `analyze_task` 表。Java 扣费建任务 → 调 Python 异步接口 → Python 后台跑并直写任务表 → Java `@Scheduled` 读表推进 → 前端轮询进度。失败/部分失败按比例退额度。

**依赖：** P1（Python 基座、额度）、`datasource`（TikHub + ASR）。

### Task 3.1: Python 数据源 —— TikHub 客户端 + ASR 转写管线

**Files:**
- Create: `sks-ai/app/datasource/tikhub.py`
- Create: `sks-ai/app/datasource/transcribe.py`
- Test: `sks-ai/tests/test_tikhub.py`（mock httpx）

**Interfaces:**
- Produces:
  - `tikhub.py`：`async def account_top_videos(url, n=20) -> list[VideoMeta]`（标题/播放/收藏/下载直链）、`async def video_meta(url) -> VideoMeta`、`async def precheck(url) -> {reachable:bool, video_count:int}`、`async def hot_board() -> list[HotItem]`。**基址必须用 `https://api.tikhub.dev`**（主域名被墙）。
  - `transcribe.py`：`async def transcribe(download_url) -> str`（下载音频 → 阿里云录音文件识别 → 完整文案；临时文件转写后即删）。

- [ ] **Step 1: 写 `test_tikhub.py`（先失败，mock httpx 响应）** — 断言 `account_top_videos` 正确解析出 N 条 `VideoMeta`、`precheck` 返回可访问性与条数、base_url 为 `api.tikhub.dev`。

- [ ] **Step 2: 运行确认失败** — Run: `pytest tests/test_tikhub.py -v` → FAIL。

- [ ] **Step 3: 实现客户端 + 转写管线**（httpx 异步、超时重试、错误转领域异常 `DataSourceError`）。

- [ ] **Step 4: 运行确认通过** — Run: `pytest tests/test_tikhub.py -v` → PASS。

- [ ] **Step 5: Commit**

```bash
git add sks-ai/app/datasource sks-ai/tests/test_tikhub.py
git commit -m "feat(ai): TikHub client (api.tikhub.dev) + ASR transcription pipeline"
```

### Task 3.2: Python 拆视频 / 拆账号 skill（写回 analyze_task）

**Files:**
- Create: `sks-ai/app/skills/video_analyze/graph.py`
- Create: `sks-ai/app/skills/account_analyze/graph.py`
- Create: `sks-ai/app/api/analyze.py`
- Test: `sks-ai/tests/test_account_analyze.py`

**Interfaces:**
- Consumes: `tikhub`、`transcribe`、`llm.chat`、`db`。
- Produces:
  - `POST /ai/analyze/precheck {url}` → **同步**返回 `{reachable, video_count}`（封装 `tikhub.precheck`；Java 扣费前置判断的入口，Task 3.3 消费）。
  - `GET /ai/hot_board` → 热点榜列表（封装 `tikhub.hot_board`；Task 1.7 的 `HotTopicJob` 消费——该 Job 在 P1 已建、留到此处接线）。
  - `POST /ai/analyze/video/text {task_id, transcript}` → **同步**返回单条结构化 `{structure, why_hot, framework, diff_hint}`（粘文案版，仅 LLM；`transcript` 为 UGC，先过 `safety.check`；结构化文本产出返回前同样过审）。
  - `POST /ai/analyze/video/link {task_id, url}` → 立即 202，后台跑「下载音频 → ASR → 结构化」并按 `task_id` 直写 `analyze_task`（`status/progress/result`）。
  - `POST /ai/analyze/account {task_id, url}` → 立即 202，后台跑：TikHub 取 TOP20 → 逐条下载音频转写 → 逐条结构化（`account_analyze_item`）写 `benchmark_video` 行 → 规律归纳（`account_analyze_summary`，文本产出过 `safety.check`）→ 迁移建议 → 结果三层写 `analyze_task.result`，TOP20 明细写 `benchmark_video`。进度分段更新 `progress`，**每次写进度必须显式 `SET updated_at = now()`（PG 没有自动更新语义，Java 的超时判定靠这列）**。异常写 `analyze_task.status='partial'/'failed'` + `error`。**状态直写同一库所以重启不丢「数据」；但 `BackgroundTasks` 是进程内执行，Python 重启后任务不会自动续跑——靠 Java 轮询的超时/停滞判定兜底退款（Task 3.3）。**

- [ ] **Step 1: 写 `test_account_analyze.py`（先失败，mock tikhub/transcribe/llm）** — 断言：跑完后 `analyze_task.status='done'`、`benchmark_video` 有 N 行、`result` 含账号画像/规律归纳/迁移建议三层；某条转写抛错时 `status='partial'` 且 `progress` 反映已完成比例。

- [ ] **Step 2: 运行确认失败** — Run: `pytest tests/test_account_analyze.py -v` → FAIL。

- [ ] **Step 3: 实现两个 skill + 后台任务（FastAPI `BackgroundTasks` 或 asyncio task），进度直写任务表。**

- [ ] **Step 4: 运行确认通过** — Run: `pytest tests/test_account_analyze.py -v` → PASS。

- [ ] **Step 5: Commit**

```bash
git add sks-ai/app sks-ai/tests/test_account_analyze.py
git commit -m "feat(ai): video/account analyze skills writing progress to analyze_task"
```

### Task 3.3: Java 拆解编排 + 任务表轮询调度 + 按比例退款

**Files:**
- Create: `sks-server/src/main/java/com/sks/analyze/{AnalyzeController,AnalyzeService,AnalyzeTask,AnalyzeTaskMapper,BenchmarkVideo,BenchmarkVideoMapper}.java`
- Create: `sks-server/src/main/java/com/sks/analyze/AnalyzeTaskPoller.java`（`@Scheduled`）
- Create: `sks-web/src/pages/Analyze.tsx`
- Modify: `sks-server/src/main/java/com/sks/aiclient/AiClient.java`（加 `analyzeAccount/analyzeVideoLink/analyzeVideoText/precheck/hotBoard`）
- Test: `sks-server/src/test/java/com/sks/analyze/AnalyzeServiceTest.java`

**Interfaces:**
- Consumes: `CreditService.deduct/refund`、`AiClient.analyzeAccount/analyzeVideoLink/analyzeVideoText/precheck`。
- Produces:
  - `POST /api/analyze/video {mode:text|link, payload}`：text 同步扣 1 直接返回；link 异步。
  - `POST /api/analyze/account {url}`：预检（调 `/ai/analyze/precheck`）→ 扣 `max(1, min(10, floor(N/2)))`（下限 1 防 N=1 免费白嫖；开始前明示）→ 建 `analyze_task(queued)` → 调 Python 异步接口（传 `task_id`）→ 返回 `taskId`。预检失败或 `N=0` 不扣费直接拒绝。
  - `GET /api/analyze/tasks/{id}` → 前端轮询进度/结果。
  - `AnalyzeTaskPoller`：`@Scheduled(fixedDelay=5000)` **轮询范围覆盖三种情况（缺一都会吞用户额度）**：① `running` 超时（5 分钟无 `updated_at` 更新）→ 判 `failed` 全额退；② `partial`（部分失败**终态**，Python 写完即不再变化）→ 按未完成条数比例退**一次**（幂等约束天然挡住二次退款，退过即跳过）；③ **stale `queued`**（受理后 1 分钟未转 `running`，说明 Python 返回 202 后即崩）→ 判 `failed` 全额退。
  - 拆账号完成（done）后，把规律归纳中的选题建议写入 `topic(source=benchmark)`（Task 1.7 benchmark 路的数据来源）。
  - 抓取阶段整体失败（`DataSourceError`）→ 全额退款 + 提示改用「拆视频（粘链接/粘文案）」逐条拆解（PRD §11.3 降级路径；MVP 不做独立的手动粘贴视频列表批量拆入口）。

- [ ] **Step 1: 写 `AnalyzeServiceTest`（先失败）**

```java
@Test
void accountPrecheckFailureDoesNotCharge() {
    creditService.credit(uid, 10, "recharge", "o1", null);
    when(aiClient.precheck(any())).thenReturn(new Precheck(false, 0));
    assertThrows(BizException.class, () -> analyzeService.startAccount(uid, "bad-url"));
    assertEquals(10, creditService.balance(uid)); // 未扣
}
@Test
void partialTaskRefundsUnfinishedProportion() {
    creditService.credit(uid, 10, "recharge", "o1", null);
    when(aiClient.precheck(any())).thenReturn(new Precheck(true, 20));
    long taskId = analyzeService.startAccount(uid, "ok-url"); // 扣 10
    analyzeTaskMapper.markPartial(taskId, /*progress*/ 50);   // 完成一半
    poller.reconcile();
    assertEquals(5, creditService.balance(uid)); // 退未完成的一半
}
```

- [ ] **Step 2: 运行确认失败** — Run: `./mvnw test -Dtest=AnalyzeServiceTest` → FAIL。

- [ ] **Step 3: 实现编排 + 轮询调度 + 按比例退款逻辑。** 退款走 `refund(uid, refundN, "analyze_account", taskId)` 幂等保证不重复退。

- [ ] **Step 4: 运行确认通过** — Run: `./mvnw test -Dtest=AnalyzeServiceTest` → PASS。

- [ ] **Step 5: 前端拆解页 + 手动验证**

`pages/Analyze.tsx`：粘链接/文案 → 异步任务展示进度条（对齐原型「可先去别处稍后回来看结果」）→ 展示 TOP20 清单（标题/播放/完整文案/结构标注）与四层结果。**逐条「深拆/仿写」按钮 V1.1 再做**（`benchmark_video` 行表已为其预留）。手动验证扣费与退款。

- [ ] **Step 6: Commit**

```bash
git add sks-server/src/main/java/com/sks/analyze sks-server/src/main/java/com/sks/aiclient sks-server/src/test sks-web/src
git commit -m "feat: analyze orchestration (async task polling + proportional refund + UI)"
```

---

# P4 · 发布复盘状态机 + 周归因

**阶段目标：** 稿件七状态机流转（draft/pending/tracking/hot/plain/flop/rejected；另有 generating/failed 两个生成期前置态不参与复盘流转）由 Java 规则判定（无 AI 判态）；手填播放量后按阈值判 hot/plain/flop；爆款出 C 层卡与续集选题、flop 看归因、rejected 回访反哺；周日定时聚合周归因卡。

**依赖：** P1（script、topic、kb C 层卡、attribution skill 需补）、P3（可选）。

### Task 4.1: Python 归因 skill（单条 + 周卡）

**Files:**
- Create: `sks-ai/app/skills/attribution/graph.py`
- Create: `sks-ai/app/api/attribution.py`
- Test: `sks-ai/tests/test_attribution.py`

**Interfaces:**
- Consumes: `llm.chat`（skill=`attribution`，thinking 开）、`safety.check`（归因文本展示给用户，返回前过审——全局约束）。
- Produces:
  - `POST /ai/attribution/single {script, play_count, baseline}` → `{diagnosis, suggestions:[...]}`。
  - `POST /ai/attribution/weekly {user_id, scripts:[...with data]}` → `{summary, wins, gaps, next_focus}`（周归因卡内容）。

- [ ] **Step 1: 写 `test_attribution.py`（先失败，mock LLM）** — 断言单条归因返回诊断+建议、周卡返回四段结构。
- [ ] **Step 2: 运行确认失败** — Run: `pytest tests/test_attribution.py -v` → FAIL。
- [ ] **Step 3: 实现归因 skill。**
- [ ] **Step 4: 运行确认通过** — Run: `pytest tests/test_attribution.py -v` → PASS。
- [ ] **Step 5: Commit**

```bash
git add sks-ai/app sks-ai/tests/test_attribution.py
git commit -m "feat(ai): attribution skill (single + weekly report)"
```

### Task 4.2: Java 复盘状态机（纯规则判态）

**Files:**
- Create: `sks-server/src/main/java/com/sks/review/{ReviewController,ReviewService,ReviewStateMachine}.java`
- Create: `sks-server/src/main/java/com/sks/review/RejectSweeper.java`（`@Scheduled`）
- Modify: `sks-server/src/main/java/com/sks/aiclient/AiClient.java`（加 `attributionSingle`、`attributionWeekly`——Task 4.3 的周归因也消费后者）
- Test: `sks-server/src/test/java/com/sks/review/ReviewStateMachineTest.java`

**Interfaces:**
- Consumes: `ScriptMapper`、`AiClient.cardGen`（Task 1.5，爆款素材→C 层卡）、`KbCardService.create`（C 层卡落库）、`TopicService.create`（source=replay）。
- Produces（客户端方法）: `AiClient.attributionSingle/attributionWeekly`（对应 Task 4.1 的两个 Python 端点）。
- Produces:
  - 状态迁移规则（**全部 Java 判定，无 AI 参与判态**）：采用→`pending`；登记链接→`tracking`；填播放量后与「近 30 天均值 × 阈值（默认 3，可调）」比较→ `hot`/`plain`/`flop`；生成 48h 未采用→`rejected`（`RejectSweeper` 扫描）。
  - `ReviewStateMachine.next(current, event, ctx)`：纯函数，非法迁移抛异常。
  - `POST /api/review/{scriptId}/adopt`、`/track {url}`、`/play {count}`、`/attribute`、`/feedback {reason}`。
  - `hot`「标记爆款素材」→ 调 card_gen 生成 C 层卡；「出续集」→ 写选题库（source=replay）。`flop`「看归因」→ 调 attribution（不扣费）。`rejected` 回访 → 反哺选题/口吻偏好。

- [ ] **Step 1: 写 `ReviewStateMachineTest`（先失败，覆盖全部合法/非法迁移）**

```java
@Test
void playCountAboveThresholdBecomesHot() {
    String s = ReviewStateMachine.classify(/*play*/ 9000, /*avg30d*/ 2000, /*threshold*/ 3);
    assertEquals("hot", s); // 9000 >= 2000*3
}
@Test
void playCountBelowMeansFlop() {
    assertEquals("flop", ReviewStateMachine.classify(500, 2000, 3));
}
@Test
void illegalTransitionThrows() {
    assertThrows(IllegalStateException.class,
        () -> ReviewStateMachine.next("draft", ReviewEvent.PLAY_COUNT, null)); // 未 tracking 不能填数
}
```

- [ ] **Step 2: 运行确认失败** — Run: `./mvnw test -Dtest=ReviewStateMachineTest` → FAIL。
- [ ] **Step 3: 实现状态机 + Sweeper + 各端点。** `classify` 阈值可配（读配置，默认 3）。`RejectSweeper` `@Scheduled` 扫 **`draft`（生成后未采用）** 超 48h 转 `rejected`——注意不能扫 `pending`（那是已采用待登记的稿子）。
- [ ] **Step 4: 运行确认通过** — Run: `./mvnw test -Dtest=ReviewStateMachineTest` → PASS。
- [ ] **Step 5: Commit**

```bash
git add sks-server/src/main/java/com/sks/review sks-server/src/main/java/com/sks/aiclient sks-server/src/test/java/com/sks/review
git commit -m "feat: review state machine (rule-based) + reject sweeper + attribution client"
```

### Task 4.3: 周归因定时任务 + 前端复盘页

**Files:**
- Create: `sks-server/src/main/java/com/sks/review/{WeeklyReportJob,WeeklyReport,WeeklyReportMapper}.java`
- Create: `sks-web/src/pages/Review.tsx`
- Test: `sks-server/src/test/java/com/sks/review/WeeklyReportJobTest.java`

**Interfaces:**
- Consumes: `AiClient.attributionWeekly`（Task 4.2 已加）、`ScriptMapper`。
- Produces:
  - `WeeklyReportJob`：`@Scheduled(cron 每周日)` 聚合各用户该周稿件数据 → 调 attribution weekly → 写 `weekly_report`。
  - `GET /api/review/weekly?week=` → 前端展示周归因卡。
  - 前端复盘页：状态看板 + 手填播放量 + 归因展示 + 周卡。

- [ ] **Step 1: 写 `WeeklyReportJobTest`（先失败）** — 断言：给定一周有数据的稿件，跑 job 后 `weekly_report` 生成一行且含四段内容。
- [ ] **Step 2: 运行确认失败** — Run: `./mvnw test -Dtest=WeeklyReportJobTest` → FAIL。
- [ ] **Step 3: 实现 job + 前端页。**
- [ ] **Step 4: 运行确认通过 + 手动走完整复盘链路** — Run: `./mvnw test -Dtest=WeeklyReportJobTest` → PASS；浏览器手动过「采用→登记→填数→判态→归因/爆款卡」。
- [ ] **Step 5: Commit**

```bash
git add sks-server/src/main/java/com/sks/review sks-web/src/pages/Review.tsx sks-server/src/test
git commit -m "feat: weekly attribution job + review UI"
```

---

# P5 · 部署与运维收尾（上线前必做，对齐设计文档 §5.3）

### Task 5.1: nginx 收尾 + 备份 + 监控

**Files:**
- Modify: `deploy/nginx/nginx.conf`（超时 + 50x 兜底 + HTTPS）
- Create: `deploy/nginx/50x.html`
- Create: `deploy/backup/pg_backup.sh`
- Create: `sks-server/src/main/java/com/sks/common/QuotaWatchJob.java`

**Interfaces:**
- Produces: 长接口不被 nginx 掐断；全站异常有兜底页；额度账本每日备份留 30 天；短信/GLM 余额告警。

- [ ] **Step 1: nginx 超时与 50x 兜底**

`nginx.conf`：`location /api/` 加 `proxy_read_timeout 300s`（**全链路超时从内到外对齐，内层短于外层**：Python 内 LLM 单次 120s × 最多 2 次（原始+1 重试）≈ 250s → Java→Python client 读超时 270s → nginx 300s，见设计文档 §5.3；若 nginx 比内层短会先掐断连接、Java 白等）；`error_page 500 502 503 504 /50x.html` 指向静态页。`50x.html`：纸感风格，文案「服务暂时不可用，已记录。加站长微信 XXX 反馈，确认属实补偿额度」（PRD §11.6）。HTTPS：certbot（Let's Encrypt）签发 + 续期 crontab，nginx 443 配置。

- [ ] **Step 2: 每日备份脚本**

`pg_backup.sh`：`docker compose exec -T postgres pg_dump -U sks sks | gzip > /backup/sks-$(date +%F).sql.gz`，上传对象存储（OSS/COS CLI），本地与远端 `find -mtime +30 -delete` 保留 30 天。宿主 crontab 每日 03:00 执行。**额度账本不可丢，此项上线前必须验证可恢复**（`gunzip -c | psql` 到临时库跑一次）。

- [ ] **Step 3: 余额监控**

`QuotaWatchJob`：`@Scheduled(cron = "0 0 9 * * *")` 每日查阿里云短信余额与智谱账户余额（各厂商余额查询 API），低于阈值（短信 <100 条 / GLM <¥20）发短信告警给站长手机（复用 SMS 通道）。外部拨测：UptimeRobot 免费版监控 `https://域名/api/health`，宕机邮件+短信提醒（控制台配置，无代码）。

- [ ] **Step 4: 验证**

Run: `curl -s https://域名/50x.html` 可见兜底页；手动 `bash deploy/backup/pg_backup.sh` 产出 gz 且能恢复到临时库；停掉 java 容器后拨测在 5 分钟内告警。
Expected: 三项全部通过。

- [ ] **Step 5: Commit**

```bash
git add deploy sks-server/src/main/java/com/sks/common/QuotaWatchJob.java
git commit -m "chore: nginx timeout/50x fallback, daily pg backup, quota watch"
```

---

## 全链路验收（P0-P5 完成后）

按设计文档 §5.2 手动过一遍主链路清单（无自动化端到端）：

- [ ] 注册 → 免费体验单自动创建 + 余额 3（注册体验）→ 尾号搜索到用户 → 管理端开通 p50 → C 端余额 63（3 + 50 + 首充赠送 10）
- [ ] 管理端补偿 +5 → 订单表留痕（`order_type=compensate`）→ C 端余额增加，且不触发/不影响首充赠送
- [ ] 校准访谈走完（含至少一轮语音回答转文字）→ 定位档案生效 + A 层卡生成 → 中途退出可续答
- [ ] 建 B 层卡（即时算 embedding）→ 生成稿件命中该卡并在右栏溯源
- [ ] 生成失败额度自动退回；同选题重新生成不扣费；逐句编辑：单句手改落库 + 单句 AI「换个说法」不扣额度且过安全审核
- [ ] 拆视频（粘文案同步 / 粘链接异步）+ 拆账号（异步，TOP20 + 四层结果；杀掉 Python 容器验证 queued/running 任务被判失败并退款）
- [ ] 采用 → 登记链接 → 填播放量 → 判 hot/plain/flop → 爆款出 C 层卡与续集选题
- [ ] 周日定时周归因卡生成
- [ ] 管理端 token 不能访问 C 端接口，反之亦然（隔离验证）
- [ ] 50x 兜底页可见；备份脚本产出可恢复；拨测告警生效（P5）

---
