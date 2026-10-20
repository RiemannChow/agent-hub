package com.riemann.agent.infrastructure.mongo;

import com.riemann.agent.domain.*;
import com.riemann.agent.domain.AgentState;
import com.riemann.agent.domain.Conversation;
import com.riemann.agent.domain.ConversationId;
import com.riemann.agent.domain.ConversationRepository;

import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * MongoDB 仓储实现（备选）。
 * 通过 @Profile("mongo") 与 SeekDbConversationRepository 二选一。
 *
 * 说明：Domain 层保持纯净、不依赖任何框架注解，因此这里直接用 ReactiveMongoTemplate
 * 读写 Conversation 聚合（生产环境可对 Conversation 增加 @Document/@Id 以精确映射）。
 */
@Repository
@Profile("mongo")
public class MongoConversationRepository implements ConversationRepository {

    private final ReactiveMongoTemplate mongo;

    public MongoConversationRepository(ReactiveMongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> save(Conversation conversation) {
        return mongo.save(conversation).then();
    }

    @Override
    public Mono<Void> appendMessages(ConversationId id, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return Mono.empty();
        }
        return mongo.findById(id.value(), Conversation.class)
                .map(c -> { messages.forEach(c::addMessage); return c; })
                .flatMap(mongo::save)
                .then();
    }

    @Override
    public Mono<Conversation> findById(ConversationId id) {
        return mongo.findById(id.value(), Conversation.class);
    }

    @Override
    public Flux<Conversation> findByUserId(String userId) {
        return mongo.find(Query.query(Criteria.where("userId").is(userId)), Conversation.class);
    }

    @Override
    public Mono<Void> updateAgentState(ConversationId id, AgentState state) {
        return mongo.findById(id.value(), Conversation.class)
                .flatMap(c -> mongo.save(c.updateState(state)))
                .then();
    }
}
