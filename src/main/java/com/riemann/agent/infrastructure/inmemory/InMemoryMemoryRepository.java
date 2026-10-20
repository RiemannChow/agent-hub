package com.riemann.agent.infrastructure.inmemory;

import com.riemann.agent.domain.Memory;
import com.riemann.agent.domain.MemoryRepository;
import com.riemann.agent.domain.ScoredMemory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * OceanBase 知识图谱问答场景下的「内存版」长期记忆仓储（@Profile("oceanbase")）。
 *
 * <p>设计上 oceanbase 实现<b>不启用长期记忆召回</b>（见 OceanBaseOrchestrationServiceImpl 说明），
 * 这里提供一份最简内存实现以满足 {@code MemoryService} 的端口依赖：写入可落内存、召回回退为空，
 * 不再依赖 SeekDB 的向量列与 l2_distance。</p>
 */
@Repository
@Profile("oceanbase")
public class InMemoryMemoryRepository implements MemoryRepository {

    private final List<Memory> store = new CopyOnWriteArrayList<>();

    @Override
    public Mono<Void> save(Memory memory) {
        store.add(memory);
        return Mono.empty();
    }

    @Override
    public Flux<Memory> recent(String userId, int limit) {
        List<Memory> list = store.stream()
                .filter(m -> userId.equals(m.userId()))
                .sorted(Comparator.comparing(Memory::createdAt).reversed())
                .limit(limit)
                .toList();
        return Flux.fromIterable(list);
    }

    @Override
    public Flux<Memory> semanticRecall(String userId, String queryEmbedding, int limit, double maxDistance) {
        // 内存态不计算向量：返回空，由 MemoryService 回退到 recent()
        return Flux.empty();
    }

    @Override
    public Mono<ScoredMemory> nearest(String userId, String queryEmbedding) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> updateContent(String id, String content, String embedding) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> trimTo(String userId, int keep) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> backfillNullEmbeddings() {
        return Mono.empty();
    }
}
