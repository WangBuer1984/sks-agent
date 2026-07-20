# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repo status: docs-first, not yet implemented

This repository is **design + planning only**. There is no `sks-server/`, `sks-ai/`, or `sks-web/` source code yet — only specification documents and HTML prototypes. Until implementation begins per the plan below, "working in this repo" means editing the docs, not running builds. The git history is all `docs:` commits.

The product is **「随口说」(Suikoushuo)** — a paid-per-use web AI agent for口播 (talking-head) video scriptwriting. Read the source-of-truth docs before making decisions; they encode choices that were deliberated and should not be silently re-litigated:

- `随口说PRD .md` — product requirements (V1.0). Business model, user flows, credit rules, exception handling, MVP/V1.1/V2 scope.
- `docs/superpowers/specs/2026-07-19-suikoushuo-tech-design.md` — tech design (V1.0). Architecture, data model, the four key flows, risks. **The authoritative technical reference.**
- `docs/superpowers/plans/2026-07-19-suikoushuo-mvp.md` — task-by-task MVP implementation plan (TDD steps with Run/Expected commands). Follow this when writing code; it uses checkbox (`- [ ]`) syntax.
- `随口说原型-07191700.html` / `随口说后台管理原型-admin.html` — interactive prototypes (C端 + 站长后台). Large HTML files; treat as visual reference, not code to edit.

## Architecture (planned, not yet built)

Monorepo with **two independent services** + a frontend, orchestrated by one `docker-compose.yml`:

```
浏览器 (React SPA) ──HTTPS──▶ nginx ──▶ Java (Spring Boot 3, 唯一公网入口)
                                        │ 内网 HTTP (同步 JSON / X-Service-Token)
                                        ▼
                                      Python (FastAPI + LangGraph, 不暴露公网)
                                        │
                          PostgreSQL 16 + pgvector ◀──┘ (业务表 + 向量 + LangGraph 检查点, 单库三合一)
                          智谱 GLM API · TikHub 数据 API · 阿里云 SMS/ASR/内容安全
```

**Why two services, why Java is the only public entry:** the credit transaction chain (扣额度 → 调生成 → 失败退额度) stays closed-loop inside Java with no cross-service reconciliation. Python is stateless (state externalized to Postgres via LangGraph checkpointer + `analyze_task` table), doesn't do auth, trusts only the internal network + shared `X-Service-Token`, and can be restarted anytime without losing data.

**Java packages** (`sks-server/src/main/java/com/sks/`): `auth` (C端 手机号+验证码) · `admin` (站长后台 账号密码, 隔离) · `user` · `credit` (额度账本, 钱的核心, 测试最厚) · `profile` (定位档案) · `kb` (知识库 A/B/C 卡) · `topic` (选题库四路) · `analyze` (拆视频/拆账号) · `script` (文案创作, 含逐句编辑) · `review` (复盘状态机) · `aiclient` (调 Python 的唯一出口, 统一超时/重试/错误码翻译) · `common` · `config`.

**Python packages** (`sks-ai/app/`): `api` (每 skill 一个 endpoint) · `skills/` (interview / script_gen / video_analyze / account_analyze / attribution / card_gen) · `rag` · `llm` (智谱 GLM 封装 + 档位配置, 业务代码不感知型号) · `safety` · `datasource` (TikHub + ASR 转写管线) · `db.py` (asyncpg, 仅用于 RAG/checkpointer/analyze_task).

## Hard invariants (do not violate when implementing)

These are project-level constraints from the plan's "Global Constraints" — every task implicitly carries them:

- **No streaming output.** All user-facing LLM natural-language output (稿件、卡片、访谈、拆解文本、归因) must be: generate complete → content-safety review passes → return as one JSON. No SSE / typewriter. (Rationale: content safety must review before display; streaming would show unreviewed content.) Use progress animations to mask the 30–60s wait.
- **AI stack single vendor: 智谱 GLM.** All LLM calls go through GLM (OpenAI-compatible protocol); vectors use 智谱 embedding-3 **fixed at 1024 dims**. Model IDs appear only in Python `llm/` config; business code never hardcodes model names. Per-skill tiering: 创作类 GLM-4.7 (thinking off) · 轻量抽取 GLM-4.5-Air · 深度归纳/归因 GLM-4.7 (thinking on).
- **Java is the only public entry.** Python accepts only requests with a correct `X-Service-Token`; every Java→Python call carries a Java-generated `X-Request-Id`.
- **Credit concurrency safety:** deduct via atomic conditional update `UPDATE credit_account SET balance = balance - :n WHERE user_id = :uid AND balance >= :n`, judge success by rows-affected. Never read-then-write. Refund idempotency via `credit_ledger` unique constraint on `(biz_id, biz_type, type)`.
- **Admin isolation:** separate `admin_user` table, all admin routes under `/api/admin/**` with an independent `SecurityFilterChain`, JWT signed with a different key/claim than C端 — the two token types are not interchangeable. No admin registration; seed via Flyway migration with password hash from env.
- **No Redis / MQ / microservices / K8s.** Verification codes, rate-limiting, and async tasks all use Postgres tables + Java `@Scheduled` polling.
- **Admin/C端 JWT secrets from env**, all keys (DB password, GLM key, TikHub key, 阿里云 key, service token, JWT secrets) via `.env` — `.env` is gitignored.
- **Testing focus:** `credit` (deduct/refund/idempotency/concurrency), review state machine, SMS rate-limiting must have JUnit coverage; Python uses pytest with mocked LLM. Java tests use Testcontainers `pgvector/pgvector:pg16` — **not H2**, to keep SQL dialect identical. TDD throughout (the plan's steps are ordered write-failing-test → implement → pass).

## Build / test / run commands (planned layout)

Repo-root orchestration:
- `docker compose --env-file .env up -d --build` — start all four containers (nginx / sks-server / sks-ai / postgres). `--build` is required for new Flyway migrations to land; a bare restart won't pick them up.
- Health checks: `curl localhost/api/health` (Java, via nginx) → `{"status":"UP"}`; Python `GET /health` → `{"status":"UP"}`.

Java (`cd sks-server`, Maven Wrapper — no global mvn needed):
- `./mvnw test` — run all tests
- `./mvnw test -Dtest=CreditServiceTest` — single test class
- `./mvnw test -Dtest="AuthServiceTest,UserServiceTest"` — multiple classes

Python (`cd sks-ai`):
- `pytest tests/test_script_gen.py -v` — single file
- `pytest tests/test_script_gen.py tests/test_retrieve.py -v` — multiple files

Frontend (`sks-web/`): React 18 + Vite + TypeScript + Tailwind; state via TanStack Query (server) + Zustand (client).

## Critical flows to understand before touching money/credit code

1. **Credit transaction (§4.1 of tech design):** insert a `script` placeholder row (`review_state='generating'`) to get a stable `script_id` **before** charging → deduct in a short `REQUIRES_NEW` transaction **outside** the Python HTTP call → call Python (30–60s) → on success backfill the row / set `draft`; on any failure (timeout, connection break, parse failure, content-safety double-hit) set `failed` + write idempotent refund ledger. The orchestration method is **not** `@Transactional`; the long HTTP call must never hold a DB connection. "Fail → refund, never miss a charge."
2. **Async analyze tasks (§4.3):** Java prechecks (calls Python `/ai/analyze/precheck`), charges (`max(1, min(10, floor(N/2)))` for 拆账号), creates `analyze_task(queued)`, calls Python async endpoint with `task_id` → Python writes progress/results **directly to the same `analyze_task` table** (not to Python-private memory/disk) → Java `@Scheduled` every 5s reads the table and advances state. Three timeout/stale cases the poller must cover or it swallows user credits: running-timeout (5min no `updated_at`, Python must explicitly set it — PG has no auto-update), partial, stale-queued (1min no running after acceptance). Single task ID across the whole chain.
3. **Review state machine (§4.4):** seven states (`draft/pending/tracking/hot/plain/flop/rejected` + generating/failed pre-states), all transitions Java rule-based — **no AI judges state**. `hot` threshold = 近30天均值 × 3 (adjustable). `rejected` = 48h unadopted via scheduled scan. MVP playback counts are user-entered (`data_source=manual`); V1.1 auto-scrape reuses the state-machine code unchanged.

## Data-model essentials

~15 business tables. Key non-obvious decisions: AI outputs (档案、拆解结果、稿件分段) are JSONB, not relation tables (prompt-driven, iterate often) — only queryable/indexed fields (status, source, layer) are promoted to columns. `kb_card` is one table for all three layers (A/B/C) with `layer` + `card_type` + `embedding vector(1024)`. `user` is a PG reserved word → table is `app_user`. Embedding model+dim are bound to the pgvector column once chosen; switching models requires a full re-embed + column change. Full schema is in tech-design §3.

## Commit convention

Conventional Commits (`feat:` / `fix:` / `test:` / `chore:` / `docs:`). The existing history is all `docs:`; implementation commits should be frequent, one per plan step.
