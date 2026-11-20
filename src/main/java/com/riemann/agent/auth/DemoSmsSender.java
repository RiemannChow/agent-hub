package com.riemann.agent.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/** 演示通道：不发短信，验证码由登录页直接展示。默认开启，生产必须切到 http。 */
public class DemoSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(DemoSmsSender.class);

    @Override
    public Mono<Void> send(String phone, String code) {
        log.warn("DEMO SMS mode: code for {} was NOT delivered by any gateway", phone);
        return Mono.empty();
    }
}
