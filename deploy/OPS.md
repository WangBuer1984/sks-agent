# OPS — 部署与运维收尾（P5）

> 对应设计文档 §5.3。本文记录<b>外部/控制台/宿主</b>侧的运维项——它们不是代码，
> 无法单元测试，上线前由人按清单执行。代码侧产物见同目录 `nginx/`、`backup/` 与
> `sks-server/src/main/java/com/sks/common/QuotaWatchJob.java`。

## 0. 联调-gated 项总览（代码留桩，上线/联调时接线）

| 项 | 留桩位置 | 联调动作 |
|----|----------|----------|
| 阿里云 SMS 发送（验证码 + 告警） | `AuthService.sendCode` `[SMS-STUB]` / `QuotaWatchJob.sendAlert` `[SMS-STUB]` | 替换为阿里云 Dysmsapi，access-key via `.env`；二者统一 seam |
| 阿里云 SMS 余额查询 | `QuotaWatchJob.querySmsBalance` → `Optional.empty()` | 接 Dysmsapi 账户余额查询 API |
| 智谱账户余额查询 | `QuotaWatchJob.queryGlmBalance` → `Optional.empty()` | 接智谱 BigModel 用户余额查询 API，glm api-key via `.env` |
| 对象存储上传（OSS/COS） | `pg_backup.sh` `OSS_BUCKET` 未设 → 跳过 | 装 aliyun-oss / coscli，设 `OSS_BUCKET` + 远端 30 天保留 |
| certbot HTTPS | `nginx.conf` 注释块 | 真实域名 + 证书签发（见 §1） |
| UptimeRobot 拨测 | 无代码 | 控制台配 `https://域名/api/health`（见 §3） |

---

## 1. HTTPS — certbot / Let's Encrypt

本地/联调保持 80-only（`nginx.conf` 当前生效配置）。上线前用真实域名签发证书：

```bash
# 1) DNS 解析指向服务器；80 端口公网可达（certbot 验证用）
# 2) 安装 certbot + nginx 插件
sudo apt update && sudo apt install -y certbot python3-certbot-nginx

# 3) 签发（自动改 nginx 配置 + 写 /etc/letsencrypt/live/域名/）
sudo certbot --nginx -d 你的域名 --non-interactive --agree-tos -m 站长邮箱

# 4) 取消 nginx.conf 中 443 块 + 80→443 跳转块的注释（域名替换为真实域名）；
#    docker compose up -d --build nginx

# 5) 续期（certbot 装好后自动写入 /etc/cron.d/certbot，可手测）
sudo certbot renew --dry-run
```

证书路径写入 `nginx.conf` 443 块的 `ssl_certificate` / `ssl_certificate_key`。

## 2. 每日备份 — 宿主 crontab

`backup/pg_backup.sh` 由宿主 crontab 触发（容器内不便装 cron，与 CLAUDE.md「无 K8s/无 MQ」一致）：

```bash
# 编辑宿主 crontab
crontab -e

# 每日 03:00 备份（额度账本不可丢）
0 3 * * * /path/to/sks-agent/deploy/backup/pg_backup.sh >> /var/log/sks-pg-backup.log 2>&1

# certbot 续期（certbot 安装时已自动写入，一般无需手动加；此处仅备忘）
# 0 3 * * * /usr/bin/certbot renew --quiet --post-hook "docker compose --project-directory /path/to/sks-agent restart nginx"
```

环境变量（写入 crontab 顶部或脚本调用方）：

```
BACKUP_DIR=/backup
SPRING_DATASOURCE_USERNAME=sks
PG_DB_NAME=sks
COMPOSE_DIR=/path/to/sks-agent
OSS_BUCKET=               # 联调后填真实 bucket 名
RETAIN_DAYS=30
```

### 上线前必做 — 恢复验证

```bash
bash deploy/backup/pg_restore_verify.sh /backup/sks-YYYY-MM-DD.sql.gz
```

脚本把备份导入临时库 `sks_verify`（不碰生产 `sks`），对关键表（`app_user` / `credit_account` /
`credit_ledger` / `script` / `kb_card` / `topic` / `analyze_task`）计数 + 删临时库。任一表缺失
→ 退出 1。**额度账本不可丢，此项上线前必须跑通一次。**

## 3. 外部拨测 — UptimeRobot（控制台配置，无代码）

免费版监控 `https://域名/api/health`：

- 类型：HTTP(s)
- URL：`https://你的域名/api/health`
- 期望响应：`{"status":"UP"}`（`HealthController` 返回）
- 间隔：5 min
- 告警：邮件 +（可选）短信提醒——UptimeRobot 控制台配置，不在代码里

容器/进程宕机 → UptimeRobot 邮件告警 + nginx 502 兜底页（`50x.html`）。

## 4. 50x 兜底页 — 上线前替换占位

`nginx/50x.html` 文案 verbatim PRD §11.6：
> 服务暂时不可用，已记录。加站长微信 XXX 反馈，确认属实补偿额度。

`XXX` 为占位 `{{CONTACT_WECHAT}}`，上线前用真实站长微信号替换（**不要把真实微信号硬编码进 git**）：

```bash
# 方式一：构建期 envsubst
export CONTACT_WECHAT=站长微信号
envsubst < deploy/nginx/50x.html > deploy/nginx/50x.html.real && mv deploy/nginx/50x.html.real deploy/nginx/50x.html

# 方式二：容器启动时 entrypoint envsubst（推荐，密钥不入镜像）
```

## 5. 余额监控 — QuotaWatchJob（代码侧）

`QuotaWatchJob` 每日 09:00 跑（`@Scheduled(cron = "0 0 9 * * *")`）：

- 查阿里云 SMS 余额（条数）+ 智谱账户余额（元）——**联调留桩**（`querySmsBalance` / `queryGlmBalance`
  返回 `Optional.empty()`）；
- 阈值判定（纯函数 `checkAndAlert`，可单测）：SMS <100 条 / GLM <¥20 触发告警，严格 `<`（at-threshold 不告警）；
- 单个查询抛异常 → catch 记 WARN + 跳过该项，不中断 Job；
- 告警走 `sendAlert`（**联调留桩**，与 `AuthService` 的 SMS-STUB 同档，待统一接线）。

阈值 + 站长手机 via `application.yml`：

```
sks.quota.sms-threshold   (默认 100)
sks.quota.glm-threshold   (默认 20)
sks.quota.admin-phone     (from .env SKS_QUOTA_ADMIN_PHONE)
```

## 6. 全链路超时对齐（§5.3 — load-bearing）

**内层短于外层，外层不可先掐断仍在跑的调用**：

| 层 | 超时 | 配置位置 |
|----|------|----------|
| Python 内 LLM 单次 | 120s × 最多 2（原始+1 重试）≈ 250s | `sks-ai/app/llm/` |
| Java AiClient read | 270s | `sks.ai.read-timeout-seconds`（`application.yml`） |
| Java AiClient connect | 10s | `sks.ai.connect-timeout-seconds` |
| nginx `/api/` | 300s | `nginx.conf` `proxy_read_timeout` / `proxy_send_timeout` |

链路：**250s < 270s < 300s**。误对齐的后果：nginx/Java 先掐断 Python 仍在跑的 LLM → 假 `AI_FAILED` →
误退款（钱不丢但工作白费、用户重试）。

## 7. 验收清单（§5.2 全链路手动过一遍）

- [ ] `curl -s https://域名/50x.html` 可见兜底页（503/502 触发 `error_page` 重定向）
- [ ] `bash deploy/backup/pg_backup.sh` 产出 `.sql.gz` 且 `pg_restore_verify.sh` 跑通
- [ ] 停掉 java 容器后 UptimeRobot 在 5 min 内告警
- [ ] QuotaWatchJob 手测：把阈值调到极高触发告警日志（联调后改 SMS）
