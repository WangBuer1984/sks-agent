# OPS — 部署与运维收尾（P5）

> 对应设计文档 §5.3。本文记录<b>外部/控制台/宿主</b>侧的运维项——它们不是代码，
> 无法单元测试，上线前由人按清单执行。代码侧产物见同目录 `nginx/`、`backup/` 与
> sks-server 仓 `src/main/java/com/sks/common/QuotaWatchJob.java`。
>
> <b>首次起栈前置</b>：`docker compose pull --ignore-buildable && docker compose up -d`（镜像化后不再 `--build` 三服务）。若卡在拉镜像——ghcr.io 三服务镜像见下「部署机初始化」GHCR 预验；Docker Hub 的 pgvector/nginx:alpine 见 §8 加速器（compose up 仍要从 Docker Hub 拉这两个）。

## 镜像化部署模型（--build 心智模型 + 部署机初始化）

镜像化后，旧的「`--build` 全量构建」语义变了：三服务仓不 build 了，按 GHCR 镜像引用；gateway 仍本地 build。

| 场景 | 新（镜像化） |
|---|---|
| 新增 Flyway 迁移生效 | sks-server 仓发新 tag → CI 出镜像 → deploy 仓 bump `sks-server.image` tag → `compose pull sks-server && compose up -d sks-server` |
| 前端发版 | sks-web 仓发新 tag → CI 出镜像 → deploy 仓 bump `sks-web.image` tag → `pull sks-web && up -d sks-web` |
| 重建/首次起栈 | `compose pull --ignore-buildable`（需 Compose v2.22+，老版本 fallback `compose pull sks-server sks-ai sks-web`）→ `compose up -d` |
| 回滚部署 | deploy 仓把 `<svc>.image` tag 改回上一版 → `pull && up -d` |

> **单服务重发不用连带重启 nginx。** 上面几行 `up -d <svc>` 会让该服务换个容器 IP。nginx 对
> `proxy_pass` 里写死的主机名只在启动时解析一次并永久缓存，所以这原本会让网关一直 502、非重启
> 网关不可恢复。`deploy/nginx/nginx.conf` 已改为 `resolver 127.0.0.11 valid=10s` + 变量式
> `proxy_pass`，换 IP 后 10s 内自愈。已实测：上游 IP 从 `.2` 换到 `.6`，新配置 200、旧配置 502
> 且错误日志仍在连旧 IP。
>
> 若哪天发现单服务重发后网关 502 不恢复，先查这两样是不是被改回去了。



### 部署机初始化（Aliyun Linux，裸机一次性）

```bash
# docker-ce 安装（dnf；以下序列实际走通，缺一步都装不上）
sudo dnf install -y dnf-plugins-core
sudo dnf config-manager --add-repo https://mirrors.aliyun.com/docker-ce/linux/centos/docker-ce.repo
# 引擎 + compose 插件（compose v2.22+ 是部署硬依赖，勿漏 docker-compose-plugin）
# --setopt=install_weak_deps=False 跳过 rootless-extras（弱依赖，镜像源缺包会整个事务失败）
sudo dnf install -y --setopt=install_weak_deps=False docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo systemctl enable --now docker
docker --version && docker compose version   # compose ≥ v2.22（--ignore-buildable 需要）

# GHCR 国内可达性预验（拆分动手前即可验，不必等镜像出）
curl -sS -o /dev/null -w '%{http_code}\n' --max-time 8 https://ghcr.io/v2/   # 401 = 通
curl -sS -o /dev/null -w '%{http_code}\n' --max-time 8 https://pkg-containers.githubusercontent.com/   # blob 后端可达
docker pull ghcr.io/astral-sh/uv:latest   # 实拉小型公共 GHCR 镜像 = 整条链通

# 三服务镜像 private，拉取前认证（交互输入 PAT，需 read:packages；或把三 package 设 public 免 login）
docker login ghcr.io -u <github-user>
```

## 0. 联调-gated 项总览（代码留桩，上线/联调时接线）

| 项 | 留桩位置 | 联调动作 |
|----|----------|----------|
| 阿里云 SMS 发送（验证码） | `AliyunSmsAuthClient`（DYPNS `SendSmsVerifyCode` 字面码，3 scene：LOGIN_REGISTER/VERIFY_OLD_PHONE/BIND_NEW_PHONE） | 填 `ALIYUN_SMS_SIGN` + `ALIYUN_SMS_TEMPLATE_LOGIN/VERIFY_OLD/BIND_NEW`（赠送签名/模板，空则走 stub 不真发） |
| 告警邮件 | `QuotaWatchJob.sendAlert` → `AlertNotifier.notify`（`RechargeOrderService` 两处 `[SMS-STUB]` 留 TODO，钱路径，下个 plan） | 填 `SPRING_MAIL_*` + `SKS_ALERT_ADMIN_EMAIL`（465 + SSL，`MailAlertNotifier`） |
| 阿里云 SMS 余额查询 | `QuotaWatchJob.querySmsBalance` → `Optional.empty()` | 接 BSS OpenAPI（QueryAccountBalance，需 AK 开 BSS 读权限；返回账户余额元，非短信条数）— dysmsapi 无余额查询 API |
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
sudo dnf install -y certbot python3-certbot-nginx

# 3) 签发（自动改 nginx 配置 + 写 /etc/letsencrypt/live/域名/）
sudo certbot --nginx -d 你的域名 --non-interactive --agree-tos -m 站长邮箱

# 4) 取消 nginx.conf 中 443 块 + 80→443 跳转块的注释（域名替换为真实域名）；
#    docker compose build nginx && docker compose up -d nginx

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
- 告警走 `sendAlert` → `AlertNotifier`（**已接线**，邮件通道）：`MailAlertNotifier` 读
  `sks.alert.admin-email` + `spring.mail.host`，host 空→stub 不发；configured→真发邮件，失败 `log.warn`
  吞掉不抛，被 `sweep` try/catch 兜底不中断 Job。

阈值 via `application.yml`；告警收件人走 `sks.alert.admin-email`（见 `application.yml` `sks.alert.admin-email` / `MailAlertNotifier`）：

```
sks.quota.sms-threshold   (默认 100)
sks.quota.glm-threshold   (默认 20)
```

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

- [ ] `curl -s https://域名/50x.html` 可见兜底页（503/502 触发 `error_page` 重定向）
- [ ] `bash deploy/backup/pg_backup.sh` 产出 `.sql.gz` 且 `pg_restore_verify.sh` 跑通
- [ ] 停掉 java 容器后 UptimeRobot 在 5 min 内告警
- [ ] QuotaWatchJob 手测：把阈值调到极高触发告警邮件到站长邮箱（`SKS_ALERT_ADMIN_EMAIL`）

## 8. 镜像加速器（首次起栈网络前置 — Docker Hub 被墙）

镜像化后 compose up 拉的是 **ghcr.io 三服务镜像**（不吃 Docker Hub mirror）+ Docker Hub 的 `pgvector/pgvector:pg16`、`nginx:alpine`（gateway 本地 build，吃 mirror）。原"拉三个 Docker Hub 基础镜像 node/python/temurin"已作废——node/python/temurin 已 bake 进 GHCR 服务镜像。
在无法直连 `registry-1.docker.io` 的网络（典型：中国大陆）下，Docker Hub 的 pgvector/nginx:alpine 会卡在 `failed to resolve source metadata`；ghcr.io 镜像见上「部署机初始化」预验。

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

