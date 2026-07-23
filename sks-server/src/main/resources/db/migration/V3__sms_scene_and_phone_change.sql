-- sms_code scene 化 + 换绑 session 关联
ALTER TABLE sms_code
  ADD COLUMN scene VARCHAR(32) NOT NULL DEFAULT 'LOGIN_REGISTER',
  ADD COLUMN session_token VARCHAR(64);

-- 换绑手机号 2-step flow 状态表
CREATE TABLE phone_change_session (
  id BIGSERIAL PRIMARY KEY,
  token VARCHAR(64) UNIQUE NOT NULL,
  user_id BIGINT NOT NULL REFERENCES app_user(id),
  old_phone VARCHAR(20) NOT NULL,
  new_phone VARCHAR(20),
  status VARCHAR(32) NOT NULL,        -- AWAITING_OLD_VERIFY / AWAITING_NEW_VERIFY / DONE
  old_verified_at TIMESTAMPTZ,
  expires_at TIMESTAMPTZ NOT NULL,     -- 建行 now()+10min；verify-old 通过时重置 now()+10min
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- 同一用户同时只允许一个未完成 session（先删后建；并发兜底）
CREATE UNIQUE INDEX uq_phone_change_active ON phone_change_session(user_id) WHERE status <> 'DONE';
