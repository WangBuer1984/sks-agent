# 阿里云服务器 · 环境初始化（裸机一次性）

> 把一台**全新的阿里云 ECS**（Aliyun Linux 3 / 其他 RHEL 系）配成「可部署随口说」的状态。
> **只在这台服务器上做**；本机把 GHCR 镜像同步到 ACR、换 tag 发版，见 [`OPS.md`](OPS.md)「发版流水」。
> 配完之后首次起栈走 [`ALIYUN_DEPLOYMENT.md`](ALIYUN_DEPLOYMENT.md)，以后发版走 [`deploy.sh`](deploy.sh)。

当前钉死的生产事实：

| 项 | 值 |
|---|---|
| ECS 地域 | 华北2（北京），hostname `iZ2ze*` |
| 域名 | `suikoushuo.com` + `www.suikoushuo.com` |
| 证书脚本 | `sudo ./deploy/issue-cert.sh`（Let's Encrypt，邮箱 `15169128616@163.com`） |
| 三服务镜像 | 个人版 ACR 命名空间 `suikoushuo`，compose 当前 tag **`v0.1.1`** |
| ECS 拉镜像 | VPC：`crpi-7eu3mopdi4xg4ext-vpc.cn-beijing.personal.cr.aliyuncs.com` |
| 基础镜像 | DaoCloud：`pgvector/pgvector:pg16`、`nginx:alpine`（compose / Dockerfile 已写死，不走 Docker Hub） |

---

## 0. 购机与安全组（控制台）

### 规格

LLM 走云端 API（智谱 GLM），服务器不吃显存。吃资源的是 sks-ai 媒体处理（ffmpeg + node WASM + 长转写临时文件）。

| 项 | 最低 | 建议 | 理由 |
|---|---|---|---|
| CPU | 2 核 | 4 核 | ffmpeg/node 解码并发 |
| 内存 | 4 GiB | 8 GiB | 5 容器 + 解码峰值；2C4G 紧但能跑 |
| 系统盘 | 40 GiB | 60 GiB | pg + 30 天备份 + ASR 临时媒体 |
| 带宽 | 5 Mbps | 按需 | C 端文本为主 |

> 小内存机（≤4G）建议加 swap：
> ```bash
> sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile
> sudo mkswap /swapfile && sudo swapon /swapfile
> echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
> ```

### 安全组（入方向）

| 端口 | 来源 | 用途 |
|---|---|---|
| 22 | 你的 IP | SSH（**勿对 0.0.0.0/0 开 22**） |
| 80 | 0.0.0.0/0 | certbot 验证 + 80→443 |
| 443 | 0.0.0.0/0 | HTTPS（唯一对外服务面） |

不开 5432 / 8080 / 8000（只在容器网 `sks-net`）。**不要**开 3389（Linux 无 RDP）。22 不要对 `0.0.0.0/0`。

### 域名 + DNS

- `suikoushuo.com` A 记录 → 本机公网 IP。
- `www.suikoushuo.com` A 或 CNAME → 同一台机器。
- certbot HTTP-01 要 **安全组 80 = 0.0.0.0/0**（Let's Encrypt 校验机不在你的 IP 白名单里）。本机 firewalld 未运行也可以，公网入站以安全组为准。

---

## 1. 系统准备

```bash
sudo dnf update -y
# Aliyun Linux 默认没有 git / dig
sudo dnf install -y git curl wget vim tar
sudo timedatectl set-timezone Asia/Shanghai
```

查 DNS 用 `getent hosts suikoushuo.com`（不必装 `dig`）。

### 宿主机 nginx（必停）

Aliyun 镜像常预装系统 nginx，会占 80。不关掉则 `issue-cert.sh` 和 compose 网关都会失败：

```bash
sudo systemctl stop nginx 2>/dev/null || true
sudo systemctl disable nginx 2>/dev/null || true
ss -lnt | grep ':80 ' || echo '80 空闲'
```

公网 80/443 只交给 compose 里的 `sks-nginx`。

### 防火墙（firewalld，可选）

本次生产机 **firewalld 未运行**，证书和 HTTPS 仍通——入站只靠安全组。

若要启用，必须**先放行 http/https 再 start**，否则会把 80 封死、Let's Encrypt 超时：

```bash
sudo firewall-cmd --permanent --add-service=http
sudo firewall-cmd --permanent --add-service=https
sudo systemctl enable --now firewalld
sudo firewall-cmd --reload
```

---

## 2. Docker + Compose（硬依赖 v2.22+）

`compose pull --ignore-buildable` 需要 Compose v2.22+，勿漏 `docker-compose-plugin`。

```bash
sudo dnf install -y dnf-plugins-core
sudo dnf config-manager --add-repo https://mirrors.aliyun.com/docker-ce/linux/centos/docker-ce.repo
# --setopt=install_weak_deps=False 跳过 rootless-extras（镜像源缺包会整个事务失败）
sudo dnf install -y --setopt=install_weak_deps=False \
  docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo systemctl enable --now docker

docker --version
docker compose version    # 必须 ≥ v2.22
```

非 root 用户才需要加组（当前若已是 root 可跳过）：

```bash
sudo usermod -aG docker $USER
newgrp docker
```

---

## 3. 基础镜像（pg / 网关）走 DaoCloud，不要直拉 Docker Hub

`docker-compose.yml` 的 postgres 和 `deploy/nginx/Dockerfile` 的 `FROM` 已钉：

- `docker.m.daocloud.io/pgvector/pgvector:pg16`
- `docker.m.daocloud.io/library/nginx:alpine`

三服务仍走 ACR。ECS **不必**再配 Docker Hub `registry-mirrors`（阿里云加速器经常仍要访问 `registry-1.docker.io` 拿 token，一样超时）。

> **不要**在 ECS 上 `docker pull ghcr.io/...` 做预验——GHCR 从这台机常只有十几 kB/s。三服务通不通用下面 ACR 登录验证。

---

## 4. 登录 ACR（ECS 只拉、不推）

仓库已建在个人版 ACR、命名空间 `suikoushuo`（北京）：

- `…/suikoushuo/sks-server`
- `…/suikoushuo/sks-ai`
- `…/suikoushuo/sks-web`

本机用**公网**地址 push（`acr-sync.sh`，见 OPS）；ECS 用 **VPC** 地址 pull（同地域、不计公网流量）。

```bash
docker login --username=dingtalk_bakexx \
  crpi-7eu3mopdi4xg4ext-vpc.cn-beijing.personal.cr.aliyuncs.com
```

用户名是 `dingtalk_bakexx`；密码是控制台「容器镜像服务 → 访问凭证」，不是阿里云登录密码。

首次部署写 `.env` 时必须有（compose 插值，不填会回落到 GHCR）：

```
SKS_IMAGE_REGISTRY=crpi-7eu3mopdi4xg4ext-vpc.cn-beijing.personal.cr.aliyuncs.com/suikoushuo
```

当前会拉 `…/suikoushuo/sks-{server,ai,web}:v0.1.1`。换 tag 不要改这份文档，走 [`OPS.md`](OPS.md)「发版流水」。

---

## 5. 安装 certbot（先装、这里不签发）

网关在容器里，**不要** `certbot --nginx`。

```bash
sudo dnf install -y certbot
```

签发在仓克隆之后、**80 空闲**（系统 nginx 已 disable、compose 网关未起或已 stop）时：`sudo ./deploy/issue-cert.sh`。
证书目录 `/etc/letsencrypt/live/suikoushuo.com/`。Let's Encrypt 超时先查安全组 80 是否 `0.0.0.0/0`，不是本机 firewalld。步骤见 [`ALIYUN_DEPLOYMENT.md`](ALIYUN_DEPLOYMENT.md) §2。

---

## 6. 克隆部署仓

```bash
sudo mkdir -p /opt/sks /backup
sudo chown "$USER:$USER" /opt/sks /backup
git clone https://github.com/WangBuer1984/sks-agent.git /opt/sks
cd /opt/sks
```

SSH 克隆也可以（`git@github.com:WangBuer1984/sks-agent.git`），需先配 deploy key 或 PAT。

[`deploy.sh`](deploy.sh) 会自己找仓根。`git clone` 前必须已 `dnf install git`。

---

## 7. crontab（备份 + 证书续期）

容器内不装 cron。备份脚本要的环境变量写在 crontab 顶部：

```bash
sudo mkdir -p /backup /var/log
crontab -e
```

```cron
BACKUP_DIR=/backup
SPRING_DATASOURCE_USERNAME=sks
PG_DB_NAME=sks
COMPOSE_DIR=/opt/sks
RETAIN_DAYS=30

# 每日 03:00 备份（额度账本不可丢）
0 3 * * * /opt/sks/deploy/backup/pg_backup.sh >> /var/log/sks-pg-backup.log 2>&1

# certbot standalone 续期必须先停网关（不要用下面注释掉的旧 post-hook 写法）
3 3 * * * /opt/sks/deploy/renew-cert.sh --quiet >> /var/log/sks-certbot-renew.log 2>&1
```

---

## 8. 自检

```bash
docker --version && docker compose version
docker login --username=dingtalk_bakexx \
  crpi-7eu3mopdi4xg4ext-vpc.cn-beijing.personal.cr.aliyuncs.com
which certbot
ls /opt/sks/docker-compose.yml /opt/sks/deploy/issue-cert.sh
```

全部 ✓ → 初始化完成。下一步 [`ALIYUN_DEPLOYMENT.md`](ALIYUN_DEPLOYMENT.md)：配 `.env`（含 `SKS_IMAGE_REGISTRY`）→ `issue-cert.sh` → `./deploy/deploy.sh all`。
