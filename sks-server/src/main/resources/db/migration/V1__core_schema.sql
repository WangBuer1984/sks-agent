CREATE EXTENSION IF NOT EXISTS vector;

-- 账号与额度
CREATE TABLE app_user (
  id BIGSERIAL PRIMARY KEY,
  phone VARCHAR(20) UNIQUE NOT NULL,
  nickname VARCHAR(50),
  gender VARCHAR(10), age INT, city VARCHAR(50),   -- 基础资料（PRD §4.3；city 注入生成做本地化选题）
  industry VARCHAR(50), identity VARCHAR(50), style VARCHAR(50),
  weekly_goal INT,
  default_platform VARCHAR(20) NOT NULL DEFAULT 'douyin',  -- 主平台（PRD §4.2「默认生成用户主平台版本」的存储位置）
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
  progress INT NOT NULL DEFAULT 0,  -- 语义钉死：已完成条数/总条数×100（整数），不是阶段进度——按比例退款 refundN = charged×(100-progress)/100 的数学依赖此口径（Task 3.3）
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
