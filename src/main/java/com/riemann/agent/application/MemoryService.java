package com.riemann.agent.application;

import com.riemann.agent.domain.Memory;
import com.riemann.agent.domain.MemoryRepository;
import com.riemann.agent.domain.ScoredMemory;
import com.riemann.agent.infrastructure.seekdb.LocalEmbedder;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 长期记忆服务（应用层）：封装“召回（recall）”“写入（save）”“去重合并”“遗忘”等能力，
 * 对应 DeepSeek harness 的记忆机制——每轮召回相关记忆注入上下文，重要事实由 Agent 主动写回。
 *
 * <h3>召回策略（v2：语义召回）</h3>
 * 用 {@link LocalEmbedder} 把当前 query 编码为向量，按 L2 距离在近邻记忆中取 top-k，
 * 使“与当前话题相关但非最近”的记忆也能被唤起（例如用户三个月前说过的偏好）。
 * 当无向量匹配（首次运行/全部超阈值）时回退到“最近 N 条”以保证仍有背景。
 *
 * <h3>写入策略（v2：去重 + 遗忘）</h3>
 * 写入前先算 query 向量并取最近邻：若距离小于 {@link #MERGE_DIST} 视为近重复，
 * 则用信息量更大的一句覆盖更新（合并），避免同义记忆无限堆积；否则新增。
 * 每用户记忆数超过 {@link #MAX_MEMORIES} 时，自动删除最早的（遗忘）。
 */
@Service
public class MemoryService {

    /** 每轮语义召回的近期记忆条数上限。 */
    private static final int RECALL_TOP_K = 5;
    /** 语义召回的距离上限（L2，归一化向量下约 0~1.41）；超出视为不相关。 */
    private static final double RECALL_MAX_DIST = 1.2;
    /** 最近邻距离小于此值视为近重复，触发合并而非新增。 */
    private static final double MERGE_DIST = 0.45;
    /** 每用户长期记忆容量上限，超出后遗忘最早者。 */
    private static final int MAX_MEMORIES = 50;

    private final MemoryRepository repository;

    public MemoryService(MemoryRepository repository) {
        this.repository = repository;
    }

    /** 升级兼容：为历史遗留的 NULL embedding 记忆补算向量。 */
    @PostConstruct
    public void backfill() {
        repository.backfillNullEmbeddings().subscribe();
    }

    /**
     * 召回该用户与当前 query 最相关的长期记忆，格式化为可注入上下文的文本块。
     * 无记忆时返回空串（调用方据此判断是否需要注入）。
     */
    public Mono<String> recall(String userId, String query) {
        String queryEmbedding = LocalEmbedder.embedToSql(query);
        return repository.semanticRecall(userId, queryEmbedding, RECALL_TOP_K, RECALL_MAX_DIST)
                .map(Memory::content)
                .collectList()
                .flatMap(list -> list.isEmpty()
                        ? repository.recent(userId, RECALL_TOP_K).map(Memory::content).collectList()
                        : Mono.just(list))
                .map(this::format);
    }

    /**
     * 持久化一条长期记忆：先去重/合并，再按容量上限遗忘。
     * 空内容直接忽略。
     */
    public Mono<Void> save(String userId, String content) {
        if (content == null || content.isBlank()) {
            return Mono.empty();
        }
        String c = content.strip();
        String embedding = LocalEmbedder.embedToSql(c);
        return repository.nearest(userId, embedding)
                .flatMap(n -> {
                    if (n.distance() < MERGE_DIST) {
                        // 近重复：用信息量更大的一句覆盖（合并）
                        String merged = (c.length() >= n.memory().content().length()) ? c : n.memory().content();
                        return repository.updateContent(n.memory().id(), merged, LocalEmbedder.embedToSql(merged));
                    }
                    return insertAndCap(userId, c);
                })
                .switchIfEmpty(Mono.defer(() -> insertAndCap(userId, c)))
                .then();
    }

    private Mono<Void> insertAndCap(String userId, String content) {
        Memory m = new Memory(UUID.randomUUID().toString(), userId, content, Instant.now());
        return repository.save(m).then(repository.trimTo(userId, MAX_MEMORIES));
    }

    private String format(List<String> memories) {
        if (memories.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【长期记忆｜来自你与该用户此前的对话，仅作背景参考；本轮仍须依据“当前问题”重新检索/问数，"
                + "不要把旧记忆当成本轮结论】\n");
        for (String m : memories) {
            sb.append("- ").append(m).append("\n");
        }
        return sb.toString();
    }
}
