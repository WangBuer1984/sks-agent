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
| 拆账号数据源 | 付费第三方数据 API（TikHub 类服务），成本计入拆账号定价 |
| 服务架构 | Java 业务层 + Python AI 层，双服务；**Java 做唯一公网入口**（方案 A） |
| 前端 | React 18 + Vite + TypeScript |
| 数据库 | PostgreSQL 16 + pgvector（单库承载业务数据 + 向量） |
| Agent 框架 | LangGraph |

**方案 A 的核心理由：** 鉴权与额度收口在 Java 一处——「扣额度 → 调生成 → 失败退额度」这条 PRD 核心事务链在 Java 内部闭环，无跨服务对账；Python 层无状态、不对公网暴露、可随时重启；一人维护安全面与心智负担最小。代价是流式输出需 Java 做 SSE 透传（一次性成本，Spring MVC `SseEmitter` 可实现）。

**被否决的备选：**
- 方案 B（前端直连双服务）：「失败退额度」变跨服务补偿逻辑，易出对账 bug；鉴权/限流/CORS 双份，运维面翻倍。
- 方案 C（Python 主服务 + Java 账务子模块）：大量 CRUD 与状态机被迫用 Python 写，浪费 Java 主力优势。

---

## 1. 总体架构与技术栈

```
浏览器 (React SPA)
      │ HTTPS (REST + SSE)
      ▼
┌─────────────────────┐   内网 HTTP    ┌──────────────────────┐
│  Java 业务服务       │ ────────────▶ │  Python AI 服务       │
│  Spring Boot 3      │ ◀──────────── │  FastAPI + LangGraph  │
│  (唯一公网入口)       │   SSE 透传     │  (不暴露公网)          │
└─────────┬───────────┘               └──────────┬───────────┘
          │                                      │
          ▼                                      ▼
   PostgreSQL 16 (+pgvector)          DeepSeek / GLM API
   （业务数据 + 向量 同一实例）          TikHub 类第三方数据 API
```

### 技术栈清单

| 层 | 选型 | 说明 |
| --- | --- | --- |
| 前端 | React 18 + Vite + TypeScript + Tailwind CSS | PRD §9 纸感视觉规范（#f4f1e9 / #8a5a2b / Noto Serif SC 等）落为 Tailwind 主题变量；服务端状态用 TanStack Query，客户端状态用 Zustand |
| 业务服务 | Java 21 + Spring Boot 3.x + Spring Security (JWT) + MyBatis-Plus 或 Spring Data JPA | 登录/额度/CRUD/状态机/定时任务；SSE 透传用 Spring MVC `SseEmitter`，不引入 WebFlux |
| AI 服务 | Python 3.12 + FastAPI + LangGraph + langchain-openai（OpenAI 兼容协议调 DeepSeek/GLM） | 无业务状态；访谈多轮状态用 LangGraph Postgres checkpointer 存回同一个库 |
| 数据库 | PostgreSQL 16 + pgvector 扩展 | 业务表 + 知识库向量 + LangGraph 检查点，单实例三合一 |
| 缓存/队列 | **不引入 Redis/MQ**：验证码与频控存 Postgres；拆账号等长任务用 DB 任务表 + Java 定时轮询 | 冷启动单机原则：能用 Postgres 解决的不加中间件，扩展时再换 |
| Embedding | 智谱 embedding-3 或 DeepSeek 兼容 embedding API | 中文效果好，免自建模型 |
| 短信 | 阿里云 SMS | 验证码发送；频控逻辑在 Java 侧 |
| ASR（语音回答） | 阿里云 / 讯飞 ASR API | 定位访谈与补卡的语音输入转文字 |
| 部署 | 单台云服务器（建议 4C8G）+ Docker Compose 四容器（nginx / java / python / postgres） | HTTPS 由 nginx + Let's Encrypt；数据库每日 `pg_dump` 上传对象存储 |

### 硬性设计原则

Python 服务完全无状态、不做用户鉴权（只信任内网来源 + 服务间共享密钥 `X-Service-Token`）。所有「谁在调、扣不扣费、结果存哪」由 Java 决定；Python 只回答「给我输入，我给你 AI 产出」。

---

## 2. 服务内部模块划分

### 2.1 Java 业务服务（单体分包，非微服务）

```
sks-server/
├── auth/        手机号+验证码登录、JWT 签发、验证码频控（1min/1h/1d 三级，PRD §11.1）
├── user/        个人中心：基础资料 + 创作资料、完善度计算
├── credit/      额度账本（核心模块，见 §4.1）、充值人工开通后台接口
├── profile/     账号定位：校准会话入口、定位档案 CRUD、校准进度保存
├── kb/          知识库：A/B/C 三层卡片 CRUD、补卡任务、卡片引用计数与删除保护
├── topic/       选题库：四路来源聚合、支柱配比排序、状态管理
├── analyze/     对标拆解：拆视频（同步）、拆账号（异步任务，见 §4.3）
├── script/      文案创作：生成请求编排、稿件存储、逐句编辑、查重、三平台版本
├── review/      发布复盘：六状态机、手动填数、归因触发、周归因卡
├── aiclient/    对 Python 服务的 HTTP/SSE 客户端封装（唯一出口，统一超时重试与错误码翻译）
└── common/      全局异常、审计日志、任务表轮询调度器（@Scheduled）
```

### 2.2 Python AI 服务（按 Skill 组织，对应 PRD §9「智能体 Skill 工作流」）

```
sks-ai/
├── api/                 FastAPI 路由层：每个 skill 一个 endpoint（同步 JSON 或 SSE）
├── skills/
│   ├── interview/       定位访谈：LangGraph 多轮状态机（猜人设→确认→5-8 问→出档案）
│   ├── script_gen/      文案生成：注入定位档案 + RAG 检索 B 层卡片 → 钩子/正文/转化分段输出
│   ├── video_analyze/   拆视频：结构标注、为什么火、可复用框架、人设差异提醒
│   ├── account_analyze/ 拆账号：第三方 API 抓 TOP20 → 逐条结构化 → 规律归纳 → 迁移建议
│   ├── attribution/     复盘归因：单条归因 + 周归因卡
│   └── card_gen/        补卡：大白话 → 结构化卡片、缺口检测、冲突检测
├── rag/                 embedding 调用、pgvector 检索、引用溯源（返回所用卡片 id 列表）
├── llm/                 多模型调度：按 skill 配置模型（创作→DeepSeek-V3 口语质感，
│                        归纳/归因→GLM 或 DeepSeek-R1），OpenAI 兼容协议统一封装，互为降级备份
└── datasource/          TikHub 类第三方 API 客户端（账号主页、视频列表、文案提取）
```

### 2.3 服务间接口契约

- Java → Python 全部走内网 REST。请求体自带**全部上下文**（定位档案 JSON、用户创作资料）；例外：RAG 检索由 Python 直连 pgvector 完成，Java 只传 `user_id` 与选题文本。
- 流式接口（文案生成、访谈对话）：Python 返回 SSE，Java 用 `SseEmitter` 逐事件透传前端；**最后一个事件携带结构化结果**（完整稿件 + 引用卡片 id），Java 收到该事件才落库、确认扣费。
- 每个请求带 `X-Request-Id`（Java 生成）串联两侧日志；带 `X-Service-Token` 共享密钥防内网误访问。
- 拆账号长任务（约 1-3 分钟）：Java 建任务记录 → 调 Python 异步接口取 `job_id` → **Java 定时轮询** Python 任务状态接口（不用回调：回调要求 Python 理解 Java 的接口与重试语义，轮询更简单，单机内网延迟可忽略）。

---

## 3. 数据模型与 RAG 设计

### 3.1 核心表（约 15 张，列关键项）

**账号与额度：**

| 表 | 要点 |
| --- | --- |
| `user` | 手机号唯一；基础资料 + 创作资料（行业/身份/出镜风格/周目标）直接放列，不拆表 |
| `sms_code` | 验证码 + 过期时间 + 错误次数；频控按手机号 + 时间窗聚合查询此表，不需要 Redis |
| `credit_account` | 每用户一行：`balance` 当前余额，仅由账本流水汇总更新 |
| `credit_ledger` | **只追加流水账**：`+50 充值 / -1 生成 / +1 失败退回 / -10 拆账号`，每笔带 `biz_type` + `biz_id`（关联稿件或任务）；退款幂等靠 `(biz_id, type)` 唯一约束；对账、审计、纠纷全靠它 |

**定位与知识库：**

| 表 | 要点 |
| --- | --- |
| `positioning_profile` | 定位档案：人设/人群/差异化/变现/红线/支柱配比，存 JSONB（结构由 AI 产出、schema 会演进）；`version` 字段支持重新校准留历史 |
| `kb_card` | 三层统一一张表：`layer`(A/B/C) + `card_type`(身份/口吻/规则/产品/FAQ/案例/爆款素材) + `title` + `content`(JSONB) + `embedding vector`；编辑卡片即时重算 embedding（PRD §7.4 立即生效） |
| `card_history` | 补卡冲突时旧值归档（PRD §11.4） |
| `card_citation` | 稿件 ↔ 卡片引用关系；删除卡片前查引用数做二次确认；卡片删除后旧稿溯源标注「卡片已删除」 |

**选题、稿件与复盘：**

| 表 | 要点 |
| --- | --- |
| `topic` | 四路来源（`source`: hot/faq/benchmark/replay）+ 依据说明 + 内容支柱标签 |
| `script` | 稿件：钩子/正文/转化三段 JSONB（含逐句结构，支持逐句改）、平台版本、`review_state`（六状态机字段就在稿件上，不单独建表）、发布链接、播放数据（手填）、`data_source`(manual/auto) |
| `analyze_task` | 拆账号任务：状态(queued/running/partial/done/failed)、进度、实扣额度；四层产出存 JSONB |
| `benchmark_video` | 拆出的 TOP20 明细，每条可独立「深拆 / 仿写」 |
| `weekly_report` | 周归因卡 |

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
  → Java 开事务：校验余额 → 写 credit_ledger(-1, biz_id=script_id) → 更新余额 → 提交
  → Java 调 Python SSE 生成，逐事件透传前端
  → 成功（收到末尾结构化事件）：稿件落库，结束
  → 失败（超时/异常/内容安全二次命中）：
      写 credit_ledger(+1, biz_id=script_id, type=refund)   ← (biz_id,type) 唯一约束保证幂等
      → 前端 toast「生成失败，额度已退回」（PRD §11.2）
```

- **先扣后调**，宁可退款不可漏扣。
- 同选题「重新生成 / 换角度」由 Java 判断 `topic_id` 相同则免扣（PRD §4.2）。
- Python 全程不感知额度。

### 4.2 定位访谈（多轮对话）

- LangGraph 状态机：`猜人设 → 用户反馈(对/不对) → 逐题访谈(5-8 问) → 生成档案 → 确认生效`。
- 会话状态用 LangGraph 官方 **Postgres checkpointer** 持久化（同一个库，`thread_id = 用户id + 会话id`），天然满足「进度保存退出、断点继续」（PRD §11.4）；Java 只记录「校准进行中，第 X 步」供工作台横幅展示。
- 语音回答：前端录音 → Java 转发 → Python 调 ASR 转文字后进入同一流程。
- 档案确认生效：Python 返回结构化档案 + 自动拆出的 A 层卡片，Java 一并落库。

### 4.3 拆账号（长任务，1-3 分钟）

```
Java：预检链接（调 Python 轻量预检接口：账号可访问性、视频数 N）
  → 预检失败：不扣额度，提示更换链接或手动粘贴（PRD §11.3）
  → 扣费 min(10, floor(N/2))（按比例向下取整，如 12 条扣 6），开始前明示；建 analyze_task(queued)
  → 调 Python 异步接口取 job_id，任务转 running
Python：抓 TOP20 → 逐条转录/结构化 → 规律归纳 → 迁移建议，结果写 job 存储
Java：@Scheduled 每 5 秒轮询 job 状态 → 进度回写 analyze_task（前端轮询 Java 显示进度条）
  → done：账号画像/TOP20 清单/规律归纳/迁移建议 四层结果落库
  → 部分失败：保留已完成部分，按未完成条数比例退额度，可免费续拆（PRD §11.3）
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
- **LLM 调用**：每个 skill 配置超时（生成 120s、拆账号单条 60s）+ 1 次自动重试；模型 API 不可用时按 skill 配置降级到备选模型（DeepSeek ↔ GLM 互为备份）。
- **内容安全**：命中违禁词自动重写一次，仍命中返回特定错误码，Java 走退款流程（PRD §11.2）。
- **查重**：生成完成后 Java 对历史稿件做相似度比对，命中不阻断，稿件顶部黄色提示 + 「换角度」按钮（PRD §11.2）。
- **401 保内容**：前端 axios 拦截器捕获 401 时把当前表单/编辑器内容存 localStorage，重登后恢复（PRD §11.6）。
- **全站兜底**：nginx 静态 50x 页面，展示站长微信 + 补偿承诺（PRD §11.6）。

### 5.2 测试策略（一人项目，把钱花在刀刃上）

- **重点覆盖（JUnit）**：`credit` 模块（扣费/退款/幂等/并发扣减）、复盘状态机迁移规则、验证码频控——这三处出 bug 直接损钱或损信任。
- **AI 层（pytest）**：测流程编排逻辑（mock LLM 响应）；prompt 效果不写自动化测试，维护一份固定「评测用例集」（10 个典型用户画像 + 选题），发版前人工过一遍。
- **端到端**：不上自动化；发版前手动过「注册→校准→拆账号→生成→采用→登记」主链路清单。

### 5.3 部署与运维

- Docker Compose 四容器：`nginx`（HTTPS 终结 + 静态前端 + 反代）、`sks-server`、`sks-ai`、`postgres`；服务器 4C8G。
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

## 6. 明确不做（MVP 边界）

- 不引入 Redis / 消息队列 / 微服务拆分 / K8s
- 不做在线支付（人工开通）、不做移动端适配（V1.1）、不做视频生成（V2）
- 不做数据自动抓取（发布复盘手动填数，表结构已预留）
- 不做 rerank / 混合检索 / 自建 embedding 模型
- 不做自动化端到端测试与 prompt 自动评测
