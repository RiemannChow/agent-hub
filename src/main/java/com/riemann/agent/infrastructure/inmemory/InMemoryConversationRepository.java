package com.riemann.agent.infrastructure.inmemory;

import com.riemann.agent.domain.AgentState;
import com.riemann.agent.domain.Conversation;
import com.riemann.agent.domain.ConversationId;
import com.riemann.agent.domain.ConversationRepository;
import com.riemann.agent.domain.Message;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OceanBase 知识图谱问答场景下的「内存版」会话仓储（@Profile("oceanbase")）。
 *
 * <p>oceanbase profile 不依赖 SeekDB / MongoDB，故用进程内 Map 承载会话，
 * 满足 {@code ConversationAppService} 的端口依赖即可，无需外接数据库。
 * 仅用于本地验证；重启即清空。</p>
 */
@Repository
@Profile("oceanbase")
public class InMemoryConversationRepository implements ConversationRepository {

    private final Map<String, Conversation> store = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> save(Conversation conversation) {
        store.put(conversation.id().value(), conversation);
        return Mono.empty();
    }

    @Override
    public Mono<Void> appendMessages(ConversationId id, List<Message> messages) {
        Conversation c = store.get(id.value());
        if (c != null) {
            for (Message m : messages) {
                c.addMessage(m);
            }
        }
        return Mono.empty();
    }

    @Override
    public Mono<Conversation> findById(ConversationId id) {
        return Mono.justOrEmpty(store.get(id.value()));
    }

    @Override
    public Flux<Conversation> findByUserId(String userId) {
        return Flux.fromIterable(store.values().stream()
                .filter(c -> userId.equals(c.userId()))
                .sorted(Comparator.comparing(Conversation::lastUpdated).reversed())
                .toList());
    }

    @Override
    public Mono<Void> updateAgentState(ConversationId id, AgentState state) {
        Conversation c = store.get(id.value());
        if (c != null) {
            c.updateState(state);
        }
        return Mono.empty();
    }
}
