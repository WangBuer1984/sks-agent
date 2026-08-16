# 随口说 · 阿里云完整部署文档（首次部署）

> 本文是**首次完整部署**的端到端 runbook：从配 `.env` → 签发证书 → 起栈 → HTTPS 网关 → 验收 → 监控/备份接线。
> 前提：服务器已按 [`SERVER_INIT.md`](SERVER_INIT.md) 初始化完成（docker、compose v2.22+、Docker Hub 加速器、ACR 登录、certbot、仓库已克隆）。
> 后续每次发版部署走 [`deploy.sh`](deploy.sh)，本文只覆盖**首次**。
> 深度运维项（超时链、备份脚本、余额监控、联调首检）见 [`OPS.md`](OPS.md) / [`GO_LIVE_CHECKLIST.md`](GO_LIVE_CHECKLIST.md)，本文不重复。

## 架构与端口

```
浏览器 ──HTTPS──▶ nginx gateway(本地 build, nginx.https.conf)
                      ├─▶ /api/   → sks-server (Java, ACR 镜像)
                      └─▶ /        → sks-web    (React SPA, ACR 镜像)
        sks-server ──内网 + X-Service-Token──▶ sks-ai (Python, ACR 镜像)
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

> ⚠️ **别往 `.env` 加 `ALIYUN_SMS_SIGN` / `_ENDPOINT` / `_TEMPLATE_*` 三行**——签名/端点/模板号已写死在 sks-server `application.yml` 的 `sks.sms.*`。在 `.env` 留一行空 `XXX=` 会覆盖 yml 的 `${VAR:默认值}` → 验证码只落库不发、也不报错。闸门 = `ALIYUN_ACCESS_KEY_ID/SECRET`：配了真发。

逐项核对见 `GO_LIVE_CHECKLIST.md §1`（状态表 ✅/⚠️/❌）。关键非默认项：
- `SKS_IMAGE_REGISTRY`（已钉 `crpi-7eu3mopdi4xg4ext-vpc.cn-beijing.personal.cr.aliyuncs.com/suikoushuo`；不填则仍拉 GHCR，ECS 上会极慢）
- `ALIYUN_ASR_KEY`（百炼/DashScope，长转写已切 Qwen，prod 必填，否则拆视频/拆账号失败）

---

## 2. 签发 HTTPS 证书（certbot，必须先于 nginx 起来）

nginx.https.conf 的证书路径已钉 `/etc/letsencrypt/live/suikoushuo.com/`（一张证覆盖裸域 + `www.suikoushuo.com`）。证书不存在 → `nginx -t` 失败起不来。

**standalone 模式**（certbot 临时占 80 → nginx 容器必须停）：

```bash
# DNS：suikoushuo.com 与 www.suikoushuo.com 都指向本机公网 IP
# （www 用 A 或 CNAME 均可）；安全组 80 已放行
# 若栈已在跑：先停网关
docker compose -f docker-compose.yml -f docker-compose.prod.yml stop nginx

sudo ./deploy/issue-cert.sh
# 等价于：
# sudo certbot certonly --standalone --expand \
#   -d suikoushuo.com -d www.suikoushuo.com \
#   --non-interactive --agree-tos -m 15169128616@163.com

sudo ls /etc/letsencrypt/live/suikoushuo.com/
# 期望：fullchain.pem  privkey.pem  chain.pem  cert.pem
```

---

## 3. 替换 50x 联系方式

域名已写在 `nginx.https.conf`（`suikoushuo.com` + `www.suikoushuo.com`）。这里只换兜底页微信号：

```bash
export CONTACT_WECHAT=站长真实微信号
envsubst < deploy/nginx/50x.html > /tmp/50x.html && mv /tmp/50x.html deploy/nginx/50x.html
grep -q "$CONTACT_WECHAT" deploy/nginx/50x.html && echo "50x 联系方式已替换"

# nginx.https.conf 语法自检（挂临时目录 + 真 cert 路径；单文件 bind-mount 在部分引擎会报 not a directory）
tmp=$(mktemp -d) && cp deploy/nginx/nginx.https.conf "$tmp/default.conf"
docker run --rm -v "$tmp:/etc/nginx/conf.d:ro" -v /etc/letsencrypt:/etc/letsencrypt:ro nginx:alpine nginx -t
rm -rf "$tmp"
```

> ⚠️ 微信号只改服务器工作副本（`/opt/sks`），**不要 git commit `50x.html`**。若 `git status` 显示该文件被改，部署后 `git checkout -- deploy/nginx/50x.html` 还原占位。

---

## 4. 起栈

先确认本机已 `./deploy/acr-sync.sh` 把当前 tag 推到 ACR，ECS 已 `docker login` ACR，`.env` 里 `SKS_IMAGE_REGISTRY` 已填。

```bash
# 拉三服务镜像（走 ACR；--ignore-buildable 跳过 nginx，它本地 build）
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

# 经网关健康探针（本地 127.0.0.1 + 公网裸域 / www 都该通）
curl -s http://127.0.0.1/api/health                 # {"status":"UP"}
curl -s https://suikoushuo.com/api/health            # {"status":"UP"}
curl -s https://www.suikoushuo.com/api/health        # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}\n' https://suikoushuo.com/50x.html  # 200

# 80 → 443 跳转（裸域与 www）
curl -sI http://suikoushuo.com/ | grep -i location     # location: https://suikoushuo.com/
curl -sI http://www.suikoushuo.com/ | grep -i location # location: https://www.suikoushuo.com/

# 前端首页经网关
curl -s -o /dev/null -w '%{http_code}\n' https://suikoushuo.com/       # 200
curl -s -o /dev/null -w '%{http_code}\n' https://www.suikoushuo.com/   # 200
```

### 业务全链路（配齐 key 后，按 `GO_LIVE_CHECKLIST.md §3-4` 手动过）

- [ ] 注册流：send-code（SMS 真发）→ 登录 → 体验额度 3 → `/api/user/me` balance=3
- [ ] §4.1 失败→退款：generate 5001 → 余额恢复 → credit_ledger refund 行
- [ ] §4.3 precheck 不扣费：`/api/analyze/account` 失败 → 余额不变
- [ ] 管理端：`/api/admin/auth/login` admin/密码 → token；admin token 访 C 端 401（双密钥隔离）
- [ ] 拆视频/拆账号（Qwen 长转写出非空 transcript；需 `ALIYUN_ASR_KEY` + 镜像含 ffmpeg/node）

---

## 6. 监控 / 备份 / 续期接线

### UptimeRobot 拨测（控制台，无代码）

免费版监控 `https://suikoushuo.com/api/health`，期望 `{"status":"UP"}`，5 分钟间隔，宕机 email + 短信。详见 `OPS.md §3`。

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
curl -s https://suikoushuo.com/50x.html | grep "加站长微信"   # 命中 = 联系方式已替换、页面可达
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
| nginx 起不来、`nginx -t` 报 `cannot load certificate` | certbot 没签发 / 证书不在 `/etc/letsencrypt/live/suikoushuo.com/` / `/etc/letsencrypt` 没挂进容器（查 prod override volume） |
| 502 Bad Gateway | 上游容器没起 / 容器名解析问题——查 `docker compose ps` 是否全 healthy；nginx 日志 `docker compose logs nginx`；resolver 127.0.0.11 是否还在 conf（被人改回写死主机名） |
| healthcheck 一直 unhealthy | 探的是 `127.0.0.1/50x.html`——80 块是否保留了 `location = /50x.html`（被改成纯 301 跳转会失效） |
| `/api/health` 通但 AI 调用超时 | 查超时链 240<270<300 是否被改（`OPS.md §6`）；sks-ai 日志；GLM/TikHub key 是否配 |
| 短信不真发 | `.env` 是否误加了 `ALIYUN_SMS_*` 空行覆盖 yml（SMS 陷阱）；`ALIYUN_ACCESS_KEY_ID/SECRET` 是否配 |
| 拉镜像卡住 / 十几 kB/s | 三服务应走 ACR VPC：查 `.env` 的 `SKS_IMAGE_REGISTRY`、ECS 是否 `docker login` 了 `crpi-7eu3mopdi4xg4ext-vpc.cn-beijing.personal.cr.aliyuncs.com`、本机是否已 `acr-sync.sh`。pgvector/nginx 才吃 Docker Hub 加速器（`docker info \| grep Mirrors`，`OPS.md §8`） |
| certbot 验证失败 | 80 公网不通（安全组 + firewalld 两层）；`suikoushuo.com` 或 `www.suikoushuo.com` DNS 没指向本机 |

---

## 9. 后续部署（每次发版）

完整流水（本机 `acr-sync.sh` → bump compose → ECS `deploy.sh`）见 [`OPS.md`](OPS.md)「发版流水」。ECS 上只跑：

```bash
cd /opt/sks
git pull
./deploy/deploy.sh                         # 全量（从 ACR 拉当前 compose tag + 起栈 + 重建 nginx）
./deploy/deploy.sh sks-server              # 或单服务：pull + up --no-deps
./deploy/deploy.sh nginx                   # 改了 nginx.https.conf / 50x.html 后
```

回滚：compose tag 改回上一版（ACR 上须已有该 tag）→ `git pull` → `./deploy/deploy.sh <svc>`。
