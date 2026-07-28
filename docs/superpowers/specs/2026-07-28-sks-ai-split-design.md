# sks-ai 拆分为独立仓库 — 设计文档

> 日期：2026-07-28
> 状态：已通过 brainstorming，待用户复核后进 writing-plans

## 1. 目标与动机

把 `sks-ai`（Python FastAPI AI 服务）从当前 monorepo 拆出，形成**独立仓库 + 独立 `.env` + 独立部署 compose**，以便 sks-ai 能**独立部署 / 独立发版**，与 sks-server 解耦发版节奏。

**已排除的替代方案**：monorepo + per-service CI（不拆仓库，仅独立 CI/CD）——用户明确要仓库级分离。

## 2. 关键决策（brainstorming 已定）

| 决策 | 选择 | 理由 |
|---|---|---|
| 是否拆仓库 | 是，方案 A 干净拆分 | 满足独立部署/发版目标，最简 |
| Postgres | 连同一个 pg 实例（共享） | 保留「单库三合一」+ `analyze_task`/`kb_card` 共享表读写设计；拆库会破坏架构，不取 |
| Git 历史 | 保留（`git subtree split` 抽离） | 追溯性好 |
| 部署 artifact | 独立 `docker-compose.yml` | 与现有 Dockerfile 一致，最简 |

## 3. 仓库结构与文件归属

### 3.1 新仓库 `sks-ai`（独立 repo）

```
sks-ai/
├── app/                      ← 原样搬过来
├── tests/                    ← 原样搬过来
├── Dockerfile                ← 原样
├── pyproject.toml            ← 原样
├── uv.lock                   ← 原样
├── docker-compose.yml        ← 新增（只跑 sks-ai，连外部 pg）
├── .env.example              ← 新增（sks-ai 的 env 契约）
├── .gitignore                ← 新增（.env 等）
├── README.md                 ← 新增（怎么跑/部署）
└── docs/
    └── API_CONTRACT.md       ← 新增（/ai/* 端点契约，给 Java 仓消费）
```

### 3.2 原仓库（变 sks-server + sks-web + deploy + docs）

- 删掉 `sks-ai/` 目录
- `docker-compose.yml` 移除 sks-ai 服务块（保留 sks-server + nginx + postgres）
- `CLAUDE.md`、`docs/`、`deploy/` 更新：sks-ai 描述从「子目录」改为「独立仓库」，跨仓协调靠 env
- 根 `.env.example` 去掉 sks-ai 专用 key，保留共享 key

## 4. .env 跨仓契约

两仓 `.env` 分工：

| key | sks-ai `.env` | 原仓 `.env` | 说明 |
|---|---|---|---|
| `SERVICE_TOKEN` | ✅ | ✅ | **必须两边一致**（Java↔Python 鉴权）|
| `DATABASE_URL` / `POSTGRES_*` | ✅（DATABASE_URL）| ✅（POSTGRES_*）| **连同一个 pg**，值对得上 |
| `ALIYUN_ACCESS_KEY_ID/SECRET` | ✅ | ✅ | 同一阿里云账号，两边一致 |
| `ZHIPU_API_KEY`、`TIKHUB_API_KEY` | ✅ | ❌ | sks-ai 专用 |
| `ALIYUN_ASR_KEY`、`ALIYUN_ASR_APP_KEY` | ✅ | ❌ | sks-ai 专用（ASR）|
| `JWT_SECRET_*`、`ADMIN_SEED_*`、`TRIAL_CREDIT`、`ALIYUN_SMS_TEMPLATE_*`、`SPRING_MAIL_*` | ❌ | ✅ | 原仓专用 |

**跨仓共享密钥**（`SERVICE_TOKEN`、pg 凭据、`ALIYUN_ACCESS_KEY_*`）写进两边 `.env.example` 注释 + 各自 README「跨仓契约」小节：轮换时两边都要改。这是方案 A 唯一协调负担，靠文档约束。

## 5. sks-ai 的 docker-compose.yml

```yaml
services:
  sks-ai:
    build: .
    container_name: sks-ai
    restart: unless-stopped
    env_file: .env
    environment:
      # 连外部共享 pg（由 sks-server 仓的 compose 管或独立 pg）。值来自 .env。
      DATABASE_URL: ${DATABASE_URL}
    ports:
      - "8000:8000"          # 暴露给 sks-server 调用 / 本地 curl
    healthcheck:
      test: ["CMD-SHELL", "python -c \"import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8000/health').status==200 else 1)\""]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 20s
```

**决策点**：
- 不含 pg 服务——连外部共享 pg。`DATABASE_URL` 从 `.env` 读。
- `ports: 8000:8000` 暴露宿主 8000（生产想藏内网可改 `expose`）。
- `build: .`——仓库根即原 `sks-ai/` 内容，Dockerfile 在根。
- 跟原仓 sks-ai 块差异：① 无 `depends_on: postgres`（外部 pg，连不上走 `/health 仍 UP` 兜底）；② `ports` 代替 `expose`。

## 6. 文档归属

| 文档 | 去向 |
|---|---|
| `随口说PRD .md`、tech-design、MVP plan、`deploy/OPS.md`、`GO_LIVE_CHECKLIST.md`、学习文档 | 留原仓 |
| `docs/API_CONTRACT.md` | 新建到 sks-ai 仓 |
| `README.md`（sks-ai 新仓）| 怎么本地跑（`uv sync`/`uv run uvicorn`）、docker 跑、`DATABASE_URL`/`.env` 契约、健康检查 |
| `CLAUDE.md`（原仓）| 架构图把 sks-ai 改成「独立仓，HTTP+X-Service-Token 跨仓调用」；删/改「Python packages」节；build commands 删 sks-ai 命令 |

**API_CONTRACT.md 内容**：`/ai/*` 端点形状、`X-Service-Token`/`X-Request-Id` 头、请求/响应体、§5.3 超时链（Python LLM 120s×≤2 ≈ 250s < Java AiClient 270s < nginx 300s）。从现有 `AiClient.java` 注释 + pydantic models 抽取。拆仓后最大腐化风险是 Java record 与 Python model 字段漂移，此文档是两仓字段契约真相。

## 7. 迁移步骤

### 7.1 抽离 sks-ai 到新仓库（带历史）
```bash
cd /Users/rick/work/sks-agent
git subtree split --prefix=sks-ai -b split-sks-ai
mkdir /Users/rick/work/sks-ai && cd /Users/rick/work/sks-ai
git init
git pull /Users/rick/work/sks-agent split-sks-ai --allow-unrelated-histories
```

### 7.2 给新仓补独立部署 artifact
在 sks-ai 新仓根加：`docker-compose.yml`（§5）、`.env.example`、`.gitignore`、`README.md`、`docs/API_CONTRACT.md`。逐个 commit。

### 7.3 建 GitHub 远程 + push
```bash
git remote add origin git@github.com:WangBuer1984/sks-ai.git
git branch -M main
git push -u origin main
```

### 7.4 原仓库清理 sks-ai
```bash
cd /Users/rick/work/sks-agent
git rm -r sks-ai
# 编辑 docker-compose.yml：删 sks-ai 服务块
# 编辑 CLAUDE.md / docs：更新 sks-ai 描述
# 编辑 .env.example：删 sks-ai 专用 key
git commit -m "chore: 拆出 sks-ai 为独立仓库（见 sks-ai 仓 API_CONTRACT）"
git push
```

### 7.5 清理临时分支
```bash
cd /Users/rick/work/sks-agent && git branch -D split-sks-ai
```

## 8. 风险与回滚

- **不可逆但可恢复**：原仓 `git rm -r sks-ai` 后，历史里 sks-ai 仍在（`git log -- sks-ai/` 可查、可 `git revert` 或从历史 checkout）。新仓有完整历史。拆错能从 git 恢复，不丢代码。
- **执行顺序**：先抽新仓并 push 成功（7.1-7.3），再动原仓（7.4）。抽离出问题时原仓未动，安全。
- **.env 真值不进 git**：两边 `.env` 都 gitignored，共享密钥靠手动从原仓 `.env` 抄到新仓 `.env`。
- **跨仓契约漂移**：`SERVICE_TOKEN`/pg 凭据/`ALIYUN_ACCESS_KEY_*` 轮换需两边同步，靠 README「跨仓契约」约束。`AiClient` record 与 pydantic model 字段变更靠 `docs/API_CONTRACT.md` 约束。

## 9. 不在本次范围

- 不拆 sks-web（前端仍留原仓）。
- 不改 sks-server 的 AiClient 逻辑（只更新文档指向 sks-ai 仓）。
- 不引入镜像 registry / CI（方案 A 不需要；后续要全栈单 compose 编排可再上方案 B）。
- 不拆 Postgres（保留共享单库）。
