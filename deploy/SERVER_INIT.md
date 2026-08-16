# 阿里云服务器 · 环境初始化（裸机一次性）

> 本文档把一台**全新的阿里云 ECS**（Aliyun Linux 3 / 其他 RHEL 系）从零配成「可部署随口说」的状态。
> 这是**一次性**操作，只在首次拿到服务器时执行。配完后续部署走 [`ALIYUN_DEPLOYMENT.md`](ALIYUN_DEPLOYMENT.md) + [`deploy.sh`](deploy.sh)。
>
> 深度运维项（超时链、备份脚本、余额监控）见 [`OPS.md`](OPS.md)，本文不重复。

## 0. 购机与安全组（控制台，5 分钟）

### 规格

随口说四个容器 + PostgreSQL 16 + pgvector，LLM 走**云端 API**（智谱 GLM，不在服务器本地跑），所以服务器不吃 LLM 显存/算力。真正吃资源的是 sks-ai 的媒体处理（ffmpeg 转码 + node WASM 视频号解码 + Qwen 长转写的临时产物）。

| 项 | 最低 | 建议 | 理由 |
|---|---|---|---|
| CPU | 2 核 | 4 核 | ffmpeg/node 解码并发；4 核留余量 |
| 内存 | 4 GiB | 8 GiB | 5 容器 + node WASM 解码峰值；2C4G 紧但能跑 |
| 系统盘 | 40 GiB | 60 GiB | pg 数据 + 30 天备份 + ASR 临时媒体（长转写产物可达 GB 级） |
| 带宽 | 5 Mbps | 按需 | C 端是文本为主；管理端有视频号媒体拉取 |

> 小内存机（≤4G）建议加 swap，防 OOM Kill（sks-ai 长转写峰值吃内存）：
> ```bash
> sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile
> sudo mkswap /swapfile && sudo swapon /swapfile
> echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
> ```

### 安全组（入方向规则）

| 端口 | 来源 | 用途 |
|---|---|---|
| 22 | 你的 IP | SSH 管理（**勿对 0.0.0.0/0 开 22**，或改用密钥 + fail2ban） |
| 80 | 0.0.0.0/0 | HTTP（certbot 验证 + 80→443 跳转） |
| 443 | 0.0.0.0/0 | HTTPS（唯一对外服务面） |

> 不开 5432（pg 仅容器内网 `sks-net`，不暴露公网）、不开 8080/8000（sks-server/sks-ai 仅内网）。

### 域名 + DNS

- 生产域名：`suikoushuo.com` 与 `www.suikoushuo.com`。
- 两条都指向 ECS 公网 IP：裸域 A 记录；`www` 用 A 或 CNAME 均可。
- 证书由 `deploy/issue-cert.sh` 一次签两个名字；certbot HTTP-01 验证需要 80 端口公网可达（见上安全组）。

---

## 1. 系统准备

```bash
# Aliyun Linux 3 是 RHEL 系，用 dnf
sudo dnf update -y
sudo dnf install -y git curl wget vim tar

# 时区
sudo timedatectl set-timezone Asia/Shanghai
```

### 防火墙（firewalld）

```bash
sudo systemctl enable --now firewalld
sudo firewall-cmd --permanent --add-service=http    # 80
sudo firewall-cmd --permanent --add-service=https   # 443
sudo firewall-cmd --reload
```

> 安全组（云平台层）和 firewalld（系统层）**两层都要放行**，任一不开都会超时。

---

## 2. Docker + Compose（硬依赖 compose v2.22+）

`--ignore-buildable` 拉镜像需要 Compose v2.22+，勿漏 `docker-compose-plugin`。

```bash
sudo dnf install -y dnf-plugins-core
sudo dnf config-manager --add-repo https://mirrors.aliyun.com/docker-ce/linux/centos/docker-ce.repo
# --setopt=install_weak_deps=False 跳过 rootless-extras（镜像源缺包会整个事务失败）
sudo dnf install -y --setopt=install_weak_deps=False \
  docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo systemctl enable --now docker

# 验证（compose 必须 ≥ v2.22）
docker --version
docker compose version
```

把当前用户加入 docker 组（免每次 sudo）：

```bash
sudo usermod -aG docker $USER
newgrp docker   # 或重新登录生效
```

---

## 3. 阿里云镜像加速器（Docker Hub 被墙）

compose up 要拉 Docker Hub 的 `pgvector/pgvector:pg16`、`nginx:alpine`（三服务走 ACR，不吃 Docker Hub mirror）。
国内直连 `registry-1.docker.io` 会卡在 `failed to resolve source metadata`。

```bash
# 先确认是否被墙（reset by peer = 被墙）
curl -sS -o /dev/null -w '%{http_code}\n' --max-time 8 https://registry-1.docker.io/v2/ || echo unreachable
```
去阿里云控制台：**容器镜像服务 → 镜像工具 → 镜像加速器**，复制专属地址`https://b86dcmgv.mirror.aliyuncs.com`。

服务器是**原生 dockerd**（不是 colima / Docker Desktop），配 `/etc/docker/daemon.json`：

```bash
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": ["https://b86dcmgv.mirror.aliyuncs.com"]
}
EOF
sudo systemctl restart docker

# 验证生效
docker info | grep -iA3 'Registry Mirrors'
```

> ⚠️ colima / Docker Desktop 配置位置不同，别照抄——那是本地开发机的事，见 `OPS.md §8`。服务器只认 `/etc/docker/daemon.json`。

---

## 4. 阿里云 ACR（三服务镜像；ECS 不要直拉 GHCR）

三服务发版仍推 `ghcr.io/wangbuer1984/sks-{server,ai,web}`。国内 ECS 拉 GHCR 常只有十几 kB/s，生产改从**同地域个人版 ACR**拉。`pgvector` / `nginx:alpine` 仍走 Docker Hub + §3 加速器。

已开通：命名空间 `suikoushuo`，地域华北2（北京）。`sks-server` / `sks-ai` / `sks-web` 三个仓库须都在这个命名空间（不要再用旧的 `suishuoshuo`）。

| 谁 | Registry | 用途 |
|---|---|---|
| 本机 | `crpi-7eu3mopdi4xg4ext.cn-beijing.personal.cr.aliyuncs.com` | push（公网） |
| ECS | `crpi-7eu3mopdi4xg4ext-vpc.cn-beijing.personal.cr.aliyuncs.com` | pull（VPC，不计公网流量） |

登录用户名：`dingtalk_bakexx`；密码在控制台「访问凭证」，不是阿里云登录密码。

**本机同步（能较快访问 GitHub 的机器，不要在 ECS 上 pull GHCR）：**

```bash
docker login ghcr.io -u WangBuer1984
docker login --username=dingtalk_bakexx \
  crpi-7eu3mopdi4xg4ext.cn-beijing.personal.cr.aliyuncs.com

./deploy/acr-sync.sh v0.1.1
```

**ECS 只登录 ACR VPC（替代原来的 `docker login ghcr.io`）：**

```bash
docker login --username=dingtalk_bakexx \
  crpi-7eu3mopdi4xg4ext-vpc.cn-beijing.personal.cr.aliyuncs.com
```

`.env` 里：

```
SKS_IMAGE_REGISTRY=crpi-7eu3mopdi4xg4ext-vpc.cn-beijing.personal.cr.aliyuncs.com/suikoushuo
```

compose 会拉 `…/suikoushuo/sks-{server,ai,web}:v0.1.1`。

---

## 5. certbot（Let's Encrypt）

我们不用 `certbot --nginx` 自动改配置（网关配置在 `nginx.https.conf`，由容器加载，不在宿主）。装 certbot + 用 standalone 模式签发即可。

```bash
sudo dnf install -y certbot
# python3-certbot-nginx 可选——我们不用它的自动改配置，但装上不影响
```

签发在首次部署时做：`sudo ./deploy/issue-cert.sh`（见 `ALIYUN_DEPLOYMENT.md §2`），签 `suikoushuo.com` + `www.suikoushuo.com`。这里只装好 certbot 本体。

---

## 6. 克隆部署仓

选定一个目录作为 `COMPOSE_DIR`（推荐 `/opt/sks`）：

```bash
sudo mkdir -p /opt/sks && sudo chown $USER:$USER /opt/sks
git clone git@github.com:WangBuer1984/sks-agent.git /opt/sks
cd /opt/sks
```

> 如果用 SSH 克隆需先在服务器配 GitHub deploy key 或 PAT；用 HTTPS 也行（只读部署）。
> 后续部署脚本 [`deploy.sh`](deploy.sh) 会自动定位仓根，可从任意子目录调用。

---

## 7. crontab（备份 + 证书续期）

宿主 crontab 触发 pg 备份（容器内不装 cron）。

```bash
crontab -e
```

```cron
# 每日 03:00 备份（额度账本不可丢）
0 3 * * * /opt/sks/deploy/backup/pg_backup.sh >> /var/log/sks-pg-backup.log 2>&1

# certbot 续期（certbot 安装时一般已自动写入 /etc/cron.d/certbot；此处补 post-hook 让续期后 reload nginx）
# 0 3 * * * /usr/bin/certbot renew --quiet --post-hook "docker compose -f /opt/sks/docker-compose.yml -f /opt/sks/docker-compose.prod.yml restart nginx"
```

`pg_backup.sh` 需要的环境变量（写入 crontab 顶部，或脚本内 export）见 `OPS.md §2`：

```
BACKUP_DIR=/backup
SPRING_DATASOURCE_USERNAME=sks
PG_DB_NAME=sks
COMPOSE_DIR=/opt/sks
RETAIN_DAYS=30
```

---

## 8. 自检清单

```bash
docker --version && docker compose version    # docker 装好，compose ≥ v2.22
docker info | grep -iA3 'Registry Mirrors'    # Docker Hub 加速器生效（pgvector / nginx）
docker login --username=dingtalk_bakexx crpi-7eu3mopdi4xg4ext-vpc.cn-beijing.personal.cr.aliyuncs.com
which certbot                                 # certbot 装好
sudo firewall-cmd --list-services            # http https 都在
ls /opt/sks/docker-compose.yml                # 仓已克隆
```

全部 ✓ → 服务器初始化完成。下一步：[`ALIYUN_DEPLOYMENT.md`](ALIYUN_DEPLOYMENT.md) 做首次完整部署。
