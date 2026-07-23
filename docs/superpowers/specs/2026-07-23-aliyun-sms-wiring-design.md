# 阿里云短信接线设计 — dysmsapi SendSms（sendCode / sendAlert）

> 日期：2026-07-23。对应 `deploy/OPS.md` §0 留桩项「阿里云 SMS 发送」与 `deploy/GO_LIVE_CHECKLIST.md`
> §3 联调首检「阿里云短信」。本设计只接 **dysmsapi 发送**（验证码 + 告警短信），
> 不接 `querySmsBalance`（余额查询在 BSS OpenAPI，另一产品，留桩 + TODO）。

## 1. 背景与范围

MVP P0–P5 已 code-complete 并合并 main。`AuthService.sendCode` 与 `QuotaWatchJob.sendAlert`
当前为 `[SMS-STUB]`：验证码只写 `sms_code` 表 + 日志，告警只 `log.warn`。
联调期需把这两处接成真实阿里云 Dysmsapi `SendSms`，使 §3 联调首检「阿里云短信」可验。

**范围**：
- ✅ `AuthService.sendCode` → 真 `SendSms`（验证码模板）
- ✅ `QuotaWatchJob.sendAlert` → 真 `SendSms`（告警模板）
- ⏸ `QuotaWatchJob.querySmsBalance`：**不接**。余额查询是 BSS OpenAPI
  （`com.aliyun:bssopenapi20171214` 的 `QueryAccountBalance`），需另加依赖 + AK 开 BSS 读权限 +
  返回是「账户可用余额（元）」非「短信条数」。本任务留桩 + TODO，下个任务再接。
- ⏸ `RechargeOrderService` 两处 `[SMS-STUB]`（开通/补偿通知站长）：**不动**（out of scope，留 TODO）。

## 2. SDK 选择

两个 Java 变体（来源：[Maven Central](https://central.sonatype.com/artifact/com.aliyun/dysmsapi20170525) ·
[阿里云 Java SDK 文档](https://help.aliyun.com/en/sms/developer-reference/using-java-openapi-example)）：

| 变体 | 坐标 | 调用风格 |
|------|------|----------|
| **V2 Tea 同步**（选） | `com.aliyun:dysmsapi20170525:3.x` | `client.sendSms(req)` 同步返回 |
| async | `com.aliyun:alibabacloud-dysmsapi20170525:4.x` | `CompletableFuture` |

选 **V2 同步**：调用点都是同步（Spring web 请求 + `@Scheduled`），async 无收益反增复杂度。

`SendSmsRequest` 字段：`PhoneNumbers` / `SignName` / `TemplateCode` / `TemplateParam`（JSON 字符串）。
响应 `body.code == "OK"` 表成功，`body.bizId` 为发送流水，`body.message` 为错误描述。
endpoint 默认 `dysmsapi.aliyuncs.com`。

## 3. 组件设计

### 3.1 `SmsClient` 接口（`com.sks.common`）

```java
public interface SmsClient {
    /** 发验证码。失败抛 BizException(SMS_SEND_FAILED)。 */
    void sendVerificationCode(String phone, String code);

    /** 发告警短信给站长。失败可抛 BizException，由调用方 QuotaWatchJob.sweep try/catch 兜底。 */
    void sendAlert(String phone, String reason);
}
```

### 3.2 `AliyunSmsClient`（`@Component implements SmsClient`）

构造读 `@Value`：

| 配置 | env | 默认 | 用途 |
|------|-----|------|------|
| `sks.sms.access-key-id` | `ALIYUN_ACCESS_KEY_ID` | 空 | AK |
| `sks.sms.access-key-secret` | `ALIYUN_ACCESS_KEY_SECRET` | 空 | SK |
| `sks.sms.endpoint` | `ALIYUN_SMS_ENDPOINT` | `dysmsapi.aliyuncs.com` | POP 端点 |
| `sks.sms.sign-name` | `ALIYUN_SMS_SIGN` | 空 | 短信签名 |
| `sks.sms.verify-template-code` | `ALIYUN_SMS_VERIFY_TEMPLATE` | 空 | 验证码模板（含 `${code}` 变量） |
| `sks.sms.alert-template-code` | `ALIYUN_SMS_ALERT_TEMPLATE` | 空 | 告警模板（含 `${reason}` 变量） |

行为：

- `configured(type)` = access-key-id/secret/sign-name + 对应 template-code 都非空。
- **懒构造 `com.aliyun.dysmsapi20170525.Client`**：仅 configured 时 `new Client(new Config().setAccessKeyId(...).setAccessKeySecret(...).setEndpoint(endpoint))`，避免空 key 启动期炸。缓存实例。
- 每条 send：
  - 未 configured → `log.info("[SMS-STUB] ...")` 返回（**不抛**）。本地/CI 无 key 行为同今，不破坏现有测试。
  - configured → 构造 `SendSmsRequest`，`client.sendSms(req)`：
    - `body.code == "OK"` → 成功返回。
    - `body.code != "OK"`（如 `isv.BUSINESS_LIMIT_CONTROL` / `isv.MOBILE_NUMBER_ILLEGAL`）→
      `log.warn` + 抛 `BizException(SMS_SEND_FAILED)`。
    - 抛异常（网络/超时）→ `log.warn` + 抛 `BizException(SMS_SEND_FAILED)`。
- `TemplateParam`：
  - 验证码：`{"code":"<6位码>"}`
  - 告警：`{"reason":"<reason>"}`（reason 截断到模板变量长度上限内）

### 3.3 `ErrorCode.SMS_SEND_FAILED(5003, "短信发送失败，请稍后再试")`

新增，归 50xx 外部服务异常（5001=AI_FAILED / 5002=CONTENT_BLOCKED 之后）。

## 4. 调用点改造（最小）

### 4.1 `AuthService.sendCode`

构造多注入 `SmsClient`。`sendCode` 末尾：

```java
// 原：log.info("[SMS-STUB] send code to phone=...: code=...");
smsClient.sendVerificationCode(phone, code);
```

- 插 `sms_code` 行在前（不变）、调 `smsClient` 在后。
- 失败（`BizException(SMS_SEND_FAILED)`）透传 → `AuthController` → `5003`。
- 失败时 `sms_code` 行已落、计入频控 = 一次「尝试」语义成立（频控防滥用发码，失败尝试也该算）。

### 4.2 `QuotaWatchJob.sendAlert`

构造多注入 `SmsClient`。`sendAlert(reason)`：

```java
// 原：log.warn("[SMS-STUB] quota alert to admin=...");
smsClient.sendAlert(adminPhone, reason);
```

- `sweep` 已对每条 `sendAlert` try/catch + 记 WARN（不中断 Job），SMS 失败同样被兜底。
- `querySmsBalance` 不动（留桩 + TODO 注明 BSS）。

### 4.3 `RechargeOrderService` 两处 `[SMS-STUB]`

不动，加 TODO 注释指向本设计（下个任务接「站长通知」）。

## 5. 配置变更

### `application.yml`（新增 `sks.sms` 段）

```yaml
sks:
  sms:
    access-key-id: ${ALIYUN_ACCESS_KEY_ID:}
    access-key-secret: ${ALIYUN_ACCESS_KEY_SECRET:}
    endpoint: ${ALIYUN_SMS_ENDPOINT:dysmsapi.aliyuncs.com}
    sign-name: ${ALIYUN_SMS_SIGN:}
    verify-template-code: ${ALIYUN_SMS_VERIFY_TEMPLATE:}
    alert-template-code: ${ALIYUN_SMS_ALERT_TEMPLATE:}
```

### `.env`（gitignored 本地）

新增行（联调填真值）：

```
ALIYUN_SMS_SIGN=              # 已有行（空）→ 填审批通过的签名
ALIYUN_SMS_VERIFY_TEMPLATE=   # 新：形如 SMS_xxxxx，模板含 ${code} 变量
ALIYUN_SMS_ALERT_TEMPLATE=    # 新：告警模板，含 ${reason} 变量
```

`ALIYUN_ACCESS_KEY_ID/SECRET` 已有（空），联调填。

### `docker-compose.yml`

`sks-server` service env 透传新增两个 template env（mirror 现有 `ALIYUN_*` 透传方式）。

## 6. 测试

### 新 `AliyunSmsClientTest`（Mockito mock `Client`，不真打阿里云）

- 未 configured（key 空）→ `sendVerificationCode` 不抛、`sendSms` 未被调。
- configured + `sendSms` 返回 `code=OK` → 不抛。
- configured + `sendSms` 返回 `code=isv.BUSINESS_LIMIT_CONTROL` → 抛 `SMS_SEND_FAILED`。
- configured + `sendSms` 抛异常 → 抛 `SMS_SEND_FAILED`。
- `sendAlert` 同上四象限（失败由调用方兜底，但抛仍要正确）。

### 现有测试不受影响

- `AuthServiceTest`（`extends AbstractDbTest`，全 Spring 上下文 + Testcontainers pg16）：注入真实
  `AliyunSmsClient`（CI/local 无 key → stub 路径不抛），`sendCode` 行为同今，全绿。
- `QuotaWatchJobTest`：`sendAlert` 改调 `SmsClient`（`@MockBean` mock），断言调用参数
  （`sendAlert(adminPhone, reason)` 被调）。`checkAndAlert` 纯函数测试不动。

## 7. 不变式守（CLAUDE.md 硬约束）

- **无 Redis/MQ**：`@Component` + `@Value`，同 `QuotaWatchJob` 模式。
- **所有 key via `.env`**（gitignored），代码不硬编码 model/key。
- **Java 唯一公网入口**：SMS 在 Java 侧直发阿里云，不出网到 Python。
- **不碰钱路径**：credit 不受影响。验证码失败退到 `5003`，不涉及扣费/退款。
- **不引入 streaming**：无关。

## 8. 联调首检映射（GO_LIVE_CHECKLIST §3「阿里云短信」）

配齐 `ALIYUN_ACCESS_KEY_ID/SECRET` + `ALIYUN_SMS_SIGN` + `ALIYUN_SMS_VERIFY_TEMPLATE` +
`ALIYUN_SMS_ALERT_TEMPLATE` 后：
- `docker compose --env-file .env up -d --build`
- `POST /api/auth/send-code {"phone":"<真实手机号>"}` → 手机收到验证码短信
- 把 `sms-threshold` 调极高触发 `QuotaWatchJob.sendAlert` → 站长手机收到告警短信
- 失败路径：填错 sign/template → `send-code` 返回 `5003 SMS_SEND_FAILED`

## 9. 不在本设计内（YAGNI / 留下次）

- `querySmsBalance` BSS 余额查询（另一 SDK + AK BSS 权限 + 元/条换算）。
- `RechargeOrderService` 开通/补偿的站长通知短信。
- 国际短信 / 港澳台（MVP 仅大陆）。
- 短信发送异步化 / 队列重试（YAGNI，频控 + 失败抛已够）。
