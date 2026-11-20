package com.riemann.agent.auth;

import reactor.core.publisher.Mono;

/** 短信下发通道。demo 模式为 no-op（验证码由 API 响应返回），http 模式调用外部网关。 */
public interface SmsSender {
    Mono<Void> send(String phone, String code);
}
