package com.riemann.agent.auth;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** 登录会话：token 即主键，关联 userId 与过期时间。 */
@Document("sessions")
public record LoginSession(
        @Id String token,
        String userId,
        long createdAt,
        long expiresAt
) {
}
