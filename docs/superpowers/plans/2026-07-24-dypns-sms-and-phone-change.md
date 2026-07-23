# DYPNS 短信认证 + 换绑手机号 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把已合并的 dysmsapi 短信接线换成 DYPNS 短信认证（个人实名、赠送签名/模板），并把告警从短信改成邮件；新增换绑手机号 2-step flow（验旧号→验新号）。

**Architecture:** `SmsClient` scene 化（3 scene），`AliyunSmsAuthClient` 走 `dypnsapi20170525.SendSmsVerifyCode` 字面码模式（Java 自生成码 + 自比对，不调 CheckSmsVerifyCode）。告警走 `AlertNotifier`/`MailAlertNotifier`（spring-boot-starter-mail）。换绑 2-step 状态落 `phone_change_session` 表 + `sms_code` 扩 `scene`/`session_token` 列，事务边界镜像 AuthService（err_count 非事务、成功收尾 self 代理 @Transactional、UNIQUE 冲突事务外 catch）。

**Tech Stack:** Java 21 / Spring Boot 3.2.5 / MyBatis-Plus / Flyway / PostgreSQL 16 (pgvector) / Testcontainers / `com.aliyun:dypnsapi20170525:2.0.0` / `spring-boot-starter-mail` / Mockito / jjjwt.

## Global Constraints

（从 `docs/superpowers/specs/2026-07-24-dypns-sms-and-phone-change-design.md` 逐条拷贝，binding）

- **字面码模式**：`TemplateParam={"code":"<6位>","min":"5"}`（阿里云只发不核验）；Java 自生成 6 位码 + 自比对，**不调 CheckSmsVerifyCode**。
- **SDK**：`com.aliyun:dypnsapi20170525:2.0.0`（替换 `dysmsapi20170525:4.6.0`）；endpoint `dypnsapi.aliyuncs.com`；调用 `client.sendSmsVerifyCodeWithOptions(req, runtime)`；Config 经 `com.aliyun.teaopenapi.models.Config` 走 AK/SK（不用无 AK 凭据方式）。setter 以 SDK javadoc 为终判，sample 已验证 2.0.0 用 `setSignName/setTemplateCode/setTemplateParam/setPhoneNumber/setCodeLength/setCodeType/setInterval`。
- **赠送签名/模板**：必须用系统赠送（`dypns.console.aliyun.com/smsCertParamsConfig/{sign,template}`），不支持自定义。SignName 真值 `速通互联验证码`，登录/注册模板 `100002`，另两模板联调期领。
- **成功判定**：`body.Code=="OK"`；失败（code≠OK 或异常）抛 `BizException(ErrorCode.SMS_SEND_FAILED)`（5003）。
- **条件降级**：key/template 空 → stub（log + no-op，不抛），本地/CI 行为不变；懒构造 Client（空 key 不破 bean）；`setDelegate(Client)` 测试 seam。
- **频控按 phone 全局**（1min≤1 / 1h≤5 / 24h≤10），**不加 scene 参数**；与服务端 `Interval=60`（按号全局）一致，接受同号 60s 跨 scene 双重拦。
- **比对按 (phone, scene)**：`findActiveCode(phone, scene)` 加 scene 过滤防跨 scene 码互用。
- **锁定 ANY-row**：`existsLocked(phone)` = `EXISTS(sms_code WHERE phone=? AND err_count>=5 AND created_at > now()-10min)`，任一 scene 锁 5 次则全号锁（防稀释绕过）；细化 `AuthService.checkLocked`，单 scene 行为等价。
- **事务边界 Critical**：`verify-old`/`verify-new` 错码路径 `err_count++` **非 @Transactional**（防回滚使锁定失效，即 AuthService 那条回归）；`verify-new` 成功收尾经 **self 代理 @Transactional `completePhoneChange`**；`app_user.phone` UNIQUE 冲突的 `DuplicateKeyException` **在事务外 `verifyNewPhone` catch** → `PHONE_ALREADY_BOUND`（事务内 catch 会 rollback-only → UnexpectedRollbackException）。
- **verify-old 对码 markUsed**：对码→markUsed 旧号码 sms_code 行 + UPDATE session（两条自动提交写），防重放 verify-old 续窗口。
- **session 先删后建**：`DELETE FROM phone_change_session WHERE user_id=? AND status<>'DONE'` 再 INSERT（不标 DONE、不用 ON CONFLICT）；部分唯一索引 `UNIQUE(user_id) WHERE status<>'DONE'`。
- **token 语义**：建行即生成（不返客户端）；verify-old 通过才返 + 重置 `expires_at=now()+10min`（new-bind 窗口从旧码验过起算）。
- **verify-new 断言 newPhone==session.new_phone**：不符→`PHONE_CHANGE_TOKEN_INVALID`。
- **newPhone 侧锁定**：send-new-code + verify-new 入口都查 `existsLocked(newPhone)`。
- **告警邮件**：`MailAlertNotifier` 用 `ObjectProvider<JavaMailSender>`（host 空别赌 bean）；465 端口须 `spring.mail.properties.mail.smtp.ssl.enable=true`；失败 `log.warn` 吞掉不抛。
- **无 Redis/MQ/K8s**；所有 key via `.env`（gitignored）；Java 唯一公网入口（DYPNS+SMTP Java 侧直发不出网 Python）；Testcontainers pgvector/pgvector:pg16 非 H2；不碰钱路径。
- **C 端无密码**：不做重置密码 flow。`RechargeOrderService` 两处 `[SMS-STUB]` 留 TODO（spec §1 允许拆出，钱路径不在本计划）。

---

## File Structure

**Create:**
- `sks-server/src/main/java/com/sks/common/SmsScene.java` — 3-scene 枚举。
- `sks-server/src/main/java/com/sks/common/AliyunSmsAuthClient.java` — DYPNS 实现（替换 dysmsapi 版 AliyunSmsClient）。
- `sks-server/src/main/java/com/sks/common/AlertNotifier.java` — 告警 seam 接口。
- `sks-server/src/main/java/com/sks/common/MailAlertNotifier.java` — SMTP 实现。
- `sks-server/src/main/java/com/sks/user/PhoneChangeSession.java` — session 实体。
- `sks-server/src/main/java/com/sks/user/PhoneChangeSessionMapper.java` — session mapper。
- `sks-server/src/main/java/com/sks/user/UserPhoneService.java` — 换绑 2-step flow 主体。
- `sks-server/src/main/java/com/sks/user/UserPhoneController.java` — `/api/user/phone/change/**` 端点。
- `sks-server/src/main/resources/db/migration/V3__sms_scene_and_phone_change.sql` — schema。
- `sks-server/src/test/java/com/sks/common/AliyunSmsAuthClientTest.java` — 替换旧 AliyunSmsClientTest。
- `sks-server/src/test/java/com/sks/common/MailAlertNotifierTest.java`
- `sks-server/src/test/java/com/sks/user/PhoneChangeSessionMapperTest.java`
- `sks-server/src/test/java/com/sks/auth/SmsCodeMapperTest.java`
- `sks-server/src/test/java/com/sks/user/UserPhoneServiceTest.java`

**Modify:**
- `sks-server/pom.xml` — 加 dypnsapi20170525:2.0.0 + spring-boot-starter-mail（Task 1）；删 dysmsapi20170525（Task 5）。
- `sks-server/src/main/java/com/sks/common/ErrorCode.java` — 加 4007/4008。
- `sks-server/src/main/java/com/sks/auth/SmsCode.java` — 加 scene/sessionToken 字段。
- `sks-server/src/main/java/com/sks/auth/SmsCodeMapper.java` — findActiveCode 加 scene、加 existsLocked、加 invalidateByToken/invalidateByPhones。
- `sks-server/src/main/java/com/sks/user/AppUserMapper.java` — 加 updatePhone。
- `sks-server/src/main/java/com/sks/auth/AuthService.java` — checkLocked→existsLocked、login 传 scene、sendCode 传 scene（Task 5）。
- `sks-server/src/main/java/com/sks/common/SmsClient.java` — 3 参签名 + 删 sendAlert（Task 5）。
- `sks-server/src/main/java/com/sks/common/QuotaWatchJob.java` — 注入换 AlertNotifier、sendAlert→notify、删 adminPhone（Task 4）。
- `sks-server/src/main/resources/application.yml` — sms 块改 3 template、spring.mail、sks.alert、删 admin-phone。
- `sks-server/src/test/java/com/sks/auth/AuthServiceTest.java` — sendCode 断言 3 参。
- `sks-server/src/test/java/com/sks/common/QuotaWatchJobTest.java` — newJob + sendAlert 断言改 AlertNotifier。

**Delete:**
- `sks-server/src/main/java/com/sks/common/AliyunSmsClient.java`（Task 5）
- `sks-server/src/test/java/com/sks/common/AliyunSmsClientTest.java`（Task 5，被 AliyunSmsAuthClientTest 取代）

---

### Task 1: Schema + 实体 + ErrorCode + pom 依赖基座

**Files:**
- Create: `sks-server/src/main/resources/db/migration/V3__sms_scene_and_phone_change.sql`
- Create: `sks-server/src/main/java/com/sks/user/PhoneChangeSession.java`
- Create: `sks-server/src/main/java/com/sks/user/PhoneChangeSessionMapper.java`
- Create: `sks-server/src/test/java/com/sks/user/PhoneChangeSessionMapperTest.java`
- Modify: `sks-server/src/main/java/com/sks/auth/SmsCode.java`（加 scene/sessionToken）
- Modify: `sks-server/src/main/java/com/sks/common/ErrorCode.java`（加 4007/4008）
- Modify: `sks-server/src/main/java/com/sks/user/AppUserMapper.java`（加 updatePhone）
- Modify: `sks-server/pom.xml`（加 dypnsapi20170525:2.0.0 + spring-boot-starter-mail，**保留 dysmsapi** 待 Task 5 删）

**Interfaces:**
- Produces: `PhoneChangeSession` 实体（id/token/userId/oldPhone/newPhone/status/oldVerifiedAt/expiresAt/createdAt）、`PhoneChangeSessionMapper`（`findByToken`/`deleteActiveByUserId`/`updateToNewVerify`/`updateNewPhone`/`markDone`）、`SmsCode.scene`/`sessionToken` 字段、`ErrorCode.PHONE_ALREADY_BOUND(4007)`/`PHONE_CHANGE_TOKEN_INVALID(4008)`、`AppUserMapper.updatePhone(userId, phone)`。

- [ ] **Step 1: Write the failing test** — `PhoneChangeSessionMapperTest`（extends AbstractDbTest，验证 V3 表 + 实体 + mapper + 部分唯一索引）

```java
package com.sks.user;

import static org.junit.jupiter.api.Assertions.*;

import com.sks.AbstractDbTest;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PhoneChangeSessionMapperTest extends AbstractDbTest {

    @Autowired PhoneChangeSessionMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private PhoneChangeSession newSession(String token, long userId, String oldPhone) {
        PhoneChangeSession s = new PhoneChangeSession();
        s.setToken(token);
        s.setUserId(userId);
        s.setOldPhone(oldPhone);
        s.setStatus("AWAITING_OLD_VERIFY");
        s.setExpiresAt(OffsetDateTime.now().plusMinutes(10));
        return s;
    }

    @Test
    void insertAndFindByToken() {
        long uid = ensureUser("13900000001");
        mapper.insert(newSession("tok-1", uid, "13900000001"));
        PhoneChangeSession s = mapper.findByToken("tok-1");
        assertNotNull(s);
        assertEquals(uid, s.getUserId());
        assertEquals("AWAITING_OLD_VERIFY", s.getStatus());
    }

    @Test
    void deleteActiveByUserIdRemovesNonDoneThenInsertSucceeds() {
        long uid = ensureUser("13900000002");
        mapper.insert(newSession("tok-2", uid, "13900000002"));
        // 重入：先删旧行再建新行（不撞部分唯一索引）
        assertEquals(1, mapper.deleteActiveByUserId(uid));
        mapper.insert(newSession("tok-3", uid, "13900000002"));
        assertNotNull(mapper.findByToken("tok-3"));
    }

    @Test
    void partialUniqueIndexBlocksSecondActiveSession() {
        long uid = ensureUser("13900000003");
        mapper.insert(newSession("tok-4", uid, "13900000003"));
        // 不先删直接建第二行活跃 session → 撞 UNIQUE(user_id) WHERE status<>'DONE'
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> mapper.insert(newSession("tok-5", uid, "13900000003")));
    }

    private long ensureUser(String phone) {
        return jdbc.queryForObject(
                "INSERT INTO app_user(phone) VALUES (?) ON CONFLICT (phone) DO UPDATE SET phone=EXCLUDED.phone RETURNING id",
                Long.class, phone);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sks-server && ./mvnw test -Dtest=PhoneChangeSessionMapperTest`
Expected: FAIL — `PhoneChangeSession` / `PhoneChangeSessionMapper` 不存在（编译失败）。

- [ ] **Step 3: Write Flyway V3 migration**

`sks-server/src/main/resources/db/migration/V3__sms_scene_and_phone_change.sql`:
```sql
-- sms_code scene 化 + 换绑 session 关联
ALTER TABLE sms_code
  ADD COLUMN scene VARCHAR(32) NOT NULL DEFAULT 'LOGIN_REGISTER',
  ADD COLUMN session_token VARCHAR(64);

-- 换绑手机号 2-step flow 状态表
CREATE TABLE phone_change_session (
  id BIGSERIAL PRIMARY KEY,
  token VARCHAR(64) UNIQUE NOT NULL,
  user_id BIGINT NOT NULL REFERENCES app_user(id),
  old_phone VARCHAR(20) NOT NULL,
  new_phone VARCHAR(20),
  status VARCHAR(32) NOT NULL,        -- AWAITING_OLD_VERIFY / AWAITING_NEW_VERIFY / DONE
  old_verified_at TIMESTAMPTZ,
  expires_at TIMESTAMPTZ NOT NULL,     -- 建行 now()+10min；verify-old 通过时重置 now()+10min
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- 同一用户同时只允许一个未完成 session（先删后建；并发兜底）
CREATE UNIQUE INDEX uq_phone_change_active ON phone_change_session(user_id) WHERE status <> 'DONE';
```

- [ ] **Step 4: Write `PhoneChangeSession` entity**

`sks-server/src/main/java/com/sks/user/PhoneChangeSession.java`:
```java
package com.sks.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/** 换绑手机号 2-step flow 的 session 状态（表 {@code phone_change_session}）。 */
@TableName("phone_change_session")
public class PhoneChangeSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String token;
    private Long userId;
    private String oldPhone;
    private String newPhone;
    private String status;
    private OffsetDateTime oldVerifiedAt;
    private OffsetDateTime expiresAt;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getOldPhone() { return oldPhone; }
    public void setOldPhone(String oldPhone) { this.oldPhone = oldPhone; }
    public String getNewPhone() { return newPhone; }
    public void setNewPhone(String newPhone) { this.newPhone = newPhone; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getOldVerifiedAt() { return oldVerifiedAt; }
    public void setOldVerifiedAt(OffsetDateTime oldVerifiedAt) { this.oldVerifiedAt = oldVerifiedAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 5: Write `PhoneChangeSessionMapper`**

`sks-server/src/main/java/com/sks/user/PhoneChangeSessionMapper.java`:
```java
package com.sks.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

/** phone_change_session 的 Mapper。 */
@Mapper
public interface PhoneChangeSessionMapper extends BaseMapper<PhoneChangeSession> {

    @Select("SELECT * FROM phone_change_session WHERE token = #{token}")
    PhoneChangeSession findByToken(String token);

    /** send-old-code 重入时先删未完成 session（不标 DONE，避免污染语义）。 */
    @Delete("DELETE FROM phone_change_session WHERE user_id = #{userId} AND status <> 'DONE'")
    int deleteActiveByUserId(Long userId);

    /** verify-old 对码：置 AWAITING_NEW_VERIFY + old_verified_at + 重置 expires_at。 */
    @Update("UPDATE phone_change_session SET status='AWAITING_NEW_VERIFY', "
            + "old_verified_at=#{oldVerifiedAt}, expires_at=#{expiresAt} WHERE id=#{id}")
    int updateToNewVerify(@Param("id") Long id,
                           @Param("oldVerifiedAt") OffsetDateTime oldVerifiedAt,
                           @Param("expiresAt") OffsetDateTime expiresAt);

    /** send-new-code：落 new_phone。 */
    @Update("UPDATE phone_change_session SET new_phone=#{newPhone} WHERE id=#{id}")
    int updateNewPhone(@Param("id") Long id, @Param("newPhone") String newPhone);

    /** verify-new 成功：置 DONE。 */
    @Update("UPDATE phone_change_session SET status='DONE' WHERE id=#{id}")
    int markDone(Long id);
}
```

- [ ] **Step 6: Add scene/sessionToken to `SmsCode` entity**

在 `SmsCode.java` 加两个字段 + getter/setter（在 `used` 字段后、`createdAt` 前）：
```java
    private String scene;
    private String sessionToken;
```
及对应 getter/setter：
```java
    public String getScene() { return scene; }
    public void setScene(String scene) { this.scene = scene; }
    public String getSessionToken() { return sessionToken; }
    public void setSessionToken(String sessionToken) { this.sessionToken = sessionToken; }
```

- [ ] **Step 7: Add ErrorCode 4007/4008**

在 `ErrorCode.java` 的 `SMS_SEND_FAILED(5003, ...)` 行**之前**插入（40xx 段）：
```java
    PHONE_ALREADY_BOUND(4007, "该手机号已被其他账号绑定"),
    PHONE_CHANGE_TOKEN_INVALID(4008, "换绑凭证无效或已过期，请重新发起"),
```
（即 `CARD_IN_USE(4006,...)` 之后、`UNAUTHORIZED(4010,...)` 之前。）

- [ ] **Step 8: Add `AppUserMapper.updatePhone`**

在 `AppUserMapper.java` 加：
```java
    /** verify-new 成功：更新用户绑定手机号（UNIQUE 约束兜底并发，冲突抛 DuplicateKeyException）。 */
    @org.apache.ibatis.annotations.Update(
            "UPDATE app_user SET phone = #{phone} WHERE id = #{userId}")
    int updatePhone(@Param("userId") Long userId, @Param("phone") String phone);
```
（文件已 import `@Param`、`@Select`；`@Update` 需补 import 或用全限定名如上。）

- [ ] **Step 9: Add pom dependencies**（保留 dysmsapi 待 Task 5 删）

在 `pom.xml` 的 dysmsapi 依赖**之后**加：
```xml
        <!-- 阿里云号码认证服务 DYPNS（短信认证 SendSmsVerifyCode，个人实名 + 赠送签名/模板） -->
        <dependency>
            <groupId>com.aliyun</groupId>
            <artifactId>dypnsapi20170525</artifactId>
            <version>2.0.0</version>
        </dependency>

        <!-- 邮件告警（MailAlertNotifier） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-mail</artifactId>
        </dependency>
```

- [ ] **Step 10: Run test to verify it passes**

Run: `cd sks-server && ./mvnw test -Dtest=PhoneChangeSessionMapperTest`
Expected: PASS（3 个测试全绿；V3 迁移在 AbstractDbTest 启动时跑，phone_change_session 表 + 部分唯一索引生效）。

- [ ] **Step 11: Smoke-run 既有 DbTest 确认 V3 不破现有迁移链**

Run: `cd sks-server && ./mvnw test -Dtest=AuthServiceTest`
Expected: PASS（V3 在启动时跑通，sms_code 加列 DEFAULT 'LOGIN_REGISTER' 回填存量行，AuthService 行为不变）。

- [ ] **Step 12: Commit**

```bash
cd sks-server && git add -A && git commit -m "feat(sms): V3 schema + PhoneChangeSession + ErrorCode 4007/4008 + pom 依赖基座"
```

---

### Task 2: SmsScene + SmsCodeMapper scene 化 + existsLocked + AuthService 适配

**Files:**
- Create: `sks-server/src/main/java/com/sks/common/SmsScene.java`（枚举前移到本任务，避免 Task 2-5 间 AuthService 带 magic string）
- Modify: `sks-server/src/main/java/com/sks/auth/SmsCodeMapper.java`
- Modify: `sks-server/src/main/java/com/sks/auth/AuthService.java`（checkLocked→existsLocked、login 传 `SmsScene.LOGIN_REGISTER.name()`）
- Create: `sks-server/src/test/java/com/sks/auth/SmsCodeMapperTest.java`
- (Task 5 才改 `AuthService.sendCode` 的 3 参签名；本任务只改 `login` 的 `findActiveCode` 调用 + `checkLocked` 逻辑。)

**Interfaces:**
- Produces: `SmsScene` 枚举（LOGIN_REGISTER/VERIFY_OLD_PHONE/BIND_NEW_PHONE）、`SmsCodeMapper.findActiveCode(String phone, String scene)`（替换旧 1 参版）、`SmsCodeMapper.existsLocked(String phone)`、`SmsCodeMapper.invalidateByToken(String token)`、`SmsCodeMapper.invalidateByPhones(String oldPhone, String newPhone)`。
- Consumes: `SmsCode.scene` 字段（Task 1）。

- [ ] **Step 1: Write the failing test** — `SmsCodeMapperTest`

```java
package com.sks.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.sks.AbstractDbTest;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** sms_code scene 化 + existsLocked 锁定判定（spec §3.0 Critical）。 */
class SmsCodeMapperTest extends AbstractDbTest {

    @Autowired SmsCodeMapper smsCodeMapper;
    @Autowired JdbcTemplate jdbc;

    private void insertCode(String phone, String scene, int errCount, String createdAtMinus) {
        jdbc.update(
                "INSERT INTO sms_code(phone, code, expire_at, err_count, used, scene, created_at) "
                        + "VALUES (?,?,now()+interval '5 min',?,false,?,now()-interval '"
                        + createdAtMinus + "')",
                phone, "123456", errCount, scene);
    }

    @Test
    void findActiveCodeFiltersByScene() {
        insertCode("13900000010", "LOGIN_REGISTER", 0, "10 second");
        insertCode("13900000010", "VERIFY_OLD_PHONE", 0, "5 second");
        // 取 LOGIN_REGISTER 的行，不混到 VERIFY_OLD_PHONE
        SmsCode login = smsCodeMapper.findActiveCode("13900000010", "LOGIN_REGISTER");
        assertNotNull(login);
        assertEquals("LOGIN_REGISTER", login.getScene());
        // 跨 scene 拿不到：VERIFY_OLD_PHONE 的码不能当登录码
        SmsCode change = smsCodeMapper.findActiveCode("13900000010", "VERIFY_OLD_PHONE");
        assertNotNull(change);
        assertNotEquals(login.getId(), change.getId());
    }

    @Test
    void existsLockedAnyRowAnyScene() {
        // 4 错 verify-old + 一条更近的 LOGIN_REGISTER 0 错码行 → 仍判锁（ANY-row，防稀释）
        insertCode("13900000011", "VERIFY_OLD_PHONE", 5, "8 minute");
        insertCode("13900000011", "LOGIN_REGISTER", 0, "1 minute");
        assertTrue(smsCodeMapper.existsLocked("13900000011"));
    }

    @Test
    void existsLockedFalseWhenNoFiveErr() {
        insertCode("13900000012", "LOGIN_REGISTER", 4, "1 minute");
        assertFalse(smsCodeMapper.existsLocked("13900000012"));
    }

    @Test
    void existsLockedFalseWhenExpiredLock() {
        // 5 错但 11 分钟前（>10min 锁定窗口）→ 不锁
        insertCode("13900000013", "LOGIN_REGISTER", 5, "11 minute");
        assertFalse(smsCodeMapper.existsLocked("13900000013"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sks-server && ./mvnw test -Dtest=SmsCodeMapperTest`
Expected: FAIL — `findActiveCode(String,String)` / `existsLocked` 不存在（编译失败）。

- [ ] **Step 3: Create `SmsScene` enum + Modify `SmsCodeMapper`** — scene 化 + existsLocked + invalidate

先建 `SmsScene` 枚举（spec §3.1；前移到本任务，避免 AuthService 带 magic string）：

`sks-server/src/main/java/com/sks/common/SmsScene.java`:
```java
package com.sks.common;

/** 短信场景（spec §3.1）：决定发送用哪个赠送模板。 */
public enum SmsScene {
    LOGIN_REGISTER,     // 登录/注册
    VERIFY_OLD_PHONE,   // 换绑 step1：验旧/当前号
    BIND_NEW_PHONE       // 换绑 step2：验新号
}
```

再把 `findActiveCode` 签名改为 `(String phone, String scene)`，加 `existsLocked`/`invalidateByToken`/`invalidateByPhones`。完整替换 `SmsCodeMapper` 为：
```java
package com.sks.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * sms_code 的 Mapper：三级频控滚动窗口聚合、最近码查询、err_count 自增、used 标记、scene 化比对、锁定判定。
 *
 * <p>频控查询保持按 phone 全局（不加 scene）；比对查询按 (phone, scene) 防跨 scene 码互用；
 * 锁定判定 existsLocked 取 ANY-row（任一 scene 锁 5 次则全号锁，防稀释绕过）。spec §3.0。
 */
@Mapper
public interface SmsCodeMapper extends BaseMapper<SmsCode> {

    @Select("SELECT COUNT(*) FROM sms_code WHERE phone = #{phone} "
            + "AND created_at >= now() - interval '1 minute'")
    long countLastMinute(String phone);

    @Select("SELECT COUNT(*) FROM sms_code WHERE phone = #{phone} "
            + "AND created_at >= now() - interval '1 hour'")
    long countLastHour(String phone);

    @Select("SELECT COUNT(*) FROM sms_code WHERE phone = #{phone} "
            + "AND created_at >= now() - interval '24 hours'")
    long countLast24Hours(String phone);

    @Select("SELECT * FROM sms_code WHERE phone = #{phone} ORDER BY created_at DESC LIMIT 1")
    SmsCode findMostRecent(String phone);

    /** 该手机号 + scene 最近一条未使用未过期码（防跨 scene 互用：登录码不能过 verify-old）。 */
    @Select("SELECT * FROM sms_code WHERE phone = #{phone} AND scene = #{scene} "
            + "AND used = false AND expire_at > now() ORDER BY created_at DESC LIMIT 1")
    SmsCode findActiveCode(@Param("phone") String phone, @Param("scene") String scene);

    /** 锁定判定（ANY-row）：任一 scene 的码 err_count>=5 且 10 分钟内 → 全号锁。 */
    @Select("SELECT EXISTS(SELECT 1 FROM sms_code WHERE phone = #{phone} "
            + "AND err_count >= 5 AND created_at > now() - interval '10 minute')")
    boolean existsLocked(String phone);

    @Update("UPDATE sms_code SET err_count = err_count + 1 WHERE id = #{id}")
    int incrementErrCount(Long id);

    @Update("UPDATE sms_code SET used = true WHERE id = #{id}")
    int markUsed(Long id);

    /** verify-new 成功：作废本次换绑的码（token 关联）。 */
    @Update("UPDATE sms_code SET used = true WHERE session_token = #{token} AND used = false")
    int invalidateByToken(String token);

    /** verify-new 成功：作废 old/new 号的 pending 码（同用户清理，防重放）。 */
    @Update("UPDATE sms_code SET used = true WHERE phone IN (#{oldPhone},#{newPhone}) AND used = false")
    int invalidateByPhones(@Param("oldPhone") String oldPhone, @Param("newPhone") String newPhone);
}
```

- [ ] **Step 4: Modify `AuthService`** — checkLocked 用 existsLocked + login 传 scene

在 `AuthService.java`：
- `checkLocked` 方法体改为：
```java
    private void checkLocked(String phone) {
        if (smsCodeMapper.existsLocked(phone)) {
            throw new BizException(ErrorCode.SMS_CODE_LOCKED);
        }
    }
```
（删掉原 `findMostRecent` + err_count/created_at 判定逻辑。`findMostRecent` 在 `login` 不再用；保留给测试 `realCodeOf`。）
- `login` 方法中 `SmsCode active = smsCodeMapper.findActiveCode(phone);` 改为：
```java
        SmsCode active = smsCodeMapper.findActiveCode(phone, SmsScene.LOGIN_REGISTER.name());
```
  并在文件顶部加 import：`import com.sks.common.SmsScene;`

- [ ] **Step 5: Run test to verify it passes**

Run: `cd sks-server && ./mvnw test -Dtest=SmsCodeMapperTest`
Expected: PASS（4 测试绿：scene 过滤、ANY-row 锁定、5 错未锁不判、过期锁不判）。

- [ ] **Step 6: Run AuthServiceTest 确认 scene 化 + existsLocked 单 scene 等价**

Run: `cd sks-server && ./mvnw test -Dtest=AuthServiceTest`
Expected: PASS（login 经 `findActiveCode(phone, SmsScene.LOGIN_REGISTER.name())` 仍查到 sendCode 写的码；existsLocked 单 scene 等价，锁定测试绿。**`sendCodeDelegatesToSmsClient` 此刻仍用 2 参 `sendVerificationCode` —— 它会在 Task 5 改 3 参后更新；本任务不动 sendCode。**）

- [ ] **Step 7: Commit**

```bash
cd sks-server && git add -A && git commit -m "refactor(sms): SmsCodeMapper scene 化 + existsLocked + AuthService 适配"
```

---

### Task 3: AlertNotifier + MailAlertNotifier

**Files:**
- Create: `sks-server/src/main/java/com/sks/common/AlertNotifier.java`
- Create: `sks-server/src/main/java/com/sks/common/MailAlertNotifier.java`
- Create: `sks-server/src/test/java/com/sks/common/MailAlertNotifierTest.java`
- Modify: `sks-server/src/main/resources/application.yml`（加 spring.mail + sks.alert）
- Modify: `deploy/GO_LIVE_CHECKLIST.md`（env 表加 SPRING_MAIL_* + SKS_ALERT_ADMIN_EMAIL）

**Interfaces:**
- Produces: `AlertNotifier.notify(String subject, String content)`、`MailAlertNotifier`（@Component，`ObjectProvider<JavaMailSender>`）。

- [ ] **Step 1: Write the failing test** — `MailAlertNotifierTest`

```java
package com.sks.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class MailAlertNotifierTest {

    private static MimeMessage dummyMsg() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    /** configured：ObjectProvider 返回 mock sender，host 非空。 */
    private static MailAlertNotifier configured(JavaMailSender mockSender) {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mockSender);
        return new MailAlertNotifier(provider, "smtp.example.com", "admin@example.com");
    }

    /** 未 configured：host 空。 */
    private static MailAlertNotifier unconfigured(JavaMailSender mockSender) {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mockSender);
        return new MailAlertNotifier(provider, "", "admin@example.com");
    }

    @Test
    void unconfiguredStubDoesNotSend() {
        JavaMailSender mock = mock(JavaMailSender.class);
        assertDoesNotThrow(() -> unconfigured(mock).notify("s", "c"));
        verifyNoInteractions(mock);
    }

    @Test
    void configuredSendsToAdmin() {
        JavaMailSender mock = mock(JavaMailSender.class);
        when(mock.createMimeMessage()).thenReturn(dummyMsg());
        configured(mock).notify("余额告警", "短信余额不足: 50条");
        verify(mock).send(org.mockito.ArgumentMatchers.any(jakarta.mail.internet.MimeMessage.class));
    }

    @Test
    void sendFailureIsSwallowed() {
        JavaMailSender mock = mock(JavaMailSender.class);
        when(mock.createMimeMessage()).thenReturn(dummyMsg());
        doThrow(new RuntimeException("smtp down")).when(mock)
                .send(any(jakarta.mail.internet.MimeMessage.class));
        assertDoesNotThrow(() -> configured(mock).notify("s", "c"));
    }

    @Test
    void noBeanStubDoesNotThrow() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        MailAlertNotifier n = new MailAlertNotifier(provider, "smtp.example.com", "admin@example.com");
        assertDoesNotThrow(() -> n.notify("s", "c"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sks-server && ./mvnw test -Dtest=MailAlertNotifierTest`
Expected: FAIL — `AlertNotifier`/`MailAlertNotifier` 不存在。

- [ ] **Step 3: Write `AlertNotifier` interface**

`sks-server/src/main/java/com/sks/common/AlertNotifier.java`:
```java
package com.sks.common;

/** 告警/站长通知 seam（spec §3.4）。实现 {@link MailAlertNotifier}：邮件 SMTP。 */
public interface AlertNotifier {
    /** 发告警。未 configured→stub 不抛；configured 但发送失败→吞掉不抛（不阻断主流程）。 */
    void notify(String subject, String content);
}
```

- [ ] **Step 4: Write `MailAlertNotifier`**

`sks-server/src/main/java/com/sks/common/MailAlertNotifier.java`:
```java
package com.sks.common;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * 邮件告警实现（spec §3.4）。
 *
 * <p>条件降级：{@code spring.mail.host} 空 / 无 {@link JavaMailSender} bean → stub（log + no-op，不抛）。
 * 用 {@link ObjectProvider} 取 sender：host 为空字符串时 Spring Boot 的 MailSender 自动配置处于边缘态，
 * 别赌 bean 一定存在。失败 → log.warn 吞掉（告警失败不阻断主流程）。
 */
@Component
public class MailAlertNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(MailAlertNotifier.class);

    private final ObjectProvider<JavaMailSender> senderProvider;
    private final String host;
    private final String adminEmail;

    public MailAlertNotifier(
            ObjectProvider<JavaMailSender> senderProvider,
            @Value("${spring.mail.host:}") String host,
            @Value("${sks.alert.admin-email:}") String adminEmail) {
        this.senderProvider = senderProvider;
        this.host = host;
        this.adminEmail = adminEmail;
    }

    @Override
    public void notify(String subject, String content) {
        if (!isPresent(host) || !isPresent(adminEmail)) {
            log.info("[ALERT-STUB] subject={} content={} (no spring.mail.host / admin-email)", subject, content);
            return;
        }
        JavaMailSender sender = senderProvider.getIfAvailable();
        if (sender == null) {
            log.info("[ALERT-STUB] no JavaMailSender bean; subject={} content={}", subject, content);
            return;
        }
        try {
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, "utf-8");
            helper.setTo(adminEmail);
            helper.setSubject(subject);
            helper.setText(content, false);
            sender.send(msg);
        } catch (Exception e) {
            log.warn("MailAlert notify failed: subject={}: {}", subject, e.getMessage());
        }
    }

    private static boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd sks-server && ./mvnw test -Dtest=MailAlertNotifierTest`
Expected: PASS（4 测试绿）。

- [ ] **Step 6: Add `spring.mail` + `sks.alert` to application.yml**

在 `application.yml` 的 `spring:` 块内加 `mail:`（与 `datasource:` 平级）；在 `sks:` 块内加 `alert:`：
```yaml
spring:
  # ... 既有 datasource / servlet / flyway ...
  mail:
    host: ${SPRING_MAIL_HOST:}
    port: ${SPRING_MAIL_PORT:465}
    username: ${SPRING_MAIL_USERNAME:}
    password: ${SPRING_MAIL_PASSWORD:}
    properties:                              # 465 = SMTPS，必须显式开 SSL（默认明文会挂起/隐晦失败）
      mail.smtp.ssl.enable: true
      # 若改 587 则用 mail.smtp.starttls.enable: true 并去掉 ssl.enable
```
```yaml
sks:
  # ... 既有 trial-credit / ai / service-token / review / quota / sms ...
  alert:
    admin-email: ${SKS_ALERT_ADMIN_EMAIL:}
```

- [ ] **Step 7: Update `deploy/GO_LIVE_CHECKLIST.md` env 表**

在 §1 env 表加 4 行（SPRING_MAIL_HOST/PORT/USERNAME/PASSWORD）+ SKS_ALERT_ADMIN_EMAIL。具体：找到 `ALIYUN_SMS_SIGN` 行附近，追加：
```
| `SPRING_MAIL_HOST` | ❌ 空 | **联调必填**：SMTP 主机（告警邮件通道） |
| `SPRING_MAIL_PORT` | 465 | SMTPS 端口（465 需 ssl.enable=true，已配） |
| `SPRING_MAIL_USERNAME` | ❌ 空 | **联调必填**：SMTP 账号 |
| `SPRING_MAIL_PASSWORD` | ❌ 空 | **联调必填**：SMTP 授权码（密钥，入 .env） |
| `SKS_ALERT_ADMIN_EMAIL` | ❌ 空 | **联调必填**：站长告警收件邮箱 |
```

- [ ] **Step 8: Run full test suite (regression)**

Run: `cd sks-server && ./mvnw test`
Expected: PASS（新增 MailAlertNotifierTest + 既有全绿；MailAlertNotifier 作为 @Component 在 Spring 上下文里 host 空→stub，不破其他测试）。

- [ ] **Step 9: Commit**

```bash
cd sks-server && git add -A && cd .. && git add deploy/GO_LIVE_CHECKLIST.md
git commit -m "feat(alert): AlertNotifier + MailAlertNotifier（邮件告警，ObjectProvider + SSL）"
```

---

### Task 4: QuotaWatchJob 告警迁移 SmsClient→AlertNotifier

**Files:**
- Modify: `sks-server/src/main/java/com/sks/common/QuotaWatchJob.java`
- Modify: `sks-server/src/test/java/com/sks/common/QuotaWatchJobTest.java`
- Modify: `sks-server/src/main/resources/application.yml`（删 `sks.quota.admin-phone`）
- Modify: `deploy/GO_LIVE_CHECKLIST.md`（删/改 `SKS_QUOTA_ADMIN_PHONE` 行）

**Interfaces:**
- Consumes: `AlertNotifier`（Task 3）。
- Produces: `QuotaWatchJob(smsThreshold, glmThreshold, AlertNotifier)`（删 adminPhone + SmsClient）；`sendAlert(reason)` → `alertNotifier.notify("SKS 余额告警", reason)`。

- [ ] **Step 1: Write the failing test** — 修改 `QuotaWatchJobTest`

把 `private SmsClient smsClient;` + `@BeforeEach` + `newJob()` 改为注入 `AlertNotifier`，`sendAlertDelegatesToSmsClient` 改名 `sendAlertDelegatesToAlertNotifier`：
```java
package com.sks.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuotaWatchJobTest {

    private static final int SMS_THRESHOLD = 100;
    private static final int GLM_THRESHOLD = 20;

    private AlertNotifier alertNotifier;

    @BeforeEach
    void setUp() {
        alertNotifier = mock(AlertNotifier.class);
    }

    private QuotaWatchJob newJob() {
        return new QuotaWatchJob(SMS_THRESHOLD, GLM_THRESHOLD, alertNotifier);
    }

    @Test
    void bothBelowThresholdAlertsBoth() {
        List<String> reasons = newJob().checkAndAlert(Optional.of(50), Optional.of(10));
        assertEquals(2, reasons.size());
        assertTrue(reasons.get(0).contains("短信"));
        assertTrue(reasons.get(1).contains("GLM"));
    }

    @Test
    void bothAboveThresholdNoAlert() {
        assertTrue(newJob().checkAndAlert(Optional.of(200), Optional.of(30)).isEmpty());
    }

    @Test
    void exactlyAtThresholdNoAlert() {
        assertTrue(newJob().checkAndAlert(Optional.of(SMS_THRESHOLD), Optional.of(GLM_THRESHOLD)).isEmpty());
    }

    @Test
    void oneQueryFailedStillAlertsOtherIfLow() {
        List<String> reasons = newJob().checkAndAlert(Optional.empty(), Optional.of(5));
        assertEquals(1, reasons.size());
        assertTrue(reasons.get(0).contains("GLM"));
    }

    @Test
    void oneQueryFailedOtherOkNoAlert() {
        assertTrue(newJob().checkAndAlert(Optional.empty(), Optional.of(200)).isEmpty());
    }

    @Test
    void bothQueriesFailedNoAlert() {
        assertTrue(newJob().checkAndAlert(Optional.empty(), Optional.empty()).isEmpty());
    }

    @Test
    void oneBelowOneAtThresholdAlertsOnlyLow() {
        List<String> reasons = newJob().checkAndAlert(Optional.of(99), Optional.of(GLM_THRESHOLD));
        assertEquals(1, reasons.size());
        assertTrue(reasons.get(0).contains("短信"));
    }

    @Test
    void sendAlertDelegatesToAlertNotifier() {
        newJob().sendAlert("短信余额不足: 50条");
        verify(alertNotifier).notify(eq("SKS 余额告警"), eq("短信余额不足: 50条"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sks-server && ./mvnw test -Dtest=QuotaWatchJobTest`
Expected: FAIL — `QuotaWatchJob(int,int,AlertNotifier)` 构造不存在（仍是 4 参 SmsClient 版）。

- [ ] **Step 3: Modify `QuotaWatchJob`** — 注入换 AlertNotifier + 删 adminPhone

字段 + 构造改为：
```java
    private final int smsThreshold;
    private final int glmThreshold;
    private final AlertNotifier alertNotifier;

    public QuotaWatchJob(
            @Value("${sks.quota.sms-threshold:100}") int smsThreshold,
            @Value("${sks.quota.glm-threshold:20}") int glmThreshold,
            AlertNotifier alertNotifier) {
        this.smsThreshold = smsThreshold;
        this.glmThreshold = glmThreshold;
        this.alertNotifier = alertNotifier;
    }
```
删 `private final String adminPhone;` 字段 + 其 `@Value` 参数 + `this.adminPhone = ...`。删 `import com.sks.common.SmsClient` 依赖（不再用 SmsClient）。

`sendAlert` 方法体改为：
```java
    protected void sendAlert(String reason) {
        alertNotifier.notify("SKS 余额告警", reason);
    }
```
更新类 javadoc：`sendAlert` 注释从「经 SmsClient seam 发告警到 admin-phone」改为「经 AlertNotifier 发邮件到站长（host 空→stub）」。

- [ ] **Step 4: Run test to verify it passes**

Run: `cd sks-server && ./mvnw test -Dtest=QuotaWatchJobTest`
Expected: PASS（8 测试绿，含 `sendAlertDelegatesToAlertNotifier`）。

- [ ] **Step 5: Remove `sks.quota.admin-phone` from application.yml**

在 `application.yml` 的 `sks.quota:` 块删 `admin-phone: ${SKS_QUOTA_ADMIN_PHONE:}` 行（及注释）。保留 `sms-threshold` / `glm-threshold`。

- [ ] **Step 6: Update GO_LIVE_CHECKLIST env 表**

把 `| SKS_QUOTA_ADMIN_PHONE | ... |` 行改为 `SKS_ALERT_ADMIN_EMAIL`（若 Task 3 已加则删本行去重），或直接删（告警收件人现走 `SKS_ALERT_ADMIN_EMAIL`）。

- [ ] **Step 7: Run full suite (regression)**

Run: `cd sks-server && ./mvnw test`
Expected: PASS（QuotaWatchJob 改注入 AlertNotifier，Spring 上下文自动 wire MailAlertNotifier bean；其他测试不破）。

- [ ] **Step 8: Commit**

```bash
cd sks-server && git add -A && cd .. && git add deploy/GO_LIVE_CHECKLIST.md
git commit -m "refactor(quota): QuotaWatchJob 告警改走 AlertNotifier（邮件），删 adminPhone"
```

---

### Task 5: SmsScene + SmsClient 3 参 + AliyunSmsAuthClient（替换 dysmsapi 版）

**Files:**
-（`SmsScene.java` 已在 Task 2 建，本任务不重复创建）
- Create: `sks-server/src/main/java/com/sks/common/AliyunSmsAuthClient.java`
- Create: `sks-server/src/test/java/com/sks/common/AliyunSmsAuthClientTest.java`
- Modify: `sks-server/src/main/java/com/sks/common/SmsClient.java`（3 参 + 删 sendAlert）
- Modify: `sks-server/src/main/java/com/sks/auth/AuthService.java`（sendCode 传 scene）
- Modify: `sks-server/src/main/java/com/sks/auth/AuthController.java`（无，sendCode 入参不变）
- Modify: `sks-server/src/test/java/com/sks/auth/AuthServiceTest.java`（sendCode 断言 3 参）
- Modify: `sks-server/src/main/resources/application.yml`（sms 块改 3 template）
- Modify: `sks-server/pom.xml`（删 dysmsapi20170525）
- Delete: `sks-server/src/main/java/com/sks/common/AliyunSmsClient.java`
- Delete: `sks-server/src/test/java/com/sks/common/AliyunSmsClientTest.java`

**Interfaces:**
- Produces: `SmsScene` 枚举（LOGIN_REGISTER/VERIFY_OLD_PHONE/BIND_NEW_PHONE）、`SmsClient.sendVerificationCode(phone, code, scene)`（删 sendAlert）、`AliyunSmsAuthClient`。

- [ ] **Step 1: Write the failing test** — `AliyunSmsAuthClientTest`

```java
package com.sks.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponseBody;
import com.aliyun.teautil.models.RuntimeOptions;
import org.junit.jupiter.api.Test;

/** AliyunSmsAuthClient 单测——mock dypnsapi Client，不真打阿里云。spec §7。 */
class AliyunSmsAuthClientTest {

    private static AliyunSmsAuthClient newConfigured(Client mock) {
        AliyunSmsAuthClient c = new AliyunSmsAuthClient(
                "ak", "sk", "dypnsapi.aliyuncs.com", "速通互联验证码",
                "100002", "100003", "100004");
        c.setDelegate(mock);
        return c;
    }

    private static AliyunSmsAuthClient newUnconfigured() {
        return new AliyunSmsAuthClient("", "", "dypnsapi.aliyuncs.com", "", "", "", "");
    }

    private static SendSmsVerifyCodeResponse resp(String code) {
        SendSmsVerifyCodeResponse r = new SendSmsVerifyCodeResponse();
        SendSmsVerifyCodeResponseBody body = new SendSmsVerifyCodeResponseBody();
        body.setCode(code);
        body.setMessage("msg");
        r.setBody(body);
        return r;
    }

    @Test
    void unconfiguredStubDoesNotThrowOrSend() {
        assertDoesNotThrow(() -> newUnconfigured()
                .sendVerificationCode("13900000000", "123456", SmsScene.LOGIN_REGISTER));
    }

    @Test
    void configuredOkDoesNotThrow() throws Exception {
        Client mock = mock(Client.class);
        when(mock.sendSmsVerifyCodeWithOptions(any(), any(RuntimeOptions.class))).thenReturn(resp("OK"));
        AliyunSmsAuthClient c = newConfigured(mock);
        assertDoesNotThrow(() -> c.sendVerificationCode("13900000000", "123456", SmsScene.LOGIN_REGISTER));
        verify(mock).sendSmsVerifyCodeWithOptions(any(SendSmsVerifyCodeRequest.class), any());
    }

    @Test
    void configuredNonOkThrowsSendFailed() throws Exception {
        Client mock = mock(Client.class);
        when(mock.sendSmsVerifyCodeWithOptions(any(), any())).thenReturn(resp("isv.BUSINESS_LIMIT_CONTROL"));
        BizException e = assertThrows(BizException.class,
                () -> newConfigured(mock).sendVerificationCode("13900000000", "123456", SmsScene.LOGIN_REGISTER));
        assertEquals(ErrorCode.SMS_SEND_FAILED, e.errorCode());
    }

    @Test
    void configuredExceptionThrowsSendFailed() throws Exception {
        Client mock = mock(Client.class);
        when(mock.sendSmsVerifyCodeWithOptions(any(), any())).thenThrow(new RuntimeException("timeout"));
        assertThrows(BizException.class,
                () -> newConfigured(mock).sendVerificationCode("13900000000", "123456", SmsScene.LOGIN_REGISTER));
    }

    @Test
    void sceneMapsToTemplateCode() throws Exception {
        Client mock = mock(Client.class);
        when(mock.sendSmsVerifyCodeWithOptions(any(), any())).thenReturn(resp("OK"));
        AliyunSmsAuthClient c = newConfigured(mock);
        c.sendVerificationCode("13900000000", "123456", SmsScene.VERIFY_OLD_PHONE);
        c.sendVerificationCode("13900000001", "654321", SmsScene.BIND_NEW_PHONE);
        // ArgumentCaptor 验证两次调用分别带 100003 / 100004
        org.mockito.ArgumentCaptor<SendSmsVerifyCodeRequest> cap =
                org.mockito.ArgumentCaptor.forClass(SendSmsVerifyCodeRequest.class);
        verify(mock, times(2)).sendSmsVerifyCodeWithOptions(cap.capture(), any());
        assertEquals("100003", cap.getAllValues().get(0).getTemplateCode());
        assertEquals("100004", cap.getAllValues().get(1).getTemplateCode());
        // TemplateParam 含 code + min
        assertTrue(cap.getValue().getTemplateParam().contains("\"code\":\"123456\""));
        assertTrue(cap.getValue().getTemplateParam().contains("\"min\":\"5\""));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sks-server && ./mvnw test -Dtest=AliyunSmsAuthClientTest`
Expected: FAIL — `AliyunSmsAuthClient` / `SmsScene` 不存在。

- [ ] **Step 3: `SmsScene` enum** — 已在 Task 2 Step 3 创建，本任务跳过（Task 5 只需 `import com.sks.common.SmsScene`，已在 Task 2 加）。

- [ ] **Step 4: Rewrite `SmsClient` interface** — 3 参 + 删 sendAlert

`sks-server/src/main/java/com/sks/common/SmsClient.java`（整体替换）：
```java
package com.sks.common;

/**
 * 短信发送 seam（DYPNS 短信认证，spec §3.2）。
 *
 * <p>实现 {@link AliyunSmsAuthClient}：条件降级（key/template 空 → stub 不抛）+ 真 SendSmsVerifyCode
 * （字面码模式，失败抛 {@link BizException}({@link ErrorCode#SMS_SEND_FAILED})）。
 * 告警不在此 seam —— 走 {@link AlertNotifier}（邮件）。
 */
public interface SmsClient {
    /** 发验证码（按 scene 选模板）。key 未配置→no-op（stub）；configured 但失败→抛 SMS_SEND_FAILED。 */
    void sendVerificationCode(String phone, String code, SmsScene scene);
}
```

- [ ] **Step 5: Write `AliyunSmsAuthClient`**

`sks-server/src/main/java/com/sks/common/AliyunSmsAuthClient.java`:
```java
package com.sks.common;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 阿里云 DYPNS 短信认证实现（spec §3.3，替换 dysmsapi 版 AliyunSmsClient）。
 *
 * <p>字面码模式：{@code TemplateParam={"code":"<6>","min":"5"}}，阿里云只发不核验，Java 自生成码 + 自比对
 * （不调 CheckSmsVerifyCode）。条件降级：access-key/secret/sign + 对应 scene 模板任一空 → stub 不抛；
 * 齐全 → 真 SendSmsVerifyCode，失败（body.Code≠OK 或异常）抛 SMS_SEND_FAILED。懒构造 Client；setDelegate 测试 seam。
 */
@Component
public class AliyunSmsAuthClient implements SmsClient {

    private static final Logger log = LoggerFactory.getLogger(AliyunSmsAuthClient.class);

    private final String accessKeyId;
    private final String accessKeySecret;
    private final String endpoint;
    private final String signName;
    private final String templateLogin;
    private final String templateVerifyOld;
    private final String templateBindNew;

    private Client override;
    private Client built;

    public AliyunSmsAuthClient(
            @Value("${sks.sms.access-key-id:}") String accessKeyId,
            @Value("${sks.sms.access-key-secret:}") String accessKeySecret,
            @Value("${sks.sms.endpoint:dypnsapi.aliyuncs.com}") String endpoint,
            @Value("${sks.sms.sign-name:}") String signName,
            @Value("${sks.sms.template-login:}") String templateLogin,
            @Value("${sks.sms.template-verify-old:}") String templateVerifyOld,
            @Value("${sks.sms.template-bind-new:}") String templateBindNew) {
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
        this.endpoint = endpoint;
        this.signName = signName;
        this.templateLogin = templateLogin;
        this.templateVerifyOld = templateVerifyOld;
        this.templateBindNew = templateBindNew;
    }

    @Override
    public void sendVerificationCode(String phone, String code, SmsScene scene) {
        String templateCode = templateFor(scene);
        if (!configured(templateCode)) {
            log.info("[SMS-STUB] scene={} phone={} code={} (no ALIYUN key/template)", scene, phone, code);
            return;
        }
        send(phone, templateCode, code);
    }

    private void send(String phone, String templateCode, String code) {
        SendSmsVerifyCodeRequest req = new SendSmsVerifyCodeRequest()
                .setPhoneNumber(phone)
                .setSignName(signName)
                .setTemplateCode(templateCode)
                .setTemplateParam("{\"code\":\"" + code + "\",\"min\":\"5\"}")
                .setCodeLength("6")
                .setCodeType(1)
                .setInterval(60);
        try {
            SendSmsVerifyCodeResponse resp = delegate().sendSmsVerifyCodeWithOptions(req, new RuntimeOptions());
            String c = resp.getBody().getCode();
            if (!"OK".equals(c)) {
                log.warn("AliyunSmsAuth send failed: phone={}, code={}, msg={}",
                        phone, c, resp.getBody().getMessage());
                throw new BizException(ErrorCode.SMS_SEND_FAILED);
            }
            log.info("AliyunSmsAuth sent: phone={}, bizId={}", phone,
                    resp.getBody().getModel() == null ? null : resp.getBody().getModel().getBizId());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AliyunSmsAuth send exception: phone={}: {}", phone, e.getMessage());
            throw new BizException(ErrorCode.SMS_SEND_FAILED);
        }
    }

    private String templateFor(SmsScene scene) {
        return switch (scene) {
            case LOGIN_REGISTER -> templateLogin;
            case VERIFY_OLD_PHONE -> templateVerifyOld;
            case BIND_NEW_PHONE -> templateBindNew;
        };
    }

    private boolean configured(String templateCode) {
        return isPresent(accessKeyId) && isPresent(accessKeySecret)
                && isPresent(signName) && isPresent(templateCode);
    }

    private static boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }

    private Client delegate() {
        if (override != null) {
            return override;
        }
        if (built == null) {
            Config config = new Config()
                    .setAccessKeyId(accessKeyId)
                    .setAccessKeySecret(accessKeySecret);
            config.endpoint = endpoint;
            try {
                built = new Client(config);
            } catch (Exception e) {
                throw new BizException(ErrorCode.SMS_SEND_FAILED);
            }
        }
        return built;
    }

    /** 测试 seam：注入 mock Client，跳过懒构建。 */
    void setDelegate(Client delegate) {
        this.override = delegate;
    }
}
```

- [ ] **Step 6: Delete old `AliyunSmsClient.java` + `AliyunSmsClientTest.java`**

```bash
cd sks-server && git rm src/main/java/com/sks/common/AliyunSmsClient.java \
  src/test/java/com/sks/common/AliyunSmsClientTest.java
```

- [ ] **Step 7: Modify `AuthService`** — sendCode 传 scene

在 `AuthService.java`：
- `sendCode` 末尾 `smsClient.sendVerificationCode(phone, code);` 改为：
```java
        smsClient.sendVerificationCode(phone, code, SmsScene.LOGIN_REGISTER);
```
  （`import com.sks.common.SmsScene` 已在 Task 2 加；`login` 已在 Task 2 用 `SmsScene.LOGIN_REGISTER.name()`，本任务不动 login。）
- sendCode javadoc「经 SmsClient seam 发送」补「scene=LOGIN_REGISTER」。

- [ ] **Step 8: Modify `AuthServiceTest`** — sendCode 断言 3 参

找到 `verify(smsClient).sendVerificationCode(eq(phone), eq(row.getCode()));` 改为：
```java
        verify(smsClient).sendVerificationCode(eq(phone), eq(row.getCode()), eq(SmsScene.LOGIN_REGISTER));
```
加 import：`import com.sks.common.SmsScene;` + `import static org.mockito.ArgumentMatchers.eq;`（后者已有则跳过）。

- [ ] **Step 9: Update application.yml sms block** — 3 template 替换 verify/alert

把 `sks.sms:` 块的 `verify-template-code` + `alert-template-code` 两行替换为：
```yaml
  sms:
    access-key-id: ${ALIYUN_ACCESS_KEY_ID:}
    access-key-secret: ${ALIYUN_ACCESS_KEY_SECRET:}
    endpoint: ${ALIYUN_SMS_ENDPOINT:dypnsapi.aliyuncs.com}    # DYPNS（非 dysmsapi）
    sign-name: ${ALIYUN_SMS_SIGN:}                             # 赠送签名（如 速通互联验证码）
    template-login: ${ALIYUN_SMS_TEMPLATE_LOGIN:}              # 登录/注册赠送模板（如 100002）
    template-verify-old: ${ALIYUN_SMS_TEMPLATE_VERIFY_OLD:}   # 换绑验旧号赠送模板
    template-bind-new: ${ALIYUN_SMS_TEMPLATE_BIND_NEW:}       # 换绑验新号赠送模板
```

- [ ] **Step 10: Remove dysmsapi from pom**

删 `pom.xml` 中 `com.aliyun:dysmsapi20170525:4.6.0` 依赖块（dypnsapi20170525:2.0.0 在 Task 1 已加）。

- [ ] **Step 11: Run AliyunSmsAuthClientTest + AuthServiceTest**

Run: `cd sks-server && ./mvnw test -Dtest=AliyunSmsAuthClientTest,AuthServiceTest`
Expected: PASS（AliyunSmsAuthClientTest 5 测试绿；AuthServiceTest sendCode 3 参断言 + login scene 化绿）。

- [ ] **Step 12: Run full suite (regression)**

Run: `cd sks-server && ./mvnw test`
Expected: PASS（dysmsapi 删除后无残留引用；AliyunSmsClient 已删，QuotaWatchJob 已不用 SmsClient，AuthService 用 3 参；全绿）。

- [ ] **Step 13: Update `.env` + GO_LIVE_CHECKLIST env（DYPNS template key）**

- `.env`：删 `ALIYUN_SMS_VERIFY_TEMPLATE=` + `ALIYUN_SMS_ALERT_TEMPLATE=`；加：
```
ALIYUN_SMS_ENDPOINT=dypnsapi.aliyuncs.com
ALIYUN_SMS_TEMPLATE_LOGIN=100002
ALIYUN_SMS_TEMPLATE_VERIFY_OLD=
ALIYUN_SMS_TEMPLATE_BIND_NEW=
```
（.env 是 gitignored，**不提交**，仅本地改。）
- `deploy/GO_LIVE_CHECKLIST.md` env 表：`ALIYUN_SMS_VERIFY_TEMPLATE` 行改为 `ALIYUN_SMS_TEMPLATE_LOGIN`（值 100002），删 `ALIYUN_SMS_ALERT_TEMPLATE` 行，加 `ALIYUN_SMS_TEMPLATE_VERIFY_OLD` + `ALIYUN_SMS_TEMPLATE_BIND_NEW` 两行（联调必填空）。

- [ ] **Step 14: Commit**

```bash
cd sks-server && git add -A && cd .. && git add deploy/GO_LIVE_CHECKLIST.md
git commit -m "feat(sms): DYPNS AliyunSmsAuthClient + SmsScene 3-scene，删 dysmsapi 版 AliyunSmsClient"
```

---

### Task 6: 换绑 flow step 1+2（send-old-code / verify-old）

**Files:**
- Create: `sks-server/src/main/java/com/sks/user/UserPhoneService.java`（先建 sendOldPhoneCode/verifyOldPhone）
- Create: `sks-server/src/main/java/com/sks/user/UserPhoneController.java`（先建 send-old-code/verify-old 端点）
- Create: `sks-server/src/test/java/com/sks/user/UserPhoneServiceTest.java`（先建 step1+2 用例）

**Interfaces:**
- Consumes: `SmsClient.sendVerificationCode(phone, code, scene)`（Task 5）、`SmsCodeMapper.findActiveCode(phone,scene)`/`existsLocked`/`incrementErrCount`/`markUsed`（Task 2）、`PhoneChangeSessionMapper`（Task 1）、`AppUserMapper.findByPhone`/`updatePhone`（Task 1）、`ErrorCode`。
- Produces: `UserPhoneService.sendOldPhoneCode(long userId)`、`UserPhoneService.verifyOldPhone(long userId, String code): String`（返 token T）。

- [ ] **Step 1: Write the failing test** — `UserPhoneServiceTest`（step1+2 用例）

```java
package com.sks.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.sks.AbstractDbTest;
import com.sks.auth.SmsCode;
import com.sks.auth.SmsCodeMapper;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import com.sks.common.SmsClient;
import com.sks.common.SmsScene;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class UserPhoneServiceTest extends AbstractDbTest {

    @Autowired UserPhoneService userPhoneService;
    @Autowired SmsCodeMapper smsCodeMapper;
    @Autowired AppUserMapper appUserMapper;
    @Autowired PhoneChangeSessionMapper sessionMapper;
    @Autowired JdbcTemplate jdbc;
    @MockBean SmsClient smsClient;

    private long register(String phone) {
        appUserMapper.insert(newUser(phone));
        return appUserMapper.findByPhone(phone).getId();
    }

    private com.sks.user.AppUser newUser(String phone) {
        com.sks.user.AppUser u = new com.sks.user.AppUser();
        u.setPhone(phone);
        return u;
    }

    private String realCodeOf(String phone, String scene) {
        SmsCode c = smsCodeMapper.findActiveCode(phone, scene);
        assertNotNull(c, "sendOldPhoneCode 应写 sms_code(scene=" + scene + ")");
        return c.getCode();
    }

    @Test
    void sendOldCodeWritesCodeAndSessionAndSends() {
        long uid = register("13900000020");
        userPhoneService.sendOldPhoneCode(uid);
        verify(smsClient).sendVerificationCode(eq("13900000020"), eq(realCodeOf("13900000020", "VERIFY_OLD_PHONE")),
                eq(SmsScene.VERIFY_OLD_PHONE));
        assertNotNull(sessionMapper.findByToken(/* 通过 jdbc 查 AWAITING_OLD_VERIFY */ findToken(uid)));
    }

    @Test
    void sendOldCodeReentryDeletesOldSession() {
        long uid = register("13900000021");
        userPhoneService.sendOldPhoneCode(uid);
        // 回拨 created_at 绕过 1 分钟频控
        jdbc.update("UPDATE sms_code SET created_at = now() - interval '2 min' WHERE phone='13900000021'");
        userPhoneService.sendOldPhoneCode(uid);
        // 仅一个活跃 session
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM phone_change_session WHERE user_id=? AND status<>'DONE'",
                Integer.class, uid));
    }

    @Test
    void verifyOldWrongCodeIncrementsErr() {
        long uid = register("13900000022");
        userPhoneService.sendOldPhoneCode(uid);
        assertThrows(BizException.class, () -> userPhoneService.verifyOldPhone(uid, "000000"));
    }

    @Test
    void verifyOldFiveWrongLocks() {
        long uid = register("13900000023");
        userPhoneService.sendOldPhoneCode(uid);
        for (int i = 0; i < 5; i++) {
            // 前 4 次错码 + 第 5 次后判锁
            try { userPhoneService.verifyOldPhone(uid, "000000"); } catch (BizException ignored) {}
        }
        // 第 5 错后 existsLocked = true → 再发码被锁拦
        assertEquals(5, smsCodeMapper.findMostRecent("13900000023").getErrCount());
        assertThrows(BizException.class, () -> userPhoneService.sendOldPhoneCode(uid));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void verifyOldLockPersistsAcrossTransactions() {
        // 防止 AbstractDbTest @Transactional 掩盖：NOT_SUPPORTED 让 err_count 真落库
        long uid = register("13900000024");
        userPhoneService.sendOldPhoneCode(uid);
        for (int i = 0; i < 5; i++) {
            try { userPhoneService.verifyOldPhone(uid, "000000"); } catch (BizException ignored) {}
        }
        assertTrue(smsCodeMapper.existsLocked("13900000024"));
    }

    @Test
    void verifyOldRightCodeReturnsTokenAndMarksUsed() {
        long uid = register("13900000025");
        userPhoneService.sendOldPhoneCode(uid);
        String code = realCodeOf("13900000025", "VERIFY_OLD_PHONE");
        String token = userPhoneService.verifyOldPhone(uid, code);
        assertNotNull(token);
        PhoneChangeSession s = sessionMapper.findByToken(token);
        assertEquals("AWAITING_NEW_VERIFY", s.getStatus());
        // 旧号码码 markUsed，重放拿不到新 T
        SmsCode old = smsCodeMapper.findActiveCode("13900000025", "VERIFY_OLD_PHONE");
        assertNull(old, "verify-old 对码后旧号码应 markUsed，findActiveCode 取不到");
    }

    @Test
    void crossSceneCodeRejected() {
        long uid = register("13900000026");
        // 发登录码（LOGIN_REGISTER），拿该码 verify-old 应失败（scene 过滤）
        // 直接插一条 LOGIN_REGISTER 码
        SmsCode login = new SmsCode();
        login.setPhone("13900000026");
        login.setCode("999999");
        login.setExpireAt(OffsetDateTime.now().plusMinutes(5));
        login.setScene("LOGIN_REGISTER");
        smsCodeMapper.insert(login);
        assertThrows(BizException.class, () -> userPhoneService.verifyOldPhone(uid, "999999"));
    }

    private String findToken(long userId) {
        return jdbc.queryForObject(
                "SELECT token FROM phone_change_session WHERE user_id=? AND status<>'DONE' ORDER BY id DESC LIMIT 1",
                String.class, userId);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sks-server && ./mvnw test -Dtest=UserPhoneServiceTest`
Expected: FAIL — `UserPhoneService` 不存在。

- [ ] **Step 3: Write `UserPhoneService`**（step1+2 部分，send-new/verify-new 留 Task 7 补）

`sks-server/src/main/java/com/sks/user/UserPhoneService.java`:
```java
package com.sks.user;

import com.sks.auth.SmsCode;
import com.sks.auth.SmsCodeMapper;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import com.sks.common.SmsClient;
import com.sks.common.SmsScene;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 换绑手机号 2-step flow（spec §3.5）。需 C 端 JWT（userId 由 controller 从 principal 取）。
 *
 * <p>step1 send-old-code + step2 verify-old（本任务）；step3 send-new-code + step4 verify-new（Task 7 补）。
 * 事务边界见 spec §6：err_count 自增路径非事务；verify-old 对码 markUsed + UPDATE session 两条自动提交写。
 */
@Service
public class UserPhoneService {

    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration SESSION_TTL = Duration.ofMinutes(10);

    private final SmsCodeMapper smsCodeMapper;
    private final AppUserMapper appUserMapper;
    private final PhoneChangeSessionMapper sessionMapper;
    private final SmsClient smsClient;
    private final SecureRandom random = new SecureRandom();

    public UserPhoneService(SmsCodeMapper smsCodeMapper, AppUserMapper appUserMapper,
                            PhoneChangeSessionMapper sessionMapper, SmsClient smsClient) {
        this.smsCodeMapper = smsCodeMapper;
        this.appUserMapper = appUserMapper;
        this.sessionMapper = sessionMapper;
        this.smsClient = smsClient;
    }

    /** step1：向当前手机号发码（VERIFY_OLD_PHONE）+ 建/覆 session。 */
    public void sendOldPhoneCode(long userId) {
        AppUser user = appUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        String oldPhone = user.getPhone();
        if (smsCodeMapper.existsLocked(oldPhone)) {
            throw new BizException(ErrorCode.SMS_CODE_LOCKED);
        }
        checkRateLimit(oldPhone);

        String code = generateCode();
        SmsCode row = new SmsCode();
        row.setPhone(oldPhone);
        row.setCode(code);
        row.setExpireAt(OffsetDateTime.now().plus(CODE_TTL));
        row.setScene(SmsScene.VERIFY_OLD_PHONE.name());
        smsCodeMapper.insert(row);

        // 先删后建（不标 DONE，部分唯一索引兜底并发）
        sessionMapper.deleteActiveByUserId(userId);
        PhoneChangeSession s = new PhoneChangeSession();
        s.setToken(UUID.randomUUID().toString().replace("-", ""));
        s.setUserId(userId);
        s.setOldPhone(oldPhone);
        s.setStatus("AWAITING_OLD_VERIFY");
        s.setExpiresAt(OffsetDateTime.now().plus(SESSION_TTL));
        sessionMapper.insert(s);

        smsClient.sendVerificationCode(oldPhone, code, SmsScene.VERIFY_OLD_PHONE);
    }

    /** step2：校旧码 → 对则 markUsed + 置 AWAITING_NEW_VERIFY + 重置 expires_at + 返 token T。 */
    public String verifyOldPhone(long userId, String code) {
        AppUser user = appUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        String oldPhone = user.getPhone();
        if (smsCodeMapper.existsLocked(oldPhone)) {
            throw new BizException(ErrorCode.SMS_CODE_LOCKED);
        }
        PhoneChangeSession s = activeSessionOf(userId);
        if (s == null || !"AWAITING_OLD_VERIFY".equals(s.getStatus())) {
            throw new BizException(ErrorCode.PHONE_CHANGE_TOKEN_INVALID);
        }
        SmsCode active = smsCodeMapper.findActiveCode(oldPhone, SmsScene.VERIFY_OLD_PHONE.name());
        if (active == null) {
            throw new BizException(ErrorCode.SMS_CODE_INVALID);
        }
        if (!active.getCode().equals(code)) {
            smsCodeMapper.incrementErrCount(active.getId());
            throw new BizException(ErrorCode.SMS_CODE_INVALID);
        }
        // 对码：markUsed（防重放续窗口）+ 置 AWAITING_NEW_VERIFY + 重置 expires_at（new-bind 窗口从现在起算）
        smsCodeMapper.markUsed(active.getId());
        sessionMapper.updateToNewVerify(s.getId(), OffsetDateTime.now(), OffsetDateTime.now().plus(SESSION_TTL));
        return s.getToken();
    }

    private PhoneChangeSession activeSessionOf(long userId) {
        return sessionMapper.selectOne(  // 简单实现：遍历未完成，取第一条
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PhoneChangeSession>()
                        .eq("user_id", userId).ne("status", "DONE")
                        .orderByDesc("id").last("LIMIT 1"));
    }

    private void checkRateLimit(String phone) {
        if (smsCodeMapper.countLastMinute(phone) >= 1
                || smsCodeMapper.countLastHour(phone) >= 5
                || smsCodeMapper.countLast24Hours(phone) >= 10) {
            throw new BizException(ErrorCode.SMS_RATE_LIMIT);
        }
    }

    private String generateCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}
```
（注：`activeSessionOf` 用 QueryWrapper 取未完成 session；也可在 mapper 加 `findActiveByUserId`，本任务先用 QueryWrapper，Task 7 若需可重构。）

- [ ] **Step 4: Write `UserPhoneController`**（step1+2 端点）

`sks-server/src/main/java/com/sks/user/UserPhoneController.java`:
```java
package com.sks.user;

import com.sks.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 换绑手机号端点（spec §5.2，/api/user/phone/change/**，需 C 端 JWT）。 */
@RestController
@RequestMapping("/api/user/phone/change")
public class UserPhoneController {

    private final UserPhoneService userPhoneService;

    public UserPhoneController(UserPhoneService userPhoneService) {
        this.userPhoneService = userPhoneService;
    }

    @PostMapping("/send-old-code")
    public ApiResponse<Void> sendOldCode(@AuthenticationPrincipal Long userId) {
        userPhoneService.sendOldPhoneCode(userId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/verify-old")
    public ApiResponse<TokenResponse> verifyOld(@AuthenticationPrincipal Long userId,
                                                @Valid @RequestBody VerifyCodeRequest req) {
        String token = userPhoneService.verifyOldPhone(userId, req.code());
        return ApiResponse.ok(new TokenResponse(token));
    }

    public record VerifyCodeRequest(@NotBlank String code) {}
    public record TokenResponse(String token) {}
}
```
（`/api/user/**` 已在 user SecurityFilterChain 的 `anyRequest().authenticated()` 内，无需改 SecurityConfig。）

- [ ] **Step 5: Run test to verify it passes**

Run: `cd sks-server && ./mvnw test -Dtest=UserPhoneServiceTest`
Expected: PASS（7 测试绿：send-old 写码+session+发、重入删旧 session、错码 err_count、5 错锁、锁定跨事务持久、对码返 T + markUsed、跨 scene 拒用）。

- [ ] **Step 6: Run full suite (regression)**

Run: `cd sks-server && ./mvnw test`
Expected: PASS。

- [ ] **Step 7: Commit**

```bash
cd sks-server && git add -A && git commit -m "feat(user): 换绑手机号 step1+2（send-old-code / verify-old + token）"
```

---

### Task 7: 换绑 flow step 3+4（send-new-code / verify-new）

**Files:**
- Modify: `sks-server/src/main/java/com/sks/user/UserPhoneService.java`（加 sendNewPhoneCode/verifyNewPhone/completePhoneChange）
- Modify: `sks-server/src/main/java/com/sks/user/UserPhoneController.java`（加 send-new-code/verify-new 端点）
- Modify: `sks-server/src/test/java/com/sks/user/UserPhoneServiceTest.java`（加 step3+4 用例）

**Interfaces:**
- Produces: `UserPhoneService.sendNewPhoneCode(long userId, String newPhone, String token)`、`UserPhoneService.verifyNewPhone(long userId, String newPhone, String code, String token)`、`UserPhoneService.completePhoneChange(String token, String newPhone)`（@Transactional，self 代理）。

- [ ] **Step 1: Write the failing test** — 追加到 `UserPhoneServiceTest`

```java
    @Test
    void sendNewCodeRequiresValidToken() {
        long uid = register("13900000030");
        assertThrows(BizException.class,
                () -> userPhoneService.sendNewPhoneCode(uid, "13900000099", "bad-token"));
    }

    @Test
    void sendNewCodeRejectsSameAsOldPhone() {
        long uid = register("13900000031");
        String token = passVerifyOld(uid, "13900000031");
        BizException e = assertThrows(BizException.class,
                () -> userPhoneService.sendNewPhoneCode(uid, "13900000031", token));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
    }

    @Test
    void sendNewCodeRejectsBoundPhone() {
        register("13900000032"); // 另一用户占了 32
        long uid = register("13900000033");
        String token = passVerifyOld(uid, "13900000033");
        BizException e = assertThrows(BizException.class,
                () -> userPhoneService.sendNewPhoneCode(uid, "13900000032", token));
        assertEquals(ErrorCode.PHONE_ALREADY_BOUND, e.errorCode());
    }

    @Test
    void sendNewCodeSendsToNewPhone() {
        long uid = register("13900000034");
        String token = passVerifyOld(uid, "13900000034");
        userPhoneService.sendNewPhoneCode(uid, "13900000044", token);
        verify(smsClient).sendVerificationCode(eq("13900000044"),
                eq(realCodeOf("13900000044", "BIND_NEW_PHONE")), eq(SmsScene.BIND_NEW_PHONE));
    }

    @Test
    void verifyNewRejectsMismatchedNewPhone() {
        long uid = register("13900000035");
        String token = passVerifyOld(uid, "13900000035");
        userPhoneService.sendNewPhoneCode(uid, "13900000045", token);
        // verify-new 传 B 号（与 session.new_phone=45 不符）
        BizException e = assertThrows(BizException.class,
                () -> userPhoneService.verifyNewPhone(uid, "13900000099", "000000", token));
        assertEquals(ErrorCode.PHONE_CHANGE_TOKEN_INVALID, e.errorCode());
    }

    @Test
    void verifyNewRightCodeUpdatesPhoneAndInvalidatesCodes() {
        long uid = register("13900000036");
        String token = passVerifyOld(uid, "13900000036");
        userPhoneService.sendNewPhoneCode(uid, "13900000046", token);
        String code = realCodeOf("13900000046", "BIND_NEW_PHONE");
        userPhoneService.verifyNewPhone(uid, "13900000046", code, token);
        // app_user.phone 更新
        assertEquals("13900000046", appUserMapper.selectById(uid).getPhone());
        // token 消费：同 token 再 verify-new 拒
        assertThrows(BizException.class,
                () -> userPhoneService.verifyNewPhone(uid, "13900000046", code, token));
    }

    @Test
    void verifyNewConcurrentBoundThrowsPhoneAlreadyBound() {
        long uid = register("13900000037");
        String token = passVerifyOld(uid, "13900000037");
        userPhoneService.sendNewPhoneCode(uid, "13900000047", token);
        // 预占 47（另一用户）→ verify-new UPDATE app_user.phone 撞 UNIQUE
        register("13900000047");
        String code = realCodeOf("13900000047", "BIND_NEW_PHONE");
        // 注：本号已发码给 47（同号），47 也在另一用户名下 —— UNIQUE 兜底
        BizException e = assertThrows(BizException.class,
                () -> userPhoneService.verifyNewPhone(uid, "13900000047", code, token));
        assertEquals(ErrorCode.PHONE_ALREADY_BOUND, e.errorCode());
    }

    /** 辅助：完成 verify-old 拿 token。 */
    private String passVerifyOld(long uid, String oldPhone) {
        userPhoneService.sendOldPhoneCode(uid);
        // 回拨 created_at 绕频控（send-new 不再发旧号码，但 send-old 已发一次）
        jdbc.update("UPDATE sms_code SET created_at = now() - interval '2 min' WHERE phone=?", oldPhone);
        String code = realCodeOf(oldPhone, "VERIFY_OLD_PHONE");
        return userPhoneService.verifyOldPhone(uid, code);
    }
```
（`realCodeOf` / `register` 已在 Task 6 测试类定义。）

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sks-server && ./mvnw test -Dtest=UserPhoneServiceTest`
Expected: FAIL — `sendNewPhoneCode`/`verifyNewPhone` 不存在。

- [ ] **Step 3: Add `sendNewPhoneCode`/`verifyNewPhone`/`completePhoneChange` to `UserPhoneService`**

在 `UserPhoneService.java` 加（并加 `@Lazy self` 注入 + `import org.springframework.context.annotation.Lazy` + `import org.springframework.transaction.annotation.Transactional` + `import org.springframework.dao.DuplicateKeyException` + `import com.sks.user.AppUser` 已有）：

构造加 `@Lazy UserPhoneService self` 参数 + 字段：
```java
    private final UserPhoneService self;
    public UserPhoneService(SmsCodeMapper smsCodeMapper, AppUserMapper appUserMapper,
                            PhoneChangeSessionMapper sessionMapper, SmsClient smsClient,
                            @Lazy UserPhoneService self) {
        // ... 赋值 ...
        this.self = self;
    }
```

加方法：
```java
    /** step3：验 T → 校 newPhone → 频控+锁定 → 发码（BIND_NEW_PHONE）+ 落 new_phone。 */
    public void sendNewPhoneCode(long userId, String newPhone, String token) {
        PhoneChangeSession s = sessionMapper.findByToken(token);
        if (s == null || !s.getUserId().equals(userId)
                || !"AWAITING_NEW_VERIFY".equals(s.getStatus())
                || s.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new BizException(ErrorCode.PHONE_CHANGE_TOKEN_INVALID);
        }
        if (!isPresent(newPhone) || newPhone.equals(s.getOldPhone())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        if (appUserMapper.findByPhone(newPhone) != null) {
            throw new BizException(ErrorCode.PHONE_ALREADY_BOUND);
        }
        if (smsCodeMapper.existsLocked(newPhone)) {
            throw new BizException(ErrorCode.SMS_CODE_LOCKED);
        }
        checkRateLimit(newPhone);

        String code = generateCode();
        SmsCode row = new SmsCode();
        row.setPhone(newPhone);
        row.setCode(code);
        row.setExpireAt(OffsetDateTime.now().plus(CODE_TTL));
        row.setScene(SmsScene.BIND_NEW_PHONE.name());
        row.setSessionToken(token);
        smsCodeMapper.insert(row);
        sessionMapper.updateNewPhone(s.getId(), newPhone);

        smsClient.sendVerificationCode(newPhone, code, SmsScene.BIND_NEW_PHONE);
    }

    /** step4：验 T + 断言 newPhone==session.new_phone → 校新码 → 对码经 self 代理 @Transactional 收尾。 */
    public void verifyNewPhone(long userId, String newPhone, String code, String token) {
        PhoneChangeSession s = sessionMapper.findByToken(token);
        if (s == null || !s.getUserId().equals(userId)
                || !"AWAITING_NEW_VERIFY".equals(s.getStatus())
                || s.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new BizException(ErrorCode.PHONE_CHANGE_TOKEN_INVALID);
        }
        if (newPhone == null || !newPhone.equals(s.getNewPhone())) {
            // token 绑定特定 newPhone；不符 → 拒
            throw new BizException(ErrorCode.PHONE_CHANGE_TOKEN_INVALID);
        }
        if (smsCodeMapper.existsLocked(newPhone)) {
            throw new BizException(ErrorCode.SMS_CODE_LOCKED);
        }
        SmsCode active = smsCodeMapper.findActiveCode(newPhone, SmsScene.BIND_NEW_PHONE.name());
        if (active == null) {
            throw new BizException(ErrorCode.SMS_CODE_INVALID);
        }
        if (!active.getCode().equals(code)) {
            smsCodeMapper.incrementErrCount(active.getId());
            throw new BizException(ErrorCode.SMS_CODE_INVALID);
        }
        // 对码：经 self 代理 @Transactional 收尾。UNIQUE 冲突透传出事务，在此（非事务调用方）catch。
        try {
            self.completePhoneChange(token, newPhone);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.PHONE_ALREADY_BOUND);
        }
    }

    /**
     * 对码后原子收尾（@Transactional，必须经 self 代理调用）：
     * UPDATE app_user.phone + session.status=DONE + 作废 pending 码。UNIQUE 冲突抛 DuplicateKeyException 透传。
     */
    @Transactional
    public void completePhoneChange(String token, String newPhone) {
        PhoneChangeSession s = sessionMapper.findByToken(token);
        appUserMapper.updatePhone(s.getUserId(), newPhone);
        sessionMapper.markDone(s.getId());
        smsCodeMapper.invalidateByToken(token);
        smsCodeMapper.invalidateByPhones(s.getOldPhone(), newPhone);
    }

    private static boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }
```

- [ ] **Step 4: Add send-new-code/verify-new endpoints to `UserPhoneController`**

```java
    @PostMapping("/send-new-code")
    public ApiResponse<Void> sendNewCode(@AuthenticationPrincipal Long userId,
                                         @Valid @RequestBody NewPhoneRequest req) {
        userPhoneService.sendNewPhoneCode(userId, req.newPhone(), req.token());
        return ApiResponse.ok(null);
    }

    @PostMapping("/verify-new")
    public ApiResponse<Void> verifyNew(@AuthenticationPrincipal Long userId,
                                        @Valid @RequestBody VerifyNewRequest req) {
        userPhoneService.verifyNewPhone(userId, req.newPhone(), req.code(), req.token());
        return ApiResponse.ok(null);
    }

    public record NewPhoneRequest(@NotBlank String newPhone, @NotBlank String token) {}
    public record VerifyNewRequest(@NotBlank String newPhone, @NotBlank String code, @NotBlank String token) {}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd sks-server && ./mvnw test -Dtest=UserPhoneServiceTest`
Expected: PASS（Task 6 的 7 + Task 7 的 7 = 14 测试绿，含并发占号 → PHONE_ALREADY_BOUND、newPhone≠session 拒、token 消费）。

- [ ] **Step 6: Run full suite (regression)**

Run: `cd sks-server && ./mvnw test`
Expected: PASS。

- [ ] **Step 7: Commit**

```bash
cd sks-server && git add -A && git commit -m "feat(user): 换绑手机号 step3+4（send-new-code / verify-new + completePhoneChange 事务外 catch）"
```

---

### Task 8: 文档收尾（OPS.md / GO_LIVE_CHECKLIST §3 验收项改邮件）

**Files:**
- Modify: `deploy/OPS.md`（§0 表：DYPNS + 邮件告警）
- Modify: `deploy/GO_LIVE_CHECKLIST.md`（§3/§7 验收项：告警短信→邮件）

- [ ] **Step 1: Update OPS.md §0 表**

`deploy/OPS.md` §0 表「阿里云 SMS 发送」行：留桩位置 / 联调动作 改为「已接 `AliyunSmsAuthClient`（DYPNS SendSmsVerifyCode 字面码），填 `ALIYUN_SMS_SIGN` + `ALIYUN_SMS_TEMPLATE_LOGIN/VERIFY_OLD/BIND_NEW`」。新增一行「告警邮件」：留桩位置 `QuotaWatchJob.sendAlert` / `RechargeOrderService`（后者 TODO）/ 联调动作「填 `SPRING_MAIL_*` + `SKS_ALERT_ADMIN_EMAIL`」。

- [ ] **Step 2: Update GO_LIVE_CHECKLIST §3 + §7**

§3「阿里云短信」行：改为「阿里云短信认证（DYPNS）」+ 验收「`POST /api/auth/send-code` 真收验证码（字面码）」。§7「QuotaWatchJob 手测：把阈值调到极高触发告警」改为「触发告警邮件到站长邮箱」。§4 人工验收若提到「告警短信」也改邮件。

- [ ] **Step 3: Commit**

```bash
git add deploy/OPS.md deploy/GO_LIVE_CHECKLIST.md
git commit -m "docs(ops): OPS §0 + GO_LIVE_CHECKLIST §3/§7 同步 DYPNS + 邮件告警"
```

---

## Self-Review（写完跑了一遍）

**Spec coverage:** spec §2（API 事实）→ Task 5 AliyunSmsAuthClient 实现 + 测试断言 setter；§3.0（Mapper scene 化/existsLocked/频控）→ Task 2；§3.1 SmsScene → Task 5；§3.2 SmsClient 3 参+删 sendAlert → Task 5；§3.3 AliyunSmsAuthClient → Task 5；§3.4 AlertNotifier/Mail → Task 3；§3.5 UserPhoneService → Task 6+7；§3.6 ErrorCode 4007/4008 → Task 1；§3.7 删 AliyunSmsClient + QuotaWatchJob 换 AlertNotifier → Task 4+5；§4 配置 → Task 1(pom)+3(mail)+4(删 admin-phone)+5(sms 块)；§4 Flyway V3 → Task 1；§5 数据流 → Task 6+7；§6 事务边界 + 事务外 catch → Task 7；§7 测试矩阵 → 各 Task 测试；§9 GO_LIVE_CHECKLIST → Task 3+5+8。RechargeOrderService 两处 stub 留 TODO（spec §1 允许拆出，钱路径，下个 plan）。✅ 无 spec 项缺任务。

**Placeholder scan:** 无 TBD/TODO（除 RechargeOrderService 明确标注 deferred + spec 允许）。Code step 全有实代码。✅

**Type consistency:** `SmsClient.sendVerificationCode(phone, code, scene)` 在 Task 5 定义、Task 6/7 调用一致；`SmsScene.{LOGIN_REGISTER,VERIFY_OLD_PHONE,BIND_NEW_PHONE}` 一致；`findActiveCode(phone, scene)` Task 2 定义、Task 6/7 调用一致；`existsLocked(phone)` 一致；`PhoneChangeSessionMapper.{findByToken,deleteActiveByUserId,updateToNewVerify,updateNewPhone,markDone}` Task 1 定义、Task 6/7 调用一致；`completePhoneChange(token, newPhone)` Task 7 定义+调用一致。`ErrorCode.PHONE_ALREADY_BOUND/PHONE_CHANGE_TOKEN_INVALID` Task 1 定义、Task 7 用。✅
