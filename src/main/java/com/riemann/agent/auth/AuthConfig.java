package com.riemann.agent.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 登录安全相关的 bean 装配。sms.mode=http 时改用 HttpSmsSender。 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfig {

    @Bean
    @ConditionalOnProperty(
            prefix = "agent.auth.sms",
            name = "mode",
            havingValue = "demo",
            matchIfMissing = true)
    public SmsSender demoSmsSender() {
        return new DemoSmsSender();
    }
}
