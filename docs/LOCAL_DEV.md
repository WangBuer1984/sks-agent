# 本地调试运行指南（Java / Python / 前端 均在宿主跑，非 docker）

> 本文档面向「我想在 IDE / 终端里直接跑工程、打断点调试」的场景。**应用代码（Java、Python、前端）全部在宿主机本地运行**，只有 Postgres 因为 pgvector 扩展麻烦，推荐仍用 docker 起一个裸 pg 容器（也可以本地装 pg+pgvector，见 §6）。
>
> 生产部署见 `deploy/OPS.md`；全 docker 联调见 git 历史。

## 0. 拓扑与端口

```
浏览器 ── http://localhost:5173 ── Vite dev (:5173, proxy /api → :8080)
                                       │
                                  sks-server (:8080)   ← Java，IDE/终端本地跑
                                       │ HTTP + X-Service-Token
                                       ▼
                                  sks-ai (:8000)       ← Python，终端本地跑
                                       │
                                  postgres (:5432)     ← 仅 pg 用 docker（pgvector/pg16）
```

| 进程 | 跑在哪 | 端口 | 启动方式 |
|---|---|---|---|
| Postgres | **docker（仅此一个）** | 宿主 `5432` | `docker compose up -d postgres` + override 暴露端口 |
| Java sks-server | 宿主 | `8080` | IDEA 跑 `SksServerApplication` 或 `./mvnw spring-boot:run` |
| Python sks-ai | 宿主 | `8000` | `uv run uvicorn app.main:app --reload` |
| 前端 sks-web | 宿主 | `5173` | `npm run dev` |

nginx 在本地调试模式下**不需要**——前端 vite proxy 直连本地 Java 8080。

---

## 1. 前置依赖

| 工具 | 版本 | 校验 |
|---|---|---|
| Docker | 含 Compose v2 | `docker compose version` |
| JDK | 21 | `java -version` |
| Maven Wrapper | 仓库自带 | `./mvnw -v`（在 `sks-server/`） |
| Python | 3.12 | `python3 --version` |
| uv | 0.11+ | `uv --version`（装：`curl -LsSf https://astral.sh/uv/install.sh \| sh`） |
| Node | 18+ | `node -v` |

---

## 2. 第一步：起 Postgres（唯一用 docker 的部分）

`docker-compose.yml` 默认**不暴露 pg 端口**（容器内网用）。本地调试要让宿主的 Java/Python 连上，加一个 override：

`docker-compose.override.yml`（放仓库根，已 gitignore，本地自建）：
```yaml
services:
  postgres:
    ports:
      - "5432:5432"
```

起 pg + 跑 Flyway 建表（Flyway 由 Java 跑，见 §3；这里只起库）：
```bash
docker compose --env-file .env up -d postgres
# 验证
docker exec sks-postgres pg_isready -U sks -d sks
```

> ⚠️ 注意：**表结构由 Java 的 Flyway 在 Java 首次启动时建**（V1/V2/V3 三个 migration）。pg 容器刚起时库是空的，属于正常——先跑 §3 起 Java，Flyway 会自动建表 + 种 admin 账号。

---

## 3. 第二步：本地跑 Java（sks-server）

### 配置来源
`sks-server/src/main/resources/application.yml` 的 datasource **默认就是本地**：
```yaml
url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/sks}
username: ${SPRING_DATASOURCE_USERNAME:sks}
password: ${SPRING_DATASOURCE_PASSWORD:change_me}
```
所以只要 pg 在 `localhost:5432`、库名 `sks`、用户 `sks`，Java 几乎零配置。**唯一必须覆盖的**是 `sks.ai.base-url`（默认 `http://sks-ai:8000` 是容器名，本地要改 `http://localhost:8000`），以及把 `.env` 里的密钥（SERVICE_TOKEN / JWT secrets / ZHIPU / 阿里云）注入进程。

### 方式 A：终端跑（推荐先用这个验证通）
```bash
cd sks-server

# 1) 把 .env 的密钥导入当前 shell（set -a 让 source 的变量也 export）
set -a && source ../.env && set +a

# 2) 覆盖三处「本地化」变量
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/${POSTGRES_DB}"
export SPRING_DATASOURCE_USERNAME="${POSTGRES_USER}"
export SPRING_DATASOURCE_PASSWORD="${POSTGRES_PASSWORD}"
export SKS_AI_BASE_URL="http://localhost:8000"   # 关键：指向本地 Python

# 3) 跑（首次会下依赖 + 跑 Flyway 建表）
./mvnw spring-boot:run
```
看到 `Started SksServerApplication in x.xxx seconds` 即成功。验证：
```bash
curl localhost:8080/api/health        # → {"status":"UP"}（本地直连 Java，不经 nginx）
```

### 方式 B：IDEA 跑（日常打断点用）
1. 打开 `sks-server` 为 Maven 项目，等 IDEA 识别 JDK 21。
2. **Run Configuration** → 选 `SksServerApplication`（主类 `com.sks.SksServerApplication`）。
3. 在 Run Config 的 **Environment variables** 里填上面方式 A 第 2 步那 4 个 + `.env` 里的密钥；或在 IDEA 装 **EnvFile 插件**直接勾选根目录 `.env`，再补 `SKS_AI_BASE_URL=http://localhost:8000`。
4. Debug 模式启动，可在 `AiClient` / `CreditService` / 各 Controller 随意打断点。

### Flyway 首跑会做什么
- `V1__core_schema.sql`：建 ~15 张业务表（`app_user` / `credit_account` / `credit_ledger` / `script` / `kb_card` / `analyze_task` …）+ pgvector 扩展。
- `V2__seed_admin.sql`：用 `ADMIN_SEED_USERNAME/PASSWORD`（来自 `.env`）种站长账号。
- `V3__sms_scene_and_phone_change.sql`：短信 scene + 换绑相关。

改了 migration 后重启 Java 即可重跑（Flyway 增量）；**不要**手删 `flyway_schema_history` 表除非你懂后果。

---

## 4. 第三步：本地跑 Python（sks-ai）

### 配置来源
`app/config.py` 用 pydantic-settings 读 `.env`，但 **`.env` 里没有 `DATABASE_URL`**（compose 是通过 `environment:` 注入的）。本地必须显式设 `DATABASE_URL`，否则会落到默认 `postgres:postgres` 连不上。

### 终端跑
```bash
cd sks-ai

# 1) 导入 .env 密钥
set -a && source ../.env && set +a

# 2) 覆盖 DATABASE_URL（指向本地 pg，用 .env 的账密）
export DATABASE_URL="postgresql://${POSTGRES_USER}:${POSTGRES_PASSWORD}@localhost:5432/${POSTGRES_DB}"

# 3) 装依赖 + 跑
uv sync
uv run uvicorn app.main:app --reload --port 8000
```
验证：
```bash
curl localhost:8000/health          # → {"status":"UP"}
```
> ⚠️ sks-ai 的 `/health` 即使 DB 连不上也返回 UP（设计如此，不阻断启动）。真要看 DB 是否通，看启动日志有没有 `init_pool failed` / `checkpointer setup failed`，或直接调一个 RAG 端点（如 `POST /ai/embed`）。

### IDEA / PyCharm 跑
Run Config 选 FastAPI / Uvicorn：module `uvicorn`，参数 `app.main:app --reload --port 8000`，工作目录 `sks-ai`，环境变量同上（`DATABASE_URL` + source `.env`）。Python 解释器用 `sks-ai/.venv`（`uv sync` 后生成）。

---

## 5. 第四步：本地跑前端（sks-web）

```bash
cd sks-web
npm install
npm run dev          # → http://localhost:5173
```
`vite.config.ts` 已配 `/api` proxy → `http://localhost:8080`（本地 Java），纯本地模式开箱即用，**无需改任何配置**。

浏览器开 `http://localhost:5173`：
- C 端走 `/api/**`（注入 `sks_token`）
- 管理端走 `/api/admin/**`（注入 `sks_admin_token`）
- 401 自动清 token 跳登录（见 `src/api/client.ts`）

---

## 6. 可选：连 pg 都不用 docker（全本地）

如果想彻底脱离 docker，本地装 PostgreSQL 16 + pgvector 扩展：
```bash
brew install postgresql@16
brew services start postgresql@16
# pgvector 扩展
brew install pgvector
createdb sks
psql -d sks -c 'CREATE USER sks WITH PASSWORD '\''change_me'\'';'
psql -d sks -c 'GRANT ALL ON DATABASE sks TO sks;'
psql -d sks -c 'CREATE EXTENSION IF NOT EXISTS vector;'
```
其余步骤（Java/Python/前端）完全不变。**不推荐**除非你有强烈的「无 docker」诉求——pgvector 扩展在 docker 镜像里已经配好，本地装多一步且版本要对齐 pg16。

---

## 7. 启动顺序与验证清单

每次本地调试的标准启动顺序：

| 步 | 动作 | 验证 |
|---|---|---|
| 1 | `docker compose --env-file .env up -d postgres` | `docker exec sks-postgres pg_isready -U sks -d sks` → OK |
| 2 | 起 Java（终端 `./mvnw spring-boot:run` 或 IDEA Debug） | `curl localhost:8080/api/health` → `{"status":"UP"}` |
| 3 | 起 Python（`uv run uvicorn app.main:app --reload`） | `curl localhost:8000/health` → `{"status":"UP"}` |
| 4 | `cd sks-web && npm run dev` | 浏览器 `localhost:5173` 能打开登录页 |
| 5 | 端到端：浏览器登录 → 发请求 | 看 Java 控制台日志 + 断点 |

> 顺序很重要：Java 必须在 Python 之前？不强制——Java 调 Python 是按需的，Python 没起时 Java 收到的 AI 请求会失败但不影响 Java 自身启动。但 **pg 必须最先**（Java Flyway 首跑要写表）。

---

## 8. 常见调试操作

### 8.1 直连 pg 看数据
```bash
psql "postgresql://sks:change_me@localhost:5432/sks"   # 账密用 .env 的
\dt                                                    # 看表
SELECT user_id, balance FROM credit_account;            # 钱核心
SELECT * FROM credit_ledger ORDER BY id DESC LIMIT 20;  # 额度流水
SELECT id, review_state, data_source FROM script ORDER BY id DESC;  # 复盘状态机
SELECT id, status, progress, updated_at FROM analyze_task ORDER BY id DESC;  # 异步任务
```

### 8.2 跳过真短信拿 C 端 token
`.env` 配了 `ALIYUN_ACCESS_KEY_ID/SECRET` 时 `POST /api/auth/send-code` 就会真发短信（签名/端点/模板号都在 `application.yml`，无需配；AK 是唯一闸门，空则只打日志不发）。不想消耗真实短信量时，可用 dev token（项目里有发 dev token 的途径，见 AuthService）跳过登录，直接拿 JWT 调受保护接口、验证 GLM 生成链路。

> **发码报 `5003` 时先看这两条**——都不是代码问题，是配置回流：
> 1. 日志是 `isv.INVALID_PARAMETERS 签名或者模版无效` → 查有没有人往 `.env` 加回 `ALIYUN_SMS_SIGN`。非 ASCII 值经 `.env` 会被 properties 加载器按 ISO-8859-1 读成乱码，而阿里云的报错完全不提编码。`AliyunSmsAuthClient.warnIfMangled` 会在日志里直接给出修法。
> 2. 日志是 `[SMS-STUB]`（压根没发） → 查 AK 是否为空，或有人往 `.env` 加了空的 `ALIYUN_SMS_TEMPLATE_*=`（空值会覆盖 yml 默认值）。

### 8.3 管理端登录
```bash
curl -s localhost:8080/api/admin/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_SEED_USERNAME\",\"password\":\"$ADMIN_SEED_PASSWORD\"}"
# 返回 sks_admin_token，用它调 /api/admin/** 钱路径
```

### 8.4 单测
```bash
# Java（Testcontainers 起 pgvector，非 H2——会看到游离 pgvector 容器，正常）
cd sks-server && ./mvnw test
cd sks-server && ./mvnw test -Dtest=CreditServiceTest     # 钱核心最厚

# Python（mock LLM）
cd sks-ai && uv run pytest tests/test_script_gen.py -v
```

---

## 9. 常见坑

| 现象 | 原因 / 解决 |
|---|---|
| Java 起来但调 AI 接口报连接错 | `SKS_AI_BASE_URL` 没设成 `http://localhost:8000`，还在用默认容器名 `sks-ai` |
| Java 报 datasource 连不上 | pg 没起 / 端口没暴露（缺 override `5432:5432`）/ 账密与 `.env` 不符 |
| Python 报 `authentication failed` 或连 `postgres:postgres` | `DATABASE_URL` 没设，落到了 config.py 的默认值 |
| Python `/health` UP 但 RAG 调用挂 | `/health` 设计上不阻断；看启动日志 `init_pool failed` |
| Flyway 报表已存在 / 版本错 | 多半是手改过库或删过 `flyway_schema_history`；本地可 `docker compose down -v` 清库重来（会丢数据）|
| 前端登录 401 循环 | 确认走 `localhost:5173` 而非 8080；vite proxy 只在 dev server 生效 |
| 改了 Java 代码不生效 | `spring-boot:run` 默认不开热重载；用 IDEA 的 DevTools 或重启；改 `application.yml` 必须重启 |
| 改了 Python 代码不生效 | `--reload` 已开，改 `app/` 下文件会自动重载；改 `config.py` / 环境变量要 Ctrl-C 重起 |
| 测试里 `AbstractDbTest` 基类掩盖 `@Transactional` 回滚 | 动 credit / 钱代码的断言用 `NOT_SUPPORTED` + count 断言，勿依赖基类自动回滚（见记忆）|

---

## 10. 当前可联调范围（2026-07-25）

| 路径 | 本地可跑？ | 备注 |
|---|---|---|
| 四容器 health / Flyway / pgvector | ✅ | Phase 0 已验 |
| 管理端登录 / 开通 / 补偿（钱路径） | ✅ | Phase 1.2 已验 user3→63→68 |
| GLM 生成链路（script/interview/card） | ✅ 可跳跑 | ZHIPU key 已填，用 dev token 跳短信 |
| 真短信登录 / 换绑 | ❌ 卡点 | DYPNS 3 项未填：sign + 验旧 + 绑新模板码 |
| TikHub 拆账号/拆视频取数 | ⏳ | key 已填，待端到端验 |
| 阿里云 ASR / 内容安全 | ⏳ | key 已填，待验 |
