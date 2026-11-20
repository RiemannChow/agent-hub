package com.riemann.agent.presentation.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A2A 推送通知配置：任务状态变化时把 Task 快照 POST 到 {@code url}。
 *
 * <p>{@code token} 是可选的回调共享密钥：服务端用它对通知体做 HMAC-SHA256 签名，对端据此验签， 避免任何人伪造任务状态通知。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PushNotificationConfig(String id, String url, String token) {

    public PushNotificationConfig {
        if (id == null || id.isBlank()) {
            id = "default";
        }
    }
}
