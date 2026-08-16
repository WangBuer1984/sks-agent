# 随口说 · 阿里云完整部署文档（首次部署）

> 本文是**首次完整部署**的端到端 runbook：从配 `.env` → 签发证书 → 起栈 → HTTPS 网关 → 验收 → 监控/备份接线。
> 前提：服务器已按 [`SERVER_INIT.md`](SERVER_INIT.md) 初始化完成（docker、compose v2.22+、镜像加速器、GHCR 认证、certbot、仓库已克隆）。
> 后续每次发版部署走 [`deploy.sh`](deploy.sh)，本文只覆盖**首次**。
> 深度运维项（超时链、备份脚本、余额监控、联调首检）见 [`OPS.md`](OPS.md) / [`GO_LIVE_CHECKLIST.md`](GO_LIVE_CHECKLIST.md)，本文不重复。

## 架构与端口

```
浏览器 ──HTTPS──▶ nginx gateway(本地 build, nginx.https.conf)
                      ├─▶ /api/   → sks-server (Java, GHCR 镜像)
                      └─▶ /        → sks-web    (React SPA, GHCR 镜像)
        sks-server ──内网 + X-Service-Token──▶ sks-ai (Python, GHCR 镜像)
        PostgreSQL 16 + pgvector ◀── 单共享库, compose 管容器, sks-server Flyway 建表
```

- 唯一公网入口 = nginx 的 443（80 仅 healthcheck 探针 + 跳 443）。
- sks-server/sks-ai/sks-web 仅 `expose` 内网端口，不映射到宿主。
- nginx 走 `resolver 127.0.0.11 valid=10s` 按请求解析上游容器名 → 单服务重启后 10s 内自愈，无需连带重启网关。

## compose 两层

| 文件 | 作用 |
|---|---|
| `docker-compose.yml` | base：5 服务编排。本地联调也用它（80-only） |
| `docker-compose.prod.yml` | 生产 override：nginx 用 `nginx.https.conf` 构建 + 挂 `/etc/letsencrypt:ro` + 开 443 |

服务器上**恒带** `-f docker-compose.yml -f docker-compose.prod.yml`（`deploy.sh` 已内置）。

---

## 1. 配置 `.env`

```bash
cd /opt/sks
cp deploy/.env.prod.example .env
vim .env        # 填实所有 <...> 占位
chmod 600 .env  # 密钥文件，限权
```

`.env.prod.example` 已按三类标注：① 复制自本地（付费 key）/ ② 生成强值 / ③ 填 prod 专值。**必读其中的 SMS 陷阱注释**：

> ⚠️ **别往 `.env` 加 `ALIYUN_SMS_SIGN` / `_ENDPOINT` / `_TEMPLATE_*` 三行**——签名/端点/模板号已写死在 sks-server `application.yml` 的 `sks.sms.*`。在 `.env` 留一行空 `XXX=` 会覆盖 yml 的 `${VAR:默认值}` → 短信静默退回 stub、不发也不报错。短信「真发 or stub」的唯一闸门 = `ALIYUN_ACCESS_KEY_ID/SECRET` 两项。

逐项核对见 `GO_LIVE_CHECKLIST.md §1`（状态表 ✅/⚠️/❌）。关键非默认项：
- `ALIYUN_ASR_KEY`（百炼/DashScope，长转写已切 Qwen，prod 必填，否则拆视频/拆账号失败）
- `SPRING_MAIL_*` + `SKS_ALERT_ADMIN_EMAIL`（告警邮件，空走 stub 静默不发）

---

## 2. 签发 HTTPS 证书（certbot，必须先于 nginx 起来）

nginx.https.conf 的 `ssl_certificate` 指向 `/etc/letsencrypt/live/<域名>/`，证书不存在 → `nginx -t` 直接失败起不来。所以 certbot 必须先跑。

**standalone 模式**（certbot 自己临时占 80 验证 → 必须此时 nginx 还没起、80 空闲）：

```bash
# DNS A 记录已指向本机公网 IP；安全组 80 已对 0.0.0.0/0 放行
sudo certbot certonly --standalone \
  -d 你的域名 \
  --non-interactive --agree-tos -m 站长邮箱

# 验证证书文件
sudo ls /etc/letsencrypt/live/你的域名/
# 期望：fullchain.pem  privkey.pem  chain.pem  cert.pem
```

> 若 80 被占用（比如 nginx 已起），先停 nginx：`docker compose -f docker-compose.yml -f docker-compose.prod.yml stop nginx`，签发完再起。

---

## 3. 域名替换 + 替换 50x 联系方式

`nginx.https.conf` 与 `50x.html` 各有一个占位要填真实值：

```bash
# 3a. nginx.https.conf 里的「你的域名」（server_name + 证书路径，共 4 处）
sed -i 's/你的域名/你的真实域名/g' deploy/nginx/nginx.https.conf

# 验证替换干净（不应再有占位）
grep -n '你的域名' deploy/nginx/nginx.https.conf || echo "替换干净"

# 3b. 50x.html 的 {{CONTACT_WECHAT}}（PRD §11.6 兜底页文案，勿把真实微信号硬编码进 git）
export CONTACT_WECHAT=站长真实微信号
envsubst < deploy/nginx/50x.html > /tmp/50x.html && mv /tmp/50x.html deploy/nginx/50x.html
grep -q "$CONTACT_WECHAT" deploy/nginx/50x.html && echo "50x 联系方式已替换"

# 3c. nginx.https.conf 语法自检（挂临时目录 + 真 cert 路径，稳妥：单文件 bind-mount 在部分引擎会报 not a directory）
tmp=$(mktemp -d) && cp deploy/nginx/nginx.https.conf "$tmp/default.conf"
docker run --rm -v "$tmp:/etc/nginx/conf.d:ro" -v /etc/letsencrypt:/etc/letsencrypt:ro nginx:alpine nginx -t
rm -rf "$tmp"
```

> ⚠️ `3a/3b` 改的是服务器上的工作副本（`/opt/sks` 克隆），**不要 git commit 这些含真实域名/微信号的改动回仓库**——它们是 per-deploy 的本地配置，gitignored 思路：域名与联系方式不入 git。若 `git status` 显示这两个文件被改，部署后 `git stash` 或 `git checkout -- <file>` 还原占位，保持仓库干净。

---

## 4. 起栈

```bash
# 拉三服务镜像（--ignore-buildable 跳过 nginx，它本地 build）
docker compose -f docker-compose.yml -f docker-compose.prod.yml pull --ignore-buildable

# 重建 nginx 网关（用 nginx.https.conf + 挂 letsencrypt 卷 + 443）
docker compose -f docker-compose.yml -f docker-compose.prod.yml build nginx

# 起全部（按 depends_on：pg → sks-server(Flyway)/sks-web → sks-ai → nginx）
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

或者直接用脚本（等价）：

```bash
./deploy/deploy.sh all
```

> 首次起栈 sks-server 会跑 Flyway 建表（`kb_card` / `analyze_task` / 额度账本等），给 30s start_period。`docker compose ps` 等 5 容器全 healthy。

---

## 5. 验收

```bash
# 容器状态（5 容器全 healthy）
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps

# 经网关健康探针（本地 127.0.0.1 + 公网域名都该通）
curl -s http://127.0.0.1/api/health                 # {"status":"UP"}
curl -s https://你的域名/api/health                  # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}\n' https://你的域名/50x.html  # 200

# 80 → 443 跳转
curl -sI http://你的域名/ | head -1                 # HTTP/1.1 301
curl -sI http://你的域名/ | grep -i location          # location: https://你的域名/

# 前端首页经网关
curl -s -o /dev/null -w '%{http_code}\n' https://你的域名/   # 200
```

### 业务全链路（配齐 key 后，按 `GO_LIVE_CHECKLIST.md §3-4` 手动过）

- [ ] 注册流：send-code（SMS 真发）→ 登录 → 体验额度 3 → `/api/user/me` balance=3
- [ ] §4.1 失败→退款：generate 5001 → 余额恢复 → credit_ledger refund 行
- [ ] §4.3 precheck 不扣费：`/api/analyze/account` 失败 → 余额不变
- [ ] 管理端：`/api/admin/auth/login` admin/密码 → token；admin token 访 C 端 401（双密钥隔离）
- [ ] 拆视频/拆账号（Qwen 长转写出非空 transcript；需 `ALIYUN_ASR_KEY` + 镜像含 ffmpeg/node）
- [ ] 告警邮件：`QuotaWatchJob` 手测（阈值调极高）→ 站长邮箱收信

---

## 6. 监控 / 备份 / 续期接线

### UptimeRobot 拨测（控制台，无代码）

免费版监控 `https://你的域名/api/health`，期望 `{"status":"UP"}`，5 分钟间隔，宕机 email + 短信。详见 `OPS.md §3`。

### 备份 + 恢复验证（**上线前必做**）

```bash
# 跑一次备份
bash deploy/backup/pg_backup.sh

# 恢复验证（导入临时库 sks_verify，不碰生产 sks；全 7 表 count 通过）
bash deploy/backup/pg_restore_verify.sh /backup/sks-$(date +%F).sql.gz
```

> **额度账本不可丢**——`pg_restore_verify.sh` 任一表缺失 → 退出 1。上线前必须跑通一次。详见 `OPS.md §2`。

crontab 已在 `SERVER_INIT.md §7` 配好（每日 03:00 备份 + certbot 续期 post-hook）。

### 证书续期

```bash
sudo certbot renew --dry-run    # 干跑验证续期流程
```

certbot 续期后 nginx 不会自动 reload——`SERVER_INIT.md §7` crontab 的 post-hook 已加 `... restart nginx`。续期改的是宿主 `/etc/letsencrypt` 证书文件（只读挂载进容器），`docker compose restart nginx` 让容器重读。

### 50x 兜底页可见性

```bash
curl -s https://你的域名/50x.html | grep "加站长微信"   # 命中 = 联系方式已替换、页面可达
```

---

## 7. 常用命令速查

```bash
# 日志
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs -f sks-server
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs -f --tail=200 nginx

# 进容器
docker compose -f docker-compose.yml -f docker-compose.prod.yml exec sks-server bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml exec postgres psql -U sks sks

# 重启单服务（不连带依赖）
docker compose -f docker-compose.yml -f docker-compose.prod.yml restart sks-ai

# 全栈停 / 起
docker compose -f docker-compose.yml -f docker-compose.prod.yml down
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

---

## 8. 故障排查

| 现象 | 排查 |
|---|---|
| nginx 起不来、`nginx -t` 报 `cannot load certificate` | certbot 没签发 / 域名替换没做 / `/etc/letsencrypt` 没挂进容器（查 prod override volume） |
| 502 Bad Gateway | 上游容器没起 / 容器名解析问题——查 `docker compose ps` 是否全 healthy；nginx 日志 `docker compose logs nginx`；resolver 127.0.0.11 是否还在 conf（被人改回写死主机名） |
| healthcheck 一直 unhealthy | 探的是 `127.0.0.1/50x.html`——80 块是否保留了 `location = /50x.html`（被改成纯 301 跳转会失效） |
| `/api/health` 通但 AI 调用超时 | 查超时链 240<270<300 是否被改（`OPS.md §6`）；sks-ai 日志；GLM/TikHub key 是否配 |
| 短信不真发 | `.env` 是否误加了 `ALIYUN_SMS_*` 空行覆盖 yml（SMS 陷阱）；`ALIYUN_ACCESS_KEY_ID/SECRET` 是否配 |
| 拉镜像卡住 | 镜像加速器没生效（`docker info \| grep Mirrors`）；GHCR 没认证；`OPS.md §8` |
| certbot 验证失败 | 80 公网不通（安全组 + firewalld 两层）；DNS 没指向本机 |

---

## 9. 后续部署（每次发版）

首次部署完成后，后续更新镜像走 [`deploy.sh`](deploy.sh)：

```bash
cd /opt/sks
git pull                                   # 拿到 deploy 仓里 bump 过的新 image tag
./deploy/deploy.sh                         # 全量更新（拉镜像 + 起栈 + 重建 nginx）
# 或单服务：
./deploy/deploy.sh sks-server              # pull + up --no-deps，不连带重启依赖
./deploy/deploy.sh nginx                   # 改了 nginx.https.conf / 50x.html 后重建网关
```

回滚：deploy 仓把 `<svc>.image` tag 改回上一版 → `git pull` → `./deploy/deploy.sh <svc>`。
