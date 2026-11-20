package com.riemann.agent.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 登录安全配置。所有开关都有安全默认值，生产环境通过环境变量覆盖。 */
@ConfigurationProperties(prefix = "agent.auth")
public record AuthProperties(Sms sms, Wechat wechat, Csrf csrf) {

    public record Sms(
            String mode,
            String gatewayUrl,
            String gatewayToken,
            Duration resendCooldown,
            int maxPerHour,
            int maxAttempts) {}

    public record Wechat(boolean demoConfirm, String callbackToken, Duration allowedSkew) {}

    public record Csrf(boolean enabled) {}
}
