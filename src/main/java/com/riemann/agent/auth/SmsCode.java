package com.riemann.agent.auth;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** 短信验证码（演示环境由后端直接返回，真实环境改为短信下发）。 */
@Document("sms_codes")
public record SmsCode(
        @Id String id,
        String phone,
        String code,
        long createdAt,
        long expiresAt
) {
}
