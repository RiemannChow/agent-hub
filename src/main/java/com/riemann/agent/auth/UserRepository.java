package com.riemann.agent.auth;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Mono;

public interface UserRepository extends ReactiveMongoRepository<User, String> {
    Mono<User> findByPhone(String phone);
    Mono<User> findByWechatOpenId(String openId);
}
