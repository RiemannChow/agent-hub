package com.riemann.agent.auth;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

/** token 即文档主键，故直接用 findById(token) 查询。 */
public interface SessionRepository extends ReactiveMongoRepository<LoginSession, String> {
}
