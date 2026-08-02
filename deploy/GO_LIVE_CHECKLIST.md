# 上线 / 联调 Checklist — 随口说 MVP

MVP P0–P5 已 code-complete（`main` HEAD `e701f1a`）。本文档是 go-live 前的联调准备清单：`.env` 占位 key 分类、外部/控制台配置、联调首检项、人工全链路验收。

> 本文档只列 key **名**与状态，不含任何密钥值。`.env` 是 gitignored 本地文件。

---

## 0. 已验证（本地容器栈 E2E 通过，无需真实外部 key）

以下在 `docker compose pull --ignore-buildable && docker compose up -d` 后已端到端验证：

- ✅ 5 容器全 healthy（postgres / sks-server / sks-ai / sks-web / nginx，sks-server/sks-ai/sks-web 为 GHCR 镜像，gateway 本地 build）
- ✅ `GET /api/health`（经 nginx）→ `{"status":"UP"}`
- ✅ `curl /50x.html` → 200（兜底页可达）
- ✅ 注册流：send-code（SMS stub）→ 登录 → `isNew` 触发体验额度钩子 → `GET /api/user/me` balance=3
- ✅ **§4.1 failure→refund 链**：generate→`5001 AI_FAILED`（GLM key 空）→ balance 恢复 → credit_ledger `trial 3 / generate debit -1 / refund 1`（净 3）→ script `failed` 1 行。「fail→refund 永不漏扣」+ I1 超时链（Python 240<Java 270<nginx 300）成立
- ✅ **§4.3 precheck→不扣费**：`/api/analyze/account`→`5001`（TikHub key 空→precheck 失败）→ balance 不变 → analyze_task 0 行 → 无 analyze debit
- ✅ **Admin seed + 管理端**：AdminSeedRunner 用 `ADMIN_SEED_PASSWORD` 哈希回填 `admin` 行 → `/api/admin/auth/login` `admin`/`<ADMIN_SEED_PASSWORD>` → token（adminId=2「站长本人」）
- ✅ **§7 首项 admin→open→C端余额**：`/api/admin/orders/open {userId, pkg:p50}` → C端 balance=63（3 trial + 50 recharge + 10 首充赠送），order `recharge p50 done`
- ✅ **JWT 双密钥隔离**：admin token→C端 `/api/user/me` 401；C端 token→`/api/admin/orders/open` 401（aud 隔离生效）

---

## 1. `.env` key 分类（联调必填项）

`.env` 现状（本地，gitignored）。✅=已真实可用；⚠️=占位（本地能跑但 prod 须换真值）；❌=空/未设（联调必填，block go-live）。

| Key | 状态 | 说明 / 联调动作 |
|---|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` | ✅ | `sks` / `sks` |
| `POSTGRES_PASSWORD` | ⚠️ `change_me` | 本地能跑；prod 须换强密码（含 pg_backup 恢复链路） |
| `JWT_SECRET_USER` | ✅ | 64 字符随机（JwtConfig guard 通过，登录验签过）；prod 可轮换 |
| `JWT_SECRET_ADMIN` | ✅ | 64 字符随机（admin 登录验签过）；prod 可轮换。**与 USER 不同密钥**（aud 隔离） |
| `SERVICE_TOKEN` | ⚠️ `change_me_internal_shared_token` | Java↔Python 共享密钥；本地两端同值能跑；prod 须换强随机（防内网伪造） |
| `ADMIN_SEED_USERNAME` | ✅ `admin` | 站长登录名 |
| `ADMIN_SEED_PASSWORD` | ⚠️ `change_me_admin_login_pwd` | 站长登录密码；**prod 须换强密码**（BCrypt 哈希后写 admin_user） |
| `TRIAL_CREDIT` | ✅ `3` | 注册体验额度 |
| `ZHIPU_API_KEY` | ❌ 空 | **联调必填**：智谱 GLM。空则 script_gen/attribution/interview/card_gen 全 AI_FAILED |
| `TIKHUB_API_KEY` | ❌ 空 | **联调必填**：TikHub。空则 precheck/hot_board/拆账号全 DataSourceError |
| `ALIYUN_ACCESS_KEY_ID` | ❌ 空 | **联调必填**：阿里云主账号 AK。空则内容安全 fail-closed（**所有 UGC 被拦**）+ ASR + 余额查询 |
| `ALIYUN_ACCESS_KEY_SECRET` | ❌ 空 | **联调必填**：阿里云 SK |
| ~~`ALIYUN_SMS_SIGN`~~ | — | **不再是 env 键**（连同端点与三个模板号）：全部写死在 sks-server `application.yml` 的 `sks.sms.*`，换签名/模板改那几行。短信「真发 or stub」的唯一闸门是上面 AK 两项 |
| `SPRING_MAIL_HOST` | ❌ 空 | **联调必填**：SMTP 主机（告警邮件通道） |
| `SPRING_MAIL_PORT` | 465 | SMTPS 端口（465 需 ssl.enable=true，已配） |
| `SPRING_MAIL_USERNAME` | ❌ 空 | **联调必填**：SMTP 账号 |
| `SPRING_MAIL_PASSWORD` | ❌ 空 | **联调必填**：SMTP 授权码（密钥，入 .env） |
| `SKS_ALERT_ADMIN_EMAIL` | ❌ 空 | **联调必填**：站长告警收件邮箱 |

### `.env` 缺失项（需新增行）

| Key | 用途 | 默认/说明 |
|---|---|---|
| `ALIYUN_ASR_KEY` | 短 ASR（paraformer）+ 长转写（`qwen3-asr-flash`）→ `/ai/asr` 与拆视频/拆账号 | DashScope/百炼 API key，与主 AK 不同 |
| `ALIYUN_ASR_APP_KEY` | **deprecated**（长转写已硬切 Qwen，sks-ai 不再读取） | 可留空；兼容旧 `.env` |
| `ASR_TMP_DIR` | sks-ai 媒体下载/转码临时目录（可选） | 空 → 系统 tempfile；生产建议挂卷并监控磁盘 |
| `ALIYUN_CONTENT_SAFETY_ENDPOINT` | 内容安全 POP 端点 | 默认 `https://green.cn-shanghai.aliyuncs.com`（`config.py` 有默认，可不设） |
| `TIKHUB_BASE_URL` | TikHub 基址 | 默认 `https://api.tikhub.dev`（**主域名被墙，必须用此值**，`config.py` 有默认） |
| `SKS_AI_BASE_URL` | Java→Python 内网地址 | 默认 `http://sks-ai:8000`（docker 内网） |
| `SKS_AI_READ_TIMEOUT` / `SKS_AI_CONNECT_TIMEOUT` | AiClient 超时（§5.3 链） | 默认 270 / 10 秒（**勿低于 270，否则 §5.3 链断裂**） |
| `sks.review.hot-threshold` / `flop-threshold` | 复盘判态阈值 | 默认 3.0 / 0.5（近 30 天均值 × 阈值） |
| `sks.quota.sms-threshold` / `glm-threshold` | 余额告警阈值 | 默认 100 条 / ¥20 |

> ~~`ALIYUN_SMS_TEMPLATE_LOGIN` / `_VERIFY_OLD` / `_BIND_NEW`~~ 已不是 `.env` 缺失项——三个模板号
> （`100001` / `100002` / `100004`，字面码 `{"code":"<6>","min":"5"}`）连同签名与端点都写死在 sks-server
> `application.yml` 的 `sks.sms.*`。**别为了「补齐」往 `.env` 加回这几行**：留一行空的 `XXX=` 会覆盖掉
> yml 里的值（`${VAR:默认值}` 对「已定义但为空」用空值），短信静默退回 stub、不发也不报错。

---

## 2. 外部 / 控制台配置（无代码，详见 `deploy/OPS.md`）

- [ ] **certbot Let's Encrypt**：真实域名签发证书 + 续期 crontab；nginx 443 server block 取消注释 + **443 块 `location /` 改 `proxy_pass http://sks-web:80`（同 80 块）+ 删 443 块 server 级 root/index**（见 gateway nginx.conf）；`certbot renew --dry-run` 通过
- [ ] **UptimeRobot**（免费版）：监控 `https://<域名>/api/health` 期望 `{"status":"UP"}`，5 分钟间隔，宕机 email + 短信
- [ ] **OSS/COS 对象存储**：`pg_backup.sh` 的 `OSS_BUCKET` 设值后启用真实上传（当前 env-gated 跳过）；备份保留 30 天
- [ ] **`{{CONTACT_WECHAT}}` 替换**：部署的 `50x.html` 用 envsubst 替换为真实站长微信号（勿硬编码）
- [ ] **pg_backup crontab**：宿主 `0 3 * * * bash deploy/backup/pg_backup.sh`
- [ ] **restore-verify 上线前必做**：`bash deploy/backup/pg_restore_verify.sh <file>` 到 temp db `sks_verify` 全 7 表 count 通过（**额度账本不可丢**）

---

## 3. 联调首检项（配真实 key 后第一时间核对）

- [ ] **GLM 结构化输出**：`script_gen` 的 `{hook,body,cta}` 句结构 schema 被智谱正确返回（json_schema 支持）；`interview` summarize schema、`card_gen` schema 同理
- [ ] **GLM thinking 转发**：`extra_body.thinking` 经 langchain-openai 透传到智谱（归因/归纳用 thinking-on 档）
- [ ] **阿里云内容安全签名**：`content_safety` 的 ACS ROA 签名（HMAC）被正确接受（fail-closed 解除，正常 UGC 不再被拦）
- [ ] **阿里云短信认证（DYPNS）**：`POST /api/auth/send-code` 真收验证码（字面码，`AliyunSmsAuthClient` DYPNS `SendSmsVerifyCode`；只需配 AK 两项，签名/模板已在 `application.yml`，见上表）
- [ ] **告警邮件（SMTP）**：`SPRING_MAIL_*` + `SKS_ALERT_ADMIN_EMAIL` 配齐，`QuotaWatchJob` 触发告警邮件送达站长邮箱（`MailAlertNotifier`，465 + SSL）
- [ ] **TikHub 响应契约**：`account_top_videos`/`precheck`/`hot_board` 的字段路径（`aweme_list`/`code==200`/`hot_list`）与防御性解析一致；`api.tikhub.dev` 可达
- [ ] **TikHub download_url 可达性**：sks-ai 本地下载 TikHub 签名直链（反爬/CDN 过期）；视频号需 `decode_key` + `node` WASM decrypt
- [ ] **sks-ai 镜像含 ffmpeg + nodejs**：`ffprobe`/`ffmpeg` 在 PATH（短 ASR pydub + Qwen 管线）；`node` 在 PATH（视频号 decode）。缺任一则长转写/校准语音会失败
- [ ] **【P2 联调首检】ASR webm→pcm**：短 ASR 经 pydub+ffmpeg 转 pcm 再送 paraformer；镜像装 ffmpeg 后应可勾（不再依赖前端改格式）
- [ ] **Qwen 长转写联调**：配 `ALIYUN_ASR_KEY`（百炼）后拆视频/拆账号能出非空 transcript；视频号分享链单条 + 拆账号 TOP N
- [ ] **GLM `max_retries=1` 生效**：`client.py` 的 §5.3 超时链可证（Python 240<Java 270<nginx 300）；真实长 LLM 调用不被外层掐断

---

## 4. 人工全链路验收（配齐 key 后，按设计文档 §5.2 / OPS.md §7 手动过）

- [ ] 注册 → 体验单自动创建 + 余额 3 → 尾号搜索到用户 → 管理端开通 p50 → C 端余额 63（3+50+10）✅ 已验
- [ ] 管理端补偿 +5 → 订单表 `order_type=compensate` → C 端余额增加，不触发首充赠送
- [ ] 校准访谈走完（含至少一轮语音回答转文字）→ 定位档案生效 + A 层卡 → 中途退出可续答
- [ ] 建 B 层卡（即时算 embedding）→ 生成稿件命中该卡并在右栏溯源
- [ ] 生成失败额度自动退回；同选题重新生成不扣费；逐句编辑：单句手改落库 + 单句 AI「换个说法」不扣且过安全
- [ ] 拆视频（粘文案同步 / 粘链接异步）+ 拆账号（异步 TOP20 + 四层结果；杀 Python 容器验证 queued/running 任务被判 failed 并退款）
- [ ] 采用→登记链接→填播放量→判 hot/plain/flop→爆款出 C 层卡与续集选题
- [ ] 周日定时周归因卡生成
- [ ] 管理端 token 不能访问 C 端接口，反之亦然（隔离验证）✅ 已验
- [ ] 50x 兜底页可见；备份脚本产出可恢复；拨测告警生效 ✅ 部分已验

---

## 5. 验证命令速查

```bash
# 起栈
docker compose pull --ignore-buildable && docker compose up -d

# 健康
curl -s localhost/api/health                          # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}' localhost/50x.html  # 200

# 注册（SMS stub，code 在 sms_code 表）
curl -s -X POST localhost/api/auth/send-code -H 'Content-Type: application/json' -d '{"phone":"139xxxxxxxx"}'
CODE=$(docker compose --env-file .env exec -T postgres psql -U sks sks -tAc \
  "SELECT code FROM sms_code WHERE phone='139xxxxxxxx' ORDER BY id DESC LIMIT 1")
curl -s -X POST localhost/api/auth/login -H 'Content-Type: application/json' \
  -d "{\"phone\":\"139xxxxxxxx\",\"code\":\"$CODE\"}"

# 余额 / §4.1 失败退款 / §4.3 precheck 不扣 —— 见本会话 E2E 记录

# 管理端
curl -s -X POST localhost/api/admin/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<ADMIN_SEED_PASSWORD>"}'

# 备份 + 恢复验证
bash deploy/backup/pg_backup.sh
bash deploy/backup/pg_restore_verify.sh /backup/sks-<date>.sql.gz
```
