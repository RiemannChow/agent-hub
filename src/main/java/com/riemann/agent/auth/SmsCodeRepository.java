package com.riemann.agent.auth;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Flux;

public interface SmsCodeRepository extends ReactiveMongoRepository<SmsCode, String> {
    Flux<SmsCode> findByPhone(String phone);
}
