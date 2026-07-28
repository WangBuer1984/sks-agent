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
| 跨 compose 网络 | **external 共享 docker 网络 `sks-net`** | 两 compose 默认各有独立网络，`postgres`/`sks-ai` DNS 名跨项目解析不到 → pg、sks-server→sks-ai 三链路全断。共享 external 网络让 DNS 名跨项目成立，`DATABASE_URL`/`SKS_AI_BASE_URL` 一个不用改，sks-ai 保持 `expose` 不发宿主端口，硬约束不破 |

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
| `DATABASE_URL` / `POSTGRES_*` | ✅（DATABASE_URL）| ✅（POSTGRES_*）| **连同一个 pg**（`postgres` 容器在共享 `sks-net`），值对得上 |
| `ALIYUN_ACCESS_KEY_ID/SECRET` | ✅ | ✅ | 同一阿里云账号，两边一致 |
| `ZHIPU_API_KEY`、`TIKHUB_API_KEY` | ✅ | ❌ | sks-ai 专用 |
| `ZHIPU_BASE_URL`、`TIKHUB_BASE_URL`、`ALIYUN_CONTENT_SAFETY_ENDPOINT` | ✅（注释带默认值）| ❌ | config.py 有默认值，`.env.example` 列出注释「默认 xxx，一般不改」 |
| `ALIYUN_ASR_KEY`、`ALIYUN_ASR_APP_KEY` | ✅ | ❌ | sks-ai 专用（ASR）|
| `JWT_SECRET_*`、`ADMIN_SEED_*`、`TRIAL_CREDIT`、`ALIYUN_SMS_TEMPLATE_*`、`SPRING_MAIL_*`、`ALIYUN_SMS_SIGN` | ❌ | ✅ | 原仓专用 |

**`ALIYUN_SMS_SIGN` 清理**：Python 的 `config.py` 声明了它但全代码库无一处使用（SMS 是 Java 侧 DYPNS 的事）。拆分正是清掉它的好时机——从 `config.py` 字段 + 原 compose `sks-ai` 块的 `environment` 传参里一并删除，env 契约表不列它。

**跨仓共享密钥**（`SERVICE_TOKEN`、pg 凭据、`ALIYUN_ACCESS_KEY_*`）写进两边 `.env.example` 注释 + 各自 README「跨仓契约」小节：轮换时两边都要改。这是方案 A 唯一协调负担，靠文档约束。

## 5. 跨 compose 网络（核心决策）+ sks-ai 的 docker-compose.yml

### 5.1 为什么必须 external 共享网络

两个独立 compose 项目默认各有各的网络，拆开后三条链路同时断：

- **sks-ai → pg**：`DATABASE_URL` 指 `postgres:5432`（compose 内网 DNS 名），新 compose 解析不到；原仓 compose 的 pg 没发宿主端口，没有值能让 sks-ai 连上。
- **sks-server → sks-ai**：`SKS_AI_BASE_URL=http://sks-ai:8000`（`application.yml` 默认），`sks-ai` DNS 名跨 compose 解析不到。
- **`ports: 8000:8000`**：发到宿主所有接口，云服务器不配防火墙就公网可达，违反 CLAUDE.md「Python 不暴露公网」硬约束。而 `expose` 在独立 compose 下只对自己项目网络有效，sks-server 够不着——一个不安全一个不工作。

**解法**：external 共享 docker 网络 `sks-net`。

```bash
docker network create sks-net   # 一次性建好（部署前）
```

两仓 compose 都把 `sks-net` 声明为 `external: true`（不再各自建同名 bridge 网络）。效果：
- `postgres`、`sks-ai` 两个 DNS 名跨项目照常解析。
- `DATABASE_URL`、`SKS_AI_BASE_URL` **一个都不用改**（仍用容器名）。
- sks-ai 保持 `expose: 8000`（不发宿主端口），硬约束原样保住，sks-server 经 `sks-net` reach 到它。

### 5.2 sks-ai 的 docker-compose.yml

```yaml
services:
  sks-ai:
    build: .
    container_name: sks-ai
    restart: unless-stopped
    env_file: .env
    environment:
      # 连共享 pg（postgres 容器在 sks-net 上，原仓 compose 管）。值来自 .env。
      DATABASE_URL: ${DATABASE_URL}
      SERVICE_TOKEN: ${SERVICE_TOKEN}
    expose:
      - "8000"              # 只在 sks-net 内可达，不发宿主（硬约束：Python 不暴露公网）
    networks:
      - sks-net
    healthcheck:
      test: ["CMD-SHELL", "python -c \"import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8000/health').status==200 else 1)\""]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 20s

networks:
  sks-net:
    external: true           # 部署前 docker network create sks-net
```

### 5.3 原仓 docker-compose.yml 的对应改动

- 删 sks-ai 服务块（§3.2）。
- `networks: sks-net` 从 `driver: bridge`（本地建）改成 `external: true`。
- pg 仍由原仓 compose 管，在 `sks-net` 上 → sks-ai 经 `DATABASE_URL=...@postgres:5432/...` 连上。

### 5.4 本地调试不受影响

本地调试走 PyCharm/终端 `uv run uvicorn`（宿主 8000）或 IDEA Java（宿主 8080），不依赖 compose 网络。compose 是部署用，本地 dev 用各自的进程。`expose` 不发宿主不影响本地调试（本地根本不用 compose 跑 sks-ai）。

### 5.5 启动顺序（必须钉死，见 §8 风险）

checkpointer 只在启动时初始化一次、无懒重试（不同于 asyncpg 池）。若 sks-ai 先于 pg 起来，interview 端点会一直坏，而 `/health` 显示 UP、`restart: unless-stopped` 不救（进程没死）。README 必须钉死：**先起原仓 compose（pg + sks-server），后起 sks-ai compose**。

## 6. 文档归属

| 文档 | 去向 |
|---|---|
| `随口说PRD .md`、tech-design、MVP plan、`deploy/OPS.md`、`GO_LIVE_CHECKLIST.md`、学习文档 | 留原仓 |
| `docs/API_CONTRACT.md` | 新建到 sks-ai 仓 |
| `README.md`（sks-ai 新仓）| 怎么本地跑（`uv sync`/`uv run uvicorn`）、docker 跑、`DATABASE_URL`/`.env` 契约、健康检查、**启动顺序（先原仓 compose 后 sks-ai，见 §5.5）** |
| `CLAUDE.md`（原仓）| 架构图把 sks-ai 改成「独立仓，HTTP+X-Service-Token 跨仓调用」；删/改「Python packages」节；build commands 删 sks-ai 命令 |
| `deploy/GO_LIVE_CHECKLIST.md`（原仓）| 点名改「4 容器全 healthy」为「原仓 3 容器（postgres/sks-server/nginx）healthy + sks-ai 独立 compose 在 `sks-net` 上 healthy」，泛泛「更新」易漏 |

**API_CONTRACT.md 两个契约面**：

1. **HTTP 端点契约**：`/ai/*` 端点形状、`X-Service-Token`/`X-Request-Id` 头、请求/响应体、§5.3 超时链（Python LLM 120s×≤2 ≈ 250s < Java AiClient 270s < nginx 300s）。从现有 `AiClient.java` 注释 + pydantic models 抽取。拆仓后 Java record 与 Python model 字段漂移是第一类腐化风险，此文档是字段契约真相。

2. **共享表契约**（更隐蔽，必须单列一节）：sks-ai 直接读写 `kb_card`、`analyze_task`（外加自建 LangGraph 检查点表 `checkpoint_*`）。这些表的 schema 由**原仓 Flyway 拥有**——拆仓后这是第二类契约面。该节列：
   - sks-ai 依赖的表/列清单 + 语义（如 `analyze_task.progress`「已完成条数比例」语义、`kb_card.embedding` 固定 `vector(1024)` 维——之前专门钉死过的口径）。
   - 声明 schema 归属：**新仓不做迁移**，部署依赖原仓迁移已执行到 V≥N（当前 V3）。
   - LangGraph `checkpoint_*` 表是例外（sks-ai 自己 `setup()` 建，归新仓管）。

## 7. 迁移步骤

### 7.1 抽离 sks-ai 到新仓库（带历史）
```bash
cd /Users/rick/work/sks-agent
git subtree split --prefix=sks-ai -b split-sks-ai
mkdir /Users/rick/work/sks-ai && cd /Users/rick/work/sks-ai
git init
git pull /Users/rick/work/sks-agent split-sks-ai
# （--allow-unrelated-histories 对空仓 pull 是多余的，删掉无害）
```

### 7.2 给新仓补独立部署 artifact + 清理 config.py
在 sks-ai 新仓根加：`docker-compose.yml`（§5）、`.env.example`、`.gitignore`、`README.md`、`docs/API_CONTRACT.md`。
同时清理 `app/config.py`：删 `ALIYUN_SMS_SIGN` 字段（Python 无一处用，§4）。逐个 commit。

### 7.3 建 GitHub 远程 + push
```bash
git remote add origin git@github.com:WangBuer1984/sks-ai.git
git branch -M main
git push -u origin main
```

### 7.4 原仓库清理 sks-ai + 改 external 网络
```bash
cd /Users/rick/work/sks-agent
git rm -r sks-ai
# 编辑 docker-compose.yml：删 sks-ai 服务块；networks.sks-net 从 driver:bridge 改 external:true
# 编辑 CLAUDE.md / docs：更新 sks-ai 描述（点名 GO_LIVE_CHECKLIST「4 容器」改「3 容器+sks-ai 独立 compose」）
# 编辑 .env.example：删 sks-ai 专用 key（ZHIPU/TIKHUB/ALIYUN_ASR_*），保留共享 key
# 注：config.py 的 ALIYUN_SMS_SIGN 清理在新仓（7.2 已做），原仓无 config.py
git commit -m "chore: 拆出 sks-ai 为独立仓库（见 sks-ai 仓 API_CONTRACT）"
git push
```

### 7.4.1 部署前置（写进两边 README / deploy 文档）
```bash
docker network create sks-net   # 一次性，每台部署机执行一次
# 然后起原仓 compose（pg + sks-server + nginx），再起 sks-ai compose（顺序见 §5.5）
```

### 7.5 清理临时分支
```bash
cd /Users/rick/work/sks-agent && git branch -D split-sks-ai
```

## 8. 风险与回滚

- **不可逆但可恢复**：原仓 `git rm -r sks-ai` 后，历史里 sks-ai 仍在（`git log -- sks-ai/` 可查、可 `git revert` 或从历史 checkout）。新仓有完整历史。拆错能从 git 恢复，不丢代码。
- **执行顺序**：先抽新仓并 push 成功（7.1-7.3），再动原仓（7.4）。抽离出问题时原仓未动，安全。
- **.env 真值不进 git**：两边 `.env` 都 gitignored，共享密钥靠手动从原仓 `.env` 抄到新仓 `.env`。
- **跨仓契约漂移**：`SERVICE_TOKEN`/pg 凭据/`ALIYUN_ACCESS_KEY_*` 轮换需两边同步，靠 README「跨仓契约」约束。`AiClient` record 与 pydantic model 字段变更 + 共享表 schema 变更靠 `docs/API_CONTRACT.md` 两个契约面约束。
- **启动顺序（被 §5 兜底说轻了，单列风险）**：`/health 仍 UP` 兜底**只覆盖 asyncpg 池**（有懒重试）。`checkpointer`（`_init_checkpointer`）只在启动时初始化一次、无懒重试。若 sks-ai 先于 pg 起来，interview 端点会一直坏，而 `/health` 显示 UP、`restart: unless-stopped` 不救（进程没死）。**缓解**：README 钉死启动顺序（先原仓 compose 后 sks-ai）；给 checkpointer 补懒重试标为 out-of-scope（本次不做，但风险写明，别让「/health 仍 UP」读起来像全兜住了）。

## 9. 不在本次范围

- 不拆 sks-web（前端仍留原仓）。
- 不改 sks-server 的 AiClient 逻辑（只更新文档指向 sks-ai 仓）。
- 不引入镜像 registry / CI（方案 A 不需要；后续要全栈单 compose 编排可再上方案 B）。
- 不拆 Postgres（保留共享单库）。
