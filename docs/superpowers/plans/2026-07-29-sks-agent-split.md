# sks-agent 四仓拆分 + 镜像化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把当前 monorepo 拆成四个独立仓库——sks-server / sks-ai / sks-web 各自独立发版（git tag → CI → GHCR 镜像），原仓改造为 deploy 仓（单 compose + gateway 本地 build + 单份 .env），nginx 拆静态服务（sks-web）与网关（gateway）两职。

**Architecture:** 三服务仓各自 CI 构建+推镜像到 GHCR（ghcr.io/wangbuer1984/<svc>:<tag>），deploy 仓 compose 按镜像引用三服务 + gateway 本地 build + postgres 官方镜像，单 named 网络 `sks-net`。gateway 做 `/api/` 反代 + `/` 反代 sks-web + 超时链 + TLS + 50x 兜底页；sks-web 镜像做 SPA 静态服务。`.env` 单份住 deploy 仓，compose `env_file` 运行时注入。

**Tech Stack:** GitHub Actions / GHCR / Docker Buildx；Java 21 Spring Boot 3 / Maven Wrapper；Python 3.12 / uv / FastAPI；React 18 / Vite / npm；nginx:alpine；PostgreSQL 16 + pgvector；Docker Compose v2.22+（`--ignore-buildable`）。

**依据：** `docs/superpowers/specs/2026-07-28-sks-ai-split-design.md`（下称「拆分 spec」）。本计划是其 §7 迁移步骤的可执行化。路径布局按拆分前 monorepo（sks-ai/、sks-server/、sks-web/ 为顶层目录）。

## Global Constraints

以下为项目级约束，**每个任务都隐式包含**，实现时不得违背：

- **Java 是唯一公网入口**：Python 不暴露公网，只接受带正确 `X-Service-Token` 的内网请求；每个 Java→Python 请求带 `X-Request-Id`。
- **额度并发安全**：扣减用原子条件更新 `UPDATE credit_account SET balance = balance - :n WHERE user_id = :uid AND balance >= :n`，靠影响行数判成败，禁止先查后写；退款幂等 via `credit_ledger` `(biz_id, biz_type, type)` 唯一约束。
- **管理端隔离**：独立 `admin_user` + 独立 SecurityFilterChain + 不同 JWT secret，token 互不通用；无注册入口，Flyway 种子。
- **无流式输出**：所有展示给用户的 LLM 自然语言产出 = 生成完整 → 内容安全审 → 一次性 JSON 返回；前端用进度动画 mask 等待。
- **AI 栈单一厂商智谱 GLM**：LLM 走 GLM（OpenAI 兼容），向量 embedding-3 固定 1024 维绑 `vector(1024)` 列；型号只在 Python `llm/` 配置层。
- **不引入 Redis/MQ/微服务/K8s**：验证码/频控/异步任务全用 Postgres 表 + Java `@Scheduled` 轮询。
- **密钥全走 .env**：DB 密码、GLM/TikHub/阿里云 key、SERVICE_TOKEN、JWT secrets、SPRING_MAIL_*、SKS_ALERT_ADMIN_EMAIL 全部 `.env` 注入，`.env` gitignored 不进 git。
- **gateway 不镜像化**：本地 build（`nginx:alpine + 两文件` nginx.conf + 50x.html），无 CI/registry。
- **GHCR owner lowercase**：仓库 URL `WangBuer1984/<svc>`（mixed），镜像路径 `ghcr.io/wangbuer1984/<svc>`（lowercase），CI tag 写 lowercase，compose 引用 lowercase，勿混淆。
- **镜像 tag 钉具体版本**：deploy compose 不用 `:latest`（本地缓存不更新风险），钉 `v0.1.0` 等具体 tag。
- **提交规范**：Conventional Commits（`feat:`/`chore:`/`docs:`/`ci:`），一步一提。
- **执行顺序不可乱**：Phase 1-3（抽仓 + CI + push/tag）全部成功、三镜像 published 且部署机可拉取后，才动 Phase 4（原仓改造）。抽离出问题时原仓未动，可从 git 恢复。

---

## 文件结构总览（决定任务边界）

```
# 三服务仓（各自独立 Git 仓库，GHCR 镜像源）
sks-ai/        app/ tests/ Dockerfile pyproject.toml uv.lock
               + .github/workflows/ci.yml  .env.example  .gitignore  .dockerignore
               + CLAUDE.md  README.md  docs/API_CONTRACT.md
               改: app/config.py(删 ALIYUN_SMS_SIGN)  Dockerfile(加 COPY uv.lock + --frozen)

sks-server/    src/ .mvn/ mvnw mvnw.cmd pom.xml Dockerfile
               + .github/workflows/ci.yml  .env.example  .gitignore  .dockerignore
               + CLAUDE.md  README.md  docs/REST_CONTRACT.md

sks-web/       src/ index.html vite.config.ts tailwind.config.js tsconfig.json postcss.config.js package.json package-lock.json
               + Dockerfile  nginx.conf  prototypes/(两份原型 HTML)
               + .github/workflows/ci.yml  .gitignore  .dockerignore  CLAUDE.md  README.md

# deploy 仓（原 sks-agent 改造）
sks-agent-deploy/  docker-compose.yml(改)  .env.example(补全)  README.md(新增)
                   deploy/nginx/{Dockerfile,nginx.conf}(重写)  deploy/{OPS.md,GO_LIVE_CHECKLIST.md,backup/}(改)
                   docs/(PRD/tech-design/MVP plan/学习文档/spec)
                   CLAUDE.md(改四仓总览)
```

每个文件单一职责：CI workflow = test gate + build/push；scoped CLAUDE.md = 约束该仓代码的硬不变量 + 本仓构建测试命令；契约文档 = 跨仓接口真相（提供方拥有）；Dockerfile/nginx.conf = 容器化与静态/网关职。

---

## Phase 1 — 抽离三服务仓到独立仓库（带历史，三次对称 split）

> **前置（每个 Task 开始前）：** `cd /Users/rick/work/sks-agent && git status` 确认三子目录无未提交改动。`subtree split` 只带已提交历史，untracked 文件不跟走。当前 untracked 的 `sks-ai/app/app.iml`、`sks-ai/tests/tests.iml` 是 IDEA 生成文件（Phase 2 的 sks-server `.gitignore` 会覆盖 `*.iml`，sks-ai 同理），split 不会带走它们——确认这不丢东西即可继续。

### Task 1: 抽离 sks-ai 仓

**Files:**
- Create: `/Users/rick/work/sks-ai/`（新仓库，从 `sks-ai/` 子目录抽离带历史）

- [ ] **Step 1: 确认原仓干净**

Run: `cd /Users/rick/work/sks-agent && git status --short sks-ai`
Expected: 无 `M`/`A`/`D` 行（untracked `.iml` 可忽略，split 不带 untracked）

- [ ] **Step 2: subtree split 抽 sks-ai**

Run: `cd /Users/rick/work/sks-agent && git subtree split --prefix=sks-ai -b split-sks-ai`
Expected: 输出一个 commit SHA（新分支 `split-sks-ai`，仅含 `sks-ai/` 子目录历史，路径已提为根）

- [ ] **Step 3: 建新仓并拉历史**

Run:
```bash
mkdir -p /Users/rick/work/sks-ai && cd /Users/rick/work/sks-ai
git init -b main && git pull /Users/rick/work/sks-agent split-sks-ai
```
Expected: 新仓 `main` 分支有完整提交历史，根目录是 `app/ tests/ Dockerfile pyproject.toml uv.lock`

- [ ] **Step 4: 验证历史与文件齐**

Run: `cd /Users/rick/work/sks-ai && git log --oneline | head -5 && ls`
Expected: 能看到原仓 sks-ai 相关提交；`ls` 列出 `app tests Dockerfile pyproject.toml uv.lock`

### Task 2: 抽离 sks-server 仓

**Files:**
- Create: `/Users/rick/work/sks-server/`

- [ ] **Step 1: subtree split 抽 sks-server**

Run: `cd /Users/rick/work/sks-agent && git subtree split --prefix=sks-server -b split-sks-server`
Expected: 输出 commit SHA，新分支 `split-sks-server`

- [ ] **Step 2: 建新仓并拉历史**

Run:
```bash
mkdir -p /Users/rick/work/sks-server && cd /Users/rick/work/sks-server
git init -b main && git pull /Users/rick/work/sks-agent split-sks-server
```
Expected: `main` 分支有历史，根目录 `src .mvn mvnw mvnw.cmd pom.xml Dockerfile`

- [ ] **Step 3: 验证历史与文件齐**

Run: `cd /Users/rick/work/sks-server && git log --oneline | head -5 && ls`
Expected: 看到原仓 sks-server 提交；`ls` 列出 `src .mvn mvnw mvnw.cmd pom.xml Dockerfile`

### Task 3: 抽离 sks-web 仓

**Files:**
- Create: `/Users/rick/work/sks-web/`

- [ ] **Step 1: subtree split 抽 sks-web（顶层目录，与上两次同款）**

Run: `cd /Users/rick/work/sks-agent && git subtree split --prefix=sks-web -b split-sks-web`
Expected: 输出 commit SHA，新分支 `split-sks-web`

- [ ] **Step 2: 建新仓并拉历史**

Run:
```bash
mkdir -p /Users/rick/work/sks-web && cd /Users/rick/work/sks-web
git init -b main && git pull /Users/rick/work/sks-agent split-sks-web
```
Expected: `main` 分支有历史，根目录含 `src index.html vite.config.ts tailwind.config.js tsconfig.json postcss.config.js package.json package-lock.json`（`dist/`、`node_modules/` 是构建产物——已核实 `git ls-files sks-web/{dist,node_modules}` 为空，未进历史，split 不带；Phase 2 `.gitignore` 防本地生成物再进）

- [ ] **Step 3: 验证历史与文件齐**

Run: `cd /Users/rick/work/sks-web && git log --oneline | head -5 && ls`
Expected: 看到原仓 sks-web 提交；源文件齐

---

## Phase 2 — 三服务仓补 CI + 文档 + 清理（各仓本地提交，尚未 push）

### Task 4: sks-ai 仓 — CI / 护栏 / 文档 / 清理

**Files:**
- Create: `sks-ai/.github/workflows/ci.yml`、`sks-ai/.gitignore`、`sks-ai/.dockerignore`、`sks-ai/.env.example`、`sks-ai/CLAUDE.md`、`sks-ai/README.md`、`sks-ai/docs/API_CONTRACT.md`
- Modify: `sks-ai/app/config.py:32`（删 `ALIYUN_SMS_SIGN` 字段）、`sks-ai/Dockerfile:10,12`（加 `COPY uv.lock` + `--frozen`）

**Interfaces:**
- Produces: `ghcr.io/wangbuer1984/sks-ai:<tag>` 镜像；CI workflow（tag 触发 build+push）；`docs/API_CONTRACT.md`（/ai/* 端点 + 共享表契约，消费方 sks-server 依此）

- [ ] **Step 1: 建 .gitignore**

Create `sks-ai/.gitignore`:
```gitignore
.env
.venv/
__pycache__/
*.pyc
.pytest_cache/
.idea/
*.iml
```

- [ ] **Step 2: 建 .dockerignore（护栏）**

Create `sks-ai/.dockerignore`:
```dockerignore
.env
.git
.venv
__pycache__
.pytest_cache
.idea
tests
```
> Dockerfile 是白名单式 `COPY app ./app`，`.env` 不会进镜像；此文件是将来谁改成 `COPY . .` 的护栏。

- [ ] **Step 3: 建 .env.example（本地 dev 参考）**

Create `sks-ai/.env.example`:
```dotenv
# 运行时由 deploy 仓 compose env_file 注入，本文件仅本地 uv run uvicorn 调试用
DATABASE_URL=postgresql://sks:change_me@localhost:5432/sks
SERVICE_TOKEN=change_me_internal_shared_token
ZHIPU_API_KEY=
TIKHUB_API_KEY=
ALIYUN_ACCESS_KEY_ID=
ALIYUN_ACCESS_KEY_SECRET=
ALIYUN_ASR_KEY=
ALIYUN_ASR_APP_KEY=
# 以下有默认值，一般不改：
# ZHIPU_BASE_URL=https://open.bigmodel.cn/api/paas/v4/
# TIKHUB_BASE_URL=https://api.tikhub.dev
# ALIYUN_CONTENT_SAFETY_ENDPOINT=https://green.cn-shanghai.aliyuncs.com
```
> 不含 `ALIYUN_SMS_SIGN`——本仓 config.py 不读（Step 5 删除）。

- [ ] **Step 4: 清理 config.py 删 ALIYUN_SMS_SIGN**

Run: `cd /Users/rick/work/sks-ai && grep -n ALIYUN_SMS_SIGN app/config.py`
Expected: 仅第 32 行 `    ALIYUN_SMS_SIGN: str = ""`（确认 Python 无一处用）

Edit `sks-ai/app/config.py`，删除第 32 行 `    ALIYUN_SMS_SIGN: str = ""` 及其上方注释行第 29 行 `# 阿里云：SMS / ASR / 内容安全同厂商。` 改为 `# 阿里云：ASR / 内容安全同厂商（SMS 归 sks-server）。`。

- [ ] **Step 5: 清理 Dockerfile 加 uv.lock + --frozen（假绿修复）**

Modify `sks-ai/Dockerfile`：

当前：
```dockerfile
COPY pyproject.toml ./
COPY app ./app
RUN uv sync --no-dev
```
改为：
```dockerfile
COPY pyproject.toml uv.lock ./
COPY app ./app
RUN uv sync --no-dev --frozen
```
> `--frozen` 严格按 uv.lock 装，与 CI `uv run pytest`（按 lock）验的版本一致；lock 与 pyproject 不同步时 build fail（正是要的 gate）。

- [ ] **Step 6: 建 CI workflow**

Create `sks-ai/.github/workflows/ci.yml`:
```yaml
name: ci
on:
  push:
    branches: [main]
    tags: ['v*']
  pull_request:
permissions:
  contents: read
jobs:
  test:
    runs-on: ubuntu-latest
    env:
      SKS_REQUIRE_REAL_DB: "1"   # 无 pgvector services 容器时 skip→FAIL，强制数据泄漏防线在 CI 被证明
    services:
      postgres:
        image: pgvector/pgvector:pg16
        env:
          POSTGRES_DB: sks
          POSTGRES_USER: sks
          POSTGRES_PASSWORD: sks
        ports: ['5432:5432']
        options: >-
          --health-cmd "pg_isready -U sks" --health-interval 5s
          --health-timeout 5s --health-retries 10
    steps:
      - uses: actions/checkout@v4
      - uses: astral-sh/setup-uv@v5
      - run: uv sync
      - run: uv run pytest -v
  build-push:
    if: startsWith(github.ref, 'refs/tags/v')
    needs: test
    runs-on: ubuntu-latest
    permissions:
      packages: write
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - run: echo "${{ secrets.GITHUB_TOKEN }}" | docker login ghcr.io -u ${{ github.actor }} --password-stdin
      - uses: docker/build-push-action@v6
        with:
          context: .
          push: true
          tags: ghcr.io/wangbuer1984/sks-ai:${{ github.ref_name }}
          platforms: linux/amd64
```
> ✅ 已核实：pytest 声明在 `pyproject.toml` 的 `[dependency-groups] dev`（PEP 735），`uv sync` 默认装 dev 组 → CI `uv sync && uv run pytest` 机制成立。
> ✅ 已核实真库用例情况：仅 `tests/test_retrieve.py` 有真库测试——它**探测运行中的 pgvector 容器**，无容器则 skip；但带 `SKS_REQUIRE_REAL_DB` 开关，设置后无容器**由 skip 改为 FAIL**（测试文件原话「数据泄漏防线的不变量必须在动态 DB 测试中证明，不能被 skip 掉」）。故 `test` job 已内联 pgvector `services` + `SKS_REQUIRE_REAL_DB: "1"`（GitHub services 容器对 runner 的 `docker ps` 可见，探测机制能发现它）——跨用户泄漏防线在 CI 真正被证明。
> ✅ 探测依赖已核实（否则开关会让 CI 必红）：探测函数走 `import docker` + `docker.from_env()` 遍历运行中容器、找 image tag 含 `pgvector` 者，再从容器 env 读 `POSTGRES_USER/PASSWORD/DB` 与 HostPort 拼 DSN。`docker` SDK 由 dev 组的 `testcontainers[postgres]` **传递引入，无需加新依赖**；但 `import docker` 失败会被函数的 `except Exception: return None` 吞掉 → 配了开关即 FAIL，故**别删 `testcontainers` dev 依赖**。
> 降级方案：若首跑发现探测机制在 GH runner 上认不出 services 容器（CI 红），去掉 `test` job 的 `env: SKS_REQUIRE_REAL_DB` 与 `services:` 块，接受 DB 用例 skip（SQL 断言仍兜底），另开 issue 跟进容器探测。

- [ ] **Step 7: 建 scoped CLAUDE.md**

Create `sks-ai/CLAUDE.md`:
```markdown
# CLAUDE.md — sks-ai 仓

本仓为 Python AI 服务（FastAPI + LangGraph），不暴露公网，只接受带 `X-Service-Token` 的内网请求。

## 硬不变量（实现时不得违背）

- **无流式输出 + 先审后返**：所有展示给用户的 LLM 自然语言产出（稿件、卡片、访谈、拆解、归因）= 生成完整 → 内容安全审通过 → 一次性 JSON 返回。禁 SSE/打字机。
- **GLM 单厂商 + 型号只在 llm/ 配置层**：LLM 走智谱 GLM（OpenAI 兼容），向量用 embedding-3 固定 1024 维，绑 pgvector `vector(1024)` 列；业务代码不硬编码型号。创作类 GLM-4.7(thinking off)/轻量抽取 GLM-4.5-Air/深度归纳归因 GLM-4.7(thinking on)。
- **不做数据库迁移**（checkpointer 例外，sks-ai 自己 setup）；共享表 `kb_card`/`analyze_task` 由 sks-server Flyway 建，本仓只读写。
- **UGC 过内容安全审**。
- **Python 不暴露公网只信 X-Service-Token**；不做鉴权，信内网 + 共享 token。

## 本仓构建/测试命令

- `uv sync`（装依赖，含 dev）
- `uv run pytest tests/test_xxx.py -v`（单文件）
- `uv run uvicorn app.main:app --reload --port 8000`（本地跑）
- 镜像构建：`docker build -t sks-ai .`（Dockerfile `uv sync --no-dev --frozen`，严格按 uv.lock）

## 契约

- `/ai/*` HTTP 端点 + 共享表契约见 `docs/API_CONTRACT.md`（本仓是服务提供方，契约归本仓拥有）。
```

- [ ] **Step 8: 建 README.md**

Create `sks-ai/README.md`:
```markdown
# sks-ai

Python AI 服务（FastAPI + LangGraph + 智谱 GLM）。内网服务，不暴露公网。

## 本地跑

```bash
uv sync
source .env  # DATABASE_URL / ZHIPU_API_KEY / TIKHUB_API_KEY / SERVICE_TOKEN / ALIYUN_* / ALIYUN_ASR_*
uv run uvicorn app.main:app --reload --port 8000
```
`.env` 仅本地调试参考；运行时由 deploy 仓 compose `env_file` 注入。

## 镜像构建

```bash
docker build -t ghcr.io/wangbuer1984/sks-ai:dev .
```
Dockerfile `uv sync --no-dev --frozen` 严格按 uv.lock 装。CI（`.github/workflows/ci.yml`）在 git tag `v*` 时 build+push 到 GHCR。镜像只保证 `linux/amd64`。

## 健康检查

`GET /health` → `{"status":"UP"}`（覆盖 asyncpg 池懒重试；checkpointer 无懒重试，依赖 compose depends_on 保证 pg 先起）。
```

- [ ] **Step 9: 建 docs/API_CONTRACT.md**

Create `sks-ai/docs/API_CONTRACT.md`。结构 + 已知内容（端点契约来自代码，契约面 1）：
```markdown
# API_CONTRACT — sks-ai /ai/* 端点 + 共享表契约

sks-ai 是服务提供方，本文件为跨仓契约真相。sks-server 的 `AiClient` record 须与本文件字段一致。

## 鉴权
所有 /ai/* 请求须带 `X-Service-Token`（与 deploy 仓 .env `SERVICE_TOKEN` 一致）+ `X-Request-Id`（Java 生成）。无则 401。

## 端点

| 方法 | 路径 | 用途 | 入参 | 出参 |
|---|---|---|---|---|
| GET | /health | 健康检查 | - | `{"status":"UP"}` |
| POST | /ai/analyze/precheck | 拆视频/拆账号预检（不扣费） | ... | ... |
| ... | ... | ... | ... | ... |

> 实现期补全：端点入参/出参 pydantic model 字段逐个从 `app/api/*` 抽取填入；sks-server `AiClient` record 字段须与本表逐字对齐。

## 共享表（sks-server Flyway 建，本仓读写）

- `kb_card`（layer A/B/C + card_type + embedding vector(1024)）
- `analyze_task`（async 任务进度/结果，Python 直接写此表，Java @Scheduled 轮询）

字段契约见拆分 spec §3 数据模型 + tech-design §3。
```

- [ ] **Step 10: 本地验证**

Run:
```bash
cd /Users/rick/work/sks-ai
grep -r ALIYUN_SMS_SIGN app/ || echo "OK: 无残留"
uv sync && uv run pytest -v
docker build -t sks-ai:plan-verify .
```
Expected: `grep` 输出 `OK: 无残留`；pytest 全绿；docker build 成功（`--frozen` 不报 lock 不一致）

- [ ] **Step 11: 提交**

Run:
```bash
cd /Users/rick/work/sks-ai
git add .gitignore .dockerignore .env.example .github app/config.py Dockerfile CLAUDE.md README.md docs
git commit -m "ci: sks-ai 仓 CI + 护栏 + 文档 + 清理（删 ALIYUN_SMS_SIGN、Dockerfile uv.lock --frozen）"
```

### Task 5: sks-server 仓 — CI / 护栏 / 文档 / 契约

**Files:**
- Create: `sks-server/.github/workflows/ci.yml`、`.gitignore`、`.dockerignore`、`.env.example`、`CLAUDE.md`、`README.md`、`docs/REST_CONTRACT.md`

**Interfaces:**
- Produces: `ghcr.io/wangbuer1984/sks-server:<tag>`；`docs/REST_CONTRACT.md`（前端↔Java REST 契约，消费方 sks-web 依此）

- [ ] **Step 1: 建 .gitignore**

Create `sks-server/.gitignore`:
```gitignore
.env
target/
*.class
*.jar
!mvnw
!mvnw.cmd
!.mvn/**
.idea/
*.iml
**/application-local.yml
```
> `*.class`/`*.jar` 沿用原根 gitignore（`target/` 已覆盖绝大多数，这两条防散落产物）。三条 `!` 否定同样沿用原根 gitignore：本仓 Maven Wrapper 是 properties-only（`git ls-files` 只有 `.mvn/wrapper/maven-wrapper.properties` + `mvnw`/`mvnw.cmd`，jar 由 `./mvnw` 运行时下载、本就不该进 git），故当前无 jar 需要豁免——留着是防将来有人 `mvn wrapper:wrapper` 生成出 jar 版 wrapper 时被 `*.jar` 静默挡掉（clone 后 `./mvnw` 跑不起来，CI 跟着红）。
> `**/application-local.yml` 接管根 gitignore 的保护——拆分后根 `.gitignore` 不跟走，此文件含本地 DB 口令/绝对路径，不进 git。否则开发者本地为 `./mvnw spring-boot:run` 创建它后，`git add -A` 即提交本地口令（密钥泄漏回归）。

- [ ] **Step 2: 建 .dockerignore**

Create `sks-server/.dockerignore`:
```dockerignore
.env
.git
target
.idea
```

- [ ] **Step 3: 建 .env.example（本地 dev 参考）**

Create `sks-server/.env.example`:
```dotenv
# 运行时由 deploy 仓 compose env_file + environment 注入，本文件仅本地 ./mvnw spring-boot:run 调试用
POSTGRES_DB=sks
POSTGRES_USER=sks
POSTGRES_PASSWORD=change_me
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/sks
SPRING_DATASOURCE_USERNAME=sks
SPRING_DATASOURCE_PASSWORD=change_me
JWT_SECRET_USER=change_me_user_secret_min_32_bytes
JWT_SECRET_ADMIN=change_me_admin_secret_min_32_bytes
SERVICE_TOKEN=change_me_internal_shared_token
ADMIN_SEED_USERNAME=admin
ADMIN_SEED_PASSWORD=change_me_admin_login_pwd
TRIAL_CREDIT=3
ALIYUN_ACCESS_KEY_ID=
ALIYUN_ACCESS_KEY_SECRET=
ALIYUN_SMS_SIGN=
ALIYUN_SMS_TEMPLATE_LOGIN=
ALIYUN_SMS_TEMPLATE_VERIFY_OLD=
ALIYUN_SMS_TEMPLATE_BIND_NEW=
SPRING_MAIL_HOST=
SPRING_MAIL_USERNAME=
SPRING_MAIL_PASSWORD=
SKS_ALERT_ADMIN_EMAIL=
# 以下有默认值，一般不改：
# SKS_AI_BASE_URL=http://localhost:8000  （compose 内默认 http://sks-ai:8000）
# SKS_AI_READ_TIMEOUT=270  SKS_AI_CONNECT_TIMEOUT=10
```
> 本地 IDEA 跑须把 `SKS_AI_BASE_URL` 显式设为 `http://localhost:8000`（compose 默认是 `http://sks-ai:8000`，对本地错）。见 `application-local.yml` + 记忆 `local-idea-run-java-env`。
> ⚠️ `JWT_SECRET_USER`/`JWT_SECRET_ADMIN` 的 `change_me_...` 占位值正是 `JwtConfig.guardSecret` 的 `KNOWN_PLACEHOLDERS` 明确拒绝的——本地跑前**必须换 ≥32 字节真值**，否则启动即抛 `IllegalStateException`（fail-closed 设计，勿改占位值本身，换真值即可）。

- [ ] **Step 4: 建 CI workflow**

Create `sks-server/.github/workflows/ci.yml`:
```yaml
name: ci
on:
  push:
    branches: [main]
    tags: ['v*']
  pull_request:
permissions:
  contents: read
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - run: ./mvnw -B test
  build-push:
    if: startsWith(github.ref, 'refs/tags/v')
    needs: test
    runs-on: ubuntu-latest
    permissions:
      packages: write
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - run: echo "${{ secrets.GITHUB_TOKEN }}" | docker login ghcr.io -u ${{ github.actor }} --password-stdin
      - uses: docker/build-push-action@v6
        with:
          context: .
          push: true
          tags: ghcr.io/wangbuer1984/sks-server:${{ github.ref_name }}
          platforms: linux/amd64
```
> Testcontainers 在 ubuntu-latest 可行（runner 自带 docker）；`./mvnw -B test` 会拉 `pgvector/pgvector:pg16` 起 Testcontainers。
> ✅ 已核实：Java 测试基类 `AbstractDbTest` 用 `@DynamicPropertySource` 自带测试 JWT 密钥 → CI `./mvnw test` **无需 .env 即可跑绿**（不依赖 JWT_SECRET_* env）。

- [ ] **Step 5: 建 scoped CLAUDE.md**

Create `sks-server/CLAUDE.md`:
```markdown
# CLAUDE.md — sks-server 仓

本仓为 Java 服务（Spring Boot 3），唯一公网入口，负责鉴权/额度/CRUD/状态机/定时任务。

## 硬不变量（实现时不得违背）

- **信用事务边界**：扣额度用原子条件更新 `UPDATE credit_account SET balance = balance - :n WHERE user_id = :uid AND balance >= :n`，靠影响行数判成败，禁先查后写；退款幂等 via `credit_ledger` `(biz_id, biz_type, type)` 唯一约束。事务编排法非 `@Transactional`——长 HTTP 调用（调 Python 30-60s）不得持 DB 连接：先插 script 占位行拿 `script_id` → 短 `REQUIRES_NEW` 事务扣额度 → 调 Python → 成功回填/draft，失败设 failed + 幂等退款 ledger。Fail→refund 永不漏扣。
- **管理端隔离**：独立 `admin_user` + 独立 SecurityFilterChain（`/api/admin/**`）+ 不同 JWT secret/claim，两套 token 互不通用；无注册，Flyway 种子（密码哈希取自 `ADMIN_SEED_PASSWORD` env）。
- **Testcontainers pgvector:pg16 非 H2**：保持 SQL 方言一致。
- **复盘状态机无 AI 判态**：七状态全 Java 规则转换；`hot` 阈值=近30天均值×3（可调）；`rejected`=48h 未采纳（@Scheduled 扫）。
- **Java 唯一公网入口**；不用 Redis/MQ/微服务/K8s。
- **JWT secrets 守卫**：`JwtConfig.guardSecret` 拒绝空/占位符（`change_me...`）；Flyway 早于 bean 执行。

## 本仓构建/测试命令

- `./mvnw test`（全测）/ `./mvnw test -Dtest=CreditServiceTest`（单类）/ `./mvnw test -Dtest="AuthServiceTest,UserServiceTest"`（多类）
- `./mvnw spring-boot:run`（本地跑，配合 `application-local.yml` + local profile + `.env`）
- 镜像构建：`docker build -t sks-server .`（Dockerfile `-DskipTests`，测试在 CI 前置 gate 跑）

## 契约

- 前端↔Java REST 契约（ErrorCode 全表 + ApiResponse 形状 + `sks_token`/`sks_admin_token` 两套 token key + 401 行为）见 `docs/REST_CONTRACT.md`。
- Java↔Python 跨仓 HTTP+X-Service-Token 契约见 sks-ai 仓 `docs/API_CONTRACT.md`（本仓 `AiClient` record 须对齐）。
```

- [ ] **Step 6: 建 README.md**

Create `sks-server/README.md`:
```markdown
# sks-server

Java 服务（Spring Boot 3 + MyBatis-Plus + Spring Security JWT），唯一公网入口。

## 本地跑

`application-local.yml` 是 **gitignored 的本地文件**（含本地口令，不进 git）——本地跑前先创建（模板见下）。激活 `local` profile 才会加载它 + `.env`：

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

`application-local.yml` 模板（本地创建，**勿提交**——已被 `.gitignore` 的 `**/application-local.yml` 挡）：
```yaml
spring:
  config:
    import: "optional:file:.env[.properties]"   # 相对路径，工作目录=仓库根；[.properties] 强制按 properties 读 .env
  datasource:
    url: jdbc:postgresql://localhost:5432/sks
    username: sks
    password: ${SPRING_DATASOURCE_PASSWORD:change_me}   # 读 .env，不硬编码
sks:
  ai:
    base-url: http://localhost:8000   # 本地 Python；compose 默认 http://sks-ai:8000 对本地错
```
`.env`（gitignored）填真值；`JWT_SECRET_USER`/`JWT_SECRET_ADMIN` 必须换 ≥32 字节真值（否则 `JwtConfig.guardSecret` 启动即抛）。见记忆 `local-idea-run-java-env`。

## 镜像构建

```bash
docker build -t ghcr.io/wangbuer1984/sks-server:dev .
```
CI 在 git tag `v*` 时 build+push 到 GHCR。镜像只保证 `linux/amd64`。
```

- [ ] **Step 7: 建 docs/REST_CONTRACT.md**

Create `sks-server/docs/REST_CONTRACT.md`。结构 + 已知内容（ErrorCode 全表从代码抽取）：
```markdown
# REST_CONTRACT — 前端 ↔ Java REST 契约

sks-server 是服务提供方，本文件为跨仓契约真相。sks-web 的 axios 调用须与本文件一致。

## ApiResponse 形状

所有 C 端 `/api/**` 与管理端 `/api/admin/**` 返回统一壳：
```json
{ "code": 0, "message": "...", "data": { ... } }
```
`code=0` 成功；非 0 见 ErrorCode 全表。

## Token key 约定（两套隔离）

- C 端：axios `userClient` baseURL `/api`，注入 cookie/header `sks_token`。
- 管理端：axios `adminClient` baseURL `/api/admin`，注入 `sks_admin_token`。
- 两套 JWT 不同 secret/claim，互不通用。

## 401 行为

401 → 清 token + 存回跳路径 `returnKey` + 跳对应登录页（C 端 `/login`、管理端 `/admin/login`）。router 守卫拦前端导航，axios 拦截器拦后端 401，双保险。
> 注：当前实现只存回跳路径、未存表单内容（PRD §11.6 表单存 localStorage 是既有 gap）。

## ErrorCode 全表

| code | 常量 | HTTP | 含义 |
|---|---|---|---|
| 0 | OK | 200 | 成功 |
| 5001 | AI_FAILED | 500 | AI 生成失败（已退款） |
| ... | ... | ... | ... |

> 实现期补全：`grep -rn "ErrorCode" src/main/java | grep -oE '[A-Z_]+ *= *[0-9]+' | sort -u` 抽全表填入。
```

- [ ] **Step 8: 本地验证**

Run:
```bash
cd /Users/rick/work/sks-server
./mvnw -B test
docker build -t sks-server:plan-verify .
```
Expected: `./mvnw test` 全绿（Testcontainers pgvector 起得来）；docker build 成功

- [ ] **Step 9: 提交**

Run:
```bash
cd /Users/rick/work/sks-server
git add .gitignore .dockerignore .env.example .github CLAUDE.md README.md docs
git commit -m "ci: sks-server 仓 CI + 护栏 + 文档 + REST_CONTRACT"
```

### Task 6: sks-web 仓 — 原型迁移 / Dockerfile / nginx.conf / CI / 文档

**Files:**
- Create: `sks-web/prototypes/`（两份原型 HTML cp 来）、`sks-web/Dockerfile`、`sks-web/nginx.conf`、`.github/workflows/ci.yml`、`.gitignore`、`.dockerignore`、`CLAUDE.md`、`README.md`

**Interfaces:**
- Produces: `ghcr.io/wangbuer1984/sks-web:<tag>`（环境无关，CI 零 secret）；`prototypes/` 前端视觉基准

- [ ] **Step 1: 复制两份原型到 prototypes/**

Run:
```bash
mkdir -p /Users/rick/work/sks-web/prototypes
cp /Users/rick/work/sks-agent/随口说原型-07191700.html /Users/rick/work/sks-web/prototypes/
cp /Users/rick/work/sks-agent/随口说后台管理原型-admin.html /Users/rick/work/sks-web/prototypes/
ls -la /Users/rick/work/sks-web/prototypes/
```
Expected: 两份 HTML 在 `prototypes/`（C 端 14MB + admin 28KB）。subtree split 只带 `sks-web/` 子目录，根级原型不会自动跟来，故显式复制。

- [ ] **Step 2: 建 Dockerfile（多阶段 node build → nginx serve dist）**

Create `sks-web/Dockerfile`:
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

- [ ] **Step 3: 建 nginx.conf（静态服务职）**

Create `sks-web/nginx.conf`:
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

- [ ] **Step 4: 建 .gitignore**

Create `sks-web/.gitignore`:
```gitignore
node_modules/
dist/
.env
.vite/
.idea/
```

- [ ] **Step 5: 建 .dockerignore（含 prototypes/ 护栏）**

Create `sks-web/.dockerignore`:
```dockerignore
node_modules
.env
.git
prototypes
dist
```
> Dockerfile `COPY . .` 会把两份原型 14MB+ 拉进 build context/build stage；最终镜像只 COPY dist 不受影响，但排除掉省一次传输。

- [ ] **Step 6: 建 CI workflow（零 secret）**

Create `sks-web/.github/workflows/ci.yml`:
```yaml
name: ci
on:
  push:
    branches: [main]
    tags: ['v*']
  pull_request:
permissions:
  contents: read
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '22'
          cache: npm
      - run: npm ci
      - run: npm run build
  build-push:
    if: startsWith(github.ref, 'refs/tags/v')
    needs: test
    runs-on: ubuntu-latest
    permissions:
      packages: write
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - run: echo "${{ secrets.GITHUB_TOKEN }}" | docker login ghcr.io -u ${{ github.actor }} --password-stdin
      - uses: docker/build-push-action@v6
        with:
          context: .
          push: true
          tags: ghcr.io/wangbuer1984/sks-web:${{ github.ref_name }}
          platforms: linux/amd64
```

- [ ] **Step 7: 建 scoped CLAUDE.md**

Create `sks-web/CLAUDE.md`:
```markdown
# CLAUDE.md — sks-web 仓

本仓为前端（React 18 + Vite + TypeScript + Tailwind）+ 静态服务（nginx:alpine serve SPA）。

## 硬不变量（实现时不得违背）

- **纸感色板**：`#f4f1e9`（底）/ `#8a5a2b`（主色）/ `Noto Serif SC`（衬线标题），落为 tailwind.config.js 主题变量。
- **TanStack Query 管服务端态 + Zustand 管客户端态**。
- **axios 双实例**：`userClient` baseURL `/api` 注入 `sks_token`；`adminClient` baseURL `/api/admin` 注入 `sks_admin_token`，两套隔离。
- **401**：清 token + 存回跳路径 `returnKey` + 跳对应登录页（C 端 `/login`、管理端 `/admin/login`）；router 守卫 + axios 拦截器双保险。**注意：当前实现只存回跳路径、未存表单内容——PRD §11.6 表单存 localStorage 是既有 gap，不得写成"401 保内容"。**
- **无流式输出** → 用多阶段进度动画 mask 等待。
- **无任何运行期/构建期 env**：axios 用相对基址 `/api`+`/api/admin`，全代码库无 `VITE_` 变量，镜像环境无关，CI 零 secret。

## 视觉基准

`prototypes/` 两份原型 HTML（C 端 + admin）是前端视觉基准，**只读不改**。

## 契约

错误码全表与 `ApiResponse` 形状见 sks-server 仓 `docs/REST_CONTRACT.md`。

## 本仓构建/测试命令

- `npm install` / `npm run dev`（本地跑）/ `npm run build`（构建，产物带 hash）
- 镜像构建：`docker build -t ghcr.io/wangbuer1984/sks-web:dev .`（CI 零 secret）
```

- [ ] **Step 8: 建 README.md**

Create `sks-web/README.md`:
```markdown
# sks-web

前端（React 18 + Vite + TypeScript + Tailwind）+ nginx 静态服务。无运行期/构建期 env，一个镜像任何环境通用。

## 本地跑

```bash
npm install
npm run dev
```

## 镜像构建

```bash
docker build -t ghcr.io/wangbuer1984/sks-web:dev .
```
CI 在 git tag `v*` 时 build+push 到 GHCR（零 secret）。镜像只保证 `linux/amd64`。
```

- [ ] **Step 9: 本地验证**

Run:
```bash
cd /Users/rick/work/sks-web
npm ci && npm run build
docker build -t sks-web:plan-verify .
docker run --rm -d -p 8088:80 --name sks-web-verify sks-web:plan-verify
curl -s -o /dev/null -w '%{http_code}' http://localhost:8088/   # 200
docker stop sks-web-verify   # 验完收掉，别留后台容器占 8088
```
Expected: `npm run build` 成功（产物带 hash）；docker build 成功；curl `/` 返回 200（SPA index.html）

- [ ] **Step 10: 提交**

Run:
```bash
cd /Users/rick/work/sks-web
git add prototypes Dockerfile nginx.conf .gitignore .dockerignore .github CLAUDE.md README.md
git commit -m "ci: sks-web 仓 Dockerfile/nginx.conf + 原型迁移 prototypes/ + CI + 文档"
```

---

## Phase 3 — 建三 GitHub 远程 + push + 打首 tag 触发镜像构建

> **gate 前置（已在目标阿里云服务器验过，拆分 spec §8 已退役此风险）**：部署机 `ghcr.io` 可达（`docker pull ghcr.io/astral-sh/uv:latest` 成功）。三仓 push tag 后 CI 出镜像；首 `compose pull` 前须解决 GHCR private（PAT `read:packages` `docker login ghcr.io` 或显式设 package public），否则 401。

### Task 7: sks-ai 远程 + push + tag + 验证镜像 published

- [ ] **Step 1: 建远程 + push main**

Run:
```bash
cd /Users/rick/work/sks-ai
git remote add origin git@github.com:WangBuer1984/sks-ai.git
git branch -M main && git push -u origin main
```
Expected: push 成功；GitHub Actions `ci` workflow `test` job 触发（push main 只跑 test 不推镜像）

- [ ] **Step 2: 等 CI test 绿**

Run: `gh run watch`（或 GitHub → Actions 页看 `ci` workflow）
Expected: `test` job 绿（`uv run pytest` 过）

- [ ] **Step 3: 打首 tag 触发 build+push**

Run:
```bash
cd /Users/rick/work/sks-ai
git tag v0.1.0 && git push origin v0.1.0
```
Expected: tag push 触发 `build-push` job（`if: startsWith(github.ref,'refs/tags/v')`）

- [ ] **Step 4: 等 build-push 绿 + 验证镜像 published**

Run: `gh run watch`（等 `build-push` job 绿）
Expected: job 绿；GitHub → Packages 页可见 `ghcr.io/wangbuer1984/sks-ai:v0.1.0`

- [ ] **Step 5: 验证部署机可拉**

Run（在部署机，先 `docker login ghcr.io` 用 PAT `read:packages`，或把 package 设 public）:
```bash
docker pull ghcr.io/wangbuer1984/sks-ai:v0.1.0
```
Expected: 拉取成功（401 = 未解决 private，回到 login 或设 public）

### Task 8: sks-server 远程 + push + tag + 验证镜像 published

- [ ] **Step 1: 建远程 + push main + 等 test 绿**

Run:
```bash
cd /Users/rick/work/sks-server
git remote add origin git@github.com:WangBuer1984/sks-server.git
git branch -M main && git push -u origin main
gh run watch   # 等 test job 绿（./mvnw test，Testcontainers pgvector）
```
Expected: push 成功；`test` job 绿

- [ ] **Step 2: 打首 tag + 等 build-push 绿 + 验证镜像**

Run:
```bash
cd /Users/rick/work/sks-server
git tag v0.1.0 && git push origin v0.1.0
gh run watch   # 等 build-push 绿
```
Expected: GitHub Packages 可见 `ghcr.io/wangbuer1984/sks-server:v0.1.0`

- [ ] **Step 3: 验证部署机可拉**

Run: `docker pull ghcr.io/wangbuer1984/sks-server:v0.1.0`
Expected: 拉取成功

### Task 9: sks-web 远程 + push + tag + 验证镜像 published

- [ ] **Step 1: 建远程 + push main + 等 test 绿**

Run:
```bash
cd /Users/rick/work/sks-web
git remote add origin git@github.com:WangBuer1984/sks-web.git
git branch -M main && git push -u origin main
gh run watch   # 等 test job 绿（npm ci && npm run build，零 secret）
```
Expected: push 成功；`test` job 绿

- [ ] **Step 2: 打首 tag + 等 build-push 绿 + 验证镜像**

Run:
```bash
cd /Users/rick/work/sks-web
git tag v0.1.0 && git push origin v0.1.0
gh run watch   # 等 build-push 绿
```
Expected: GitHub Packages 可见 `ghcr.io/wangbuer1984/sks-web:v0.1.0`

- [ ] **Step 3: 验证部署机可拉**

Run: `docker pull ghcr.io/wangbuer1984/sks-web:v0.1.0`
Expected: 拉取成功

> **Phase 3 完成门槛（Phase 4 gate）**：三镜像 `sks-server:v0.1.0` / `sks-ai:v0.1.0` / `sks-web:v0.1.0` 均 published 且部署机 `docker pull` 成功。否则**不要动原仓**。

---

## Phase 4 — 原仓库改造为 deploy 仓

> **gate**：Phase 3 三镜像 published + 可拉取 + 原仓 `git rm` 已确认要删三目录（见拆分 spec §8 回滚——`git rm` 后历史里仍在，可 checkout，不丢代码）。

### Task 10: 删三服务目录 + 两原型（原仓）

**Files:**
- Delete: `sks-ai/`、`sks-server/`、`sks-web/`、`随口说原型-07191700.html`、`随口说后台管理原型-admin.html`（原仓根；原型已 cp 进 sks-web 仓 `prototypes/`）

- [ ] **Step 1: 确认 Phase 3 gate 过**

Run: `docker pull ghcr.io/wangbuer1984/sks-server:v0.1.0 && docker pull ghcr.io/wangbuer1984/sks-ai:v0.1.0 && docker pull ghcr.io/wangbuer1984/sks-web:v0.1.0`
Expected: 三镜像均拉取成功

- [ ] **Step 2: git rm 三目录 + 两原型**

Run:
```bash
cd /Users/rick/work/sks-agent
git rm -r sks-ai sks-server sks-web
git rm 随口说原型-07191700.html 随口说后台管理原型-admin.html
```
Expected: tracked 文件全部删除进入 staged；历史里仍可 `git log -- sks-web/` 查。**注意：`git rm -r` 只删 tracked 文件——三目录会留壳**，残留 ignored 文件（`sks-server/src/main/resources/application-local.yml`、`target/`、`*.iml`、`sks-web/node_modules/` 等），Step 3 处理。

- [ ] **Step 3: 迁移 application-local.yml + 清掉残留壳**

残留的 `application-local.yml` 是开发者现成的本地配置（含本地 DB 口令），**迁去新 sks-server 仓**而非删掉；其 `spring.config.import` 写死了老 monorepo 绝对路径 `optional:file:/Users/rick/work/sks-agent/.env[.properties]`，迁移后必须改成相对路径（新仓工作目录=仓库根），并在新仓根从 `.env.example` 建 `.env` 填真值：

Run:
```bash
mkdir -p /Users/rick/work/sks-server/src/main/resources
mv /Users/rick/work/sks-agent/sks-server/src/main/resources/application-local.yml /Users/rick/work/sks-server/src/main/resources/
# 手动编辑迁过去的文件：import 行改为 "optional:file:.env[.properties]"（Task 5 README 模板同款）
rm -rf /Users/rick/work/sks-agent/sks-ai /Users/rick/work/sks-agent/sks-server /Users/rick/work/sks-agent/sks-web   # 清掉 ignored 残留壳
```
Expected: 新仓有 application-local.yml（import 已改相对路径，且被新仓 `.gitignore` 的 `**/application-local.yml` 挡住不进 git）；原仓三目录物理消失

- [ ] **Step 4: 暂不 commit（后续 Task 11-17 一并编辑后统一提交）**

Run: `git status --short | head`
Expected: 显示 `D sks-ai/...`、`D sks-server/...`、`D sks-web/...`、`D 随口说原型...` 等 staged 删除

### Task 11: 改 docker-compose.yml（镜像化 5 服务）

**Files:**
- Modify: `docker-compose.yml`（三服务改 image + 删 build 块；sks-ai depends_on 改 sks-server healthy；新增 sks-web 块；nginx ports 删 8080:80 + healthcheck 改 /50x.html + depends_on 加 sks-web；networks/container_name/restart 不动）

- [ ] **Step 1: sks-server/sks-ai 删 build 块、改 image（nginx 保留本地 build）**

Edit `docker-compose.yml`：删 **sks-server 与 sks-ai 两个** `build:` 块替换为 `image:`。**nginx 的 `build:` 块不动**（gateway 本地 build 是硬不变量，不镜像化）；**sks-web 是新增服务块（Task 11 Step 4），本无 build 可删**。compose 里共三个 `build:` 块（sks-server 第 21 行、sks-ai 第 47 行、nginx 第 76 行）——只动前两个，勿碰 nginx。

`sks-server`：删 `build:` 的 `context: ./sks-server` 块，改为：
```yaml
    image: ghcr.io/wangbuer1984/sks-server:v0.1.0
```
`sks-ai`：删 `build:` 的 `context: ./sks-ai` 块，改为：
```yaml
    image: ghcr.io/wangbuer1984/sks-ai:v0.1.0
```
（钉具体 tag `v0.1.0`，不用 `:latest`）

- [ ] **Step 2: sks-ai depends_on 从 postgres 改 sks-server healthy**

Edit `docker-compose.yml` sks-ai 块，把：
```yaml
    depends_on:
      postgres:
        condition: service_healthy
```
改为：
```yaml
    depends_on:
      sks-server:
        condition: service_healthy   # 等 sks-server 健康（/api/health UP = Spring 已起 = Flyway 已跑完 = kb_card/analyze_task 表已建）
```
> sks-server 依赖 postgres healthy，故 sks-ai 间接得 pg；sks-ai RAG 读的表由 sks-server Flyway 建，故直依 sks-server healthy。

- [ ] **Step 3: sks-ai environment 删 ALIYUN_SMS_SIGN 行**

Edit `docker-compose.yml` sks-ai 块 `environment:`，删除 `ALIYUN_SMS_SIGN: ${ALIYUN_SMS_SIGN}` 行（现第 60 行）。其余 `SERVICE_TOKEN / ZHIPU_API_KEY / TIKHUB_API_KEY / ALIYUN_ACCESS_KEY_ID / ALIYUN_ACCESS_KEY_SECRET` 行不动。
> §3.2 已从 config.py 删该字段、§4 SMS 归 sks-server；env_file 即便带进来 config.py 也不读，删行零行为变化，纯去 cruft。

- [ ] **Step 4: 新增 sks-web 块**

Edit `docker-compose.yml`，在 `sks-ai` 块后、`nginx` 块前插入：
```yaml
  sks-web:
    image: ghcr.io/wangbuer1984/sks-web:v0.1.0
    container_name: sks-web
    restart: unless-stopped
    networks:
      - sks-net
    expose:
      - "80"
    healthcheck:
      test: ["CMD-SHELL", "wget -q --spider http://localhost/ || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 3
```
> sks-web 无 env，无 env_file/environment；对齐兄弟服务补 container_name/restart/networks。

- [ ] **Step 5: nginx ports 删 8080:80、healthcheck 改 /50x.html、depends_on 加 sks-web**

Edit `docker-compose.yml` nginx 块：
- `ports`：删 `- "8080:80"` 本地调试映射（现第 84 行，§5.3 compose=部署用，本地调试走宿主进程），仅留 `- "80:80"`
- `depends_on`：加 `sks-web: { condition: service_healthy }`（`/` 反代静态），保留 `sks-server: { condition: service_healthy }`（`/api/` 反代 Java）
- `healthcheck` test 改：
```yaml
      test: ["CMD-SHELL", "wget -q --spider http://127.0.0.1/50x.html || exit 1"]
```
> 现探 `/`，拆分后 `/` 反代 sks-web——探 `/` 会穿透到 sks-web，sks-web 独立重启窗口里 gateway 被误标 unhealthy（违背独立部署互不连带）。改探 `/50x.html`：gateway 本地文件、恒在、不穿透，顺带验兜底页可达。interval/timeout/retries 不动（同现状）。

- [ ] **Step 6: networks/container_name/restart 顶层块不动**

Run: `cd /Users/rick/work/sks-agent && grep -nE 'networks:|container_name|restart:|sks-net' docker-compose.yml`
Expected: 顶层 `networks: sks-net: driver: bridge` + 各服务 `networks: - sks-net` + `container_name` + `restart: unless-stopped` 均在（骨架省略 ≠ 删除，一律保留）

- [ ] **Step 7: 验证 compose 语法**

Run: `cd /Users/rick/work/sks-agent && docker compose config --quiet`
Expected: 无报错（5 服务：postgres/sks-server/sks-ai/sks-web/nginx，顶层 networks + volumes 齐全）

### Task 12: 重写 deploy/nginx Dockerfile + nginx.conf（gateway 职）

**Files:**
- Create: `.dockerignore`（deploy 仓根，收窄 gateway build context）
- Modify: `deploy/nginx/Dockerfile`（删 node 阶段，改 `FROM nginx:alpine` + COPY 两文件）
- Modify: `deploy/nginx/nginx.conf`（基于现文件编辑，**勿整体覆盖**——保留三条承重注释；`location /` 改 proxy_pass；删 server 级 root/index；443 注释块同步；超时口径统一 240s）

- [ ] **Step 1: Dockerfile 删 node 阶段**

Edit `deploy/nginx/Dockerfile`。现文件含 `FROM node:22-alpine AS web-build` 阶段（COPY sks-web/、npm build）。删除整个 web-build 阶段，改为：
```dockerfile
FROM nginx:alpine
COPY deploy/nginx/nginx.conf /etc/nginx/conf.d/default.conf
COPY deploy/nginx/50x.html /usr/share/nginx/html/50x.html
```
> COPY 路径保持 `deploy/nginx/` 前缀不动（依赖 compose `context: .`）。若想收窄 context 到 `deploy/nginx` 加速 build，必须同时去掉 COPY 路径前缀——只改一边 build fail。§5.2 保留 `context: .`，故前缀不动；context 体积改由下一步的根 `.dockerignore` 收窄。

- [ ] **Step 1b: 建 deploy 仓根 .dockerignore（收窄 gateway build context）**

三服务仓各有 `.dockerignore`（Task 4/5/6），deploy 仓此前没有——而 gateway 用 `context: .`，`docker build` 会把整个仓库根打包送给 daemon，含 **`.git`（现 18MB，`git rm` 三目录不会缩小它，14MB 原型永久留在历史里）**。镜像不受影响（Dockerfile 是白名单式 COPY 两文件），纯属每次 `compose build nginx` 白传。

Create `.dockerignore`（deploy 仓根）:
```dockerignore
.git
.env
docs
deploy/backup
.claude
```
Expected: `docker build -f deploy/nginx/Dockerfile .` 的 "transferring context" 从十几 MB 降到 KB 级。注意**别写 `deploy`**——会把要 COPY 的 nginx.conf/50x.html 一起排除掉，build 直接 fail。

- [ ] **Step 2: nginx.conf 80 块——删 server 级 root/index、location / 改 proxy_pass（保留承重注释）**

Edit `deploy/nginx/nginx.conf`（基于现文件，**不覆盖**）：
- 删第 5-7 行 server 级 `root /usr/share/nginx/html; index index.html;`（gateway 不再 serve 静态；`location = /50x.html` 自带 root 不依赖 server 级）
- 第 9-12 行 50x 注释 + `error_page 500 502 503 504 /50x.html;`（注释 9-11、指令第 12 行）保留。拆分后此条更值钱：sks-web 容器挂时 `location /` 反代拿 502 → 仍走 error_page 渲染兜底页
- 第 13 行 `# 不加 internal：brief 验收要求 curl https://域名/50x.html 可直访兜底页。` **保留**（验收项，清单第 15/117 行 curl 它）
- 第 14-16 行 `location = /50x.html { root /usr/share/nginx/html; }` 保留
- 第 18-21 行超时链注释 **保留**（后果半句「nginx 不可短于 Java，否则会先掐断仍在跑的 Python 调用 → 假 AI_FAILED → 误退款」是最跟钱相关的一条注释）
- 第 22-30 行 `location /api/ { proxy_pass http://sks-server:8080/api/; ... }` 保留
- 第 32-35 行 `location / { try_files $uri $uri/ /index.html; }` 改为：
```nginx
    # 静态不再本地 serve —— 反代 sks-web 容器（try_files fallback 在 sks-web 内）
    location / {
        proxy_pass http://sks-web:80;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
```

- [ ] **Step 3: nginx.conf 超时链注释口径统一 240s**

Edit `deploy/nginx/nginx.conf` 第 19 行注释，把 `Python 内 LLM 单次 120s × 最多 2（原始+1 重试）≈ 250s` 改为 `Python 内 LLM 单次 120s × 最多 2（原始+1 重试）= 240s`。
> 与 GO_LIVE_CHECKLIST 第 17/90 行 `Python 240` 口径一致（拆分未引入两套口径，重写时顺手统一）。不影响 `300 > 270 > 240` 结论。

- [ ] **Step 4: nginx.conf 443 注释块同步改写**

Edit `deploy/nginx/nginx.conf` 末尾注释掉的 443 server 块（现第 44-68 行）：
- 删第 53-54 行 server 级 `root /usr/share/nginx/html; index index.html;`（gateway 无 dist，`location = /50x.html` 自带 root）
- 第 67 行 `location / { try_files $uri $uri/ /index.html; }` 改为 `location / { proxy_pass http://sks-web:80; ...同 80 块... }`
> 二阶陷阱：启用 TLS 时（取消注释 443 块 + 80 块换成 301 跳转），唯一 serve `/` 和 `/api/` 的就只剩 443 块——443 块 `location /` 若保留旧 try_files 会复活静态服务，gateway 无 dist → `/` 直接 404，全站黑。
> 跳转块不用手写：现文件**第 70-75 行**已备好注释掉的 `server { listen 80; return 301 https://$host$request_uri; }`，启用时取消注释并按其自带注释「删除上方 80 块」即可（第 41-42 行是对此的说明性提示，非可用配置）。本 Step 只改 443 块，70-75 行原样保留。

- [ ] **Step 5: 验证 nginx.conf 语法**

Run: `cd /Users/rick/work/sks-agent && docker build -t sks-gateway:plan-verify -f deploy/nginx/Dockerfile . && docker run --rm --add-host sks-web:127.0.0.1 --add-host sks-server:127.0.0.1 sks-gateway:plan-verify nginx -t`
Expected: build 成功（秒级，无 node 阶段）；`nginx -t` 输出 `syntax is ok, test is successful`
> ⚠️ 必须 `--add-host`：nginx 对字面 `proxy_pass http://sks-web:80`（非 upstream 块）在**解析期就做 DNS 解析**，脱离 compose 网络跑 `nginx -t` 会报 `host not found in upstream "sks-server"`——**即使 conf 完全正确也红**，照做的人会误判 conf 写错、回头改本正确的文件。`--add-host` 让名字解析到 127.0.0.1（`-t` 不发实际请求，能解析即可）；或起栈后 `docker compose exec nginx nginx -t`（网络内 DNS 天然有）。

### Task 13: 编辑 .env.example 补全为单份全量

**Files:**
- Modify: `.env.example`（按拆分 spec §4 枚举逐行核，补齐缺失键）

- [ ] **Step 1: 补全 .env.example**

Edit `.env.example`，按拆分 spec §4 表逐行核对，确保含。注释一律**另起一行**（现 .env.example 是纯 `KEY=VALUE`，行尾内联注释在 compose dotenv 解析下不稳，别引入）：
```dotenv
POSTGRES_DB=sks
POSTGRES_USER=sks
POSTGRES_PASSWORD=change_me
# JWT：change_me_... 占位值会被 JwtConfig.guardSecret 启动即抛，必须换 ≥32 字节真值
JWT_SECRET_USER=change_me_user_secret_min_32_bytes
JWT_SECRET_ADMIN=change_me_admin_secret_min_32_bytes
SERVICE_TOKEN=change_me_internal_shared_token
ADMIN_SEED_USERNAME=admin
ADMIN_SEED_PASSWORD=change_me_admin_login_pwd
TRIAL_CREDIT=3
ALIYUN_ACCESS_KEY_ID=
ALIYUN_ACCESS_KEY_SECRET=
# DYPNS 短信：四键无默认必填，漏配走 stub 静默不发（藏得住，靠此表枚举兜底）
ALIYUN_SMS_SIGN=
ALIYUN_SMS_TEMPLATE_LOGIN=
ALIYUN_SMS_TEMPLATE_VERIFY_OLD=
ALIYUN_SMS_TEMPLATE_BIND_NEW=
# ALIYUN_SMS_ENDPOINT 有默认 dypnsapi.aliyuncs.com，可不入 .env
# Python ASR：两键无默认必填
ALIYUN_ASR_KEY=
ALIYUN_ASR_APP_KEY=
ZHIPU_API_KEY=
TIKHUB_API_KEY=
# 邮件告警：host/收件人空走 stub 静默不发
SPRING_MAIL_HOST=
SPRING_MAIL_USERNAME=
SPRING_MAIL_PASSWORD=
SKS_ALERT_ADMIN_EMAIL=
# SPRING_MAIL_PORT 有默认 465（ssl.enable=true 已钉 yaml 不走 env）
# ZHIPU_BASE_URL / TIKHUB_BASE_URL / ALIYUN_CONTENT_SAFETY_ENDPOINT 有默认，一般不改
```
> 漏配 SMS/MAIL 不报错——DYPNS/MailAlertNotifier key 空 → stub 静默不发，缺键藏得住，故靠此表枚举兜底。`.env` 真值 gitignored 不进 git。

- [ ] **Step 2: 验证 .env.example 与 §4 闭环**

Run: `cd /Users/rick/work/sks-agent && diff <(grep -oE '^[A-Z_]+' .env.example | sort -u) <(echo -e "POSTGRES_DB\nPOSTGRES_USER\nPOSTGRES_PASSWORD\nJWT_SECRET_USER\nJWT_SECRET_ADMIN\nSERVICE_TOKEN\nADMIN_SEED_USERNAME\nADMIN_SEED_PASSWORD\nTRIAL_CREDIT\nALIYUN_ACCESS_KEY_ID\nALIYUN_ACCESS_KEY_SECRET\nALIYUN_SMS_SIGN\nALIYUN_SMS_TEMPLATE_LOGIN\nALIYUN_SMS_TEMPLATE_VERIFY_OLD\nALIYUN_SMS_TEMPLATE_BIND_NEW\nALIYUN_ASR_KEY\nALIYUN_ASR_APP_KEY\nZHIPU_API_KEY\nTIKHUB_API_KEY\nSPRING_MAIL_HOST\nSPRING_MAIL_USERNAME\nSPRING_MAIL_PASSWORD\nSKS_ALERT_ADMIN_EMAIL" | sort -u) && echo OK`
Expected: 输出 `OK`（§4 必填键全在 .env.example，无差）

### Task 14: 编辑 deploy/OPS.md（镜像化 + 部署机初始化 + 死路径 + 超时口径 + certbot）

**Files:**
- Modify: `deploy/OPS.md`（多处）

> 现状核实（grep 过全文）：OPS.md **不含** docker-ce 安装参数、**不含** GHCR 预验命令——拆分 spec §8「已写进 OPS.md」的描述与实际不符，须**补写**非确认。`--build` 出现 4 处（第 7/38/151/193 行）；超时 `250s` 出现 2 处（第 134/139 行）；死路径 2 处（第 5/134 行指向将删的子目录）；certbot `apt` 1 处（第 32 行）。

- [ ] **Step 1: --build 流程改镜像化（第 7/38/151/193 行 + 加场景表）**

Edit `deploy/OPS.md`，4 处 `docker compose ... up -d --build` 改镜像化：
- 第 7 行「首次起栈前置」：`docker compose up -d --build` → `docker compose pull --ignore-buildable && docker compose up -d`；"卡在拉 Docker Hub 基础镜像" 改 "卡在拉镜像——ghcr.io 三服务镜像见 Step 2 GHCR 预验；Docker Hub 的 pgvector/nginx:alpine 见 §8 加速器"（compose up 仍要从 Docker Hub 拉这两个，别把原有指引丢掉）。
- 第 38 行（certbot 步骤 4）：`docker compose up -d --build nginx` → `docker compose build nginx && docker compose up -d nginx`（gateway 仍本地 build）。
- 第 151、193 行：`docker compose --env-file .env up -d --build` → `docker compose pull --ignore-buildable && docker compose up -d`。
另在文件靠前处加拆分 spec §6「--build 心智模型」场景表（迁移生效/前端发版/重建/回滚）。

| 场景 | 新（镜像化） |
|---|---|
| 新增 Flyway 迁移生效 | sks-server 仓发新 tag → CI 出镜像 → deploy 仓 bump `sks-server.image` tag → `compose pull sks-server && compose up -d sks-server` |
| 前端发版 | sks-web 仓发新 tag → CI 出镜像 → deploy 仓 bump `sks-web.image` tag → `pull sks-web && up -d sks-web` |
| 重建/首次起栈 | `compose pull --ignore-buildable`（需 Compose v2.22+，老版本 fallback `compose pull sks-server sks-ai sks-web`）→ `compose up -d` |
| 回滚部署 | deploy 仓把 `<svc>.image` tag 改回上一版 → `pull && up -d` |

- [ ] **Step 2: 补写「部署机初始化」节（docker-ce 安装 + GHCR 预验 + docker login）**

OPS.md 现无此节，**补写**（非确认；spec §8「已写进」与实际不符）：
```bash
# docker-ce 安装（Aliyun Linux 用 dnf；以下是在部署机实际走通的完整序列，缺一步都装不上）
# 1) 先加 docker-ce repo（裸机没有，直接 dnf install docker-ce 报无此包）
sudo dnf install -y dnf-plugins-core
sudo dnf config-manager --add-repo https://mirrors.aliyun.com/docker-ce/linux/centos/docker-ce.repo
# 2) 装引擎 + compose 插件（compose v2.22+ 是部署硬依赖，勿漏 docker-compose-plugin）
#    --setopt=install_weak_deps=False 跳过 rootless-extras（弱依赖，镜像源缺包会导致整个事务失败）
sudo dnf install -y --setopt=install_weak_deps=False docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo systemctl enable --now docker
docker --version && docker compose version   # compose ≥ v2.22（--ignore-buildable 需要）

# GHCR 国内可达性预验（拆分动手前即可验，不必等镜像出）
curl -sS -o /dev/null -w '%{http_code}\n' --max-time 8 https://ghcr.io/v2/   # 401 = 通
curl -sS -o /dev/null -w '%{http_code}\n' --max-time 8 https://pkg-containers.githubusercontent.com/   # blob 后端可达
docker pull ghcr.io/astral-sh/uv:latest   # 实拉小型公共 GHCR 镜像 = 整条链通

# 三服务镜像 private，拉取前认证（交互输入 PAT，需 read:packages 权限）
docker login ghcr.io -u <github-user>
# 或把三 package 设为 public，免 login
```
> 预验通过后此风险退役；§7.4 gate 2 实拉只剩验 private 认证。

- [ ] **Step 3: 改写镜像加速器节（§8 节，第 149-198 行）**

镜像化后 compose up 拉的是 **ghcr.io 三服务镜像**（不吃 Docker Hub mirror）+ Docker Hub 的 `pgvector/pgvector:pg16`、`nginx:alpine`（gateway 本地 build，吃 mirror）。原节"拉三个 Docker Hub 基础镜像 node/python/temurin"作废——node/python/temurin 已 bake 进 GHCR 服务镜像。改写为：
- 仍需 mirror 的：`pgvector`、`nginx:alpine`（Docker Hub）。
- **不吃 mirror** 的：`ghcr.io/wangbuer1984/<svc>` 三服务镜像——mirror 只对 docker.io 生效，ghcr 不走；ghcr 不可达见 Step 2 预验 / fallback（阿里云 ACR 个人版双推 或 `docker save`→scp→`docker load`）。
- colima / Docker Desktop / 原生 dockerd 配置表保留（对 Docker Hub 镜像仍适用）。

- [ ] **Step 4: 修死路径（第 5、134 行指向将删子目录）**

Edit `deploy/OPS.md`：
- 第 5 行 `sks-server/src/main/java/com/sks/common/QuotaWatchJob.java` → `sks-server 仓 src/main/java/com/sks/common/QuotaWatchJob.java`（deploy 仓已删 sks-server/，指向服务仓）。
- 第 134 行 `sks-ai/app/llm/` → `sks-ai 仓 app/llm/`。

- [ ] **Step 5: 超时口径 250s→240s（第 134、139 行）**

Edit `deploy/OPS.md`：
- 第 134 行 `120s × 最多 2（原始+1 重试）≈ 250s` → `= 240s`。
- 第 139 行 `链路：250s < 270s < 300s` → `链路：240s < 270s < 300s`。
> 与 nginx.conf（Task 12 Step 3）+ GO_LIVE_CHECKLIST 第 17/90 行统一为 240。

- [ ] **Step 6: certbot 安装 apt→dnf（第 32 行）**

Edit `deploy/OPS.md` 第 32 行 `sudo apt update && sudo apt install -y certbot python3-certbot-nginx` → `sudo dnf install -y certbot python3-certbot-nginx`（部署机 Aliyun Linux 用 dnf；dnf 无需单独 update）。

- [ ] **Step 7: 验证 OPS.md 无残留 --build / 250 / apt / 死路径**

Run: `cd /Users/rick/work/sks-agent && grep -nE 'up -d --build|≈ 250|250s <|\bapt install|sks-server/src/|sks-ai/app/' deploy/OPS.md`
Expected: 无匹配（--build 全改镜像化、250 全改 240、apt 改 dnf、子目录路径全指服务仓）

### Task 15: 编辑 CLAUDE.md（四仓总览 + 指向）

**Files:**
- Modify: `CLAUDE.md`（改成四仓总览）

- [ ] **Step 1: 重写 CLAUDE.md 为四仓架构总览**

Edit `CLAUDE.md`，改成四仓总览：
- 顶部说明：本仓为 deploy 仓，四仓架构（sks-server/sks-ai/sks-web 各自 GHCR 镜像独立发版 + 本仓 gateway 本地 build + compose 编排 + 单份 .env）。
- 架构图：`浏览器 → nginx(gateway,本地build) → /api/ → sks-server(image)；/ → sks-web(image)`；`sks-server → 内网 HTTP+X-Service-Token → sks-ai(image)`；`PostgreSQL 16 + pgvector`。
- **指向三服务仓 scoped CLAUDE.md**：硬不变量与构建测试命令在各服务仓 `CLAUDE.md`（sks-server / sks-ai / sks-web）。
- **原型指向 sks-web 仓 `prototypes/`**（两份原型已 `git rm` 出本仓，避免死引用）。
- **删「Python packages」节、build commands 分仓**（原根 CLAUDE.md 的 sks-ai/sks-server/sks-web 包结构节 + build/test/run commands 节随服务仓搬走，本仓只留 compose/gateway 部署命令）。
- **删根 CLAUDE.md 对原型的旧引用位置描述**（原型现归 sks-web 仓 prototypes/）。

- [ ] **Step 2: 验证无死引用**

Run: `cd /Users/rick/work/sks-agent && grep -n 'sks-ai/\|sks-server/\|sks-web/' CLAUDE.md`
Expected: 无指向已删子目录的路径引用（若引用应改为指向各服务仓）

### Task 16: 编辑 deploy/GO_LIVE_CHECKLIST.md（5 容器 + 第 70 行 443 扩写）

**Files:**
- Modify: `deploy/GO_LIVE_CHECKLIST.md`

- [ ] **Step 1: 4 容器 → 5 容器**

Edit `deploy/GO_LIVE_CHECKLIST.md` 第 13 行。原文（整行照录，含尾部 P0–P5 半句，勿漏）：
```markdown
- ✅ 4 容器全 healthy（postgres / sks-server / sks-ai / nginx，P0–P5 最新代码）
```
改为：
```markdown
- ✅ **5 容器**全 healthy（postgres / sks-server / sks-ai / sks-web / nginx，P0–P5 最新代码；sks-server/sks-ai/sks-web 为 GHCR 镜像，gateway 本地 build）
```

- [ ] **Step 2: 扩写 certbot/TLS 验收项（现第 70 行）**

Edit `deploy/GO_LIVE_CHECKLIST.md` 第 70 行 `- [ ] **certbot Let's Encrypt**：...nginx 443 server block 取消注释；...`，扩写为：
```markdown
- [ ] **certbot Let's Encrypt**：真实域名签发证书 + 续期 crontab；nginx 443 server block 取消注释 + **443 块 `location /` 改 `proxy_pass http://sks-web:80`（同 80 块）+ 删 443 块 server 级 root/index**（见 gateway nginx.conf）；`certbot renew --dry-run` 通过
```
> 签证书那天操作者只读此清单不读拆分 spec——警告必须落在此处。启用 443 时 80 块换 301 跳转，唯一 serve `/`+`/api/` 的就只剩 443 块，写错即全站黑。

- [ ] **Step 3: 改镜像化起栈命令（第 11、113 行 --build）**

Edit `deploy/GO_LIVE_CHECKLIST.md`：
- 第 11 行 `以下在 docker compose --env-file .env up -d --build 后已端到端验证：` → `以下在 docker compose pull --ignore-buildable && docker compose up -d 后已端到端验证：`
- 第 113 行 `docker compose --env-file .env up -d --build` → `docker compose pull --ignore-buildable && docker compose up -d`
> spec §6 要求 OPS.md 与 GO_LIVE_CHECKLIST 都改 --build 心智模型——清单这两处原本漏改（Task 16 旧版只做 4→5 容器 + 第 70 行 443）。

- [ ] **Step 4: 确认超时口径 240 与 nginx.conf 一致**

Run: `cd /Users/rick/work/sks-agent && grep -n '240\|250' deploy/GO_LIVE_CHECKLIST.md`
Expected: 只见 `240`（Python 240<Java 270<nginx 300），无 `250`（Task 12 Step 3 + Task 14 Step 5 已全统一）

### Task 17: 新增 README.md（部署全栈说明）

**Files:**
- Create: `sks-agent/README.md`

- [ ] **Step 1: 建 README.md**

Create `README.md`:
```markdown
# sks-agent-deploy

部署/编排仓。四仓架构：sks-server/sks-ai/sks-web 各自 GHCR 镜像独立发版，本仓 gateway 本地 build + 单 compose 编排 + 单份 .env。

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
```

### Task 18: 提交 deploy 仓改造 + push

- [ ] **Step 1: 检查改动清单**

Run: `cd /Users/rick/work/sks-agent && git status --short`
Expected: staged 删除（三目录 + 两原型）+ 修改（docker-compose.yml、deploy/nginx/Dockerfile、deploy/nginx/nginx.conf、.env.example、deploy/OPS.md、CLAUDE.md、deploy/GO_LIVE_CHECKLIST.md）+ 新增（README.md）

- [ ] **Step 2: 提交**

Run:
```bash
cd /Users/rick/work/sks-agent
git add -A
git commit -m "chore: 四仓拆分——本仓变为 deploy 仓（sks-server/sks-ai/sks-web 见各自仓 + GHCR 镜像，nginx 拆静态/网关）"
git push
```
Expected: commit + push 成功

---

## Phase 5 — 清理 + 部署验证

### Task 19: 清理临时分支 + 起栈验证 5 容器 healthy

- [ ] **Step 1: 清理 split 临时分支**

Run: `cd /Users/rick/work/sks-agent && git branch -D split-sks-ai split-sks-server split-sks-web`
Expected: 三分支删除

- [ ] **Step 2: 起栈**

Run（**部署机 amd64**，已 `docker login ghcr.io` + `.env` 填好；不在本地 mac arm64 跑——amd64 镜像走模拟慢且 healthy 可能 flaky，§5.3 本地调试走宿主进程不用 compose）:
```bash
cd <deploy 仓 clone 路径>   # 非本机 Mac 路径 /Users/rick/...，按部署机实际 clone 位置
docker compose pull --ignore-buildable
docker compose up -d
```
Expected: 按 depends_on 起五服务，gateway 本地 build（秒级）

- [ ] **Step 3: 等 5 容器全 healthy**

Run: `docker compose ps`
Expected: postgres / sks-server / sks-ai / sks-web / nginx 五服务 `healthy`（gateway 探 `/50x.html`，sks-web 探 `/`，sks-server 探 `/api/health`，sks-ai 探 `/health`，pg `pg_isready`）

- [ ] **Step 4: 验证核心链路**

Run:
```bash
curl -s localhost/api/health                          # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}\n' localhost/   # 200（前端经 gateway→sks-web）
curl -s -o /dev/null -w '%{http_code}\n' localhost/50x.html  # 200（兜底页）
```
Expected: 三条均 200/UP——拆分后 `/` 经 gateway 反代 sks-web（前端可达），`/api/` 反代 sks-server（Java 健康），`/50x.html` gateway 本地（兜底页可达）

---

## Self-Review（计划作者自查）

**1. Spec coverage**：拆分 spec §7.1→Phase 1（Task 1-3）；§7.2→Phase 2（Task 4-6）；§7.3→Phase 3（Task 7-9）；§7.4→Phase 4（Task 10-18，含 git rm / compose / nginx / .env.example / OPS.md / CLAUDE.md / GO_LIVE_CHECKLIST / README）；§7.5→Phase 5 Task 19 Step 1。§3.2 清理（config.py + Dockerfile）→Task 4 Step 4-5。§3.3 sks-web Dockerfile/nginx.conf→Task 6 Step 2-3。§3.4 gateway nginx.conf 骨架+三承重注释+443 陷阱→Task 12。§4 env 枚举→Task 13。§5.2 compose 五服务+healthcheck+networks/volumes→Task 11。§6 文档归属+scoped CLAUDE.md→Task 4-6 各 Step 7 + Task 15-16。§7.4.1 部署运行→Task 19 Step 2-4。§8 gate（镜像 published+可拉取）→Phase 3 各 Task 末 + Task 10 Step 1。**Task 14 经复核扩为 7 步**（覆盖 OPS.md 全部 `--build`×4 / 镜像加速器节重写 / 死路径×2 / 超时 250→240×2 / certbot apt→dnf / 部署机初始化补写）；**Task 16 补 Step 3** 改 GO_LIVE 第 11/113 行 `--build`。初版 Self-Review 写「无遗漏」过于乐观——实漏 OPS.md 与 GO_LIVE 多处，本轮已补齐。

**2. Placeholder scan**：无 TBD/TODO/"implement later"；CI workflow、Dockerfile、nginx.conf、.gitignore、.dockerignore、.env.example、scoped CLAUDE.md、README 均给全文；契约文档（API_CONTRACT/REST_CONTRACT）的"实现期补全"部分给了具体抽取命令（`grep -rn ErrorCode ...`、`grep -l DATABASE_URL tests/`）非空指令。

**3. Type consistency**：服务名统一 `nginx`（命令语境，gateway 是角色名）——Task 11/19 命令均 `nginx`，无 `nginx-gateway`。镜像 tag 统一 `v0.1.0`（Task 7-9 打 tag、Task 11 compose 引用）。超时口径统一 `240s`（Task 12 Step 3 nginx.conf + Task 14 Step 5 OPS.md + Task 16 Step 4 验证，三文件同口径）。`SKS_AI_BASE_URL` 默认 `http://sks-ai:8000`（Task 5 .env.example 注释 + scoped CLAUDE.md 不变量）。healthcheck 探测目标：gateway `/50x.html`、sks-web `/`、sks-server `/api/health`、sks-ai `/health`、pg `pg_isready`——Task 11/19 一致。**application-local.yml**：已核实**未被 git 追踪**（split 不带走、无口令入史风险）；gitignored（Task 5 Step 1 `.gitignore` 含 `**/application-local.yml`，与原根 gitignore 同款，挡本地口令），**不提交**；现存文件由 Task 10 Step 3 迁去新仓并把 import 从老 monorepo 绝对路径改相对路径，Task 5 README 另给从零创建模板。**nginx -t 验证**：Task 12 Step 5 用 `--add-host sks-web/sks-server:127.0.0.1`（字面 proxy_pass 解析期 DNS，脱网必加）。**.dockerignore 四仓齐**：三服务仓（Task 4/5/6）+ deploy 仓根（Task 12 Step 1b，收窄 gateway `context: .`，排 `.git` 18MB；勿排 `deploy` 本身否则 COPY 失败）。**CI 真库用例**：sks-ai `test` job 内联 pgvector services + `SKS_REQUIRE_REAL_DB=1`，探测依赖 `docker` SDK 经 `testcontainers[postgres]` 传递引入（Task 4 Step 6 已核实，勿删该 dev 依赖）。**.env.example**：注释另起一行（Task 13 + Task 5），无行尾内联 `#`（compose dotenv 不剥行尾注释，会污染值）。**JWT 占位值**：Task 5/13 标注 `change_me_...` 会被 `JwtConfig.guardSecret` 启动即抛，须换真值。

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-29-sks-agent-split.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
