package com.riemann.agent.auth;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** 用户（手机号用户或微信用户，二者字段可空其一）。 */
@Document("users")
public record User(
        @Id String id,
        String phone,
        String wechatOpenId,
        String nickname,
        long createdAt,
        long lastLoginAt
) {
}
