# 随口说 技术栈学习指南（从零开始，分步骤）

> 这份文档面向「这套工具链我都不熟」的同学。每个概念讲三件事：**它是什么 → 在这个仓库的哪里看 → 怎么动手验证**。建议按顺序一步步来，每步动手做完再进下一步。
>
> 配套阅读：本地调试运行见 `docs/LOCAL_DEV.md`；生产部署见 `deploy/OPS.md`。

## 0. 全景：这套系统由哪些零件组成

先记住一张图，后面每个零件都会展开：

```
浏览器 (React) ──HTTP──▶ Nginx ──反代──▶ Java (Spring Boot) ──内网──▶ Python (FastAPI)
                              │                    │                     │
                              │                    └──── Postgres ────────┘
                              │                    (业务表 + 向量 + 检查点)
                              └─ 同时托管前端静态文件
```

每个零件配一个工具来"管它"：

| 零件 | 工具 | 工具的作用 |
|---|---|---|
| Postgres / Nginx / Java / Python 运行环境 | **Docker** | 把"环境+程序"打包成容器，隔离运行 |
| Python 依赖（fastapi 等） | **uv** | 装/锁 Python 库（Python 版的 npm） |
| Java 依赖（spring 等） | **Maven (mvnw)** | 装/锁 Java 库 |
| 数据库建表脚本 | **Flyway** | 给建表做版本控制 |
| 前端构建/开发服务器 | **Node + npm** | 跑/编译 React |
| 流量分发、藏后端 | **Nginx** | 反向代理 + 托管静态文件 |

---

## Step 1 · Docker：为什么不是"安装"而是"拉镜像"

### 概念
Docker 不往你电脑系统里装东西，而是**拉一个别人打包好的"镜像"(image)，跑成一个"容器"(container)**。镜像 = 只读模板（里面已经装好 nginx/pg/Python…），容器 = 镜像跑起来的活实例。

打个比方：镜像是菜谱+食材的预制菜包，容器是你把它加热出来的那盘菜。菜包可以反复加热出很多盘，删掉一盘不影响菜包。

### 在哪看
`docker-compose.yml`：
```yaml
postgres:
  image: pgvector/pgvector:pg16      # 拉这个现成镜像
  container_name: sks-postgres

nginx:
  build:                              # 不拉现成的，自己造
    context: .
    dockerfile: deploy/nginx/Dockerfile
```
- `image:` = 用现成镜像（第一次会自动 `docker pull`）。
- `build:` = 从 Dockerfile 自己造一个镜像。

### 动手做
```bash
# 看本机已有哪些镜像（postgres/nginx/java 都在这）
docker images

# 看正在跑的容器
docker ps

# 看所有容器（含已停的）
docker ps -a
```
**预期**：`docker images` 里能看到 `pgvector/pgvector`、`sks-agent-nginx` 等；`docker ps -a` 里能看到 `sks-postgres` Up、还有几个 Exited 的 pgvector（那是测试残留，可不管）。

### 关键命令
```bash
docker compose --env-file .env up -d postgres    # 起一个服务
docker compose down                              # 停并删容器（数据卷保留）
docker compose down -v                            # 连数据卷一起删（库就没了，慎用）
docker logs -f sks-postgres                       # 看某容器日志
docker exec -it sks-postgres bash                 # 进容器里看
```

---

## Step 2 · Dockerfile：怎么"自己造"一个镜像

### 概念
Dockerfile 是一份"造镜像的菜谱"，一行行指令告诉 Docker 从哪个基础镜像开始、装什么、拷什么文件进去、启动时跑什么。

### 在哪看
`sks-ai/Dockerfile`（Python 服务的造法）：
```dockerfile
FROM python:3.12-slim              # 基础镜像：官方 Python 3.12 精简版
COPY --from=ghcr.io/astral-sh/uv:0.11.29 /uv /usr/local/bin/uv   # 顺便把 uv 二进制拷进来
WORKDIR /app
COPY pyproject.toml ./
COPY app ./app
RUN uv sync --no-dev               # 装依赖
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]   # 启动命令
```

`sks-server/Dockerfile`（Java 的，分两阶段：构建+运行）：
```dockerfile
FROM eclipse-temurin:21-jdk AS build    # 阶段1：用 JDK 编译
RUN ... ./mvnw ... package
FROM eclipse-temurin:21-jre             # 阶段2：只用 JRE 跑（更小）
COPY --from=build .../app.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

### 动手做
```bash
# 看 Java 服务镜像多大
docker images | grep sks-agent

# 看一个镜像的构建历史（怎么一层层堆出来的）
docker history sks-agent-sks-ai | head
```

### 体会
改了 `Dockerfile` 或它 `COPY` 的源码后，必须 `docker compose up -d --build`（带 `--build`）才重新造镜像；不带 `--build` 的 `restart` 只重启旧镜像，你的改动不进去。这就是 LOCAL_DEV 里反复强调"改了要 `--build`"的原因。

---

## Step 3 · uv：Python 的依赖管理

### 概念
uv = Python 版的 npm。干两件事：①按清单装依赖到一个隔离环境（`.venv`）；②锁定版本（`uv.lock`）保证谁装都一样。

为什么不用老的 `pip`？uv 用 Rust 写，快几十倍，且自带环境管理，不用先 `python -m venv` 再 `pip install` 那两步。

### 在哪看
`sks-ai/pyproject.toml` = 依赖清单（声明要哪些库、可选范围）。
`sks-ai/uv.lock` = 锁文件（精确到每个库每个版本，提交进 git）。
`sks-ai/app/config.py` = Python 代码里读配置（`pydantic-settings` 自动读 `.env`）。

### 动手做（在 `sks-ai/` 目录）
```bash
cd sks-ai
uv sync                 # 装依赖，生成 .venv/
uv run python -c "import fastapi; print(fastapi.__version__)"   # 在 .venv 里跑命令
uv run uvicorn app.main:app --port 8000   # 起服务
```
**预期**：`uv sync` 第一次会下一堆库；`uv run uvicorn ...` 启动后 `curl localhost:8000/health` 返回 `{"status":"UP"}`。

### 关键区别
- `uv sync` = 装依赖（只做一次或改了 pyproject 后）。
- `uv run <命令>` = "在 .venv 里执行这条命令"（每次跑服务都这样）。
- `--reload` = 改 Python 代码自动重启，调试用。

---

## Step 4 · Maven (mvnw)：Java 的依赖管理

### 概念
Maven 是 Java 版的 npm/uv。`pom.xml` = 依赖清单。仓库里用 `./mvnw`（Maven Wrapper）而不是全局装的 `mvn`——好处是**不依赖你机器上装没装 Maven**，版本也锁定。

### 在哪看
`sks-server/pom.xml` = 依赖清单（spring-boot、mybatis-plus、postgresql 驱动、jjwt、aliyun sdk…）。
`.mvn/` + `mvnw` = Maven Wrapper 文件。

### 动手做（在 `sks-server/` 目录）
```bash
cd sks-server
./mvnw -v                                   # 看 wrapper 版本
./mvnw dependency:tree | head -30           # 看依赖树（谁依赖谁）
./mvnw spring-boot:run                       # 跑起 Spring Boot
./mvnw test                                  # 跑测试
```

### 关键命令
```bash
./mvnw spring-boot:run        # 开发时跑（不跑测试）
./mvnw test                   # 跑全部测试
./mvnw test -Dtest=CreditServiceTest   # 只跑一个测试类
```

---

## Step 5 · Flyway：给建表做版本控制

### 概念
Flyway 把"建表/改表 SQL"当代码一样版本化管理。每改一次表，加一个 `V编号__描述.sql` 文件；程序启动时 Flyway 自动扫，跟库里的 `flyway_schema_history` 对账——跑过的不再跑，新的按顺序跑。

好处：每个环境（你电脑、同事电脑、生产）的库结构永远一致；改表不用手动 `psql` 敲 SQL，提交一个文件大家启动就自动升级。

### 在哪看
`sks-server/src/main/resources/db/migration/`：
```
V1__core_schema.sql          # 第1版：建 17 张业务表
V2__seed_admin.sql           # 第2版：种站长账号
V3__sms_scene_and_phone_change.sql   # 第3版：短信 scene + 换绑
```
开关在 `application.yml`：
```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
```

### 动手做
Java 启动时日志会打 Flyway 的进度，找这几行：
```
o.f.core.internal.command.DbValidate     : Successfully validated 3 migrations
o.f.core.internal.command.DbMigrate      : Current version of schema "public": 3
o.f.core.internal.command.DbMigrate      : Schema "public" is up to date. No migration necessary.
```

看 Flyway 自己的对账表（库里）：
```bash
docker exec sks-postgres psql -U sks -d sks -c \
  'SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;'
```
**预期**：3 行，V1/V2/V3，success 都是 t（true）。

### 体会
- 想加新表/字段？新建 `V4__xxx.sql`，重启 Java，Flyway 自动跑。
- **不要**手改已提交的 V1-V3（Flyway 会校验 checksum 不匹配报错）；改表一律加新版本号。
- 想重来：`docker compose down -v` 删数据卷，再起 Java，Flyway 从 V1 重跑全部。

---

## Step 6 · Node + npm：前端的运行和构建

### 概念
Node.js 是 JavaScript 运行时（让 JS 能脱离浏览器跑）。前端项目用它干两件事：① `npm install` 装前端依赖（React、axios…）到 `node_modules/`；② `npm run dev/build` 跑构建工具（vite）。

vite 干两件你调试时离不开的事：① **dev server** 监听 5173，改代码自动热刷新；② **proxy** 把 `/api` 请求转发到 Java 的 8080（这样前端不用配跨域）。

### 在哪看
`sks-web/package.json` = 依赖清单 + 脚本：
```json
"scripts": { "dev": "vite", "build": "vite build" }
```
`sks-web/vite.config.ts` = vite 配置，关键是 proxy：
```ts
server: {
  port: 5173,
  proxy: {
    '/api': {
      target: 'http://localhost:8080',   // /api 转给 Java
      changeOrigin: true,
    },
  },
}
```

### 动手做（在 `sks-web/` 目录）
```bash
cd sks-web
npm install         # 装依赖（生成 node_modules/，第一次慢）
npm run dev         # 起 dev server
```
**预期**：终端显示 `Local: http://localhost:5173/`，浏览器打开能看到前端页面。

### dev vs build 的区别
- `npm run dev` = 开发模式，热刷新，源码不压缩，方便调试。
- `npm run build` = 生产模式，编译压缩成静态文件扔 `dist/`，由 nginx 托管。生产不放源码。

---

## Step 7 · Nginx：反向代理 + 托管静态文件

### 概念
Nginx 在这套系统里干两件事：
1. **反向代理**：浏览器只跟 nginx 说话，nginx 再把 `/api/` 请求转给 Java。Java 不暴露公网，对外只有 nginx 一个入口。
2. **托管静态文件**：前端的 `dist/` 给 nginx，nginx 直接把 HTML/JS/CSS 发给浏览器，不用经过 Java。

> "反向"代理 vs "正向"代理：正向代理替**你**上网（你翻墙用的）；反向代理替**服务器**收请求（用户不知道后面有几个 Java）。这里 nginx 是反向代理。

### 在哪看（这是重点）
**配置文件就一个：`deploy/nginx/nginx.conf`**。改代理只改这一个文件。

核心三段：

① 反代 Java：
```nginx
location /api/ {
    proxy_pass http://sks-server:8080/api/;   # /api/ 开头 → 转给 Java 容器
    proxy_set_header X-Real-IP $remote_addr;  # 把用户真实 IP 透传给 Java
    proxy_read_timeout 300s;                   # 外层超时
}
```

② 托管前端 SPA：
```nginx
location / {
    try_files $uri $uri/ /index.html;   # 找不到文件就回 index.html（React 前端路由）
}
```

③ 50x 兜底页（Java/Python 全挂时还能显示个页面）：
```nginx
error_page 500 502 503 504 /50x.html;
```

### 为什么 `proxy_pass http://sks-server:8080` 能用容器名当主机名？
因为 `docker-compose.yml` 里所有服务都在同一个 `sks-net` 网络下（`networks: sks-net`）。Docker 内置 DNS 让容器之间能用容器名互访——`sks-server` 就解析到 Java 容器的 IP。

### 动手做
```bash
# 看正在跑的 nginx 容器
docker ps | grep nginx

# 不进容器看配置（确认镜像里打的是不是你改的）
docker exec sks-nginx cat /etc/nginx/conf.d/default.conf 2>/dev/null || \
docker exec sks-nginx cat /etc/nginx/nginx.conf

# 测一下代理通不通（经 nginx 访问 Java 的 health）
curl localhost/api/health        # 应返回 {"status":"UP"}
```

### 改配置的流程（重要）
因为 nginx 镜像是 `build:` 出来的、配置是 COPY 进镜像的，所以：
1. 编辑 `deploy/nginx/nginx.conf`。
2. 重建：`docker compose --env-file .env up -d --build nginx`。
3. 验证：`curl localhost/api/health`。

> 本地纯调试时你**不需要 nginx**——vite 的 dev server 自带 `/api → localhost:8080` 代理（见 Step 6）。nginx 是生产把 Java 藏起来、同时托管前端构建产物用的。

---

## Step 8 · 把四件套串起来：一次完整本地启动

现在每个零件都认识了，按 `LOCAL_DEV.md` 的步骤串一遍。这里只列最小可跑路径（纯本地，pg 用 docker）：

```bash
# 1. 起 pg（唯一用 docker 的部分）
docker compose --env-file .env up -d postgres
docker exec sks-postgres pg_isready -U sks -d sks    # 验证

# 2. 起 Java
cd sks-server
set -a && source ../.env && set +a
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/$POSTGRES_DB"
export SPRING_DATASOURCE_USERNAME="$POSTGRES_USER"
export SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD"
export SKS_AI_BASE_URL="http://localhost:8000"   # 关键：指向本地 Python
./mvnw spring-boot:run
# 另开终端验证：curl localhost:8080/api/health → {"status":"UP"}

# 3. 起 Python（再开一个终端）
cd sks-ai
set -a && source ../.env && set +a
export DATABASE_URL="postgresql://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5432/$POSTGRES_DB"
uv sync
uv run uvicorn app.main:app --reload --port 8000
# 验证：curl localhost:8000/health → {"status":"UP"}

# 4. 起前端（再开一个终端）
cd sks-web
npm install
npm run dev
# 浏览器开 http://localhost:5173
```

启动顺序的约束：**pg 必须先起**（Java Flyway 首次要建表）；Java 和 Python 顺序随意（Java 调 Python 是按需的）；前端最后起。

---

## Step 9 · 环境变量：`.env` 是怎么流到各处的

### 概念
密钥（DB 密码、GLM key、JWT secret…）不进 git，统一放根目录 `.env`（gitignored）。各服务通过不同机制读它：
- **Docker 容器**：compose 里 `env_file: .env`，整文件注入容器环境。
- **Java**：Spring 不直接读 `.env`，靠 `application.yml` 里的 `${VAR:默认值}` 占位符读环境变量。
- **Python**：`config.py` 用 `pydantic-settings`，`env_file=".env"` 直接读文件。

### 在哪看
`.env.example` = 模板（无密钥）；`.env` = 真值（gitignored）。
`application.yml` 里满眼 `${...}`：
```yaml
url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/sks}
sks:
  ai:
    base-url: ${SKS_AI_BASE_URL:http://sks-ai:8000}
  service-token: ${SERVICE_TOKEN:}
```
`${VAR:默认}` = "优先用环境变量 VAR，没有就用默认值"。

### 为什么本地起 Java 要 `set -a && source .env && set +a`
`source .env` 把文件里的变量读进当前 shell；`set -a` 让读进的变量也 export 给子进程（`./mvnw` 是子进程）。否则 Java 进程拿不到这些密钥。

### 动手做
```bash
set -a && source .env && set +a
echo $ZHIPU_API_KEY    # 能看到值（别贴出来）
echo $POSTGRES_DB      # sks
```

---

## 学习路线建议

1. **先吃透 Step 1-2（Docker）**：跑 `docker ps`/`docker images`/`docker logs`，进容器逛逛，建立"容器=隔离的小机器"直觉。
2. **再过 Step 5（Flyway）+ Step 9（.env）**：这俩决定数据怎么来、密钥怎么传，理解了再看代码不懵。
3. **Step 3/4/6（uv/mvn/npm）**：会跑 `sync`/`install`/`run` 就行，依赖管理细节用到再查。
4. **Step 7（Nginx）**：能读懂 `nginx.conf` 的 `location` + `proxy_pass`，知道改完要 `--build`，就够调试用了。
5. **最后 Step 8**：串起来跑一次完整本地启动，每个终端对应一个零件，你就拥有了对整套系统的"心智模型"。

每个 Step 后面都有「动手做」，建议真的敲一遍看预期输出——看十遍不如跑一遍。
