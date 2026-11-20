package com.riemann.agent.auth;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 登录会话：token 即主键，关联 userId 与过期时间。
 *
 * <p>csrfToken 与会话同时生成，只对「靠 Cookie 自动携带凭证」的请求生效；前端用 Bearer header 时不经 Cookie，天然不受 CSRF 影响。旧会话该字段为
 * null，会被要求重新登录。
 */
@Document("sessions")
public record LoginSession(
        @Id String token, String userId, long createdAt, long expiresAt, String csrfToken) {}
