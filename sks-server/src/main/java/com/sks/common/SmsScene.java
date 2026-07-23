package com.sks.common;

/** 短信场景（spec §3.1）：决定发送用哪个赠送模板。 */
public enum SmsScene {
    LOGIN_REGISTER,     // 登录/注册
    VERIFY_OLD_PHONE,   // 换绑 step1：验旧/当前号
    BIND_NEW_PHONE       // 换绑 step2：验新号
}
