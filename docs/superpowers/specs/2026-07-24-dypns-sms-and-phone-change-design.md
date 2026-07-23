# 阿里云短信认证（DYPNS）接线 + 换绑手机号 设计

> 日期：2026-07-24。**本文 supersedes** `2026-07-23-aliyun-sms-wiring-design.md`（dysmsapi 版）——
> 那版基于「短信服务（Dysmsapi）」需企业资质，当前无企业资质走不通；本设计改用「号码认证服务」下的
> **短信认证**子能力（DYPNS `SendSmsVerifyCode`），个人实名即可接入。已合并到 main 的 dysmsapi 接线
> （`AliyunSmsClient` / `SmsClient.sendVerificationCode` / `AuthService.sendCode` / `QuotaWatchJob.sendAlert`）
> 将被本设计的 `AliyunSmsAuthClient` + scene 化接口取代；告警从短信改走邮件。

## 1. 背景与范围

C 端注册/登录均 = **手机号 + 验证码，无密码**（CLAUDE.md `auth`）。原 dysmsapi 接线因无企业资质无法联调。
改用 DYPNS 短信认证（个人实名 + 系统赠送签名/模板，无需审批）。同时新增**换绑手机号**账户 flow
（2-step：验旧号 → 验新号）。**重置密码不在范围**（C 端无密码，不成立）。

**范围**：

- ✅ `SmsClient` scene 化（3 scene）+ `AliyunSmsAuthClient`（dypnsapi `SendSmsVerifyCode`，字面码模式）。
- ✅ `SmsCodeMapper` 查询 scene 化（比对按 (phone,scene)；锁定改 ANY-row；频控保持按 phone 全局）——见 §3.0。
- ✅ 登录/注册验证码发送：`AuthService.sendCode` 调 `sendVerificationCode(phone, code, LOGIN_REGISTER)`。
- ✅ 换绑手机号 2-step flow：新 `UserPhoneService` + `phone_change_session` 表 + sms_code 扩列。
- ✅ 告警/站长通知：`AlertNotifier` + `MailAlertNotifier`（邮件 SMTP），`SmsClient` 删 `sendAlert`，
  `QuotaWatchJob` 注入从 `SmsClient` 换 `AlertNotifier`；`RechargeOrderService` 两处 stub 接通。
- ⏸ `querySmsBalance`：仍不接（BSS OpenAPI，独立任务）。
- ⏸ `RechargeOrderService` 两处站长通知：接 `AlertNotifier`（邮件）—— 默认纳入本 spec，实现期若开通/补偿
  flow 改动较大可拆出，但通知 seam（`AlertNotifier.notify`）本 spec 定下。
- ❌ 重置密码：不做（C 端无密码）。
- ❌ 一键登录 / 本机号码校验：不做（号码认证另一子能力，YAGNI）。

## 2. DYPNS API 事实（来自官方文档 + 用户提供的 SDK 示例）

- 产品：号码认证服务（PNVS/DYPNS）下的**短信认证**子能力（非「号码认证」一键登录）。
- endpoint：`dypnsapi.aliyuncs.com`。
- API：`SendSmsVerifyCode`（版本 2017-05-25）。SDK：`com.aliyun:dypnsapi20170525`（版本以 Maven Central
  最新为准，实现期核对；sample 用 2.0.0 时代 API）。
- 签名/模板：**必须用系统赠送签名 + 赠送模板**（控制台 `dypns.console.aliyun.com/smsCertParamsConfig/{sign,template}`），
  不支持自定义。赠送签名配赠送模板。用户真实值：SignName=`速通互联验证码`，登录/注册模板=`100002`
  （其余两模板码联调期从控制台领取后填 `.env`）。
- `SendSmsVerifyCodeRequest` 关键 setter（以 SDK javadoc 为终判）：
  `setPhoneNumber` / `setSignName` / `setTemplateCode` / `setTemplateParam` / `setCodeLength` / `setCodeType` /
  `setInterval`。调用 `client.sendSmsVerifyCodeWithOptions(req, runtime)`（`runtime` =
  `com.aliyun.teautil.models.RuntimeOptions`）。Config 经 `com.aliyun.teaopenapi.models.Config`
  `setAccessKeyId/setAccessKeySecret` + `endpoint`（不用 sample 的无 AK 凭据方式，走 .env AK/SK）。
- `TemplateParam`：字面码模式 `{"code":"<6位>","min":"5"}` —— **注意 `min` 变量**（有效分钟，来自用户 sample）。
  变量名以实际赠送模板占位为准（联调核对，不一致则改我们传的 key 名匹配模板，不改模板）。
- 响应：`body.Code=="OK"` = 请求成功；`body.Model`（含 `VerifyCode`/`BizId`/`OutId`）；`body.Message`；
  `body.Success`。失败看 `body.Message` + 错误码（`MOBILE_NUMBER_ILLEGAL` / `BUSINESS_LIMIT_CONTROL`(天级流控) /
  `FREQUENCY_FAIL`(频控) / `INVALID_PARAMETERS` / `FUNCTION_NOT_OPENED`）。异常类型 `TeaException`
  （`getMessage` + `getData().get("Recommend")` 诊断地址）。
- **字面码 vs 占位码**：`##code##` 占位 → 阿里云生成码 + 可 `CheckSmsVerifyCode` 核验；**字面值**
  （`{"code":"123456"}`）→ 阿里云只发不核验。本设计走**字面码**（Approach 1，Java 自生成码 + 自比对）。
- `CheckSmsVerifyCode` 契约（备查，本设计**不调用**）：必填 `PhoneNumber` + `VerifyCode`，响应
  `Model.VerifyResult` ∈ {`PASS`,`UNKNOWN`}。Approach 1 不用它。

## 3. 组件

### 3.0 SmsCodeMapper 查询 scene 化 / 频控 / 锁定（Critical — 不改会跨 scene 串码）

sms_code 加 `scene` + `session_token` 列后，现有四个查询必须按下调整，否则换绑与登录跨 scene 互用码：

- **频控** `countLastMinute/Hour/24h(phone)`：**保持按 phone 全局，不加 scene 参数**。日限本质是成本/骚扰控制，
  应按号全局（同号每天 ≤10 条跨所有 scene 合计），且与 DYPNS 服务端 `Interval=60`（按号全局）一致。
  **接受「同号 60s 内跨 scene 发码被本地 + 服务端双重拦」**（如刚发登录码 60s 内发不出换绑旧号码）——
  这正是防滥用该有的行为，非 bug。
- **比对查询** `findActiveCode(phone, scene)`：**加 `scene` 参数**，按 `(phone, scene)` 过滤取「最近一条未用未过期码」。
  - `login` 传 `LOGIN_REGISTER`；`verify-old` 传 `VERIFY_OLD_PHONE`；`verify-new` 传 `BIND_NEW_PHONE`。
  - **防跨 scene 码互用**：登录码（LOGIN_REGISTER）不能拿来过 verify-old；反之亦然。不加 scene 过滤则
    `findActiveCode(phone)` 会取到别 scene 的行 → 比对错行、err_count 记错行、跨 scene 码互用。
- **锁定判定** `existsLocked(phone)`：**改为 ANY-row** ——
  `EXISTS(sms_code WHERE phone=? AND err_count>=5 AND created_at > now()-10min)`，任一 scene 锁 5 次则**全号锁**。
  - 防绕过：若只看「最近一行 err_count」，攻击者可在 4 错后插一条别 scene 0 错码行（更近），使
    `findMostRecent` 读到 0 错行 → 锁定被稀释。ANY-row 修正此绕过。
  - **此改动细化 `AuthService.checkLocked`**：从 `findMostRecent(phone).err_count>=5` → `existsLocked(phone)`。
    单 scene 下行为等价（锁定窗口内发新码被锁拦，故仅一行）；多 scene 下修正绕过。AuthService 现有锁定
    测试须仍绿（会，单 scene 等价）。
- `findMostRecent(phone)`：保留（测试 `realCodeOf` 用；锁定改走 `existsLocked`）。

### 3.1 `SmsScene` 枚举（`com.sks.common`，新）

```
LOGIN_REGISTER      // 登录/注册
VERIFY_OLD_PHONE    // 换绑 step1：验旧/当前号（验证绑定手机号模板）
BIND_NEW_PHONE      // 换绑 step2：验新号（绑定新手机号模板）
```

3 scene。重置密码 / 修改绑定手机号 不在列。

### 3.2 `SmsClient` 接口（`com.sks.common`，改）

```java
void sendVerificationCode(String phone, String code, SmsScene scene);
```

- 原 2 参签名改 3 参。`AuthService.sendCode` 传 `LOGIN_REGISTER`；`UserPhoneService` 按步骤传另两 scene。
- **显式删除 `sendAlert(String phone, String reason)` 方法**（原接口有，`QuotaWatchJob` 在用）。告警改走
  `AlertNotifier`（§3.4），`SmsClient` 不再背告警职责。

### 3.3 `AliyunSmsAuthClient`（`@Component implements SmsClient`，替换 dysmsapi 版 `AliyunSmsClient`）

构造读 `@Value` `sks.sms.*`：`access-key-id` / `access-key-secret` / `endpoint`(默认
`dypnsapi.aliyuncs.com`) / `sign-name` / `template-login` / `template-verify-old` / `template-bind-new`，
均空默认。

- `configured(scene)` = access-key-id/secret/sign-name + 该 scene 对应 template-code 都非空。
- 懒构造 `dypnsapi20170525.Client`（仅 configured 时 `new Client(new Config().setAccessKeyId(...)
  .setAccessKeySecret(...).endpoint=endpoint)`），缓存。空 key 不破 bean 构造。
- `sendVerificationCode(phone, code, scene)`：
  - 未 configured → `log.info("[SMS-STUB] scene={} phone={} code={}", ...)` 返回，**不抛**（本地/CI 行为不变）。
  - configured → 按 scene 选 templateCode；构造 `SendSmsVerifyCodeRequest`：
    `setPhoneNumber(phone)` / `setSignName(signName)` / `setTemplateCode(templateCode)` /
    `setTemplateParam("{\"code\":\""+code+"\",\"min\":\"5\"}")` / `setCodeLength("6")` / `setCodeType(1)` /
    `setInterval(60)`；`client.sendSmsVerifyCodeWithOptions(req, runtime)`：
    - `body.Code=="OK"` → 成功返回。
    - `body.Code!="OK"` → `log.warn` + `throw new BizException(ErrorCode.SMS_SEND_FAILED)`。
    - 抛异常（`TeaException` / 其它）→ `log.warn` + `throw BizException(SMS_SEND_FAILED)`。
- `setDelegate(Client)` 包级字段 `override` 注入 Mockito mock，跳过懒构造（测试 seam，同旧版）。
- 实现期核实点：`CodeLength`/`CodeType` 在 doc 标「占位符模式才必填」，字面码模式可能被忽略或拒 ——
  先按设计设上，联调若报错再去掉。

### 3.4 `AlertNotifier` 接口 + `MailAlertNotifier`（`com.sks.common`，新）

```java
public interface AlertNotifier { void notify(String subject, String content); }
```

`MailAlertNotifier`（`@Component`）发邮件给站长邮箱（`sks.alert.admin-email`）。

- **注入用 `ObjectProvider<JavaMailSender>`**（非直接 `JavaMailSender`）：`spring.mail.host` 为空字符串时
  Spring Boot 的 `MailSenderAutoConfiguration` 处于「属性存在但为空」的边缘态，**别赌 bean 一定存在**；
  `ObjectProvider` 按需取，无则走 stub。
- 条件降级：`spring.mail.host` 空 / 无 `JavaMailSender` bean → `log.info("[ALERT-STUB] ...")` 不抛（本地/CI 无 SMTP）。
- 失败 → `log.warn` 吞掉，**不抛**（告警失败不阻断主流程；`QuotaWatchJob.sweep` 已 try/catch 兜底，
  `RechargeOrderService` 通知失败也不阻断开通/补偿）。

### 3.5 `UserPhoneService`（`com.sks.user`，新）—— 换绑 2-step flow 主体

需 C 端 JWT。事务边界见 §6。

- `sendOldPhoneCode(userId)`：取当前 `app_user.phone` → 频控 + 锁定判定（`existsLocked`）→ 生成码 + 写
  `sms_code(scene=VERIFY_OLD_PHONE)` + 建/覆 `phone_change_session(status=AWAITING_OLD_VERIFY,
  old_phone=current, token=T 建行即生成, expires_at=now()+10min)` →
  `smsClient.sendVerificationCode(oldPhone, code, VERIFY_OLD_PHONE)`。
  - **建/覆**：表有部分唯一索引 `UNIQUE(user_id) WHERE status<>'DONE'`（见 §4 Flyway），作并发兜底（同用户
    并发两发 send-old-code → 一成一撞）。**正常路径先删后建**：`DELETE FROM phone_change_session WHERE
    user_id=? AND status<>'DONE'` 再 INSERT 新行 —— **不标 DONE**（DONE 语义仅留给换绑成功完成，标旧 DONE
    会污染语义），也**不用 `ON CONFLICT`**（部分唯一索引配 `ON CONFLICT` 须带谓词 `(user_id) WHERE
    status<>'DONE'`，不带会报「找不到匹配约束」语法坑，直接避开）。
  - token T **建行即生成并存表，但此时不返客户端**。
- `verifyOldPhone(userId, code)`：错码 `err_count++`（SMS_CODE_INVALID，达 5 锁，经 `findActiveCode(phone,
  VERIFY_OLD_PHONE)`）；对码 → **`markUsed` 该旧号码 sms_code 行**（镜像 `completeLogin`，防 5 分钟 TTL 内
  重放 verify-old 反复重置 expires_at、变相无限续窗口）+ `session.status=AWAITING_NEW_VERIFY,
  old_verified_at=now` + **`expires_at` 重置 = now()+10min（new-bind 窗口）** + **此时才把 T 返客户端**。
  - markUsed + UPDATE session 是两条**自动提交写（非事务）**，不原子也无害：中间崩了用户重新发码即可
    （旧码已 markUsed 无法重放，发新码走频控）。verify-new 的作废 pending 码是后续兜底，中间窗口靠 markUsed 先堵。
  - 修正原表述「verify-old 通过才发短命 token」与表定义（建行即 token+expires_at）的矛盾：token 建行即
    存，但**有效期窗口从 verify-old 通过起算**（重置 expires_at），避免用户花 9 分钟输对旧码后新号步骤只剩 1 分钟。
- `sendNewPhoneCode(userId, newPhone, T)`：验 T（`status=AWAITING_NEW_VERIFY` + 未过期）→ 校 newPhone 非空/格式/
  非同旧号（PARAM_INVALID）→ 校 newPhone 未被他人占用（PHONE_ALREADY_BOUND，预查）→ 频控 newPhone +
  **`existsLocked(newPhone)` 锁定判定**（newPhone 侧也防 5 次暴破，镜像旧号侧）→
  生成码 + 写 `sms_code(scene=BIND_NEW_PHONE, session_token=T)` + `session.new_phone=newPhone` →
  `smsClient.sendVerificationCode(newPhone, code, BIND_NEW_PHONE)`。
- `verifyNewPhone(userId, newPhone, code, T)`：验 T → **`existsLocked(newPhone)` 锁定判定**（newPhone 侧防
  暴破，镜像 send-new-code）→ **断言 `newPhone == session.new_phone`，不符→
  `PHONE_CHANGE_TOKEN_INVALID`**（token 绑定特定 newPhone；send-new-code 发 A 号、verify-new 传 B 号必须拒，
  否则码和号的绑定关系被绕开）→ 错码 `err_count++`（newPhone 码，经 `findActiveCode(newPhone, BIND_NEW_PHONE)`)；
  对码 → 经 self 代理 `completePhoneChange(T, newPhone)`（`@Transactional`）：`UPDATE app_user.phone=newPhone`
  （UNIQUE 约束兜底并发）+ `session.status=DONE` + 作废所有 pending sms_code（session_token=T 或
  phone∈{oldPhone,newPhone}）+ token 消费。
  - **UNIQUE 冲突翻译位置（Critical）**：`app_user.phone` UNIQUE 冲突的 `DuplicateKeyException` 翻成
    `PHONE_ALREADY_BOUND` 的 catch **必须在 self 代理 `@Transactional` 方法 `completePhoneChange` 之外的
    调用方 `verifyNewPhone`（非事务）里**。事务内 catch 会令事务 rollback-only，继续走抛
    `UnexpectedRollbackException`（即 refund 处深挖过的同款坑）。`completePhoneChange` 让
    `DuplicateKeyException` 透传出（已回滚的）事务，由外层 `verifyNewPhone` catch → `PHONE_ALREADY_BOUND`。

### 3.6 ErrorCode 追加

复用：`SMS_RATE_LIMIT(4002)` / `SMS_CODE_INVALID(4003)` / `SMS_CODE_LOCKED(4004)` / `SMS_SEND_FAILED(5003)`。
新增：
- `PHONE_ALREADY_BOUND(4007, "该手机号已被其他账号绑定")`
- `PHONE_CHANGE_TOKEN_INVALID(4008, "换绑凭证无效或已过期，请重新发起")`

其余（newPhone 空/格式错/同旧号）用 `PARAM_INVALID(4005)`，不新加码。

### 3.7 删除 / 替换

删旧 dysmsapi 版 `AliyunSmsClient`。`ErrorCode.SMS_SEND_FAILED(5003)` 保留。`SmsClient.sendAlert` 删（§3.2）。
`QuotaWatchJob` 注入从 `SmsClient` 换 `AlertNotifier`（`sendAlert(reason)` → `alertNotifier.notify(...)`）。
`RechargeOrderService` 两处 `[SMS-STUB]` 改调 `AlertNotifier.notify(...)`。

## 4. 配置变更

### `pom.xml`

- `com.aliyun:dysmsapi20170525` → 换 `com.aliyun:dypnsapi20170525`（版本以 Maven Central 为准）。
- 加 `spring-boot-starter-mail`。

### `application.yml`（新增/改 `sks.sms` + `spring.mail` + `sks.alert`）

```yaml
sks:
  sms:
    access-key-id: ${ALIYUN_ACCESS_KEY_ID:}
    access-key-secret: ${ALIYUN_ACCESS_KEY_SECRET:}
    endpoint: ${ALIYUN_SMS_ENDPOINT:dypnsapi.aliyuncs.com}
    sign-name: ${ALIYUN_SMS_SIGN:}
    template-login: ${ALIYUN_SMS_TEMPLATE_LOGIN:}
    template-verify-old: ${ALIYUN_SMS_TEMPLATE_VERIFY_OLD:}
    template-bind-new: ${ALIYUN_SMS_TEMPLATE_BIND_NEW:}
spring:
  mail:
    host: ${SPRING_MAIL_HOST:}
    port: ${SPRING_MAIL_PORT:465}
    username: ${SPRING_MAIL_USERNAME:}
    password: ${SPRING_MAIL_PASSWORD:}
    properties:                              # 465 = SMTPS，必须显式开 SSL（默认明文 SMTP 会挂起/隐晦失败）
      mail.smtp.ssl.enable: true
      # 若改 587 则用 mail.smtp.starttls.enable: true 并去掉 ssl.enable
sks.alert.admin-email: ${SKS_ALERT_ADMIN_EMAIL:}
```

### `.env`（gitignored，联调填真值）

```
ALIYUN_ACCESS_KEY_ID=<子账号 AK，需 dypns:SendSmsVerifyCode 权限>
ALIYUN_ACCESS_KEY_SECRET=<子账号 SK>
ALIYUN_SMS_ENDPOINT=dypnsapi.aliyuncs.com
ALIYUN_SMS_SIGN=速通互联验证码
ALIYUN_SMS_TEMPLATE_LOGIN=100002
ALIYUN_SMS_TEMPLATE_VERIFY_OLD=<赠送模板，联调领>
ALIYUN_SMS_TEMPLATE_BIND_NEW=<赠送模板，联调领>
SPRING_MAIL_HOST=<SMTP 主机>
SPRING_MAIL_PORT=465
SPRING_MAIL_USERNAME=<SMTP 账号>
SPRING_MAIL_PASSWORD=<SMTP 授权码，密钥>
SKS_ALERT_ADMIN_EMAIL=站长邮箱
```

旧 `ALIYUN_SMS_ALERT_TEMPLATE` 删除（告警不走短信）。`ALIYUN_SMS_VERIFY_TEMPLATE` 改名
`ALIYUN_SMS_TEMPLATE_LOGIN`。

### `docker-compose.yml`

无需改（`sks-server` 已 `env_file: .env`，新增 env 自动透传）。

### Flyway `V3__sms_scene_and_phone_change.sql`

- `sms_code` 加列：`scene VARCHAR(32) NOT NULL DEFAULT 'LOGIN_REGISTER'`、`session_token VARCHAR(64)`
  （nullable；LOGIN_REGISTER 行为 null）。
- 建 `phone_change_session`（含部分唯一索引防重入堆行）：
  ```sql
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
  CREATE UNIQUE INDEX uq_phone_change_active ON phone_change_session(user_id) WHERE status <> 'DONE';
  ```
  `uq_phone_change_active`：同一用户同时只允许一个未完成 session，send-old-code 重入/并发不会堆多行。

## 5. 数据流

### 5.1 登录/注册验证码（已有，仅签名 + findActiveCode scene 化）

`AuthController send-code` → `AuthService.sendCode`（频控 + 锁定判定 `existsLocked` + 生成码 + 写 sms_code +
`smsClient.sendVerificationCode(phone, code, LOGIN_REGISTER)`）→ login 比对
`findActiveCode(phone, LOGIN_REGISTER)`（同今本地自比对，**不调 CheckSmsVerifyCode**）。

### 5.2 换绑手机号（新，2-step，需 C 端 JWT）

```
1. POST /api/user/phone/change/send-old-code           [JWT]
   → UserPhoneService.sendOldPhoneCode: 频控+锁定(existsLocked) → 码+sms_code(VERIFY_OLD_PHONE)
     + 建/覆 session(AWAITING_OLD_VERIFY, token=T建行即生成, expires_at=now()+10min)
     → smsClient.sendVerificationCode(oldPhone, code, VERIFY_OLD_PHONE)
2. POST /api/user/phone/change/verify-old {code}        [JWT]
   → verifyOldPhone: 错码 err_count++(findActiveCode(phone,VERIFY_OLD_PHONE), SMS_CODE_INVALID)
     / 对码 markUsed旧号码行 + session.status=AWAITING_NEW_VERIFY + old_verified_at=now
     + expires_at重置=now()+10min + 返T
3. POST /api/user/phone/change/send-new-code {newPhone,T}
   → sendNewPhoneCode: 验T(AWAITING_NEW_VERIFY+未过期) → 校newPhone(空/格式/同旧→PARAM_INVALID;
     占号→PHONE_ALREADY_BOUND 预查) → 频控newPhone + existsLocked(newPhone) → 码+sms_code(BIND_NEW_PHONE,token=T)
     + session.new_phone=newPhone → smsClient.sendVerificationCode(newPhone,code,BIND_NEW_PHONE)
4. POST /api/user/phone/change/verify-new {newPhone,code,T}
   → verifyNewPhone: 验T → existsLocked(newPhone) → 断言newPhone==session.new_phone(不符→PHONE_CHANGE_TOKEN_INVALID)
     → 错码 err_count++(findActiveCode(newPhone,BIND_NEW_PHONE)) / 对码 → self.completePhoneChange(T,newPhone)
       @Transactional: UPDATE app_user.phone=newPhone(UNIQUE兜底,DuplicateKeyException透传)
       + session.status=DONE + 作废pending码 + token消费
     [外层verifyNewPhone catch DuplicateKeyException → PHONE_ALREADY_BOUND]
```

- JWT：subject = `user_id`（已核 `JwtUtil.issue(user.getId(), "user", ...)`）—— 确定，换绑后 token 仍有效，
  无需重发。
- 丢旧号自助兜底：MVP 无 → 站长后台手工改绑（TODO）。

## 6. 错误处理 + 事务边界

- `send-old-code` / `send-new-code`：**非 `@Transactional`**（频控查 + sms_code insert 自动提交 + SMS 发送
  在 insert 之后、tx 外）——同 `AuthService.sendCode`。
- `verify-old` 错码路径：**非 `@Transactional`**（`err_count++` 必须即时提交，否则 BizException 回滚使锁定
  形同虚设 —— 即 AuthService 那条 Critical 回归）。对码成功：**`markUsed` 旧号码 sms_code 行 + UPDATE
  session（重置 expires_at + status）+ 返 T** —— 两条自动提交写（不原子无害，中间崩则重发码；旧码已
  markUsed 无法重放，堵住「重放 verify-old 反复重置 expires_at 续窗口」）。
- `verify-new` 错码路径：**非 `@Transactional`**（newPhone 码 err_count）。对码成功：**`@Transactional` 经
  self 代理 `completePhoneChange`**（UPDATE app_user.phone + session.status=DONE + 作废 pending 码，原子收尾）。
- **newPhone 侧锁定判定**：send-new-code + verify-new 入口都查 `existsLocked(newPhone)`（newPhone 的 6 位码
  也防 5 次暴破，镜像旧号侧；§3.5 已写，§7 有对应测试）。
- **PHONE_ALREADY_BOUND 的 catch 位置**：`app_user.phone` UNIQUE 冲突 → `DuplicateKeyException` 透传出
  `completePhoneChange`（事务已回滚），**在调用方 `verifyNewPhone`（非事务）catch** → `PHONE_ALREADY_BOUND`。
  **切勿在 `completePhoneChange` 事务内 catch**（rollback-only → `UnexpectedRollbackException`）。
- SMS 发送失败（5003）：sms_code 行已落、计一次频控尝试（失败尝试也算，同 AuthService 语义）。
- 告警邮件失败：`MailAlertNotifier` 吞掉，不抛、无码。
- 并发：newPhone 唯一性靠 `app_user.phone UNIQUE` 约束兜底；send-new-code 预查 + verify-new 约束兜底双保险。
- 成功后作废：verify-new 成功 → 所有 pending sms_code（session_token=T 或 phone∈{oldPhone,newPhone}）标
  `used=true`，防重放。

## 7. 测试

- **`AliyunSmsAuthClientTest`**（unit，mock `dypnsapi20170525.Client`，`setDelegate` 注入）：
  - 未 configured → 3 scene 都 stub 不抛、`sendSmsVerifyCodeWithOptions` 未调。
  - configured + `body.Code="OK"` → 不抛；断言 request `setPhoneNumber`/`setSignName`/`setTemplateCode`/
    `setTemplateParam`(含 `code`+`min`)/`setCodeLength("6")`/`setCodeType(1)`/`setInterval(60)`。
  - configured + `body.Code≠"OK"` → `BizException`，`errorCode()==SMS_SEND_FAILED`。
  - configured + client 抛 → `SMS_SEND_FAILED`。
  - scene→template 映射：`LOGIN_REGISTER`→`template-login`、`VERIFY_OLD_PHONE`→`template-verify-old`、
    `BIND_NEW_PHONE`→`template-bind-new`（3 scene 都断言 `setTemplateCode` 取对应值）。
- **`MailAlertNotifierTest`**（unit，`ObjectProvider<JavaMailSender>` mock）：
  - 未 configured / 无 bean → 不抛、`send` 未调、stub 日志。
  - configured → `MimeMessage` 收件人=站长邮箱、subject/content 正确。
  - `send` 抛 → 被吞、调用方无感、`log.warn`。
- **`AuthServiceTest`**（改）：`sendCodeDelegatesToSmsClient` 断言改
  `verify(smsClient).sendVerificationCode(eq(phone), eq(row.getCode()), eq(SmsScene.LOGIN_REGISTER))`。
  锁定测试改走 `existsLocked`（单 scene 行为等价，仍绿）；login 经 `findActiveCode(phone, LOGIN_REGISTER)`
  仍查到 sendCode 写的码。其余频控/login 测试不动。
- **`QuotaWatchJobTest`**（改）：注入 `mock(AlertNotifier)`；`sendAlert` 断言改
  `verify(alertNotifier).notify(eq(...), eq(reason))`。`checkAndAlert` 纯函数测试不动。
- **`UserPhoneServiceTest`**（新，extends AbstractDbTest，Testcontainers pg16）：
  - send-old-code：调 `sendVerificationCode(oldPhone, code, VERIFY_OLD_PHONE)` + 写 `sms_code(VERIFY_OLD_PHONE)`；频控生效；
    重入建/覆 session（部分唯一索引，不堆多行）。**实现提示**：连发两次会先撞 1 分钟 ≤1 频控
    （`SMS_RATE_LIMIT`）走不到覆盖逻辑 —— 测试须回拨第一条 `sms_code.created_at`（`realCodeOf` 同款直改表
    `UPDATE sms_code SET created_at=now()-interval '2 min' WHERE phone=?`）绕过频控，再发第二次验证建/覆（先删后建，旧行不在）。
  - **跨 scene 码互用被拒**：发登录码（LOGIN_REGISTER）后拿该码 verify-old → 失败（`findActiveCode(phone,
    VERIFY_OLD_PHONE)` 取不到登录码行）；反之亦然。
  - verify-old 错码 → `err_count++`；5 次错 → `SMS_CODE_LOCKED`（用 `Propagation.NOT_SUPPORTED`/非事务跑，
    断言跨事务持久，镜像 `fiveWrongAttemptsLockPersistsAcrossTransactions` 防 AbstractDbTest `@Transactional` 掩盖）。
  - verify-old 对码 → 返 T，session.status=AWAITING_NEW_VERIFY，expires_at 重置；**旧号码 sms_code 行
    markUsed**（重放 verify-old 不再发新 T，窗口不被无限续）。
  - **锁定稀释绕过被堵**：4 错 verify-old 后插一条 LOGIN_REGISTER 0 错码行，第 5 错 verify-old →
    `existsLocked` 仍判锁（ANY-row），send-old-code 被拦。
  - send-new-code 无/坏/过期/消费 T → `PHONE_CHANGE_TOKEN_INVALID`；newPhone==oldPhone → `PARAM_INVALID`；
    newPhone 占号 → `PHONE_ALREADY_BOUND`（预查）。
  - send-new-code 合法 → 调 `sendVerificationCode(newPhone, code, BIND_NEW_PHONE)` + 写码 + session.new_phone 落。
  - verify-new `newPhone != session.new_phone` → `PHONE_CHANGE_TOKEN_INVALID`。
  - verify-new 错码 → newPhone 码 err_count++；**5 次错 newPhone → `SMS_CODE_LOCKED`**
    （newPhone 侧 `existsLocked` 镜像旧号侧，防 newPhone 6 位码暴破；send-new-code 入口同判锁）。
  - verify-new 对码 → `app_user.phone` A→B；session.status=DONE；pending 码作废；token 消费（同 T 再 verify-new →
    `PHONE_CHANGE_TOKEN_INVALID`）。
  - 并发占号：预插另一用户 phone=B → `completePhoneChange` 抛 `DuplicateKeyException` → 外层 verify-new catch
    → `PHONE_ALREADY_BOUND`（**断言不在事务内 catch**，无 `UnexpectedRollbackException`）。
  - `@MockBean SmsClient`（stub），码从 `sms_code` 表直查（复用 `realCodeOf` 套路）。
- 回归：现有 147 Java 测试除上述 2 处断言改动 + `existsLocked` 等价细化外全绿；Python 不受影响。

## 8. 不变式守（CLAUDE.md 硬约束）

- **无 Redis/MQ**：scene 化 `@Component` + `@Value`；session 用 `phone_change_session` 表 + 过期懒清理
  （查询时 `expires_at` 过滤即可，无需 `@Scheduled`；或加 `@Scheduled` 兜底清理 DONE/过期行，YAGNI 视实现定）。
- **所有 key via `.env`**（gitignored）：AK/SK、SMTP 授权码、赠送签名/模板码、站长邮箱。代码不硬编码。
- **Java 唯一公网入口**：DYPNS + SMTP 都 Java 侧直发，不出网到 Python。
- **不碰钱路径**：换绑改 `app_user.phone` 不涉扣费/退款；验证码失败 5003 不涉钱；告警失败吞掉。
- **不引入 streaming**：无关。
- **Testcontainers pgvector/pgvector:pg16（非 H2）**：`UserPhoneServiceTest` 走真实 PG。
- **事务边界 Critical**：err_count 自增路径非事务（防回滚使锁定失效）；verify-new 成功收尾经 self 代理
  `@Transactional` 原子化（镜像 AuthService `completeLogin`）；UNIQUE 冲突在事务外 catch（防 rollback-only）。

## 9. 联调首检映射（GO_LIVE_CHECKLIST §3 + 新增换绑）

配齐 `.env`（AK/SK + sign + 3 模板 + SMTP + 站长邮箱）后：
- `docker compose --env-file .env up -d --build`。
- 登录/注册：`POST /api/auth/send-code {"phone":"<真>"}` → 真收短信（字面码，收到的码 = 我们生成）。
- 换绑：登录 → send-old-code → verify-old（收旧号码）→ send-new-code → verify-new（收新号码）→
  app_user.phone 更新。
- 告警邮件：把 `sms-threshold` 调极高触发 `QuotaWatchJob` → 站长**邮箱**收告警邮件
  （**GO_LIVE_CHECKLIST §3 / §7 原「站长手机收告警短信」验收项同步改成「站长邮箱收告警邮件」**）。
- 失败路径：填错 sign/template → send-code 返回 `5003`；SMTP 错 → 日志 warn（不阻断）。

## 10. 不在本设计内（YAGNI / 留下次）

- `CheckSmsVerifyCode` 阿里云核验（Approach 2）—— Approach 1 已够，自比对 + 本地频控覆盖。
- `querySmsBalance` BSS 余额查询（独立任务）。
- 丢旧号自助换绑兜底（客服手工改绑留 TODO）。
- 一键登录 / 本机号码校验（号码认证另一子能力）。
- 短信发送异步化 / 队列重试（YAGNI，频控 + 失败抛已够）。

## 11. 参考文档

- SendSmsVerifyCode API：https://help.aliyun.com/zh/pnvs/developer-reference/api-dypnsapi-2017-05-25-sendsmsverifycode
- CheckSmsVerifyCode API（备查，不调）：https://help.aliyun.com/zh/pnvs/developer-reference/api-dypnsapi-2017-05-25-checksmsverifycode
- 赠送签名配置：https://dypns.console.aliyun.com/smsCertParamsConfig/sign
- 赠送模板配置：https://dypns.console.aliyun.com/smsCertParamsConfig/template
- OpenAPI 在线调试（Java 示例）：https://next.api.aliyun.com/api/Dypnsapi/2017-05-25/SendSmsVerifyCode?tab=DEMO&lang=JAVA
- Maven dypnsapi20170525：https://mvnrepository.com/artifact/com.aliyun/dypnsapi20170525
- Client javadoc：https://aliyunsdk-pages.alicdn.com/apidocs/Dypnsapi/2017-05-25/java-tea/latest/com/aliyun/dypnsapi20170525/Client.html
- 个人开发者接入：https://help.aliyun.com/zh/pnvs/use-cases/sms-verify-for-individual-developers
