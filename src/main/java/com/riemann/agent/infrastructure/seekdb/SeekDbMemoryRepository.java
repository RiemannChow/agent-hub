package com.riemann.agent.infrastructure.seekdb;

import com.riemann.agent.domain.Memory;
import com.riemann.agent.domain.MemoryRepository;
import com.riemann.agent.domain.ScoredMemory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 长期记忆的 SeekDB（OceanBase，兼容 MySQL 协议）实现，对应 DeepSeek harness 的持久化记忆库。
 *
 * <p>记忆存储在 demo.memory 表（与 conversation / message 同库）。CREATE TABLE IF NOT EXISTS
 * 幂等建表，使服务在脱离初始化脚本时也能独立运行。阻塞型 JDBC 通过 {@code
 * Mono.fromRunnable(...).subscribeOn(Schedulers.boundedElastic())} 包裹，调用方视角非阻塞。
 *
 * <p>每条记忆自动用 {@link LocalEmbedder} 计算 256 维向量写入 embedding 列，供语义召回与去重合并； 同时用 content 的 MD5 作为 {@code
 * (user_id, content_hash)} 唯一键，使“完全相同内容”的写入在 数据库层原子去重（INSERT ... ON DUPLICATE KEY
 * UPDATE），彻底杜绝并发/可见性导致的重复插入。
 */
@Repository
@Profile("seekdb")
public class SeekDbMemoryRepository implements MemoryRepository {

    private final JdbcTemplate jdbc;

    public SeekDbMemoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbcTemplate;
    }

    private void ensureTable() {
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS memory (
                    id          VARCHAR(36)  PRIMARY KEY,
                    user_id     VARCHAR(64)  NOT NULL,
                    content     TEXT         NOT NULL,
                    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    embedding   VECTOR(256) NULL,
                    content_hash VARCHAR(64) NULL,
                    INDEX idx_memory_user (user_id, created_at),
                    UNIQUE KEY uk_user_hash (user_id, content_hash)
                )
                """);
        // 升级兼容：老表可能缺列/缺唯一键，安全补列（已存在则忽略报错）
        try {
            jdbc.execute("ALTER TABLE memory ADD COLUMN embedding VECTOR(256) NULL");
        } catch (Exception ignored) {
        }
        try {
            jdbc.execute("ALTER TABLE memory ADD COLUMN content_hash VARCHAR(64) NULL");
        } catch (Exception ignored) {
        }
        try {
            jdbc.execute("ALTER TABLE memory ADD UNIQUE KEY uk_user_hash (user_id, content_hash)");
        } catch (Exception ignored) {
        }
    }

    @Override
    public Mono<Void> save(Memory memory) {
        String hash = md5(memory.content());
        String embedding = LocalEmbedder.embedToSql(memory.content());
        return Mono.fromRunnable(
                        () ->
                                // 唯一键 (user_id, content_hash) 命中则只更新内容/向量，不新增行 —— 原子去重
                                jdbc.update(
                                        "INSERT INTO memory (id, user_id, content, embedding, content_hash, created_at) "
                                                + "VALUES (?, ?, ?, ?, ?, ?) "
                                                + "ON DUPLICATE KEY UPDATE content = VALUES(content), "
                                                + "embedding = VALUES(embedding), content_hash = VALUES(content_hash)",
                                        memory.id(),
                                        memory.userId(),
                                        memory.content(),
                                        embedding,
                                        hash,
                                        Timestamp.from(memory.createdAt())))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    public Flux<Memory> recent(String userId, int limit) {
        return Mono.fromCallable(
                        () -> {
                            ensureTable();
                            return jdbc.query(
                                    "SELECT id, user_id, content, created_at FROM memory "
                                            + "WHERE user_id = ? ORDER BY created_at DESC LIMIT ?",
                                    (rs, i) ->
                                            new Memory(
                                                    rs.getString("id"),
                                                    rs.getString("user_id"),
                                                    rs.getString("content"),
                                                    rs.getTimestamp("created_at").toInstant()),
                                    userId,
                                    limit);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    @Override
    public Flux<Memory> semanticRecall(
            String userId, String queryEmbedding, int limit, double maxDistance) {
        return Mono.fromCallable(
                        () -> {
                            ensureTable();
                            return jdbc.query(
                                    "SELECT id, user_id, content, created_at FROM memory "
                                            + "WHERE user_id = ? AND embedding IS NOT NULL "
                                            + "AND l2_distance(embedding, ?) <= ? "
                                            + "ORDER BY l2_distance(embedding, ?) ASC LIMIT ?",
                                    (rs, i) ->
                                            new Memory(
                                                    rs.getString("id"),
                                                    rs.getString("user_id"),
                                                    rs.getString("content"),
                                                    rs.getTimestamp("created_at").toInstant()),
                                    userId,
                                    queryEmbedding,
                                    maxDistance,
                                    queryEmbedding,
                                    limit);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    @Override
    public Mono<ScoredMemory> nearest(String userId, String queryEmbedding) {
        return Mono.fromCallable(
                        () -> {
                            ensureTable();
                            var list =
                                    jdbc.query(
                                            "SELECT id, user_id, content, created_at, l2_distance(embedding, ?) AS dist "
                                                    + "FROM memory WHERE user_id = ? AND embedding IS NOT NULL "
                                                    + "ORDER BY dist ASC LIMIT 1",
                                            (rs, i) ->
                                                    new ScoredMemory(
                                                            new Memory(
                                                                    rs.getString("id"),
                                                                    rs.getString("user_id"),
                                                                    rs.getString("content"),
                                                                    rs.getTimestamp("created_at")
                                                                            .toInstant()),
                                                            rs.getDouble("dist")),
                                            queryEmbedding,
                                            userId);
                            return list.isEmpty() ? null : list.get(0);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(Mono::justOrEmpty);
    }

    @Override
    public Mono<Void> updateContent(String id, String content, String embedding) {
        return Mono.fromRunnable(
                        () ->
                                // 合并近重复记忆时同步更新 content_hash，避免后续同内容写入绕过唯一键
                                jdbc.update(
                                        "UPDATE memory SET content = ?, embedding = ?, content_hash = ? WHERE id = ?",
                                        content,
                                        embedding,
                                        md5(content),
                                        id))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    public Mono<Void> trimTo(String userId, int keep) {
        return Mono.fromRunnable(
                        () -> {
                            ensureTable();
                            Long cnt =
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM memory WHERE user_id = ?",
                                            Long.class,
                                            userId);
                            long excess = (cnt == null ? 0 : cnt) - keep;
                            if (excess <= 0) {
                                return;
                            }
                            var ids =
                                    jdbc.query(
                                            "SELECT id FROM memory WHERE user_id = ? ORDER BY created_at ASC LIMIT ?",
                                            (rs, i) -> rs.getString("id"),
                                            userId,
                                            (int) excess);
                            for (String id : ids) {
                                jdbc.update("DELETE FROM memory WHERE id = ?", id);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    public Mono<Void> backfillNullEmbeddings() {
        return Mono.fromRunnable(
                        () -> {
                            ensureTable();
                            // 先删除内容完全相同的多余行，避免回填 content_hash 触发唯一键冲突
                            jdbc.update(
                                    "DELETE t1 FROM memory t1 JOIN memory t2 "
                                            + "ON t1.user_id = t2.user_id AND t1.content = t2.content AND t1.id > t2.id");
                            var nulls =
                                    jdbc.query(
                                            "SELECT id, user_id, content, created_at FROM memory "
                                                    + "WHERE embedding IS NULL OR content_hash IS NULL",
                                            (rs, i) ->
                                                    new Memory(
                                                            rs.getString("id"),
                                                            rs.getString("user_id"),
                                                            rs.getString("content"),
                                                            rs.getTimestamp("created_at")
                                                                    .toInstant()));
                            for (Memory m : nulls) {
                                jdbc.update(
                                        "UPDATE memory SET embedding = ?, content_hash = ? WHERE id = ?",
                                        LocalEmbedder.embedToSql(m.content()),
                                        md5(m.content()),
                                        m.id());
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
