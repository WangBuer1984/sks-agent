# 随口说 sks-agent 技术栈学习指南（完整实操版）

> **阅读说明（四仓拆分后）**：本文写于本仓还是 monorepo 的时候。Java / Python / 前端源码分别在并列的 `sks-server`、`sks-ai`、`sks-web`；动手命令请到对应仓目录执行。本仓 nginx 只反代，**不再托管**前端 `dist/`（静态由 sks-web 镜像 serve）。生产部署以 `deploy/` 为准，不要按本文的 `try_files` 改网关。
>
> 这份文档把整套技术栈拆成 9 个 Step，每个 Step 讲三件事：**它是什么 → 在仓库哪里看 → 怎么动手验证**。按顺序做，做完你就拥有了对整套系统的心智模型。
>
> 适用对象：对 Docker / Java / Python / 前端工具链都不熟的同学。每个命令都带预期输出，照着敲即可。

---

## 0. 全景图

整套系统由这些零件组成，每个零件配一个工具来"管它"：

```
浏览器 (React) ──HTTP──▶ Nginx ──反代──▶ Java (Spring Boot) ──内网──▶ Python (FastAPI)
                              │                    │                     │
                              │                    └──── Postgres ────────┘
                              │                    (业务表 + 向量 + 检查点)
                              └─ 反代 sks-web（静态在 web 镜像内）
```

| 零件 | 工具 | 工具的作用 |
|---|---|---|
| Postgres / Nginx / Java / Python 运行环境 | Docker | 把"环境+程序"打包成容器，隔离运行 |
| Python 依赖（fastapi 等） | uv | 装/锁 Python 库（Python 版的 npm） |
| Java 依赖（spring 等） | Maven (mvnw) | 装/锁 Java 库 |
| 数据库建表脚本 | Flyway | 给建表做版本控制 |
| 前端构建/开发服务器 | Node + npm + vite | 跑/编译 React |
| 流量分发、藏后端 | Nginx | 反向代理（`/api/` → Java，`/` → sks-web） |

---

## Step 1 · Docker：镜像 vs 容器

### 概念
Docker 不往你电脑系统里装东西，而是**拉一个别人打包好的"镜像"(image)，跑成一个"容器"(container)**。镜像 = 只读模板（里面已经装好某软件），容器 = 镜像跑起来的活实例。一个镜像可以跑出很多容器，删容器不影响镜像。

### 在哪看
`../../docker-compose.yml` 里两种引入方式：
```yaml
postgres:
  image: pgvector/pgvector:pg16      # 用现成镜像（第一次自动 docker pull）

nginx:
  build:                              # 从 Dockerfile 自己造一个镜像
    context: .
    dockerfile: deploy/nginx/Dockerfile
```

### 动手做
```bash
docker images                  # 看本机有哪些镜像
docker ps                       # 看正在跑的容器
docker ps -a                    # 看所有容器（含已停的）
```
预期看到 `pgvector/pgvector:pg16`、`sks-agent-nginx` 等镜像，以及 `sks-postgres` 容器。

**进容器逛逛**：
```bash
docker exec -it sks-postgres bash           # 交互式进容器
docker exec sks-postgres psql -U sks -d sks -c '\dt'   # 一句话执行，不进去
```
- `exec -it ... bash` = 交互进容器（bash 是持续 shell，能待着）
- `exec 容器名 命令` = 执行完立刻退出，只拿结果
- 命令拆解：`docker exec` = 进容器执行；`sks-postgres` = 容器名；`psql -U sks -d sks -c '\dt'` = 用 sks 用户连 sks 库执行 `\dt`（列所有表）

### 端口映射
看 `docker ps` 的 PORTS 列：
- `0.0.0.0:5432->5432/tcp` = 宿主端口→容器端口（映射了，宿主能访问）
- `8000/tcp`（无 `->`）= 只 `expose`，容器内网开放，**宿主访问不到**

这是"Java 是唯一公网入口"的物理实现：Java/Python 只 expose 不映射宿主，只有 nginx 暴露 80。

### 常用命令
```bash
docker compose --env-file .env up -d postgres   # 起一个服务
docker compose down                              # 停删容器（保留数据卷）
docker compose down -v                          # 连数据卷一起删（库归零，慎用）
docker logs -f sks-postgres                      # 看日志
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

### 关键直觉
改了 `Dockerfile` 或它 `COPY` 的源码后，必须 `docker compose up -d --build`（带 `--build`）才重新造镜像；不带 `--build` 的 `restart` 只重启旧镜像，改动不进去。

---

## Step 3 · uv：Python 的依赖管理

### 概念
uv = Python 版的 npm。干两件事：①按清单装依赖到一个隔离环境（`.venv`）；②锁定版本（`uv.lock`）保证谁装都一样。用 Rust 写，比老 `pip` 快几十倍，且自带环境管理。

### 在哪看
- `sks-ai/pyproject.toml` = 依赖清单
- `sks-ai/uv.lock` = 锁文件（精确版本，进 git）
- `sks-ai/app/config.py` = Python 代码读配置（pydantic-settings 自动读 `../../.env`）

### 动手做（在 `sks-ai/` 目录）
```bash
cd sks-ai
uv sync                 # 装依赖，生成 .venv/
uv run uvicorn app.main:app --reload --port 8000   # 起服务（--reload 改代码自动重启）
```
注意 uv 会自己管理 Python 版本，不依赖系统的 python3。

### 关键命令
- `uv sync` = 装依赖（只做一次或改了 pyproject 后）
- `uv run <命令>` = "在 .venv 里执行这条命令"（每次跑服务都这样）
- `--reload` = 改 Python 代码自动重启，调试用

---

## Step 4 · Maven (mvnw)：Java 的依赖管理

### 概念
Maven 是 Java 版的 npm/uv。`pom.xml` = 依赖清单。仓库里用 `./mvnw`（Maven Wrapper）而不是全局装的 `mvn`——不依赖你机器上装没装 Maven，版本也锁定。

### 在哪看
- `sks-server/pom.xml` = 依赖清单（spring-boot、mybatis-plus、postgresql 驱动、flyway、jjwt 等）
- `.mvn/` + `mvnw` = Maven Wrapper 文件

### 动手做（在 `sks-server/` 目录）
```bash
cd sks-server
./mvnw -v                                   # 看 wrapper 版本
./mvnw dependency:tree | head -30           # 看依赖树
./mvnw spring-boot:run                       # 跑起 Spring Boot
./mvnw test                                  # 跑测试
./mvnw test -Dtest=CreditServiceTest        # 只跑一个测试类
```

### 前置：JDK 21
```bash
brew install openjdk@21                 # 21 与 17 可共存
/usr/libexec/java_home -V                # 确认有 21
java -version                            # 确认默认是 21
# IDEA 模块 SDK 也要切 21（Project Structure → SDK=21 + Java Compiler target=21）
```

---

## Step 5 · Flyway：给建表做版本控制

### 概念
Flyway 把"建表/改表 SQL"当代码一样版本化管理。每改一次表，加一个 `V编号__描述.sql` 文件；程序启动时 Flyway 自动扫，跟库里的 `flyway_schema_history` 对账——跑过的不再跑，新的按顺序跑。好处：每个环境的库结构永远一致，改表不用手动 `psql`，提交一个文件大家启动就自动升级。

### 在哪看
`sks-server/src/main/resources/db/migration/`：
```
V1__core_schema.sql          # 第1版：建业务表
V2__seed_admin.sql           # 第2版：种站长账号
V3__sms_scene_and_phone_change.sql   # 第3版：短信 scene + 换绑
```
**文件名规则**：`V` + 版本号 + `__`（双下划线）+ 描述 + `.sql`。双下划线是分隔符，写错成单下划线 Flyway 不认。

开关在 `application.yml`：
```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
```

### Flyway 是 Java 依赖，不是独立软件
- 在 `pom.xml` 声明 `flyway-core` 依赖 → Maven 下载 → 打进 jar。不需要 `brew install flyway`。
- Spring Boot 自动配置：启动时扫到 flyway 库 + 配置开了 → 自动执行迁移，**早于业务 bean**。

### 看账本
```bash
docker exec sks-postgres psql -U sks -d sks -c \
  'SELECT installed_rank, version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank;'
```

### 改表铁律
- ✅ 加新表/字段/索引：新建 `V{N+1}__描述.sql`（`ALTER TABLE ... ADD COLUMN` 等），重启 Java 自动跑，提交 git。
- ❌ 改已提交的旧 migration：报 checksum 错（已跑的不准改）。
- 本地推倒重来：`docker compose down -v` → 重起 pg → 起 Java，Flyway 从 V1 全跑。

---

## Step 6 · Node + npm + vite：前端的运行和构建

### 概念
Node.js 是 JavaScript 运行时（让 JS 能脱离浏览器跑）。前端项目用它干两件事：① `npm install` 装前端依赖（React、axios…）到 `node_modules/`；② `npm run dev/build` 跑构建工具（vite）。

vite 干两件调试离不开的事：① **dev server** 监听 5173，改代码热刷新；② **proxy** 把 `/api` 请求转发到 Java 的 8080（前端不用配跨域）。

### 在哪看
`sks-web/package.json`：
```json
"scripts": { "dev": "vite", "build": "vite build" }
```
`sks-web/vite.config.ts`（关键：proxy）：
```ts
server: {
  port: 5173,
  proxy: {
    '/api': { target: 'http://localhost:8080', changeOrigin: true },   // /api 转给 Java
  },
}
```

### 动手做（在 `sks-web/` 目录）
```bash
cd sks-web
npm install         # 装依赖（第一次慢）
npm run dev         # 起 dev server
```
预期终端显示 `Local: http://localhost:5173/`，浏览器打开能看到前端页面。

### dev vs build
- `npm run dev` = 开发模式，热刷新，源码不压缩，方便调试。
- `npm run build` = 生产模式，编译压缩成静态文件扔 `dist/`，由 nginx 托管。生产不放源码。

---

## Step 7 · Nginx：反向代理

### 概念
Nginx 在这套系统里干两件事：
1. **反向代理**：浏览器只跟 nginx 说话，nginx 把 `/api/` 请求转给 Java。Java 不暴露公网，对外只有 nginx 一个入口。
   > "反向"代理 = 替**服务器**收请求（用户不知道后面有几个 Java）。正向代理替**你**上网（翻墙软件）。
2. **反代前端**：`/` 转给 sks-web 容器（SPA 的 `try_files` 在 web 镜像里，不在网关）。

### 在哪看（重点）
**配置文件：`../../deploy/nginx/nginx.conf`**（本地 80-only）。核心三段：

① 反代 Java：
```nginx
location /api/ {
    set $api_upstream sks-server:8080;
    proxy_pass http://$api_upstream$request_uri;
    proxy_read_timeout 300s;
}
```

② 反代前端：
```nginx
location / {
    set $web_upstream sks-web:80;
    proxy_pass http://$web_upstream$request_uri;
}
```

③ 50x 兜底页：
```nginx
error_page 500 502 503 504 /50x.html;
location = /50x.html { root /usr/share/nginx/html; }
```

### 为什么 `proxy_pass http://sks-server:8080` 能用容器名当主机名？
因为 `../../docker-compose.yml` 里所有服务在同一个 `sks-net` 网络下。Docker 内置 DNS 让容器之间能用容器名互访——`sks-server` 解析到 Java 容器的 IP。

### 改配置的流程
因为 nginx 镜像是 `build:` 出来的、配置是 COPY 进镜像的，所以：
1. 编辑 `../../deploy/nginx/nginx.conf`。
2. 重建：`docker compose --env-file .env up -d --build nginx`。
3. 验证：`curl localhost/api/health`。

> 本地纯调试时**不需要 nginx**——vite 的 dev server 自带 `/api → localhost:8080` 代理。nginx 是生产把 Java 藏起来、并把 `/` 反代到 sks-web 用的。

---

## Step 8 · 把四件套串起来：一次完整本地启动

每个零件都认识了，串一遍。纯本地模式（pg 用 docker，应用代码全在宿主跑）：

```bash
# 1. 起 pg（唯一用 docker 的部分）
docker compose --env-file .env up -d postgres
docker exec sks-postgres pg_isready -U sks -d sks    # 验证 ready

# 2. 起 Java（IDEA Run，见 Step 4 + §env 配置）
curl localhost:8080/api/health        # → {"status":"UP"}

# 3. 起 Python
cd sks-ai
set -a && source ../.env && set +a
export DATABASE_URL="postgresql://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5432/$POSTGRES_DB"
uv sync
uv run uvicorn app.main:app --reload --port 8000
curl localhost:8000/health           # → {"status":"UP"}

# 4. 起前端
cd sks-web
npm install && npm run dev
# 浏览器开 http://localhost:5173
```
启动顺序约束：**pg 必须先起**（Java Flyway 首次要建表）；Java 和 Python 顺序随意；前端最后起。

---

## Step 9 · `../../.env` 怎么流到各处

### 概念
密钥（DB 密码、GLM key、JWT secret…）不进 git，统一放根目录 `../../.env`（gitignored）。各服务通过不同机制读它：

| 在哪跑 | 机制 | .env 怎么进进程 |
|---|---|---|
| docker 容器 | compose `env_file: .env` | 整文件注入容器环境变量 |
| Java 终端 | `set -a && source ../.env && set +a` | 搬进 shell 环境变量 |
| Java IDEA | `application-local.yml` 的 `spring.config.import` | Spring 把 .env 当属性源读 |
| Python | `config.py` 的 pydantic-settings `env_file=".env"` | 直接读文件 |

### .env 结构（看 `../../.env.example` 模板）
分四类：
- **pg 读**：`POSTGRES_DB/USER/PASSWORD` —— pg 镜像首次启动建库建用户。
- **Java 读**（经 Spring `${VAR:默认}` 占位符）：`JWT_SECRET_USER/ADMIN`、`SERVICE_TOKEN`、`ADMIN_SEED_*`、`TRIAL_CREDIT`、`ALIYUN_*`。
- **Python 读**（经 pydantic）：`ZHIPU_API_KEY`、`TIKHUB_API_KEY`、`ALIYUN_*`。
- **空值占位**：`ZHIPU_API_KEY=`（模板留空，真 .env 填真值，.env 不进 git）。

占位符对应关系：`../../.env` 的 `JWT_SECRET_USER=fmrK...` ↔ `application.yml` 的 `${JWT_SECRET_USER:}` ↔ 代码的 `@Value("${JWT_SECRET_USER:}")`，**名字必须一一对应**。

### 本地 IDEA 跑 Java 的配置方案

**三件套**（不靠任何插件、IDEA 原生）：
1. `sks-server/src/main/resources/application-local.yml`（gitignored）：
   ```yaml
   spring:
     config:
       import: "optional:file:/Users/rick/work/sks-agent/.env[.properties]"
     datasource:
       url: jdbc:postgresql://localhost:5432/sks
       username: sks
       password: <你 .env 里的真实密码>
   sks:
     ai:
       base-url: http://localhost:8000
   ```
   > `[.properties]` 方括号必须有：Spring 按扩展名选加载器，`../../.env` 没对应加载器，不带方括号会静默跳过 → 密钥读不到。方括号强制按 properties 加载器读 .env。
2. `../../.gitignore` 加 `**/application-local.yml`（含密钥，不进 git）。
3. IDEA Run Config 激活 profile `local`：VM options 加 `-Dspring.profiles.active=local`。**不用填任何密钥环境变量。**

### 改 pg 密码的正确流程
> ⚠️ `POSTGRES_PASSWORD` 只在 pg 容器**首次启动（数据卷为空）**时生效。库已建好后改 .env 重启 pg 不会改库密码。

- **路径 A：保留库改密码** → `ALTER USER sks WITH PASSWORD '新密码';` + 改 .env + 重启 Java（pg 不用重启）。
- **路径 B：推倒重来** → 改 .env → `docker compose down -v` → `docker compose up -d postgres`（pg 用新密码首次初始化）→ 起 Java Flyway 重建。

### 验证 pg 密码必须走 TCP（带 `-h`）
```bash
# ✅ 正确：-h 127.0.0.1 强制走 TCP，会真校验密码
docker exec -e PGPASSWORD=新密码 sks-postgres psql -h 127.0.0.1 -U sks -d sks -c 'SELECT 1 AS ok;'

# ❌ 错误：不带 -h 走容器内本地 socket = trust，填啥密码都连得上，是假象
```
Java JDBC 走 TCP（`jdbc:postgresql://localhost:5432`），才真校验密码。本地 socket 是 trust 不校验。

---

## 常见坑速查表

| 现象 | 根因 | 修法 |
|---|---|---|
| `curl localhost:8080/api/health` 连不上 | 容器版 Java 只 expose 不 ports；本地 Java 没起 | 起本地 Java（IDEA），或走 nginx |
| 改 .env 没生效 | docker 要 `up -d --build` 不是 `restart`；本地 Java 要重启 | 重建容器 / IDEA Stop+Run |
| 改 Flyway 没生效 | 要 `--build` 重建镜像（容器版）；本地版重启 Java 即可 | 同上 |
| 密钥读不到、占位符空 | .env 没加载进来 | application-local.yml 的 `spring.config.import` 带 `[.properties]` |
| `FATAL: password authentication failed` | Java 发的密码 ≠ pg 库里的密码 | 改 .env 后要 `ALTER USER` 或 `down -v` 重建 |
| pg 密码改了验证还成功 | 验证没带 `-h`，走本地 socket trust | 带 `-h 127.0.0.1` 走 TCP 验证 |
| `JDK 17 不支持 jvm target 21` | IDEA 模块 SDK 还是 17 | Project Structure → SDK=21 + Java Compiler target=21 |
| Python `/health` UP 但 RAG 挂 | /health 设计不阻断；DATABASE_URL 没设 | export DATABASE_URL；看启动日志 init_pool |
| 前端登录 401 循环 | 确认走 `localhost:5173` 而非 8080；vite proxy 只在 dev server 生效 | 走 vite 5173 |
| admin 登录 401 | 路径写错成 /api/admin/login | 正确路径 /api/admin/auth/login |

---

## 学习路线

1. **Step 1-2（Docker）**：`docker ps/images/logs/exec`，进容器逛，建立"容器=隔离小机器"直觉。
2. **Step 5（Flyway）+ Step 9（.env）**：搞清数据怎么来、密钥怎么传。
3. **Step 3/4/6（uv/mvn/npm）**：会 `sync`/`install`/`run` 即可，细节用到再查。
4. **Step 7（Nginx）**：读懂 `../../deploy/nginx/nginx.conf` 的 `location` + `proxy_pass`，知道改完要 `--build`。
5. **Step 8**：串起来跑一次完整本地启动，每终端对应一个零件。

每个 Step 后面都有「动手做」，建议真的敲一遍看预期输出——看十遍不如跑一遍。
