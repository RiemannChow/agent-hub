package com.riemann.agent.auth;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 短信验证码。attempts 用于限制暴力破解：连续输错达到上限即作废该验证码。
 *
 * <p>旧文档没有 attempts 字段时 Spring Data 会绑定为 0，兼容已有数据。
 */
@Document("sms_codes")
public record SmsCode(
        @Id String id, String phone, String code, long createdAt, long expiresAt, int attempts) {}
