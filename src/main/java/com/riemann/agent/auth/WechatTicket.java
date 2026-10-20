package com.riemann.agent.auth;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** 微信扫码登录票据：状态 PENDING -> CONFIRMED，确认后写入 token 与 userId。 */
@Document("wechat_tickets")
public record WechatTicket(
        @Id String ticket,
        String status,
        String openId,
        String userId,
        String token,
        long createdAt,
        long expiresAt
) {
}
