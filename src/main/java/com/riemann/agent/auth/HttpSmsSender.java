package com.riemann.agent.auth;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** 生产通道：把验证码 POST 到外部短信网关，不在响应里回传明文。 */
@Component
@ConditionalOnProperty(prefix = "agent.auth.sms", name = "mode", havingValue = "http")
public class HttpSmsSender implements SmsSender {

    private final WebClient client;
    private final String gatewayToken;

    public HttpSmsSender(AuthProperties properties) {
        AuthProperties.Sms cfg = properties.sms();
        if (cfg == null || cfg.gatewayUrl() == null || cfg.gatewayUrl().isBlank()) {
            throw new IllegalStateException("agent.auth.sms.gateway-url is required in http mode");
        }
        this.client = WebClient.builder().baseUrl(cfg.gatewayUrl()).build();
        this.gatewayToken = cfg.gatewayToken();
    }

    @Override
    public Mono<Void> send(String phone, String code) {
        return client.post()
                .bodyValue(Map.of("phone", phone, "code", code))
                .headers(
                        headers -> {
                            if (gatewayToken != null && !gatewayToken.isBlank()) {
                                headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + gatewayToken);
                            }
                        })
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(10))
                .then();
    }
}
