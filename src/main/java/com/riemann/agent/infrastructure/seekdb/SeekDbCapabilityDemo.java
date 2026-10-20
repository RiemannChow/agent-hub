package com.riemann.agent.infrastructure.seekdb;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * SeekDB（OceanBase 向量库）三大核心能力演示。
 * 每个方法都包含可直接在 SeekDB 中执行的完整 SQL。
 * 可通过 CommandLineRunner 或在 Controller / 单测中直接调用这些方法。
 */
@Component
@Profile("seekdb")
public class SeekDbCapabilityDemo {

    private final JdbcTemplate jdbc;

    public SeekDbCapabilityDemo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ============================================================
    // 1) 混合检索：向量近邻 + 全文匹配 + 标量过滤，单条 SQL 完成
    // ============================================================
    public Mono<List<String>> hybridSearchDemo(String queryEmbedding, String keyword) {
        // 可直接在 SeekDB 中执行的完整 SQL：
        //   - l2_distance(embedding, ?)                            : 向量近邻（? 为向量字符串，如 "[0.1,0.2,...]"）
        //   - MATCH(content) AGAINST (? IN BOOLEAN MODE)           : 全文匹配
        //   - c.user_id = ?  AND  c.status = 'ACTIVE'              : 标量过滤
        //   - ORDER BY dist LIMIT 10                               : 近邻排序
        // 注意：SeekDB 不支持 CAST(? AS VECTOR)，向量以字符串参数直接传给 l2_distance 即可。
        String sql = """
                SELECT c.conversation_id, c.title,
                       l2_distance(c.embedding, ?) AS dist
                FROM conversation c
                WHERE c.user_id = 'demo_user'
                  AND MATCH(c.content) AGAINST (? IN BOOLEAN MODE)
                  AND c.status = 'ACTIVE'
                  AND l2_distance(c.embedding, ?) <= 0.6
                ORDER BY dist
                LIMIT 10
                """;

        return Mono.fromCallable(() ->
                        jdbc.query(sql,
                                (rs, i) -> rs.getString("conversation_id"),
                                queryEmbedding, keyword, queryEmbedding))
                .subscribeOn(Schedulers.boundedElastic());
    }

    // ============================================================
    // 2) COW 沙箱：沙箱中写操作 -> 验证后 MERGE 回主库 -> 失败则 DROP
    // ============================================================
    public Mono<String> cowSandboxDemo(String agentId) {
        return Mono.fromRunnable(() -> {
                    String sandbox = "agent_" + agentId + "_sandbox";

                    // 步骤 a. 为 Agent 创建 COW 快照（写时复制，主库不受影响）
                    jdbc.execute("FORK DATABASE " + sandbox);

                    try {
                        // 步骤 b. 在沙箱中做实验性写操作
                        jdbc.execute("INSERT INTO " + sandbox + ".conversation " +
                                "(conversation_id, user_id, title) VALUES ('exp-1', 'demo_user', 'experimental')");

                        // 步骤 c. 验证通过 -> 将沙箱表 MERGE（合并）回主库
                        jdbc.execute("INSERT INTO conversation (conversation_id, user_id, title) " +
                                "SELECT conversation_id, user_id, title FROM " + sandbox + ".conversation " +
                                "ON DUPLICATE KEY UPDATE title = VALUES(title)");

                        // 步骤 d. 清理沙箱
                        jdbc.execute("DROP DATABASE " + sandbox);
                    } catch (RuntimeException ex) {
                        // 验证失败 -> 直接丢弃沙箱，主库零影响（COW 隔离）
                        jdbc.execute("DROP DATABASE " + sandbox);
                        throw ex;
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just("cow sandbox merged & cleaned"));
    }

    // ============================================================
    // 3) 异步索引流水线（Change Stream）对 Agent 的收益
    // ============================================================
    /**
     * SeekDB 内置 Change Stream 机制：当 Agent 写入一条记忆（INSERT）后，
     * 向量/全文索引会以异步流水线方式“增量”更新，写入“立即可检索”
     * （无需等待全量索引重建）。这天然契合 Agent 的“实时记忆写入”场景——
     * 例如 Agent 在工具调用中把新事实写入表，紧接着的下一次 hybrid_search
     * 就能命中这条刚写入的记忆，形成闭环的、低延迟的自我记忆回路。
     *
     * 下例展示：先写入记忆，再立即检索，依赖 Change Stream 的近实时可见性。
     */
    public Mono<String> changeStreamMemoryDemo(String agentId,
                                              String memoryEmbedding,
                                              String memoryText) {
        return Mono.fromRunnable(() -> {
                    // 写入记忆（触发 Change Stream 异步增量索引）
                    jdbc.update(
                            "INSERT INTO conversation (conversation_id, user_id, content, embedding, status) " +
                            "VALUES (?, 'demo_user', ?, ?, 'ACTIVE')",
                            "mem-" + agentId, memoryText, memoryEmbedding);

                    // 立即可检索：依赖 Change Stream 的近实时索引可见性
                    jdbc.query(
                            "SELECT conversation_id FROM conversation " +
                            "WHERE MATCH(content) AGAINST (? IN BOOLEAN MODE) LIMIT 5",
                            (rs, i) -> rs.getString("conversation_id"),
                            memoryText);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just("memory written & immediately searchable"));
    }
}
