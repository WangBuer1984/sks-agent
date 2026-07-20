# 随口说 · 技术选型与技术方案设计

**版本：** V1.0 · 2026-07-19
**依据：** 《随口说 PRD V1.0》+ 交互原型（随口说原型-07191700.html）
**范围：** MVP 阶段（登录与额度、校准定位、知识库 A/B 层 + 补卡、文案创作、拆视频、拆账号、发布复盘手动填数版状态机）

---

## 0. 已确认的约束与决策

| 项 | 决策 |
| --- | --- |
| 团队 | 独立开发者一人全栈开发 + 运维 |
| 规模与部署 | 冷启动阶段（百位用户以内），单台国内云服务器 |
| 拆账号数据源 | 付费第三方数据 API（**TikHub**），成本计入拆账号定价 |
| 服务架构 | Java 业务层 + Python AI 层，双服务；**Java 做唯一公网入口**（方案 A） |
| 代码组织 | **单仓库 monorepo**：`sks-server`（Java）/ `sks-ai`（Python）/ `sks-web`（前端）三工程 + docker-compose 同处一库。服务运行时仍完全独立（各自容器、仅内网 REST 通信、无源码依赖），但代码集中——跨服务改契约一个原子提交、一次 clone、一份 `.env`，最适合一人全栈 + 单机冷启动。DB 迁移由 Java 用 Flyway 执行，Python 只连库读写约定表 |
| 前端 | React 18 + Vite + TypeScript |
| 数据库 | PostgreSQL 16 + pgvector（单库承载业务数据 + 向量） |
| Agent 框架 | LangGraph |

**方案 A 的核心理由：** 鉴权与额度收口在 Java 一处——「扣额度 → 调生成 → 失败退额度」这条 PRD 核心事务链在 Java 内部闭环，无跨服务对账；Python 层无状态、不对公网暴露、可随时重启；一人维护安全面与心智负担最小。

**不做流式输出（重要决策）**：内容安全要求 LLM 输出**先过审核再展示**，与逐 token 流式展示存在时序矛盾（用户会先看到未审内容）。权衡后决定 MVP **放弃打字机效果**：生成完整稿 → 审核 → 一次性返回。代价是用户等待 30-60 秒（前端用进度动画/阶段提示缓解），收益是最合规 + 全链路砍掉 SSE（Java 无需透传、前端无需流式读取、失败判定大幅简化）。

**被否决的备选：**
- 方案 B（前端直连双服务）：「失败退额度」变跨服务补偿逻辑，易出对账 bug；鉴权/限流/CORS 双份，运维面翻倍。
- 方案 C（Python 主服务 + Java 账务子模块）：大量 CRUD 与状态机被迫用 Python 写，浪费 Java 主力优势。

---

## 1. 总体架构与技术栈

```
浏览器 (React SPA)
      │ HTTPS (REST)
      ▼
┌─────────────────────┐   内网 HTTP    ┌──────────────────────┐
│  Java 业务服务       │ ────────────▶ │  Python AI 服务       │
│  Spring Boot 3      │ ◀──────────── │  FastAPI + LangGraph  │
│  (唯一公网入口)       │  同步 JSON     │  (不暴露公网)          │
└─────────┬───────────┘               └──────────┬───────────┘
          │                                      │
          ▼                                      ▼
   PostgreSQL 16 (+pgvector)          智谱 GLM API（对话 + embedding）
   （业务数据 + 向量 同一实例）          TikHub 第三方数据 API
```

### 技术栈清单

| 层 | 选型 | 说明 |
| --- | --- | --- |
| 前端 | React 18 + Vite + TypeScript + Tailwind CSS | PRD §9 纸感视觉规范（#f4f1e9 / #8a5a2b / Noto Serif SC 等）落为 Tailwind 主题变量；服务端状态用 TanStack Query，客户端状态用 Zustand |
| 业务服务 | Java 21 + Spring Boot 3.x + Spring Security (JWT) + **MyBatis-Plus** | 登录/额度/CRUD/状态机/定时任务；无流式，纯同步 REST（长耗时接口延长超时 + 前端进度提示） |
| AI 服务 | Python 3.12 + FastAPI + LangGraph + langchain-openai（OpenAI 兼容协议调智谱 GLM） | 无业务状态；访谈多轮状态用 LangGraph Postgres checkpointer 存回同一个库。**AI 核心栈单一厂商（智谱）：GLM 对话 + embedding-3 同平台同 key，运维最简** |
| 数据库 | PostgreSQL 16 + pgvector 扩展 | 业务表 + 知识库向量 + LangGraph 检查点，单实例三合一 |
| 缓存/队列 | **不引入 Redis/MQ**：验证码与频控存 Postgres；拆账号等长任务用 DB 任务表 + Java 定时轮询 | 冷启动单机原则：能用 Postgres 解决的不加中间件，扩展时再换 |
| Embedding | 智谱 embedding-3，**1024 维**（OpenAI 兼容协议） | 中文 RAG 主流选择，维度可配（此项目定 1024，精度足够且省存储）。**注意：embedding 模型与维度一经选定即绑定 pgvector 列，换模型需全库重算向量并改列定义** |
| 短信 | 阿里云 SMS | 验证码发送；频控逻辑在 Java 侧 |
| ASR | **阿里云智能语音**（一句话识别 + 录音文件识别） | 两个用途、两个 API：① **定位访谈/补卡的语音回答转文字**——短音频（≤60s）用**一句话识别**，同步返回；② **拆账号/拆视频链接版的音频转写**（TikHub 无抖音字幕接口，完整文案必须走转录管线）——长音频用**录音文件识别**批量转写。与短信/内容安全同厂商 |
| 部署 | 单台云服务器（建议 4C8G）+ Docker Compose 四容器（nginx / java / python / postgres） | HTTPS 由 nginx + Let's Encrypt；数据库每日 `pg_dump` 上传对象存储 |

### 硬性设计原则

Python **进程**无状态（可随时重启、水平扩容），不做用户鉴权（只信任内网来源 + 服务间共享密钥 `X-Service-Token`）。所有「谁在调、扣不扣费、结果存哪」由 Java 决定；Python 只回答「给我输入，我给你 AI 产出」。

**「进程无状态」的准确含义**：Python 自身不在内存里长期保存会话/任务状态——需要跨请求存活的状态（访谈多轮上下文、异步拆解任务进度）**一律外置到 Postgres**（LangGraph checkpointer、`analyze_task` 表），因此任何一台 Python 实例挂掉重启都不丢数据。

### 模型选型（各 AI 能力用什么模型）

**AI 核心栈统一用智谱**：所有 LLM 任务用 GLM，向量用 embedding-3——单一厂商、单一 key、单一账单，一人维护最省心。按任务在 `llm/` 配置层绑定 GLM 的不同档位（高频轻量用便宜快档、深度任务开 thinking），业务代码不感知具体型号；版本号随厂商升级时配置层单点更新。

| 功能 / Skill | 任务性质 | 模型 | 理由 |
| --- | --- | --- | --- |
| 文案创作 `script_gen` | 中文口播口语，高频量大 | **GLM-4.7**（thinking 关） | 中文创作 + 结构化分段（钩子/正文/转化 + 三平台版本 + 溯源）输出稳；创作不需思考链，关 thinking 更快省 |
| 定位访谈 `interview` | 多轮追问 + 归纳成档案 | **GLM-4.7**（thinking 关） | 对话自然，档案走结构化输出 |
| 补卡 `card_gen` | 大白话→结构化卡片、抽取 | **GLM-4.5-Air** | 轻量抽取任务，用便宜档控成本 |
| 单句重写 `rewrite_sentence` | 逐句编辑的「这句换个说法」 | **GLM-4.5-Air** | 单句改写极轻量，便宜档足够；**不扣额度**（成本可忽略，被刷再限流） |
| 拆视频 `video_analyze` | 结构标注 + 为什么火 | **GLM-4.7**（thinking 关） | 结构标注为主的抽取任务 |
| 拆账号规律归纳 `account_analyze` | TOP20 逐条结构化 + 全局规律归纳 | 逐条：**GLM-4.5-Air**；规律归纳：**GLM-4.7（thinking 开）** | 逐条抽取用便宜档控成本；规律归纳需全局推理，开 thinking |
| 复盘归因 `attribution` | 数据→归因 + 改进建议 + 周归因卡 | **GLM-4.7（thinking 开）** | 因果推理 + 结构化 JSON 可靠，低频成本可接受 |

**配套 AI 能力（非对话模型）：**

| 能力 | 用途 | 选型 |
| --- | --- | --- |
| 文本向量 Embedding | 知识库 B 层 RAG | 智谱 embedding-3，1024 维 |
| 语音识别 ASR | ① 访谈/补卡语音回答转文字（一句话识别）；② 拆账号/拆视频音频转写（录音文件识别） | 阿里云智能语音 |
| 内容安全审核 | LLM 输出 + UGC 过审 | 阿里云内容安全 |

> **为何统一用 GLM（而非混用 DeepSeek/Kimi）**：① 本项目所有 AI 产出都要落 JSONB 并前端渲染，GLM 的结构化 JSON 输出/工具调用稳定性国内领先，直接减少解析失败与重试；② GLM 与 embedding-3 同属智谱，AI 栈单厂商单 key，一人运维最省；③ 与 LangGraph 编排配合稳；④ Kimi 的超长上下文优势本项目用不到（拆账号 TOP20 合计仅一两万字，128K 足够）。**待验证点**：文案创作是北极星 skill，上线前用「评测用例集」盲评确认 GLM-4.7 的口语质感达标；若不足，该 skill 可单独切换模型（改配置一行）。

---

## 2. 服务内部模块划分

### 2.1 Java 业务服务（单体分包，非微服务）

```
sks-server/
├── auth/        C 端登录：手机号+验证码、JWT 签发、验证码频控（1min/1h/1d 三级，PRD §11.1）
├── admin/       管理端（站长后台）：账号密码登录（admin_user 表 + BCrypt）、开通订单操作、
│                补偿额度（经营统计 V1.1 再做）。接口统一 /api/admin/** 前缀 + 独立 Spring Security 过滤器链，
│                JWT 与 C 端隔离（不同签名主体/claim），C 端 token 不能访问管理接口，反之亦然。
│                无注册入口，站长账号由数据库迁移脚本种子写入（密码哈希从环境变量注入）
├── user/        个人中心：基础资料 + 创作资料、完善度计算
├── credit/      额度账本（核心模块，见 §4.1）；开通/补偿的额度写入由 admin/ 调用本模块完成
├── profile/     账号定位：校准会话入口、定位档案 CRUD、校准进度保存
├── kb/          知识库：A/B/C 三层卡片 CRUD、补卡任务、卡片引用计数与删除保护
├── topic/       选题库：四路来源聚合、支柱配比排序、状态管理
│                （热点路数据源 = TikHub 抖音热点榜接口，Java 定时经 Python `/ai/hot_board` 拉取，
│                 再对热点标题调 `/ai/embed` 算向量、在 pgvector 里与用户 B 层卡余弦匹配打分——复用既有
│                 embed 接口 + SQL，不新增匹配端点。
│                 **MVP 决策：热点路提前纳入**——TikHub 热点榜接口现成、成本低；这与 PRD 列 V1.1 的
│                 「热点监控推送」不同，后者指主动 push 通知，仍留 V1.1。发布复盘的播放量自动抓取也仍留 V1.1）
├── analyze/     对标拆解：拆视频（粘贴文案→同步，仅 LLM 约 10-20s；粘贴链接→**异步任务**，
│                走转写管线约 1 分钟）、拆账号（异步任务，见 §4.3）；均扣 1 / 10 额度
├── script/      文案创作：生成请求编排、稿件存储、稿件列表/详情/**逐句编辑**（钩子/正文/转化
│                三段内每句是独立单元：单句手改、单句 AI 重写「这句换个说法」——重写走轻量档
│                GLM-4.5-Air、**不扣额度**、产出照常过内容安全）、查重、三平台版本
│                （三平台版本**按需生成**：默认只生成用户主平台版，切换平台时再生成，
│                  同选题不加扣额度——省约 2/3 生成 token 成本）
├── review/      发布复盘：七状态机（draft/pending/tracking/hot/plain/flop/rejected）、手动填数、归因触发、周归因卡
├── aiclient/    对 Python 服务的 HTTP 客户端封装（唯一出口，统一超时重试与错误码翻译）
└── common/      全局异常、审计日志、任务表轮询调度器（@Scheduled）
```

### 2.2 Python AI 服务（按 Skill 组织，对应 PRD §9「智能体 Skill 工作流」）

```
sks-ai/
├── api/                 FastAPI 路由层：每个 skill 一个 endpoint（同步 JSON；拆账号与拆视频链接版为异步任务式）
│                        含 asr 路由（一句话识别，访谈/补卡语音回答转文字）
├── skills/
│   ├── interview/       定位访谈：LangGraph 多轮状态机（猜人设→确认→5-8 问→出档案）
│   ├── script_gen/      文案生成：注入定位档案 + RAG 检索 B 层卡片 → 钩子/正文/转化逐句输出；
│   │                    含单句重写 rewrite（「这句换个说法」，轻量档、不扣额度）
│   ├── video_analyze/   拆视频：结构标注、为什么火、可复用框架、人设差异提醒
│   ├── account_analyze/ 拆账号：第三方 API 抓 TOP20 → 逐条结构化 → 规律归纳 → 迁移建议
│   ├── attribution/     复盘归因：单条归因 + 周归因卡
│   └── card_gen/        补卡：大白话 → 结构化卡片、缺口检测、冲突检测
├── rag/                 embedding 调用、pgvector 检索、引用溯源（返回所用卡片 id 列表）
├── llm/                 统一调智谱 GLM，按 skill 绑定档位（高频创作类→GLM-4.7 thinking 关；
│                        轻量抽取→GLM-4.5-Air；深度归纳/归因→GLM-4.7 thinking 开），OpenAI 兼容协议统一封装
└── datasource/          TikHub 第三方 API 客户端（账号主页、视频列表、下载直链、热点榜）
                         + 转写管线（下载音频 → 阿里云录音文件识别 → 完整文案）
                         注：国内服务器必须用 api.tikhub.dev 域名（主域名被墙）
```

### 2.3 服务间接口契约

- Java → Python 全部走内网 REST。请求体自带**全部上下文**（定位档案 JSON、用户创作资料）；**Python 直连数据库的例外**：① RAG 检索直连 pgvector（Java 只传 `user_id` 与选题文本）；② 定位访谈的 LangGraph checkpointer 读写会话状态；③ 异步拆解任务（拆账号/拆视频链接版）进度/结果直写 `analyze_task` 表。这三处之外，Python 不碰业务库。
- **Java 对 pgvector 的读写边界（与上条不矛盾，明确写出防误读）**：`kb_card` 是 Java 拥有的业务表，Java 可以对它做 pgvector 读写——**写**：KB 卡片 CRUD 时调 Python `/ai/embed` 取向量后写 `embedding` 列；**读**：选题热点路对热点标题算向量后用 SQL 在 B 层卡上做余弦匹配打分（§2.1 topic 模块，简单 SQL 排序，不是 LangGraph skill）。上条「RAG 检索直连 pgvector 归 Python」特指**文案生成的 B 层卡召回**（`retrieve_b_cards`，检索结果要注入 prompt、与生成流程耦合）——这条留在 Python；Java 不做 script 生成链路上的 RAG 检索。
- **文案生成**接口为**同步 JSON**：Python 生成完整结果 + 内容安全审核通过后一次性返回（含完整稿件 + 引用卡片 id），Java 收到才落库、确认扣费。生成期间前端展示进度动画/阶段提示（如「正在检索知识库 → 正在撰写 → 安全审核中」）。**定位访谈**同为同步 JSON（一问一答，每轮一次请求），但**不涉及扣费**（PRD §4.2 校准不消耗额度）。
- 每个请求带 `X-Request-Id`（Java 生成）串联两侧日志；带 `X-Service-Token` 共享密钥防内网误访问。
- 异步拆解任务（拆账号约 3-5 分钟 / 拆视频链接版约 1 分钟）：Java 建 `analyze_task` 记录 → 调 Python 异步接口**传 `task_id`**，Python 受理后立即返回 202 → Python 后台跑并把进度/结果按 `task_id` 写回 `analyze_task` 表 → **Java @Scheduled 直接读 `analyze_task` 表**推进状态（不轮询 Python 接口、不用回调、不引入独立 job_id：Python 直写同一张表，全链路只有一个任务 ID）。

---

## 3. 数据模型与 RAG 设计

### 3.1 核心表（约 15 张业务表，列关键项；另有 Flyway 自身的 `flyway_schema_history` 与 LangGraph checkpointer 启动时自建的检查点表，不在此列）

**账号与额度：**

| 表 | 要点 |
| --- | --- |
| `app_user` | **C 端用户**（`user` 是 PG 保留字，表名用 `app_user`）：手机号唯一；基础资料 + 创作资料（行业/身份/出镜风格/周目标）直接放列，不拆表 |
| `admin_user` | **管理端用户（独立表，与 C 端完全隔离）**：`username` 唯一 + `password_hash`（BCrypt）+ 姓名 + 状态 + 最后登录时间。管理端登录走此表（账号密码，不走手机号验证码）；无注册入口，站长账号由迁移脚本种子写入（密码哈希取自环境变量）；MVP 只有站长一人，表结构天然支持后续加管理员。MVP 不做登录锁定/爆破防护，后续按需再加 `failed_attempts`/`locked_until` |
| `sms_code` | 验证码 + 过期时间 + 错误次数；频控按手机号 + 时间窗聚合查询此表，不需要 Redis |
| `credit_account` | 每用户一行：`balance` 当前余额。**并发扣减用原子条件更新**：`UPDATE credit_account SET balance = balance - :n WHERE user_id = :uid AND balance >= :n`，靠影响行数判断是否成功——解决 PRD §11.6「多端同时在线」下的超扣问题，无需 Redis 或分布式锁 |
| `credit_ledger` | **只追加流水账**：`+3 注册体验 / +50 充值 / +10 首充赠送 / -1 生成 / -1 拆视频 / -10 拆账号 / +N 失败退回`，每笔带 `biz_type` + `biz_id`（关联稿件或任务）；退款幂等靠 `(biz_id, biz_type, type)` 唯一约束（三列：不同业务域的 id 可能相撞，须带上 `biz_type`）；对账、审计、纠纷全靠它。扣减流程：同一事务内先原子扣 `credit_account` 成功、再写本表流水。**计费规则补充（PRD 未定，本设计确定）：拆视频扣 1 额度**（与生成同价，防免费被刷）；**拆账号扣 `max(1, min(10, floor(N/2)))`——下限 1 防 N=1 时免费白嫖，N=0 在预检阶段直接拒绝** |
| `recharge_order` | **人工开通订单（商业模式核心）**：用户、套餐（50 条 / 150 条）、金额、手机尾号、状态(**免费体验/已开通**)、操作人（`admin_user_id`，关联管理端用户表，**可空**：免费体验单由系统在注册时自动创建、无操作人，开通时才回填）、开通时间、备注凭证。**用户注册即自动创建订单（状态=免费体验）并赠送体验额度**（写 `credit_ledger(+N, biz_type=trial, biz_id=trial订单id)`，**N 可配置**：`sks.trial-credit` 环境变量注入、默认 3——「免费体验」必须真能体验，开通前用户可实际生成几条感受价值）；站长核对微信转账后开通，首充把该订单转为已开通、复购新增订单。无「驳回」态——无法核对的转账不入账，站长微信人工询问即可（PRD §11.1）。开通成功即写一笔 `credit_ledger(+N, biz_type=recharge, biz_id=order_id)`；**首充额外写一笔 `+10, biz_type=bonus, biz_id=同一 order_id`（PRD 首充送 10 条；biz_type 不同不撞唯一约束）**。**首充判定唯一口径：该用户此前无已开通的充值单（补偿单不算）** |

**定位与知识库：**

| 表 | 要点 |
| --- | --- |
| `positioning_profile` | 定位档案：人设/人群/差异化/变现/红线/支柱配比，存 JSONB（结构由 AI 产出、schema 会演进）；`version` 字段支持重新校准留历史 |
| `kb_card` | 三层统一一张表：`layer`(A/B/C) + `card_type`(身份/口吻/规则/产品/FAQ/案例/爆款素材) + `title` + `content`(JSONB) + `embedding vector(1024)`（智谱 embedding-3）；编辑卡片即时重算 embedding（PRD §7.4 立即生效） |
| `card_history` | 补卡冲突时旧值归档（PRD §11.4） |
| `card_citation` | 稿件 ↔ 卡片引用关系；删除卡片前查引用数做二次确认；卡片删除后旧稿溯源标注「卡片已删除」 |

**选题、稿件与复盘：**

| 表 | 要点 |
| --- | --- |
| `topic` | 四路来源（`source`: hot/faq/benchmark/replay）+ 依据说明 + 内容支柱标签 |
| `script` | 稿件：钩子/正文/转化三段 JSONB，**每段内是句数组 `[{idx, text}]`**（生成时即按句拆好），支持**逐句编辑**（单句手改 / 单句 AI 重写）、平台版本、`review_state`（复盘状态机**七状态**：draft/pending/tracking/hot/plain/flop/rejected；另有两个**生成期前置态** generating/failed——扣费前先插占位行拿 id 用，不参与复盘状态机。状态机字段就在稿件上，不单独建表）、发布链接、播放数据（手填）、`data_source`(manual/auto) |
| `analyze_task` | **异步拆解任务通用表**：`task_type`(account 拆账号 / video 拆视频链接版) + 状态(queued/running/partial/done/failed) + 进度 + 实扣额度 + 产出 JSONB。拆账号存账号画像/规律归纳/迁移建议三层（TOP20 明细见下行）；拆视频存单条结构标注/为什么火/框架/差异提醒 |
| `benchmark_video` | 拆账号拆出的 **TOP20 明细（一条一行，非 JSONB）**：标题/播放/收藏/完整文案/结构标注；列表需逐条展示，且为 **V1.1 的逐条「深拆 / 仿写」预留**（MVP 不做深拆/仿写按钮），故建行。关联 `analyze_task_id` |
| `weekly_report` | 周归因卡；`(user_id, week_start)` 唯一约束防定时任务重跑重复插行 |

**取舍说明：** AI 产出（档案、拆解结果、稿件分段）一律 JSONB 而非拆关系表——这些结构由 prompt 决定、迭代频繁，JSONB 免去反复迁移；需要查询/索引的字段（状态、来源、层级）才提列。复盘状态机不建独立表，`script.review_state` + 状态变迁审计日志即可。

### 3.2 RAG 设计（刻意保持简单）

1. **A 层不走检索**：身份卡/口吻样本/表达规则每次生成**全量注入** prompt（单用户 A 层至多几千 token，检索反而丢信息）。
2. **B 层才是 RAG**：以「选题标题 + 选题依据」为 query，pgvector 余弦相似度取 top-5 产品/FAQ/案例卡；相似度低于阈值不注入（宁缺勿滥，防幻觉溯源）。
3. **溯源落地**：Python 返回 `cited_card_ids` → Java 写 `card_citation` → 前端右栏展示「本次引用的卡片」。
4. **C 层（爆款素材）**：生成续集/仿写时按 topic 关联直查，不走向量。
5. 不做 rerank、不做混合检索、不做 chunk 切分——卡片本身是天然 chunk（一卡一向量），卡片量级几十到几百，top-5 足够。

---

## 4. 四条关键流程

### 4.1 额度事务：扣费 → 生成 → 失败退回

```
前端点「生成」
  → Java 先插一行 script(review_state='generating') 拿到 script_id
     ← 扣费流水的 biz_id 必须在扣费前就存在且稳定（退款幂等靠它），
       所以「先建稿件占位行、再扣费」，不能等生成成功才落库
  → Java 短事务（独立提交，不包住 HTTP 调用）：
       原子扣 credit_account（UPDATE ... WHERE balance >= 1）→ 写 credit_ledger(-1, biz_id=script_id)
  → Java 同步调 Python 生成（在任何数据库事务之外；Python：生成完整稿 → 内容安全审核 → 返回 JSON）
  → 成功（收到完整结构化响应）：回填该 script 行内容、置 review_state='draft'，返回前端展示
  → 失败（超时/异常/内容安全二次命中）：
      占位行置 'failed'；写 credit_ledger(+1, biz_id=script_id, type=refund)
                                          ← (biz_id,biz_type,type) 唯一约束保证幂等
      → 前端 toast「生成失败，额度已退回」（PRD §11.2）
```

> **事务边界（Spring 实现要点）**：生成编排方法本身**不加 `@Transactional`**；扣费/退款各自是 `REQUIRES_NEW`（或 `TransactionTemplate`）的独立短事务，30-60s 的 Python HTTP 调用绝不能在事务内——否则长时间占住连接池连接，且失败时 refund 会在 rollback-only 事务里执行。

- **先扣后调**，宁可退款不可漏扣。
- **失败判定边界**：超时、Java↔Python 连接中断、响应解析失败——**全部判失败并退款**（唯一「成功」判据是收到完整结构化响应并回填成功）。退款靠 `(biz_id, biz_type, type)` 幂等，重复触发不会多退。客户端提交生成后断连不影响：Java 收到 Python 响应仍正常落库，用户回来能看到稿件。
- 同选题「重新生成 / 换角度」由 Java 判断 `topic_id` 相同且**已有非 failed 成功稿**则免扣（PRD §4.2）。失败退款后用户重试：若该选题从未成功过，视为**首次**成功扣费；已成功过再改角度才走免费逻辑。
- Python 全程不感知额度。

### 4.2 定位访谈（多轮对话）

- LangGraph 状态机：`猜人设 → 用户反馈(对/不对) → 逐题访谈(5-8 问) → 生成档案 → 确认生效`。
- 会话状态用 LangGraph 官方 **Postgres checkpointer** 持久化（同一个库，`thread_id = 用户id + 会话id`），天然满足「进度保存退出、断点继续」（PRD §11.4）；Java 只记录「校准进行中，第 X 步」供工作台横幅展示。
- 语音回答（**MVP 即支持**）：前端录音 → Java `POST /api/profile/voice` 接收音频 → 调 Python `POST /ai/asr`（阿里云**一句话识别**，短音频同步返回文字）→ 转出的文字作为该轮 reply 进入同一访谈流程。与拆解转写共用厂商但走不同 API（一句话识别 vs 录音文件识别）。
- 档案确认生效：访谈到 `summarize` 完成后，Java 调 Python 只读接口 `GET /ai/interview/result?thread_id=`（从 checkpointer 读、**不推进状态机**）取结构化档案 + 自动拆出的 A 层卡片，一并落库——确认动作与访谈步进解耦，避免「confirm 再推一步状态机」的数据流断裂。

### 4.3 异步拆解任务（拆账号约 3-5 分钟 / 拆视频链接版约 1 分钟）

**拆视频（链接版）与拆账号共用异步任务式**：Java 扣费建 `analyze_task(task_type=video)` → 调 Python 异步接口（传 `task_id`）立即返回 → Python 后台走「下载音频 → ASR 转写 → 结构化」并按 `task_id` 写回任务表 → 前端轮询进度。以下以拆账号为例（拆视频是其单条简化版）：


```
Java：预检链接（调 Python 轻量预检接口 `/ai/analyze/precheck`：账号可访问性、视频数 N）
  → 预检失败或 N=0：不扣额度，提示更换链接或改用「拆视频」逐条拆（PRD §11.3）
  → 扣费 max(1, min(10, floor(N/2)))（按比例向下取整，如 12 条扣 6；下限 1 防 N=1 免费），开始前明示；建 analyze_task(queued)
  → 调 Python 异步接口（传 task_id），受理后任务转 running
Python：TikHub 取 TOP20 元数据与下载直链 → **逐条下载音频 → 阿里云录音文件识别转写出完整文案**（TikHub 无抖音字幕接口，转录管线是必经之路；音频临时文件转写后即删）→ 逐条结构化 → 规律归纳 → 迁移建议；**进度与结果直接写 `analyze_task` 表（Python 连同一个库），不使用 Python 私有内存/落盘存储**——与「进程无状态」原则自洽，Python 重启不丢任务
Java：@Scheduled 每 5 秒读 `analyze_task` 状态推进（轮询范围覆盖三种情况，缺一都会吞用户额度）：
  ① running 超时（5 分钟无 updated_at 更新，Python 每次写进度必须显式 set updated_at——PG 无自动更新语义）→ 判 failed 全额退
  ② partial（部分失败终态）→ 按未完成条数比例退一次（幂等约束保证只退一次，partial 不再二次演化）
  ③ stale queued（受理后 1 分钟未转 running，说明 Python 受理后即崩）→ 判 failed 全额退
  → done：账号画像/TOP20 清单/规律归纳/迁移建议 四层结果落库
  → 部分失败：保留已完成部分，按未完成条数比例退额度，可免费续拆（PRD §11.3）
  → 第三方数据 API 整体不可用/限流：抓取阶段失败则全额退款，并提示改用「拆视频（粘链接/粘文案）」逐条拆解（PRD §11.3 的降级路径；MVP 不做独立的「手动粘贴视频列表批量拆」入口）
```

### 4.4 复盘状态机

- 状态迁移全部由 Java 规则判定（**无 AI 参与判态**）：采用→`pending`；登记链接→`tracking`；填入播放量后与「近 30 天均值 × 阈值（默认 3，可调）」比较→`hot / plain / flop`；生成 48h 未采用→`rejected`（定时任务扫描）。
- MVP 播放量手动填写；`data_source` 字段预留，V1.1 接自动抓取时状态机代码零改动。
- `hot`「标记爆款素材」→ 调 Python card_gen 生成 C 层卡；「出续集选题」→ 写入选题库（source=replay）。
- `flop`「看归因」→ 调 Python attribution（不扣费），改进建议写入创作偏好。
- `rejected` 回访（选题不对 / 不像我说话）→ 分别反哺选题偏好 / 口吻样本。
- 周归因卡：Java 每周日定时聚合该周稿件数据 → 调 Python 归纳 → 存 `weekly_report`。

---

## 5. 错误处理、测试与部署

### 5.1 错误处理（对齐 PRD §11）

- **统一错误码**：Java 全局异常处理器输出 `{code, message, detail}`；Python 错误由 `aiclient` 翻译成 Java 错误码，前端只认一套。
- **LLM 调用**：每个 skill 配置超时（生成 120s、拆账号单条 60s）+ 1 次自动重试；仍失败则返回错误码、Java 走退款流程（MVP 从简，**不做跨厂商自动降级**——失败退额度 + 重试已足够兜底）。
- **内容安全**：接**阿里云内容安全**文本审核 API（已用阿里云 SMS，同厂商省对接），对 LLM 输出（及用户提交的定位素材/知识库等 UGC）过审；命中则自动重写一次，仍命中返回特定错误码、Java 走退款流程（PRD §11.2）。自建违禁词库仅作为廉价前置过滤，不替代审核 API。
- **查重**：MVP 用**轻量文本相似**（SimHash / 关键词 Jaccard，Java 本地算，零额外成本）在同用户历史稿件内比对；命中不阻断，稿件顶部黄色提示 + 「换角度」按钮（PRD §11.2）。若日后要更准可复用 pgvector（需给 `script` 加 embedding 列、Python 侧算向量），MVP 不做。
- **401 保内容**：前端 axios 拦截器捕获 401 时把当前表单/编辑器内容存 localStorage，重登后恢复（PRD §11.6）。
- **全站兜底**：nginx 静态 50x 页面，展示站长微信 + 补偿承诺（PRD §11.6）。

### 5.2 测试策略（一人项目，把钱花在刀刃上）

- **重点覆盖（JUnit）**：`credit` 模块（扣费/退款/幂等/并发扣减）、复盘状态机迁移规则、验证码频控——这三处出 bug 直接损钱或损信任。
- **AI 层（pytest）**：测流程编排逻辑（mock LLM 响应）；prompt 效果不写自动化测试，维护一份固定「评测用例集」（10 个典型用户画像 + 选题），发版前人工过一遍。
- **端到端**：不上自动化；发版前手动过「注册→校准→拆账号→生成→采用→登记」主链路清单。

### 5.3 部署与运维

- Docker Compose 四容器：`nginx`（HTTPS 终结 + 静态前端 + 反代）、`sks-server`、`sks-ai`、`postgres`；服务器 4C8G。**monorepo 单库**，`docker-compose.yml` 在仓库根，各服务 build context 直接指向子目录（`./sks-server`、`./sks-ai`、`./sks-web`），一次 `docker compose up -d --build` 全量起。服务运行时仍互相独立，日后需要也可各自拆容器/换机器。
- **同步接口超时（全链路对齐，内层短于外层）**：唯一较长的同步接口是文案生成（等 30-60s）。最坏情形 = LLM 超时 120s × (1 次原始 + 1 次自动重试) ≈ 250s，因此从内到外：Python 内 LLM 单次超时 **120s**（§5.1）→ Java→Python HTTP client 读超时 **270s** → nginx `proxy_read_timeout` **300s**。拆账号与拆视频（链接版）都是异步任务式（立即返回 job、前端轮询），不占用长连接。
- 配置管理：`.env` 注入（数据库密码、模型 API key、TikHub key、短信 key、服务间共享密钥）；**密钥不进 git**。
- 日志：两服务 JSON 日志落盘 + `X-Request-Id` 串联；先不上日志系统。
- 备份：每日 `pg_dump` → 对象存储（OSS/COS），保留 30 天——额度账本不可丢。
- 监控：外部拨测（UptimeRobot 类）+ 每日定时任务检查短信余额 / 模型 API 余额并告警。

### 5.4 MVP 开发顺序

1. 骨架：登录 + 额度 + 人工开通后台（钱的链路先通）
2. 知识库 CRUD + RAG 检索 + 文案创作（核心价值闭环）
3. 定位访谈（LangGraph 最复杂的一块）
4. 拆视频 → 拆账号（依赖第三方 API 联调）
5. 发布复盘状态机 + 周归因（纯 Java，收尾）

---

## 6. 已知风险与待决事项

Review 后补充，实现前需留意（含推荐默认值，可按需调整）：

| 项 | 说明与推荐处理 |
| --- | --- |
| **JWT 即时失效** | 纯 JWT 无法主动作废，换绑手机号/封号/登出场景下旧 token 仍有效。`app_user.token_version` 列与签发时写入 claim 已就位，但 **MVP 的过滤器不查库比对版本（避免每请求一次查询），即接受「旧 token 到自然过期前仍有效」的风险**；C 端 token 过期设 7 天。V1.1 若需要即时失效，在 `UserJwtFilter` 补一行版本比对即可（表结构无需变更）。 |
| **生成式 AI 合规备案** | 本项目属「生成式人工智能服务」，除 ICP 备案外，可能需算法/大模型服务备案。**上线前的现实门槛**，需尽早向服务商/监管确认，别等上线才发现。 |
| **额度账本备份粒度** | `pg_dump` 每日一次最坏丢一整天钱账。推荐对 Postgres 开 **WAL 归档做 PITR**（或额度相关表更高频备份），钱账不可只靠日备。 |
| **单位经济性核算** | ¥0.86/条，但每次生成注入全量 A 层 + top5 B 层 + 三平台版本，token 不少；拆账号 = TikHub 调用费（列表 + 20 条详情/直链，付费接口）+ 20 条**阿里云录音文件识别费**（每条数十秒到几分钟音频）+ LLM 逐条结构化与归纳费。**实现前务必粗算「每次生成 / 每次拆账号」的真实成本 vs 定价（拆账号约 ¥8.6/次）**，确认不亏本——直接关系商业模式成立与否。 |
| **GLM 档位与成本** | 统一用 GLM，但按 skill 分档：高频创作类 GLM-4.7（thinking 关）、轻量抽取 GLM-4.5-Air、深度任务 GLM-4.7（thinking 开）。在 `llm/` 配置层绑定，并纳入上面的成本核算。 |
| **文案创作口语质感（北极星）** | `script_gen` 起步用 GLM-4.7，上线前必须用「评测用例集」盲评确认口语质感达标——这是采用率的命门；若不达标，该 skill 单独切换模型（架构支持改一行配置）。 |

---

## 7. 明确不做（MVP 边界）

- 不引入 Redis / 消息队列 / 微服务拆分 / K8s
- 不做在线支付（人工开通）、不做移动端适配（V1.1）、不做视频生成（V2）
- 不做数据自动抓取（发布复盘手动填数，表结构已预留）
- 不做 rerank / 混合检索 / 自建 embedding 模型
- 不做自动化端到端测试与 prompt 自动评测
- 不做对标视频的**逐条深拆/仿写**（V1.1；`benchmark_video` 行表已预留）
- 不做管理端**经营统计**页（V1.1；MVP 管理端只有开通与补偿）
