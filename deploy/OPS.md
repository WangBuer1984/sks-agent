# OPS — 部署与运维

> 本文记录<b>外部/控制台/宿主</b>侧的运维项——它们不是代码，无法单元测试，上线前由人按清单执行。
> 代码侧产物见同目录 `nginx/`、`backup/` 与 sks-server 仓 `src/main/java/com/sks/common/QuotaWatchJob.java`。
>
> 裸机初始化（装 Docker / ACR 登录）见 [`SERVER_INIT.md`](SERVER_INIT.md)，本文不重复。
> 首次上云见 [`ALIYUN_DEPLOYMENT.md`](ALIYUN_DEPLOYMENT.md)；上线清单见 [`GO_LIVE_CHECKLIST.md`](GO_LIVE_CHECKLIST.md)。
>
> <b>首次起栈前置</b>：本机 `./deploy/acr-sync.sh v0.1.1` 后，ECS `.env` 填 `SKS_IMAGE_REGISTRY`（VPC），再 `./deploy/deploy.sh all`。三服务走 ACR；postgres / 网关基础镜像走 DaoCloud。卡在拉镜像：三服务见 `SERVER_INIT.md` §4。

## 镜像化部署模型

镜像化后，旧的「`--build` 全量构建」语义变了：三服务仓不 build 了，按 ACR 镜像引用（由 GHCR 同步）；gateway 仍本地 build。

| 场景 | 新（镜像化） |
|---|---|
| 新增 Flyway 迁移生效 | sks-server 仓发新 tag → CI 出 GHCR 镜像 → 本机 `./deploy/acr-sync.sh <tag>` → deploy 仓 bump `docker-compose.yml` 三处 tag → ECS `git pull && ./deploy/deploy.sh sks-server` |
| 前端发版 | 同上，目标换成 `sks-web` |
| 重建/首次起栈 | 本机已 sync 当前 tag → ECS `./deploy/deploy.sh all` |
| 回滚部署 | deploy 仓把 compose tag 改回上一版（ACR 上须已有该 tag）→ ECS `git pull && ./deploy/deploy.sh <svc>` |

> **单服务重发不用连带重启 nginx。** 上面几行 `up -d <svc>` 会让该服务换个容器 IP。nginx 对
> `proxy_pass` 里写死的主机名只在启动时解析一次并永久缓存，所以这原本会让网关一直 502、非重启
> 网关不可恢复。`deploy/nginx/nginx.conf` 已改为 `resolver 127.0.0.11 valid=10s` + 变量式
> `proxy_pass`，换 IP 后 10s 内自愈。已实测：上游 IP 从 `.2` 换到 `.6`，新配置 200、旧配置 502
> 且错误日志仍在连旧 IP。
>
> 若哪天发现单服务重发后网关 502 不恢复，先查这两样是不是被改回去了。

## 发版流水 — GHCR → ACR → ECS

当前 compose 钉 **`v0.1.1`**。换版本（例如 `v0.1.2`）时：

1. **确认 GHCR 三个仓都有该 tag**（`ghcr.io/wangbuer1984/sks-{server,ai,web}:<tag>`）。
2. **本机同步到 ACR**（不要在 ECS 上拉 GHCR；Apple Silicon 脚本已强制 `linux/amd64`）：

```bash
docker login ghcr.io -u WangBuer1984
docker login --username=dingtalk_bakexx \
  crpi-7eu3mopdi4xg4ext.cn-beijing.personal.cr.aliyuncs.com
./deploy/acr-sync.sh v0.1.2
```

3. **deploy 仓改 `docker-compose.yml` 三处** `:v0.1.1` → 新 tag（`acr-sync.sh` 的默认参数可一起改，传参即可不必改）。
4. **ECS**（VPC 拉镜像，不计公网流量）：

```bash
# 首次或凭证过期
docker login --username=dingtalk_bakexx \
  crpi-7eu3mopdi4xg4ext-vpc.cn-beijing.personal.cr.aliyuncs.com

# .env 必须有（compose 插值）：
# SKS_IMAGE_REGISTRY=crpi-7eu3mopdi4xg4ext-vpc.cn-beijing.personal.cr.aliyuncs.com/suikoushuo

git pull
./deploy/deploy.sh all          # 或 ./deploy/deploy.sh sks-server
```

ACR 命名空间 `suikoushuo`（个人版北京）。公网 push / VPC pull 地址见 [`SERVER_INIT.md`](SERVER_INIT.md) §4。

---

| 项 | 留桩位置 | 联调动作 |
|----|----------|----------|
| 阿里云验证码短信 | `AliyunSmsAuthClient`（DYPNS `SendSmsVerifyCode`） | **已实现真发**。闸门是 `ALIYUN_ACCESS_KEY_ID/SECRET`：配了就真发，未配则只落库（本地/CI）。签名/模板写死在 `application.yml`，别往 `.env` 加空的 `ALIYUN_SMS_*` |
| 阿里云 SMS 余额查询 | `QuotaWatchJob.querySmsBalance` → `Optional.empty()` | 未接 BSS OpenAPI。告警邮件先不做，此项暂无出口 |
| 智谱账户余额查询 | `QuotaWatchJob.queryGlmBalance` → `Optional.empty()` | 未接智谱余额 API。同上 |
| certbot HTTPS | `nginx.https.conf` + `docker-compose.prod.yml` | 真实域名 + standalone 签发（见 [`ALIYUN_DEPLOYMENT.md`](ALIYUN_DEPLOYMENT.md) §2） |
| UptimeRobot 拨测 | 无代码 | 控制台配 `https://suikoushuo.com/api/health`（见 §3） |

---

## 1. HTTPS — 本地 80-only，生产走 `nginx.https.conf`

本地/联调保持 80-only（`nginx.conf`）。**不要**在 `nginx.conf` 里取消 443 注释块来上线——那块把 80 整段换成纯 301，会让 gateway healthcheck 探 `/50x.html` 失败。

生产路径（逐步命令见 [`ALIYUN_DEPLOYMENT.md`](ALIYUN_DEPLOYMENT.md) §2–3）：

1. 宿主 `sudo ./deploy/issue-cert.sh` 签发（nginx 容器必须停，80 空闲；`suikoushuo.com` + `www.suikoushuo.com`）
2. `docker compose -f docker-compose.yml -f docker-compose.prod.yml` 构建网关（build arg `NGINX_CONF=nginx.https.conf`，挂 `/etc/letsencrypt:ro`，开 443）

续期：`sudo certbot renew --dry-run`；续期后要 reload 容器，post-hook 见 [`SERVER_INIT.md`](SERVER_INIT.md) §7。

## 2. 每日备份 — 宿主 crontab

`backup/pg_backup.sh` 由宿主 crontab 触发（容器内不便装 cron）：

```bash
# 编辑宿主 crontab
crontab -e

# 每日 03:00 备份（额度账本不可丢）
0 3 * * * /opt/sks/deploy/backup/pg_backup.sh >> /var/log/sks-pg-backup.log 2>&1

# certbot 续期后 reload 容器内 nginx
# 0 3 * * * /usr/bin/certbot renew --quiet --post-hook "docker compose -f /opt/sks/docker-compose.yml -f /opt/sks/docker-compose.prod.yml restart nginx"
```

环境变量（写入 crontab 顶部或脚本调用方）：

```
BACKUP_DIR=/backup
SPRING_DATASOURCE_USERNAME=sks
PG_DB_NAME=sks
COMPOSE_DIR=/opt/sks
RETAIN_DAYS=30
```

### 上线前必做 — 恢复验证

```bash
bash deploy/backup/pg_restore_verify.sh /backup/sks-YYYY-MM-DD.sql.gz
```

脚本把备份导入临时库 `sks_verify`（不碰生产 `sks`），对关键表（`app_user` / `credit_account` /
`credit_ledger` / `recharge_order` / `script` / `kb_card` / `topic` / `analyze_task`）计数 + 删临时库。任一表缺失
→ 退出 1。**额度账本不可丢，此项上线前必须跑通一次。**

## 3. 外部拨测 — UptimeRobot（控制台配置，无代码）

免费版监控 `https://suikoushuo.com/api/health`：

- 类型：HTTP(s)
- URL：`https://suikoushuo.com/api/health`
- 期望响应：`{"status":"UP"}`（`HealthController` 返回）
- 间隔：5 min
- 告警：邮件 +（可选）短信提醒——UptimeRobot 控制台配置，不在代码里

容器/进程宕机 → UptimeRobot 邮件告警 + nginx 502 兜底页（`50x.html`）。

## 4. 50x 兜底页 — 上线前替换占位

`nginx/50x.html` 文案 verbatim PRD §11.6：
> 服务暂时不可用，已记录。加站长微信 XXX 反馈，确认属实补偿额度。

`XXX` 为占位 `{{CONTACT_WECHAT}}`，上线前用真实站长微信号替换（**不要把真实微信号硬编码进 git**）。生产在服务器工作副本上 envsubst，步骤见 [`ALIYUN_DEPLOYMENT.md`](ALIYUN_DEPLOYMENT.md) §3。

## 5. 余额监控 — QuotaWatchJob（代码侧）

`QuotaWatchJob` 每日 09:00 跑（`@Scheduled(cron = "0 0 9 * * *")`）：

- 查阿里云 SMS 余额 + 智谱账户余额——**仍为空实现**（`querySmsBalance` / `queryGlmBalance` 返回 `Optional.empty()`）
- 告警邮件**先不做**（`MailAlertNotifier` 在 host 空时不发）
- 阈值判定（纯函数 `checkAndAlert`，可单测）仍在：SMS <100 条 / GLM <¥20，严格 `<`

## 6. 全链路超时对齐（§5.3 — load-bearing）

**内层短于外层，外层不可先掐断仍在跑的调用**：

| 层 | 超时 | 配置位置 |
|----|------|----------|
| Python 内 LLM 单次 | 120s × 最多 2（原始+1 重试）= 240s | sks-ai 仓 `app/llm/` |
| Java AiClient read | 270s | `sks.ai.read-timeout-seconds`（`application.yml`） |
| Java AiClient connect | 10s | `sks.ai.connect-timeout-seconds` |
| nginx `/api/` | 300s | `nginx.conf` `proxy_read_timeout` / `proxy_send_timeout` |

链路：**240s < 270s < 300s**。误对齐的后果：nginx/Java 先掐断 Python 仍在跑的 LLM → 假 `AI_FAILED` →
误退款（钱不丢但工作白费、用户重试）。

## 7. 验收清单（§5.2 全链路手动过一遍）

- [ ] `curl -s https://suikoushuo.com/50x.html`（以及 `https://www.suikoushuo.com/50x.html`）可见兜底页（503/502 触发 `error_page` 重定向）
- [ ] `bash deploy/backup/pg_backup.sh` 产出 `.sql.gz` 且 `pg_restore_verify.sh` 跑通
- [ ] 停掉 java 容器后 UptimeRobot 在 5 min 内告警

## 8. Docker Hub（本地开发机；生产已不依赖）

生产 compose 已钉 DaoCloud：`docker.m.daocloud.io/pgvector/pgvector:pg16` 与 `docker.m.daocloud.io/library/nginx:alpine`。三服务走 ACR。ECS 起栈**不必**配 `registry-mirrors`。

本节只给**本机**（colima / Docker Desktop）若仍要直拉 Docker Hub 时用。阿里云加速器经常仍访问 `registry-1.docker.io` 拿 token，国内会超时。

诊断 + 配阿里云加速器（每账号唯一地址，<b>本文档只记 key 名/步骤，不含地址值</b>）：

```bash
# 1) 确认是否被墙（reset by peer = 被墙）
curl -sS -o /dev/null -w '%{http_code}' --max-time 8 https://registry-1.docker.io/v2/ || echo "unreachable"

# 2) 去阿里云控制台开「容器镜像服务 → 镜像工具 → 镜像加速器」，复制专属地址
#    形如 https://<your-id>.mirror.aliyuncs.com
```

<b>关键：先搞清本机 Docker 引擎是谁</b>（`docker context ls`）—— 不同引擎配
`registry-mirrors` 的位置不同，配错位置不报错但不生效：

| 引擎 | 配置位置 | 生效方式 |
|------|----------|----------|
| colima（macOS 常见，context 显示 `colima *`） | `~/.colima/default/colima.yaml` 的 `docker:` 段加 `registry-mirrors:` | `colima restart`（colima 自动合并 exec-opts/features + mirror 进 VM 内 `/etc/docker/daemon.json`） |
| Docker Desktop（GUI） | Docker Desktop → Settings → Docker Engine 加 `registry-mirrors` | Apply & Restart |
| 原生 dockerd（Linux） | `/etc/docker/daemon.json` 加 `registry-mirrors` | `sudo systemctl restart docker` |

colima 配置示例（`~/.colima/default/colima.yaml`，`docker:` 段「maps directly to daemon.json」）：

```yaml
docker:
  registry-mirrors:
    - https://<your-id>.mirror.aliyuncs.com
```

验证生效（`docker info` 应列出 Registry Mirrors）：

```bash
colima restart
docker info | grep -iA3 'registry mirrors'
# 期望：
#  Registry Mirrors:
#   https://<your-id>.mirror.aliyuncs.com/
```

生效后重新构建：`docker compose pull --ignore-buildable && docker compose up -d`。

> <b>踩坑</b>：colima 的 dockerd 跑在 Linux VM 里，读 VM 内 `/etc/docker/daemon.json`，
> <b>不读</b>宿主 `~/.docker/daemon.json`。把 mirror 写进 `~/.docker/daemon.json` 不会报错，
> 但 `docker info` 仍无 mirror、构建仍失败。colima 场景必须走 `colima.yaml` 或 `colima ssh` 进 VM 改。

