# 本地开发实操练习手册（随口说 sks-agent）

> 这份是**手把手踩坑实录**，记录 2026-07-25~26 从零把本地环境跑通的全过程。每个命令都带预期输出、每个坑都标了根因和修法。照着敲一遍就能复现。
>
> 配套：概念看 `docs/TOOLCHAIN_GUIDE.md`；本地运行速查看 `docs/LOCAL_DEV.md`；生产部署看 `deploy/OPS.md`。

## 总览：最终目标状态

```
浏览器 ──http://localhost:5173── Vite dev (:5173, proxy /api → :8080)
                                      │
                                 sks-server (:8080)   ← Java，IDEA 本地跑（application-local.yml + local profile）
                                      │ HTTP + X-Service-Token
                                      ▼
                                 sks-ai (:8000)       ← Python，终端本地跑（uv run uvicorn）
                                      │
                                 postgres (:5432)     ← 仅 pg 用 docker（pgvector/pg16）
```

应用代码（Java/Python/前端）全在宿主本地跑，只有 pg 用 docker。

---

## Step 1 · Docker：镜像 vs 容器

### 1.1 看本机有哪些镜像
```bash
docker images
```
预期看到 `pgvector/pgvector:pg16`、`sks-agent-nginx`、`sks-agent-sks-ai`、`sks-agent-sks-server` 等。

**三类镜像**：
- 现成镜像（compose `image:` 拉的）：`pgvector/pgvector:pg16`、`nginx:alpine`
- 自造镜像（compose `build:` 从 Dockerfile 造的）：`sks-agent-nginx` / `sks-agent-sks-ai` / `sks-agent-sks-server`
- 无关残留：`mysql:8.0`、`redis:7.0`、`hello-world`、`testcontainers/ryuk`（可 `docker rm` 清）

### 1.2 看正在跑的容器
```bash
docker ps
```
注意 PORTS 列：
- `0.0.0.0:5432->5432/tcp` = 宿主端口→容器端口（映射了，宿主能访问）
- `8000/tcp`（无 `->`）= 只 `expose`，容器内网开放，**宿主访问不到**

这就是"Java 是唯一公网入口"的物理实现：Java/Python 只 expose 不映射宿主，只有 nginx 暴露 80。

### 1.3 进容器逛逛
```bash
docker exec -it sks-postgres bash      # 交互式进去
docker exec sks-postgres psql -U sks -d sks -c '\dt'   # 一句话执行，不进去
```
- `exec -it ... bash` = 交互进容器（bash 是持续 shell，能待着）
- `exec 容器名 命令` = 执行完立刻退出，只拿结果
- 命令拆解：`docker exec sks-postgres psql -U sks -d sks -c '\dt'`
  - `docker exec` = 进容器执行；`sks-postgres` = 容器名；`psql` = 要跑的命令
  - `-U sks` = 用哪个 pg 用户；`-d sks` = 连哪个库；`-c '\dt'` = 执行 `\dt`（列所有表）后退出

### 1.4 常用命令
```bash
docker compose --env-file .env up -d postgres   # 起一个服务
docker compose down                              # 停删容器（保留数据卷）
docker compose down -v                          # 连数据卷一起删（库归零，慎用）
docker logs -f sks-postgres                      # 看日志
```

---

## Step 5 · Flyway：给建表做版本控制

### 5.1 migration 文件命名规则
位置：`sks-server/src/main/resources/db/migration/`
```
V1__core_schema.sql          ← V + 版本号 + __(双下划线) + 描述 + .sql
V2__seed_admin.sql
V3__sms_scene_and_phone_change.sql
```
- 双下划线 `__` 是分隔符，写错成单下划线 Flyway 不认。
- 版本号决定执行顺序；描述给人看。

### 5.2 Flyway 是 Java 依赖，不是独立软件
- 在 `sks-server/pom.xml` 里声明 `flyway-core` 依赖 → Maven 下载 → 打进 jar。
- 不需要 `brew install flyway`。
- Spring Boot 自动配置：启动时扫 classpath 有 flyway 库 + `application.yml` 开了 `spring.flyway.enabled=true` → 自动执行迁移，**早于业务 bean**。

### 5.3 Flyway 工作流程
1. 扫 `db/migration/` 的 `V*.sql`，按版本号排序。
2. 查库里的 `flyway_schema_history` 账本对账。
3. 文件有、账本没记 → 跑它，记一行（含 checksum 指纹）。
4. 文件被改过、checksum 不一致 → **报错启动失败**（已跑的不准改）。

看账本：
```bash
docker exec sks-postgres psql -U sks -d sks -c \
  'SELECT installed_rank, version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank;'
```

### 5.4 改表铁律
- ✅ 加新表/字段/索引：新建 `V{N+1}__描述.sql`（`ALTER TABLE ... ADD COLUMN` 等），重启 Java 自动跑，提交 git。
- ❌ 改已提交的 V1-V3：报 checksum 错。
- 本地推倒重来：`docker compose down -v` → 重起 pg → 起 Java，Flyway 从 V1 全跑。

### 5.5 练习：亲手加一个 migration（已验证流程）
1. 建 `V4__add_user_avatar.sql`：
   ```sql
   ALTER TABLE app_user ADD COLUMN avatar_url VARCHAR(500);
   ```
2. 起 Java，看日志：
   ```
   Successfully validated 4 migrations
   Migrating schema "public" to version "4 - add user avatar"
   Successfully applied 1 migration
   ```
3. 验证：
   ```bash
   docker exec sks-postgres psql -U sks -d sks -c '\d app_user' | grep avatar
   docker exec sks-postgres psql -U sks -d sks -c 'SELECT installed_rank, version, description FROM flyway_schema_history ORDER BY installed_rank;'
   ```
4. 撤销（已跑的不能直接删文件！正确顺序：先反向清库，再删文件）：
   ```bash
   docker exec sks-postgres psql -U sks -d sks -c 'ALTER TABLE app_user DROP COLUMN avatar_url;'
   docker exec sks-postgres psql -U sks -d sks -c "DELETE FROM flyway_schema_history WHERE version='4';"
   rm sks-server/src/main/resources/db/migration/V4__add_user_avatar.sql
   ```
   重启 Java，应回 `Successfully validated 3 migrations` + `No migration necessary`。

### ⚠️ Flyway 的关键行为
**Flyway 迁移比业务 bean 早执行，且不可自动回滚。** 曾出现 JwtConfig 报错退出但 V4 已成功提交的情况——改 migration 后第一次启动要盯紧 Flyway 日志确认 `Successfully applied`。

---

## Step 9 · `.env` 怎么流到各处

### 9.1 四种搬运机制（同一份 .env，不同地方生效方式不同）

| 在哪跑 | 机制 | .env 怎么进进程 |
|---|---|---|
| docker 容器 | compose `env_file: .env` | 整文件注入容器环境变量 |
| Java 终端 | `set -a && source ../.env && set +a` | 搬进 shell 环境变量 |
| Java IDEA | `application-local.yml` 的 `spring.config.import` | Spring 把 .env 当属性源读 |
| Python | `config.py` 的 `pydantic-settings` `env_file=".env"` | 直接读文件 |

### 9.2 .env 结构（看 `.env.example` 模板）
分四类：
- **pg 读**：`POSTGRES_DB/USER/PASSWORD` —— pg 镜像首次启动建库建用户。
- **Java 读**（经 Spring `${VAR:默认}` 占位符）：`JWT_SECRET_USER/ADMIN`、`SERVICE_TOKEN`、`ADMIN_SEED_*`、`TRIAL_CREDIT`、`ALIYUN_*`。
- **Python 读**（经 pydantic）：`ZHIPU_API_KEY`、`TIKHUB_API_KEY`、`ALIYUN_*`。
- **空值占位**：`ZHIPU_API_KEY=`（模板留空，真 .env 填真值，.env 不进 git）。

占位符对应关系：`.env` 的 `JWT_SECRET_USER=fmrK...` ↔ `application.yml` 的 `${JWT_SECRET_USER:}` ↔ `JwtConfig` 的 `@Value("${JWT_SECRET_USER:}")`，**名字必须一一对应**。

### 9.3 改 pg 密码的正确流程

> ⚠️ **`POSTGRES_PASSWORD` 只在 pg 容器首次启动（数据卷为空）时生效。** 库已建好后改 .env 重启 pg **不会**改库密码。

**路径 A：保留库改密码**
1. 在库里改密码：
   ```bash
   docker exec sks-postgres psql -U sks -d sks -c "ALTER USER sks WITH PASSWORD '新密码';"
   ```
2. 改 `.env` 的 `POSTGRES_PASSWORD=新密码`。
3. 重启 **Java**（pg 不用重启，密码在库里已改）。

**路径 B：推倒重来（本地学习用，生产绝不可）**
```bash
# 1. 先改 .env 的 POSTGRES_PASSWORD
# 2. 删容器 + 数据卷
docker compose --env-file .env down -v
# 3. 重起 pg（数据卷空，pg 用新密码首次初始化）
docker compose --env-file .env up -d postgres
# 4. 起 Java，Flyway 重建所有表
```

### 9.4 ⚠️ 验证 pg 密码必须走 TCP（带 `-h`）
```bash
# ✅ 正确：-h 127.0.0.1 强制走 TCP，会真校验密码（scram-sha-256）
docker exec -e PGPASSWORD=新密码 sks-postgres psql -h 127.0.0.1 -U sks -d sks -c 'SELECT 1 AS ok;'

# ❌ 错误：不带 -h 走容器内本地 socket = trust，填啥密码都连得上，是假象
docker exec -e PGPASSWORD=xxx sks-postgres psql -U sks -d sks -c 'SELECT 1;'
```
Java JDBC 走 TCP（`jdbc:postgresql://localhost:5432`），才真校验密码。本地 socket 是 trust 不校验。

### 9.5 本地 IDEA 跑 Java 的最终方案（不靠 EnvFile 插件）

**为什么不用 EnvFile**：IDEA 2026.2 上 EnvFile 不可靠——误把 .env 当可执行程序跑报 `Cannot run program ".env": Permission denied`，或配置不生效。改用 `application-local.yml` + profile，IDEA 原生，稳。

**三件套**：
1. `sks-server/src/main/resources/application-local.yml`（gitignored）：
   ```yaml
   spring:
     config:
       # [.properties] 强制按 properties 加载器读 .env（.env 扩展名 Spring 默认不认，不带方括号会静默跳过 → JWT 空 → 守卫拒绝启动）
       import: "optional:file:/Users/rick/work/sks-agent/.env[.properties]"
     datasource:
       url: jdbc:postgresql://localhost:5432/sks
       username: sks
       password: <你 .env 里的真实密码>
   sks:
     ai:
       base-url: http://localhost:8000
   ```
2. `.gitignore` 加 `**/application-local.yml`（含密钥，不进 git）。
3. IDEA Run Config 激活 profile：VM options 加 `-Dspring.profiles.active=local`，或 Environment variables 填 `SPRING_PROFILES_ACTIVE=local`。**不用填任何密钥环境变量，不用 EnvFile。**

### 9.6 ⚠️ `[.properties]` 方括号是关键（踩出来的硬知识）
`spring.config.import` 按文件扩展名选加载器，`.env` 没对应加载器。不带 `[.properties]` → `optional:` 静默跳过 → JWT_SECRET_USER 空 → `JwtConfig.guardSecret` 抛 `IllegalStateException` 拒绝启动：
```
JWT_SECRET_USER must be set to a real ≥32-byte secret (got blank/placeholder)
```
加 `[.properties]` → 强制 properties 加载器读 .env → KEY=VALUE 成 Spring 属性 → `@Value` 解析到。**任何 Spring Boot 项目本地读 .env，照这写法。**

### 9.7 JWT 密钥守卫
`JwtConfig.java` 拒绝以下值启动（防弱密钥上线）：
- 空 / blank
- `.env.example` 占位符：`change_me_user_secret_min_32_bytes`、`change_me_admin_secret_min_32_bytes`
- 历史硬编码回退：`user-secret-32bytes-xxxxxxxxxxxxx`、`admin-secret-32bytes-xxxxxxxxxxx`

只要 `.env` 有真随机 ≥32 字节值 + `application-local.yml` 正确加载 .env，就能过。

### 9.8 启动成功的标志
日志依次出现：
```
The following 1 profile is active: "local"          ← profile 生效
HikariPool-1 - Start completed                       ← 库密码对
Successfully validated 3 migrations ... up to date   ← Flyway 对账
JwtUtil 创建成功（无报错）                            ← .env 加载成功
Started SksServerApplication in x.xxx seconds        ← 完成
[AdminSeed] 种子回填完成                             ← admin 账号就位
```

---

## 完整本地启动顺序（验证过）

### 前置：JDK 21
```bash
brew install openjdk@21                 # 21 与 17 可共存
/usr/libexec/java_home -V                # 确认有 21
java -version                            # 确认默认是 21
# IDEA 模块 SDK 也要切 21（Project Structure → SDK=21 + Java Compiler target=21）
```

### 1. 起 pg（唯一用 docker 的部分）
建 `docker-compose.override.yml`（gitignored，本地自建）：
```yaml
services:
  postgres:
    ports:
      - "5432:5432"
```
```bash
docker compose --env-file .env up -d postgres
docker exec sks-postgres pg_isready -U sks -d sks     # 验证 ready
```

### 2. 起 Java（IDEA，按 9.5 配好 application-local.yml + local profile）
IDEA 里 Run `SksServerApplication`，看到 `Started SksServerApplication` 即成。

验证端到端：
```bash
set -a && source .env && set +a
curl localhost:8080/api/health
# → {"status":"UP"}

curl -s localhost:8080/api/admin/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_SEED_USERNAME\",\"password\":\"$ADMIN_SEED_PASSWORD\"}"
# → {"code":0,...,"data":{"token":"eyJ...","name":"站长本人"}}
```
> ⚠️ admin 登录路径是 `/api/admin/auth/login`（不是 `/api/admin/login`，有 `/auth/`）。

### 3. 起 Python（终端，待 Step 3 实操）
```bash
cd sks-ai
set -a && source ../.env && set +a
export DATABASE_URL="postgresql://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5432/$POSTGRES_DB"
uv sync
uv run uvicorn app.main:app --reload --port 8000
# 验证：curl localhost:8000/health → {"status":"UP"}
```
> ⚠️ Python 的 `DATABASE_URL` **必须显式 export**——`.env` 里没这变量（compose 是用 environment 注入的），不设会落到 config.py 默认 `postgres:postgres` 连不上。

### 4. 起前端（终端）
```bash
cd sks-web
npm install
npm run dev
# 浏览器开 http://localhost:5173
```
vite proxy 默认 `/api → localhost:8080`，纯本地模式开箱即用，**无需 nginx**。

---

## 常见坑速查表

| 现象 | 根因 | 修法 |
|---|---|---|
| `curl localhost:8080/api/health` 连不上 | 容器版 Java 只 expose 不 ports；本地 Java 没起 | 起本地 Java（IDEA），或用 `curl localhost/api/health` 走 nginx |
| 改 .env 没生效 | docker 要 `up -d --build` 不是 `restart`；本地 Java 要重启 | 重建容器 / IDEA Stop+Run |
| 改 Flyway 没生效 | 要 `--build` 重建镜像（容器版）；本地版重启 Java 即可 | 同上 |
| `JWT_SECRET_USER must be set ... (got blank/placeholder)` | .env 没加载进来 | `application-local.yml` 的 `spring.config.import` 带 `[.properties]` 方括号 |
| `Cannot run program ".env": Permission denied` | EnvFile 插件把 .env 当程序跑 | 弃用 EnvFile，改用 application-local.yml 方案 |
| `FATAL: password authentication failed for user "sks"` | Java 发的密码 ≠ pg 库里的密码 | 改 .env 后要 `ALTER USER` 或 `down -v` 重建；见 9.3 |
| pg 密码改了验证还显示成功 | 验证没带 `-h`，走本地 socket trust | 带 `-h 127.0.0.1` 走 TCP 验证；见 9.4 |
| `JDK 17 不支持 jvm target 21` | IDEA 模块 SDK 还是 17 | Project Structure → SDK=21 + Java Compiler target=21 |
| Python `/health` UP 但 RAG 挂 | /health 设计不阻断；DATABASE_URL 没设 | export DATABASE_URL；看启动日志 init_pool |
| `AbstractDbTest` 基类掩盖 @Transactional 回滚 | 基类自动回滚掉本应持久化的写 | 动 credit/钱代码用 NOT_SUPPORTED+count 断言 |
| admin 登录 401 | 路径写错成 /api/admin/login | 正确路径 /api/admin/auth/login |

---

## 学习路线（建议顺序）

1. **Step 1（Docker）**：`docker ps/images/logs/exec`，进容器逛，建立"容器=隔离小机器"直觉。
2. **Step 5（Flyway）+ Step 9（.env）**：搞清数据怎么来、密钥怎么传。
3. **Step 3/4/6（uv/mvn/npm）**：会 `sync`/`install`/`run` 即可，细节用到再查。
4. **Step 7（Nginx）**：读懂 `deploy/nginx/nginx.conf` 的 `location` + `proxy_pass`，知道改完要 `--build`。
5. **完整启动**：按本文"完整本地启动顺序"跑一遍，每终端对应一个零件。

每个坑都标了根因和修法，照着练，遇到问题先查"常见坑速查表"。
