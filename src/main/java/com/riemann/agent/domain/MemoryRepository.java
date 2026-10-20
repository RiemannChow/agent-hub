package com.riemann.agent.domain;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 长期记忆存储端口（Port），定义在 Domain 层，由 Infrastructure 层提供实现。
 */
public interface MemoryRepository {

    /** 持久化一条记忆（embedding 由实现层按 content 计算）。 */
    Mono<Void> save(Memory memory);

    /** 取某用户最近 limit 条记忆（按时间倒序）。 */
    Flux<Memory> recent(String userId, int limit);

    /**
     * 语义召回：按 query 向量取该用户最相近的 limit 条记忆（l2_distance 升序，且不超 maxDistance）。
     * 仅检索已写入 embedding 的记忆。
     */
    Flux<Memory> semanticRecall(String userId, String queryEmbedding, int limit, double maxDistance);

    /** 取该用户与给定向量最相近的一条记忆（含距离），用于去重/合并判断；无则返回 empty。 */
    Mono<ScoredMemory> nearest(String userId, String queryEmbedding);

    /** 合并：用新内容覆盖更新已有记忆（并刷新其 embedding）。 */
    Mono<Void> updateContent(String id, String content, String embedding);

    /** 遗忘：保留该用户最近 keep 条，删除更早的。 */
    Mono<Void> trimTo(String userId, int keep);

    /** 回填：为未写入 embedding 的历史记忆补算向量（升级兼容）。 */
    Mono<Void> backfillNullEmbeddings();
}
