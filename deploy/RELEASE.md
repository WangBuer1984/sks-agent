# 日常发版（初始化完成之后）

> 前提：ECS 已按 [`SERVER_INIT.md`](SERVER_INIT.md) 初始化，[`ALIYUN_DEPLOYMENT.md`](ALIYUN_DEPLOYMENT.md) 的首次上云已跑通。线上钉哪个版本，**只看** `docker-compose.yml` 三处 `image:`（可以 web 一个 tag、server/ai 另一个）。下文 `v0.1.2` 只是举例。
>
> 本文只讲**开发完怎么上线**。不要从这份文档去装 Docker、签证书、配 `.env`。

## 以后每次都这样

发 **sks-web / sks-server / sks-ai** 的代码，**每一次**都走这五步，没有「这次可以省略 bump」的例外：

1. 那个仓 merge 到 `main` 后打新 `v*` tag 并 push（`git push origin main` **不会**出镜像）
2. 等 GitHub Actions 的 `build-push` 变绿（GHCR `linux/amd64`）
3. 本机 `./deploy/acr-sync.sh <tag> <svc>`（不要在 ECS 拉 GHCR；GHCR `docker login` 仅匿名 pull 401 时才需要）
4. **bump**：`sks-agent` 的 `docker-compose.yml` **只改被发的那一行** tag，commit 并 push
5. ECS：`cd /opt/sks && git pull && ./deploy/deploy.sh <svc>`

不是三个服务每次都齐发：改了前端就只 tag / sync / bump / deploy `sks-web`。server、ai 同理。同时改了两个服务：各打各的 tag、各 sync 一次，compose 改两行。

| 你改了什么 | 要不要五步 |
|---|---|
| 前端 / Java / Python 业务代码 | 要。只 bump **改了的那个**服务 |
| 只改 `sks-agent` 网关 conf、`50x.html` | 不打镜像 tag、不 sync、不 bump；push agent 后 ECS `git pull && ./deploy/deploy.sh nginx` |
| 只改 `sks-agent` 文档 | 不 bump；ECS `git pull` 即可 |

不用 `:latest` 代替 bump：缓存会让你以为更新了其实没有，回滚也没有上一版 tag 可改回去。

---

## 流水线（和上一节同一件事）

```
服务仓 git tag v*  →  GitHub Actions 推 GHCR（linux/amd64）
        →  本机 acr-sync.sh 拷到 ACR（不要在 ECS 拉 GHCR）
        →  sks-agent 改 compose 里「被发的那个服务」的 tag 并 push
        →  ECS git pull && ./deploy/deploy.sh <svc>
```

仓库根：本机服务仓在 `/Users/rick/work/sks-{web,server,ai}`；deploy 仓 `/Users/rick/work/sks-agent`；ECS 仓根 `/opt/sks`。

## 为什么要 bump compose

ECS **不编译** 你刚 push 的 Git。`deploy.sh sks-web` 只做一件事：按 `docker-compose.yml` 里写死的名字去 ACR `pull`，再起容器。

| 步骤 | 实际效果 |
|---|---|
| 服务仓打 `v0.1.2` | GHCR 多了一份**新**镜像，旧 `v0.1.1` 还在 |
| 本机 `acr-sync` | ACR 里也多了这份 `v0.1.2`，**线上容器没换** |
| **bump**（改 compose 那一行并 push） | 告诉 ECS：下次 pull 用 `v0.1.2` 而不是 `v0.1.1` |
| ECS `deploy.sh sks-web` | 这时才会把正在跑的容器换成新镜像 |

不同步就 bump：ECS pull 失败（ACR 没有该 tag）。  
只 sync 不 bump：`deploy.sh` 仍去拉 compose 里的旧 tag，新功能不会上线。

不用 `:latest`：Docker 常以为本地已是 latest 而不拉；回滚也没有「改回上一版 tag」可写。

只改网关 conf / `50x.html` **不用 bump**（没有业务镜像），见 §C。

---

## 三仓 tag 互相独立

`sks-web:v0.1.2` 和 `sks-server:v0.1.1` 可以同时在线上。compose **按服务各钉各的**，不要为了发前端把 server/ai 也改成同一个新 tag——ACR 上没有的 tag，ECS `pull` 会失败。

| 改了什么 | 打 tag 的仓 | acr-sync 第二参 | compose 改哪一行 | ECS 命令 |
|---|---|---|---|---|
| 前端 SPA | `sks-web` | `sks-web` | `sks-web:…` | `./deploy/deploy.sh sks-web` |
| Java API / Flyway | `sks-server` | `sks-server` | `sks-server:…` | `./deploy/deploy.sh sks-server` |
| Python AI | `sks-ai` | `sks-ai` | `sks-ai:…` | `./deploy/deploy.sh sks-ai` |
| 网关 conf / `50x.html` | 无镜像 tag | 不用 sync | 不用改 image | `./deploy/deploy.sh nginx` |
| 三个都发同一版本 | 三仓都打**同一个** tag | 省略（默认 `all`） | 三处都改 | `./deploy/deploy.sh all` |

跨仓契约变了（`REST_CONTRACT.md` / `API_CONTRACT.md`）：先发**拥有方**（一般先 server 或 ai），再发调用方，避免短暂不兼容。

---

## A. 只发一个服务（最常见）

下面以「前端改完，下一版 `v0.1.2`」为例。Java / Python 把仓名和 `deploy.sh` 参数换成表里那一行即可。

### 1. 代码上 main

服务仓测试过、已 merge/push 到 `origin/main`。

### 2. 打 tag，等 GHCR

```bash
cd /Users/rick/work/sks-web          # 或 sks-server / sks-ai
git checkout main && git pull
git tag v0.1.2
git push origin v0.1.2
```

到该仓 GitHub Actions 看 `ci`：`test` 绿且 **`build-push` 绿**。镜像名：

`ghcr.io/wangbuer1984/sks-web:v0.1.2`

tag 打错了不要复用同一个名字改内容；再打 `v0.1.3`。

### 3. 本机同步到 ACR

在能较快访问 GitHub 的机器上（Mac 即可）。**不要在 ECS 上跑。** Apple Silicon 脚本已强制 `linux/amd64`。

```bash
cd /Users/rick/work/sks-agent
# GHCR：镜像若已公开、或本机以前 login 过，可跳过。pull 报 401 再登：
# docker login ghcr.io -u WangBuer1984
docker login --username=dingtalk_bakexx \
  crpi-7eu3mopdi4xg4ext.cn-beijing.personal.cr.aliyuncs.com
./deploy/acr-sync.sh v0.1.2 sks-web
```

成功时常见一行 Info：`Not all multiplatform-content is present and only the available single-platform image was pushed`——**可忽略**。CI 只打 `linux/amd64`，脚本也按这个平台推，ECS 要的就是这份。

脚本结尾会告诉你下一步（单服务是 `deploy.sh sks-web`，不是 `all`）。若仍看到旧版脚本印 `然后 ./deploy/deploy.sh all`，**不要照做**；以本文表格为准。

ACR 公网（本机 push）：`crpi-7eu3mopdi4xg4ext.cn-beijing.personal.cr.aliyuncs.com/suikoushuo`  
ECS VPC pull：`.env` 里的 `SKS_IMAGE_REGISTRY`（已在首次部署配好）。

### 4. bump：只改被发的那一行

`acr-sync` 只是把镜像放到 ACR。不改 compose，ECS 下次仍 pull `v0.1.1`。

只改 `docker-compose.yml` 里**这一个服务**，例如这次前端：

```yaml
sks-web:
  image: ${SKS_IMAGE_REGISTRY:-ghcr.io/wangbuer1984}/sks-web:v0.1.2
```

`sks-server` / `sks-ai` 仍留现在的 tag（例如 `v0.1.1`）。提交并 `git push origin main`（sks-agent）。ECS `git pull` 之后才能拿到这行新指针。

### 5. ECS 拉起

```bash
cd /opt/sks
git pull
# 凭证过期才需要：
# docker login --username=dingtalk_bakexx \
#   crpi-7eu3mopdi4xg4ext-vpc.cn-beijing.personal.cr.aliyuncs.com
./deploy/deploy.sh sks-web
```

`deploy.sh` 恒带 `docker-compose.yml` + `docker-compose.prod.yml`。单服务是 `pull` + `up -d --no-deps`，**不必**重启 nginx（resolver 10s 内自愈）。

### 6. 验收

ECS 上：

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps
curl -sS http://127.0.0.1/api/health          # {"status":"UP"}
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1/50x.html
```

公网：

```bash
curl -sS https://suikoushuo.com/api/health
curl -sS -o /dev/null -w '%{http_code}\n' https://suikoushuo.com/
```

前端发版额外看（确认不是还在用旧 SPA 壳）：

```bash
curl -sS https://suikoushuo.com/robots.txt     # 应是文本，不是整页 HTML
docker inspect sks-web --format '{{.Config.Image}}'
```

`sks-server` 带新 Flyway 时看容器日志是否迁移成功，再探 `/api/health`。

---

## B. 三个服务同一 tag

只有三仓 **都** 打了同一个 tag、且三个 GHCR 镜像都 build 成功，才：

```bash
cd /Users/rick/work/sks-agent
./deploy/acr-sync.sh v0.1.2          # 默认 all
```

然后 compose **三处**都改成该 tag，ECS：

```bash
cd /opt/sks && git pull && ./deploy/deploy.sh all
```

`all` 会 pull 镜像并 **重建 nginx**。只换业务镜像时用 A，不必 `all`。

---

## C. 只改网关（无新镜像）

改的是 `sks-agent` 的 `deploy/nginx/nginx.https.conf` 或 `50x.html`（微信号用服务器上的替换，不要把真号 commit 进 git）。

```bash
# 本机：改完 push sks-agent
# ECS：
cd /opt/sks && git pull && ./deploy/deploy.sh nginx
```

不要 `certbot --nginx`（网关在容器里）。证书续期用 `./deploy/renew-cert.sh`，见 [`SERVER_INIT.md`](SERVER_INIT.md) §7。

---

## D. 回滚

ACR 上必须已经有旧 tag（`v0.1.1` 已在，可回）。

1. `sks-agent` 把该服务 `image:` 改回旧 tag，push。
2. ECS：`git pull && ./deploy/deploy.sh <svc>`

没有「在 ECS 上 docker tag 一下」的捷径——线上只信 compose 钉的 tag。

---

## 不要做

| 不要 | 原因 |
|---|---|
| 只 push `main` 就去 ECS pull | CI 没打镜像，线上还是旧 tag |
| 在 ECS `docker pull ghcr.io/...` | 国内 GHCR 极慢；生产只拉 ACR VPC |
| `acr-sync.sh v0.1.2` 却只打了 web 的 tag | 默认 sync 三个服务，server/ai 没有该 tag 会失败 |
| compose 三处一起改成新 tag，但只发了 web | ECS pull server/ai 失败 |
| 本地/ECS `docker compose up --build` 编三服务 | 生产三服务只引用镜像；只有 nginx 本地 build |
| `certbot --nginx` / 裸 `certbot renew` | 80 被 `sks-nginx` 占用；续期用 `renew-cert.sh` |
| 复用已推过的 tag 改镜像内容 | GHCR/ACR 缓存，ECS 可能拉到旧层 |
| 觉得「这次特殊」想跳过 bump / 改用 `:latest` | 线上还跑旧镜像，或缓存导致假更新；回滚也没指针 |
| 只 `acr-sync`、compose 不改就 `deploy.sh` | 线上还在 pull 旧 tag，新镜像白拷了一份 |
| 单服务 sync 完照脚本旧结尾跑 `deploy.sh all` | 会去 pull 没有新 tag 的 server/ai；用表里的 `deploy.sh <svc>` |

---

## 相关文件

| 文件 | 角色 |
|---|---|
| 本文 | 日常发版唯一入口 |
| [`acr-sync.sh`](acr-sync.sh) | 本机 GHCR → ACR |
| [`deploy.sh`](deploy.sh) | ECS 上 pull / up / 重建 nginx |
| [`OPS.md`](OPS.md) | HTTPS、备份、监控等运维，不是发版主路径 |
| [`ALIYUN_DEPLOYMENT.md`](ALIYUN_DEPLOYMENT.md) | 仅首次上云 |
