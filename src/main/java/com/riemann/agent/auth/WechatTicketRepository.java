package com.riemann.agent.auth;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

public interface WechatTicketRepository extends ReactiveMongoRepository<WechatTicket, String> {
}
