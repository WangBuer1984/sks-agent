# 阿里云短信接线 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `AuthService.sendCode` 与 `QuotaWatchJob.sendAlert` 的 `[SMS-STUB]` 接成真实阿里云 Dysmsapi `SendSms`，使 §3 联调首检「阿里云短信」可验。

**Architecture:** 新增 `SmsClient` 接口 + `AliyunSmsClient`（`@Component`，V2 Tea 同步 SDK `com.aliyun:dysmsapi20170525:4.6.0`）。条件降级：key/template 任一空 → stub（log + no-op，不抛，本地/CI 行为同今）；齐 → 真 `SendSms`，失败抛 `BizException(SMS_SEND_FAILED=5003)`。`AuthService` / `QuotaWatchJob` 注入 `SmsClient` 调用。`querySmsBalance` 不接（BSS，留桩）。

**Tech Stack:** Java 21 / Spring Boot 3.2.5 / MyBatis-Plus / Testcontainers pg16 / Mockito（spring-boot-starter-test 自带）/ 阿里云 dysmsapi20170525 V2 Tea SDK。

## Global Constraints

- **无 Redis/MQ**：`@Component` + `@Value`，同 `QuotaWatchJob` 模式。
- **所有 key via `.env`**（gitignored），代码不硬编码 model/key。`application.yml` 用 `${ENV:default}` 映射。
- **Java 唯一公网入口**：SMS 在 Java 侧直发阿里云，不出网到 Python。
- **不碰钱路径**：credit 不受影响；验证码失败退到 `5003`，不涉及扣费/退款。
- **不引入 streaming**：无关。
- **测试**：Java 用 Testcontainers `pgvector/pgvector:pg16`（非 H2）；`AliyunSmsClient` 用 Mockito mock `Client`（不真打阿里云）；TDD（RED→GREEN）。
- **sks-server 用 `env_file: .env`**（`docker-compose.yml:25-26`），所有 `.env` 变量已自动进容器 —— **无需改 docker-compose.yml**，只靠 `application.yml` 的 `${...}` 映射 env→property。
- 参考 spec：`docs/superpowers/specs/2026-07-23-aliyun-sms-wiring-design.md`。

---

## File Structure

| 文件 | 责任 | 动作 |
|------|------|------|
| `sks-server/pom.xml` | 加 dysmsapi 依赖 | Modify |
| `sks-server/src/main/java/com/sks/common/ErrorCode.java` | 加 `SMS_SEND_FAILED(5003)` | Modify |
| `sks-server/src/main/java/com/sks/common/SmsClient.java` | 发送接口 | Create |
| `sks-server/src/main/java/com/sks/common/AliyunSmsClient.java` | 条件降级 + 真 SendSms | Create |
| `sks-server/src/test/java/com/sks/common/AliyunSmsClientTest.java` | 单测（mock Client） | Create |
| `sks-server/src/main/java/com/sks/auth/AuthService.java` | `sendCode` 调 `SmsClient` | Modify |
| `sks-server/src/test/java/com/sks/auth/AuthServiceTest.java` | 加 sendCode 委托测试 | Modify |
| `sks-server/src/main/java/com/sks/common/QuotaWatchJob.java` | `sendAlert` 调 `SmsClient` | Modify |
| `sks-server/src/test/java/com/sks/common/QuotaWatchJobTest.java` | 构造改 + 加 sendAlert 委托测试 | Modify |
| `sks-server/src/main/resources/application.yml` | 加 `sks.sms` 段 | Modify |
| `.env` | 加 2 个 template key（gitignored） | Modify |

---

## Task 1: AliyunSmsClient + SmsClient 接口 + pom 依赖 + ErrorCode

**Files:**
- Modify: `sks-server/pom.xml`
- Modify: `sks-server/src/main/java/com/sks/common/ErrorCode.java`
- Create: `sks-server/src/main/java/com/sks/common/SmsClient.java`
- Create: `sks-server/src/main/java/com/sks/common/AliyunSmsClient.java`
- Test: `sks-server/src/test/java/com/sks/common/AliyunSmsClientTest.java`

**Interfaces:**
- Produces: `SmsClient`（接口，方法 `void sendVerificationCode(String phone, String code)` / `void sendAlert(String phone, String reason)`）；`AliyunSmsClient`（`@Component implements SmsClient`，构造读 `sks.sms.*`，测试 seam `setDelegate(Client)`）；`ErrorCode.SMS_SEND_FAILED`。Task 2 / Task 3 依赖这些。

- [ ] **Step 1: 加 dysmsapi 依赖到 pom**

在 `sks-server/pom.xml` 的 `<dependencies>` 内、`<!-- 测试 -->` 注释块之前插入：

```xml
        <!-- 阿里云短信 Dysmsapi V2 Tea 同步 SDK（§3 联调首检「阿里云短信」：sendCode/sendAlert 真发送） -->
        <dependency>
            <groupId>com.aliyun</groupId>
            <artifactId>dysmsapi20170525</artifactId>
            <version>4.6.0</version>
        </dependency>
```

- [ ] **Step 2: 加 ErrorCode.SMS_SEND_FAILED**

在 `sks-server/src/main/java/com/sks/common/ErrorCode.java` 的 `CONTENT_BLOCKED(5002, ...)` 行后加：

```java
    CONTENT_BLOCKED(5002, "内容不符合安全规范，已被拦截"),
    SMS_SEND_FAILED(5003, "短信发送失败，请稍后再试");
```

（即把原 `CONTENT_BLOCKED(5002, "...");` 末尾分号改逗号，再追加 `SMS_SEND_FAILED` 行带分号收尾。）

- [ ] **Step 3: 写 SmsClient 接口**

Create `sks-server/src/main/java/com/sks/common/SmsClient.java`：

```java
package com.sks.common;

/**
 * 短信发送 seam（§3 联调首检「阿里云短信」）。
 *
 * <p>实现 {@link AliyunSmsClient}：条件降级（key 空 → stub 不抛）+ 真 SendSms（失败抛
 * {@link BizException}({@link ErrorCode#SMS_SEND_FAILED})）。{@link com.sks.auth.AuthService#sendCode}
 * 发验证码、{@link QuotaWatchJob#sendAlert} 发告警，均经此 seam。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-07-23-aliyun-sms-wiring-design.md}。
 */
public interface SmsClient {

    /** 发验证码。key 未配置时为 no-op（stub）；configured 但发送失败时抛 SMS_SEND_FAILED。 */
    void sendVerificationCode(String phone, String code);

    /** 发告警短信给站长。失败可抛 SMS_SEND_FAILED，由 {@link QuotaWatchJob#sweep} try/catch 兜底。 */
    void sendAlert(String phone, String reason);
}
```

- [ ] **Step 4: 写 AliyunSmsClientTest（RED）**

Create `sks-server/src/test/java/com/sks/common/AliyunSmsClientTest.java`：

```java
package com.sks.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody;
import org.junit.jupiter.api.Test;

/**
 * {@link AliyunSmsClient} 单测——Mockito mock {@link Client}，不真打阿里云。
 *
 * <p>承重断言（spec §6）：
 * <ul>
 *   <li>未 configured（key 空）→ 不抛、不发；
 *   <li>configured + code=OK → 不抛；
 *   <li>configured + code≠OK → 抛 SMS_SEND_FAILED；
 *   <li>configured + 异常 → 抛 SMS_SEND_FAILED。
 * </ul>
 */
class AliyunSmsClientTest {

    /** configured 客户端：keys/templates 全非空 + 注入 mock Client（跳过懒构建）。 */
    private static AliyunSmsClient newConfigured(Client mockClient) {
        AliyunSmsClient c = new AliyunSmsClient(
                "ak", "sk", "sign", "SMS_VERIFY", "SMS_ALERT", "dysmsapi.aliyuncs.com");
        c.setDelegate(mockClient);
        return c;
    }

    /** 未 configured：全空。 */
    private static AliyunSmsClient newUnconfigured() {
        return new AliyunSmsClient("", "", "", "", "", "dysmsapi.aliyuncs.com");
    }

    private static SendSmsResponse resp(String code) {
        SendSmsResponse r = new SendSmsResponse();
        SendSmsResponseBody body = new SendSmsResponseBody();
        body.setCode(code);
        body.setBizId("biz-1");
        body.setMessage("msg");
        r.setBody(body);
        return r;
    }

    @Test
    void unconfiguredStubDoesNotThrowOrSend() {
        AliyunSmsClient c = newUnconfigured();
        assertDoesNotThrow(() -> c.sendVerificationCode("13900000000", "123456"));
    }

    @Test
    void configuredOkDoesNotThrow() throws Exception {
        Client mock = mock(Client.class);
        when(mock.sendSms(any())).thenReturn(resp("OK"));
        AliyunSmsClient c = newConfigured(mock);
        assertDoesNotThrow(() -> c.sendVerificationCode("13900000000", "123456"));
        verify(mock).sendSms(any(SendSmsRequest.class));
    }

    @Test
    void configuredNonOkThrowsSendFailed() throws Exception {
        Client mock = mock(Client.class);
        when(mock.sendSms(any())).thenReturn(resp("isv.BUSINESS_LIMIT_CONTROL"));
        AliyunSmsClient c = newConfigured(mock);
        BizException e = assertThrows(BizException.class,
                () -> c.sendVerificationCode("13900000000", "123456"));
        org.junit.jupiter.api.Assertions.assertEquals(
                ErrorCode.SMS_SEND_FAILED, ((com.sks.common.BizException) e).getCode());
    }

    @Test
    void configuredExceptionThrowsSendFailed() throws Exception {
        Client mock = mock(Client.class);
        when(mock.sendSms(any())).thenThrow(new RuntimeException("timeout"));
        AliyunSmsClient c = newConfigured(mock);
        assertThrows(BizException.class, () -> c.sendVerificationCode("13900000000", "123456"));
    }

    @Test
    void alertUnconfiguredStubNoThrow() {
        AliyunSmsClient c = newUnconfigured();
        assertDoesNotThrow(() -> c.sendAlert("13900000000", "短信余额不足"));
    }
}
```

> 注：若 `BizException` 无 `getCode()`（见 Step 6 校验），`configuredNonOkThrowsSendFailed` 里改用 `assertThrows(BizException.class, ...)` 仅断言类型即可。

- [ ] **Step 5: Run test to verify it fails**

Run: `cd sks-server && ./mvnw test -Dtest=AliyunSmsClientTest -q`
Expected: 编译失败 / FAIL —— `AliyunSmsClient` 类不存在。

- [ ] **Step 6: 写 AliyunSmsClient（GREEN）**

先确认 `BizException` 是否暴露 `ErrorCode getCode()`：

Run: `grep -nE "getCode|class BizException|ErrorCode" sks-server/src/main/java/com/sks/common/BizException.java`
- 若有 `getCode()` 返回 `ErrorCode`：Step 4 的断言原样可用。
- 若无：把 `configuredNonOkThrowsSendFailed` 里的 `assertEquals(...)` 那两行删掉，只留 `assertThrows(BizException.class, ...)`。

Create `sks-server/src/main/java/com/sks/common/AliyunSmsClient.java`：

```java
package com.sks.common;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.teaopenapi.models.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 阿里云 Dysmsapi SendSms 实现（§3 联调首检「阿里云短信」）。
 *
 * <p><b>条件降级</b>：access-key / secret / sign / 对应 template 任一为空 → 走 stub（log + no-op，
 * <b>不抛</b>），本地 / CI 无 key 行为同留桩期；齐全 → 真 {@code SendSms}。失败（{@code body.code != "OK"}
 * 或异常）抛 {@link BizException}({@link ErrorCode#SMS_SEND_FAILED})。
 *
 * <p><b>懒构建 Client</b>：仅 configured 时 {@code new Client(config)}，避免空 key 启动期炸；
 * 实例缓存。测试经 {@link #setDelegate(Client)} 注入 mock 跳过懒构建。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-07-23-aliyun-sms-wiring-design.md}。
 */
@Component
public class AliyunSmsClient implements SmsClient {

    private static final Logger log = LoggerFactory.getLogger(AliyunSmsClient.class);

    private final String accessKeyId;
    private final String accessKeySecret;
    private final String signName;
    private final String verifyTemplateCode;
    private final String alertTemplateCode;
    private final String endpoint;

    /** 测试 seam：非空时优先用，跳过懒构建。 */
    private Client override;
    /** 懒构建的真实 client，缓存。 */
    private Client built;

    public AliyunSmsClient(
            @Value("${sks.sms.access-key-id:}") String accessKeyId,
            @Value("${sks.sms.access-key-secret:}") String accessKeySecret,
            @Value("${sks.sms.sign-name:}") String signName,
            @Value("${sks.sms.verify-template-code:}") String verifyTemplateCode,
            @Value("${sks.sms.alert-template-code:}") String alertTemplateCode,
            @Value("${sks.sms.endpoint:dysmsapi.aliyuncs.com}") String endpoint) {
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
        this.signName = signName;
        this.verifyTemplateCode = verifyTemplateCode;
        this.alertTemplateCode = alertTemplateCode;
        this.endpoint = endpoint;
    }

    @Override
    public void sendVerificationCode(String phone, String code) {
        if (!configured(verifyTemplateCode)) {
            log.info("[SMS-STUB] send code to phone={}: code={} (no ALIYUN key/template)", phone, code);
            return;
        }
        send(phone, verifyTemplateCode, "{\"code\":\"" + code + "\"}");
    }

    @Override
    public void sendAlert(String phone, String reason) {
        if (!configured(alertTemplateCode)) {
            log.warn("[SMS-STUB] quota alert to admin={}: {} (no ALIYUN alert template)", phone, reason);
            return;
        }
        send(phone, alertTemplateCode, "{\"reason\":\"" + truncate(reason, 200) + "\"}");
    }

    private void send(String phone, String templateCode, String templateParam) {
        SendSmsRequest req = new SendSmsRequest()
                .setPhoneNumbers(phone)
                .setSignName(signName)
                .setTemplateCode(templateCode)
                .setTemplateParam(templateParam);
        try {
            SendSmsResponse resp = delegate().sendSms(req);
            String code = resp.getBody().getCode();
            if (!"OK".equals(code)) {
                log.warn("AliyunSms send failed: phone={}, code={}, msg={}",
                        phone, code, resp.getBody().getMessage());
                throw new BizException(ErrorCode.SMS_SEND_FAILED);
            }
            log.info("AliyunSms sent: phone={}, bizId={}", phone, resp.getBody().getBizId());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AliyunSms send exception: phone={}: {}", phone, e.getMessage());
            throw new BizException(ErrorCode.SMS_SEND_FAILED);
        }
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

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) : s);
    }
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `cd sks-server && ./mvnw test -Dtest=AliyunSmsClientTest -q`
Expected: PASS（5 测试全绿）。

- [ ] **Step 8: 跑全量测试确认未破坏现有**

Run: `cd sks-server && ./mvnw test -q`
Expected: 全绿（新增的 SmsClient bean 未被 AuthService/QuotaWatchJob 注入前不影响其它测试）。

- [ ] **Step 9: Commit**

```bash
git add sks-server/pom.xml sks-server/src/main/java/com/sks/common/ErrorCode.java \
  sks-server/src/main/java/com/sks/common/SmsClient.java \
  sks-server/src/main/java/com/sks/common/AliyunSmsClient.java \
  sks-server/src/test/java/com/sks/common/AliyunSmsClientTest.java
git commit -m "feat(sms): AliyunSmsClient + SmsClient seam（条件降级 + 真 SendSms，失败 5003）"
```

---

## Task 2: AuthService.sendCode 接 SmsClient

**Files:**
- Modify: `sks-server/src/main/java/com/sks/auth/AuthService.java`（构造 + sendCode）
- Test: `sks-server/src/test/java/com/sks/auth/AuthServiceTest.java`

**Interfaces:**
- Consumes: `SmsClient`（Task 1 产出）。
- Produces: `AuthService` 构造新增 `SmsClient` 形参；`sendCode` 末尾调 `smsClient.sendVerificationCode`。

- [ ] **Step 1: 写失败测试（sendCode 委托 SmsClient）**

在 `sks-server/src/test/java/com/sks/auth/AuthServiceTest.java` 顶部 import 区加：

```java
import com.sks.common.SmsClient;
import org.springframework.boot.test.mock.mockito.MockBean;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
```

在类字段区（`@Autowired` 那几行下面）加：

```java
    @MockBean SmsClient smsClient;
```

在类末尾追加测试方法（`AuthServiceTest` 与 `SmsCode` 同在 `com.sks.auth` 包，无需 import / 限定；`SmsCode` 字段访问器 `getCode()`）：

```java
    @Test
    void sendCodeDelegatesToSmsClient() {
        String phone = "13900000099";
        authService.sendCode(phone);
        SmsCode row = smsCodeMapper.findMostRecent(phone);
        assertNotNull(row, "sendCode 应落 sms_code 行");
        verify(smsClient).sendVerificationCode(eq(phone), eq(row.getCode()));
    }
```

> `@MockBean SmsClient` 默认 no-op（不抛），既有频控/锁定/登录测试不受影响；sms_code 行照常落库。

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sks-server && ./mvnw test -Dtest=AuthServiceTest#sendCodeDelegatesToSmsClient -q`
Expected: FAIL —— `AuthService` 构造无 `SmsClient`，或 `verify` 失败（仍走 stub log 未调 smsClient）。

- [ ] **Step 3: AuthService 构造注入 SmsClient**

Modify `sks-server/src/main/java/com/sks/auth/AuthService.java`：

import 区加：
```java
import com.sks.common.SmsClient;
```

字段区（`private final RechargeOrderService rechargeOrderService;` 后）加：
```java
    private final SmsClient smsClient;
```

构造参数末尾（`@Lazy AuthService self` 后）加 `SmsClient smsClient`，函数体末尾加 `this.smsClient = smsClient;`：

```java
    public AuthService(
            SmsCodeMapper smsCodeMapper,
            AppUserMapper appUserMapper,
            JwtUtil jwtUtil,
            RechargeOrderService rechargeOrderService,
            @Lazy AuthService self,
            SmsClient smsClient) {
        this.smsCodeMapper = smsCodeMapper;
        this.appUserMapper = appUserMapper;
        this.jwtUtil = jwtUtil;
        this.rechargeOrderService = rechargeOrderService;
        this.self = self;
        this.smsClient = smsClient;
    }
```

- [ ] **Step 4: sendCode 末尾改调 SmsClient**

把 `sendCode` 末尾的：
```java
        // MVP 留桩：联调时替换为阿里云 SMS。验证码已在 sms_code 表中，测试可直查。
        log.info("[SMS-STUB] send code to phone={}: code={} (ttl={}min)", phone, code, CODE_TTL.toMinutes());
```
改为：
```java
        // §3 联调首检「阿里云短信」：经 SmsClient seam 发送（key 空→stub 不抛；configured→真 SendSms，
        // 失败抛 SMS_SEND_FAILED 透传 controller 5003）。sms_code 行已落，验证码可直查。
        smsClient.sendVerificationCode(phone, code);
```

更新类 javadoc 末尾的 `<b>SMS 发送留桩</b>` 那条（`<li>` 内）描述为「经 `SmsClient` seam 发送（条件降级 + 真 SendSms）」。

- [ ] **Step 5: Run test to verify it passes**

Run: `cd sks-server && ./mvnw test -Dtest=AuthServiceTest -q`
Expected: PASS（新委托测试 + 既有频控/锁定/登录测试全绿——`@MockBean SmsClient` 默认 no-op，不抛，sms_code 行照常落）。

- [ ] **Step 6: Commit**

```bash
git add sks-server/src/main/java/com/sks/auth/AuthService.java \
  sks-server/src/test/java/com/sks/auth/AuthServiceTest.java
git commit -m "feat(auth): sendCode 经 SmsClient 发送（替换 SMS-STUB）"
```

---

## Task 3: QuotaWatchJob.sendAlert 接 SmsClient

**Files:**
- Modify: `sks-server/src/main/java/com/sks/common/QuotaWatchJob.java`
- Test: `sks-server/src/test/java/com/sks/common/QuotaWatchJobTest.java`

**Interfaces:**
- Consumes: `SmsClient`（Task 1 产出）。
- Produces: `QuotaWatchJob` 构造新增 `SmsClient` 形参；`sendAlert` 调 `smsClient.sendAlert`。

- [ ] **Step 1: 写失败测试（sendAlert 委托 + 既有构造兼容）**

Modify `sks-server/src/test/java/com/sks/common/QuotaWatchJobTest.java`：

import 区加（`SmsClient` 同在 `com.sks.common` 包，无需 import）：
```java
import org.junit.jupiter.api.BeforeEach;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
```

字段区把：
```java
    private final QuotaWatchJob job =
            new QuotaWatchJob(SMS_THRESHOLD, GLM_THRESHOLD, "13900000000");
```
改为（加 SmsClient 构造参数 + mock）：
```java
    private SmsClient smsClient;

    @BeforeEach
    void setUp() {
        smsClient = mock(SmsClient.class);
    }

    private QuotaWatchJob newJob() {
        return new QuotaWatchJob(SMS_THRESHOLD, GLM_THRESHOLD, "13900000000", smsClient);
    }
```

把所有 `job.checkAndAlert(...)` 调用中的 `job.` 改为 `newJob().`（7 处测试方法各用一次 `newJob()`）。

类末尾追加 sendAlert 委托测试：
```java
    @Test
    void sendAlertDelegatesToSmsClient() {
        QuotaWatchJob job = newJob();
        job.sendAlert("短信余额不足: 50条");
        verify(smsClient).sendAlert(eq("13900000000"), eq("短信余额不足: 50条"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sks-server && ./mvnw test -Dtest=QuotaWatchJobTest -q`
Expected: 编译失败（`QuotaWatchJob` 构造无 `SmsClient` 参数）。

- [ ] **Step 3: QuotaWatchJob 构造注入 SmsClient + sendAlert 改调**

Modify `sks-server/src/main/java/com/sks/common/QuotaWatchJob.java`：

（`QuotaWatchJob` 与 `SmsClient` 同在 `com.sks.common` 包，无需 import，直接用 `SmsClient`。）

字段区加：
```java
    private final SmsClient smsClient;
```

构造方法加参数 + 赋值：
```java
    public QuotaWatchJob(
            @Value("${sks.quota.sms-threshold:100}") int smsThreshold,
            @Value("${sks.quota.glm-threshold:20}") int glmThreshold,
            @Value("${sks.quota.admin-phone:}") String adminPhone,
            SmsClient smsClient) {
        this.smsThreshold = smsThreshold;
        this.glmThreshold = glmThreshold;
        this.adminPhone = adminPhone;
        this.smsClient = smsClient;
    }
```

`sendAlert` 方法体改为：
```java
    protected void sendAlert(String reason) {
        // §3 联调首检「阿里云短信」：经 SmsClient seam 发告警到 admin-phone（key 空→stub；
        // configured→真 SendSms，失败抛 SMS_SEND_FAILED，被 sweep try/catch 兜底不中断 Job）。
        smsClient.sendAlert(adminPhone, reason);
    }
```

`sendAlert` 原 javadoc 的「与 AuthService 的 SMS-STUB 同档…本任务不重构」改为「经 `SmsClient` seam 发送（同 `AuthService.sendCode`，已接线）」。

`querySmsBalance` 的 TODO 注释补一句：「余额查询在 BSS OpenAPI（`com.aliyun:bssopenapi20171214`），非 dysmsapi；下个任务接。」（行为不变，仅注释。）

- [ ] **Step 4: Run test to verify it passes**

Run: `cd sks-server && ./mvnw test -Dtest=QuotaWatchJobTest -q`
Expected: PASS（8 测试全绿：7 个 checkAndAlert + 1 个 sendAlert 委托）。

- [ ] **Step 5: Commit**

```bash
git add sks-server/src/main/java/com/sks/common/QuotaWatchJob.java \
  sks-server/src/test/java/com/sks/common/QuotaWatchJobTest.java
git commit -m "feat(quota): sendAlert 经 SmsClient 发送（替换 SMS-STUB）"
```

---

## Task 4: application.yml + .env 配置

**Files:**
- Modify: `sks-server/src/main/resources/application.yml`
- Modify: `.env`（gitignored 本地）

**Interfaces:**
- 无代码产出；把 env→property 映射打通，使 Task 1–3 的 `@Value` 能读到真实值。

- [ ] **Step 1: application.yml 加 sks.sms 段**

在 `sks-server/src/main/resources/application.yml` 的 `quota:` 段之后（文件末尾）追加：

```yaml
  # 阿里云短信（§3 联调首检「阿里云短信」——AliyunSmsClient 条件降级：key/template 空→stub 不抛；
  # 齐全→真 SendSms，失败抛 SMS_SEND_FAILED 5003）。env 来自 .env（gitignored）。
  # sks-server 用 env_file:.env，所有 ALIYUN_* 已自动进容器，无需改 docker-compose.yml。
  sms:
    access-key-id: ${ALIYUN_ACCESS_KEY_ID:}
    access-key-secret: ${ALIYUN_ACCESS_KEY_SECRET:}
    endpoint: ${ALIYUN_SMS_ENDPOINT:dysmsapi.aliyuncs.com}
    sign-name: ${ALIYUN_SMS_SIGN:}
    verify-template-code: ${ALIYUN_SMS_VERIFY_TEMPLATE:}
    alert-template-code: ${ALIYUN_SMS_ALERT_TEMPLATE:}
```

- [ ] **Step 2: .env 加 2 个 template key**

在本地 `.env` 的 `ALIYUN_SMS_SIGN=` 行后追加（**留空**，联调时填真值）：

```
ALIYUN_SMS_VERIFY_TEMPLATE=
ALIYUN_SMS_ALERT_TEMPLATE=
```

- [ ] **Step 3: 验证空值降级（启动期不炸）**

Run: `cd sks-server && ./mvnw test -q`
Expected: 全绿（application.yml 默认空值 → AliyunSmsClient stub 路径，AuthService/QuotaWatch 测试照过）。

- [ ] **Step 4: 同步 GO_LIVE_CHECKLIST / OPS（key 清单补 2 个 template）**

Modify `deploy/GO_LIVE_CHECKLIST.md` 的 §1 `.env` 缺失项表，加 2 行：

```markdown
| `ALIYUN_SMS_VERIFY_TEMPLATE` | 验证码模板（含 `${code}` 变量，审批通过） | 联调必填：空则 sendCode 走 stub 不真发 |
| `ALIYUN_SMS_ALERT_TEMPLATE` | 告警模板（含 `${reason}` 变量） | 联调必填：空则 sendAlert 走 stub |
```

Modify `deploy/OPS.md` §0 留桩表「阿里云 SMS 发送」行的「联调动作」改为「已接 `AliyunSmsClient`（dysmsapi SendSms），填 `ALIYUN_SMS_SIGN` + `ALIYUN_SMS_VERIFY_TEMPLATE` + `ALIYUN_SMS_ALERT_TEMPLATE`」；`querySmsBalance` 行保留「BSS OpenAPI，下个任务接」。

- [ ] **Step 5: Commit**

```bash
git add sks-server/src/main/resources/application.yml deploy/GO_LIVE_CHECKLIST.md deploy/OPS.md
git commit -m "chore(sms): application.yml sks.sms 段 + .env/GO_LIVE_CHECKLIST/OPS 同步 2 个 template key"
```

> `.env` 不 commit（gitignored）——Step 2 的 `.env` 改动不入此 commit。

---

## Definition of Done

- `AliyunSmsClientTest` 5 测试绿（stub/OK/non-OK/exception/alert-stub）。
- `AuthServiceTest` 全绿（含新 sendCode 委托测试）。
- `QuotaWatchJobTest` 全绿（8 测试）。
- `./mvnw test -q` 全量绿。
- `application.yml` 映射 6 个 `sks.sms.*` property；`.env` 加 2 个空 template key（gitignored）。
- 未改 `docker-compose.yml`（env_file 已透传）。
- `ErrorCode.SMS_SEND_FAILED=5003` 新增。
- 联调首检（配真 key 后）：`POST /api/auth/send-code` 真收到短信；填错 sign/template → `5003`。
