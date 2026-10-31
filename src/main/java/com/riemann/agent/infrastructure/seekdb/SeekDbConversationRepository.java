package com.riemann.agent.infrastructure.seekdb;

import com.riemann.agent.domain.*;
import com.riemann.agent.domain.AgentState;
import com.riemann.agent.domain.Conversation;
import com.riemann.agent.domain.ConversationId;
import com.riemann.agent.domain.ConversationRepository;
import com.riemann.agent.domain.Message;
import com.riemann.agent.domain.ScoredConversation;
import com.riemann.agent.infrastructure.seekdb.sql.SqlGuard;
import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * SeekDB（OceanBase，兼容 MySQL 协议）仓储实现。
 *
 * <p>阻塞型 JDBC 通过 {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())}
 * 包裹到弹性调度器上，从调用方视角看整条链路仍然是非阻塞的（业务代码无 .block()）。
 *
 * <p>通过 {@code @Profile("seekdb")} 与 Mongo 实现二选一。
 */
@Repository
@Profile("seekdb")
public class SeekDbConversationRepository implements ConversationRepository {

    private static final String TENANT = "demo_user";

    private final JdbcTemplate jdbc;

    /** LLM 生成的 SQL 必须先过这道守卫（只读 / 白名单 / 强制 LIMIT），仓储层不再直接执行裸 SQL。 */
    private final SqlGuard sqlGuard;

    public SeekDbConversationRepository(JdbcTemplate jdbcTemplate, SqlGuard sqlGuard) {
        this.jdbc = jdbcTemplate;
        this.sqlGuard = sqlGuard;
    }

    // ===================== Repository Port 实现 =====================

    @Override
    public Mono<Void> save(Conversation conversation) {
        // 仅 upsert 会话行（agent_state / updated_at / embedding）。消息通过 appendMessages 增量落库，
        // 避免每次整聚合并重插历史消息造成重复写入。
        // embedding 由会话内用户消息拼接后经 LocalEmbedder 计算，供 hybridSearch 做向量近邻检索。
        String content =
                conversation.messages().stream()
                        .filter(m -> m instanceof Message.UserMessage)
                        .map(m -> ((Message.UserMessage) m).content())
                        .collect(Collectors.joining("\n"));
        String embedding = LocalEmbedder.embedToSql(content);
        return Mono.fromRunnable(
                        () ->
                                jdbc.update(
                                        """
                            INSERT INTO conversation (conversation_id, user_id, title, agent_state, updated_at, embedding)
                            VALUES (?, ?, ?, ?, ?, ?)
                            ON DUPLICATE KEY UPDATE agent_state = VALUES(agent_state), updated_at = VALUES(updated_at), embedding = VALUES(embedding)
                            """,
                                        conversation.id().value(),
                                        conversation.userId(),
                                        "untitled",
                                        conversation.agentState().status(),
                                        Timestamp.from(Instant.now()),
                                        embedding))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * 升级兼容：为历史会话（embedding 为 NULL）补算向量，使 hybridSearch 的近邻检索覆盖存量对话。 仅在会话行 embedding
     * 为空时执行一次轻量回填（启动时自动触发）。
     */
    @PostConstruct
    public void backfillEmbeddings() {
        try {
            List<String> ids =
                    jdbc.query(
                            "SELECT conversation_id FROM conversation WHERE embedding IS NULL",
                            (rs, i) -> rs.getString(1));
            for (String cid : ids) {
                List<String> parts =
                        jdbc.query(
                                "SELECT content FROM message WHERE conversation_id = ? AND type = 'USER' ORDER BY created_at",
                                (rs, i) -> rs.getString(1),
                                cid);
                String text = String.join("\n", parts);
                jdbc.update(
                        "UPDATE conversation SET embedding = ? WHERE conversation_id = ?",
                        LocalEmbedder.embedToSql(text),
                        cid);
            }
        } catch (Exception ignored) {
            // 回填失败不影响主链路
        }
    }

    @Override
    public Mono<Void> appendMessages(ConversationId id, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return Mono.empty();
        }
        return Mono.fromRunnable(
                        () -> {
                            for (Message m : messages) {
                                insertMessage(id.value(), m);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    public Mono<Conversation> findById(ConversationId id) {
        // 注意：新会话在落库前并不存在对应行，因此必须用 jdbc.query（返回空列表）而非
        // queryForObject（无匹配行时抛 EmptyResultDataAccessException）。否则新建会话的首条
        // 消息会因 findById 抛错而中断整条 SSE 流，表现为“服务端无返回”。缺失行返回 empty，
        // 交由上层 switchIfEmpty 新建会话。
        return Mono.fromCallable(
                        () -> {
                            List<Conversation> list =
                                    jdbc.query(
                                            "SELECT conversation_id, user_id, agent_state, updated_at FROM conversation WHERE conversation_id = ?",
                                            (rs, i) -> {
                                                Conversation c =
                                                        new Conversation(
                                                                new ConversationId(
                                                                        rs.getString(
                                                                                "conversation_id")),
                                                                rs.getString("user_id"),
                                                                null,
                                                                AgentState.initial("seek-agent")
                                                                        .withStatus(
                                                                                rs.getString(
                                                                                        "agent_state")));
                                                Timestamp ts = rs.getTimestamp("updated_at");
                                                if (ts != null) {
                                                    c.withUpdatedAt(ts.toInstant());
                                                }
                                                loadMessages(c);
                                                return c;
                                            },
                                            id.value());
                            return list.isEmpty() ? null : list.get(0);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .filter(Objects::nonNull);
    }

    @Override
    public Flux<Conversation> findByUserId(String userId) {
        return Mono.fromCallable(
                        () ->
                                jdbc.query(
                                        "SELECT conversation_id, user_id, agent_state, updated_at FROM conversation WHERE user_id = ? ORDER BY updated_at DESC",
                                        (rs, i) -> {
                                            Conversation c =
                                                    new Conversation(
                                                            new ConversationId(
                                                                    rs.getString(
                                                                            "conversation_id")),
                                                            rs.getString("user_id"),
                                                            null,
                                                            AgentState.initial("seek-agent")
                                                                    .withStatus(
                                                                            rs.getString(
                                                                                    "agent_state")));
                                            Timestamp ts = rs.getTimestamp("updated_at");
                                            if (ts != null) {
                                                c.withUpdatedAt(ts.toInstant());
                                            }
                                            loadMessages(c);
                                            return c;
                                        },
                                        userId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    @Override
    public Mono<Void> updateAgentState(ConversationId id, AgentState state) {
        return Mono.fromRunnable(
                        () ->
                                jdbc.update(
                                        "UPDATE conversation SET agent_state = ?, updated_at = ? WHERE conversation_id = ?",
                                        state.status(),
                                        Timestamp.from(state.updatedAt()),
                                        id.value()))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    // ===================== SeekDB 专属能力 =====================

    /**
     * 混合检索：向量近邻 (l2_distance) + 全文 (MATCH...AGAINST, CJK 需 ngram 索引) + 标量过滤 (status)，对全量 ACTIVE
     * 对话做近邻排序。
     *
     * <p>keyword 为空时退化为纯向量检索；非空时叠加全文过滤。距离越小越相似。
     */
    public Mono<List<ScoredConversation>> hybridSearch(
            String queryEmbedding, String keyword, double threshold, int limit) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT c.conversation_id, c.title, c.content, ")
                .append("l2_distance(c.embedding, ?) AS dist ")
                .append("FROM conversation c ")
                // 仅检索带向量 embedding 的“知识库/客服”对话；普通聊天会话 embedding 为 NULL，排除之
                .append("WHERE c.status = 'ACTIVE' AND c.embedding IS NOT NULL ");
        List<Object> params = new ArrayList<>();
        params.add(queryEmbedding);
        if (keyword != null && !keyword.isBlank()) {
            sql.append("AND MATCH(c.content) AGAINST (? IN BOOLEAN MODE) ");
            params.add(keyword);
        }
        sql.append("AND l2_distance(c.embedding, ?) <= ? ")
                .append("ORDER BY dist ")
                .append("LIMIT ?");
        params.add(queryEmbedding);
        params.add(threshold);
        params.add(limit);
        return Mono.fromCallable(
                        () ->
                                jdbc.query(
                                        sql.toString(),
                                        (rs, i) ->
                                                new ScoredConversation(
                                                        rs.getString("conversation_id"),
                                                        rs.getString("title"),
                                                        rs.getString("content"),
                                                        rs.getDouble("dist")),
                                        params.toArray()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * (b) Agent 状态快照的 COW 沙箱：FORK DATABASE 为 Agent 创建写时复制快照。
     *
     * <p>幂等：若同名沙箱已存在（同一 agentId 上一轮 fork 后未清理），先 DROP 再 FORK， 避免 OceanBase 对「已存在的库再
     * FORK」直接报错导致工具调用失败。
     */
    public Mono<Void> forkSandbox(String sandboxDbName) {
        if (!isValidDbName(sandboxDbName)) {
            return Mono.error(new IllegalArgumentException("非法沙箱名，拒绝 FORK：" + sandboxDbName));
        }
        return Mono.fromRunnable(
                        () -> {
                            jdbc.execute("DROP DATABASE IF EXISTS " + sandboxDbName);
                            jdbc.execute("FORK DATABASE " + sandboxDbName);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /** 沙箱库名白名单：仅允许字母数字下划线，长度受限，避免任何 DDL 注入可能。 */
    private static boolean isValidDbName(String name) {
        if (name == null || name.isEmpty() || name.length() > 64) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_')) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把一条写 SQL 路由到「当前活跃的 COW 沙箱」；若没有沙箱则原样在主库执行。
     *
     * <p>这是沙箱隔离的关键接入口：写工具（如写回对话/记忆）统一走这里， 当 {@code sandboxDbName} 非空时，把语句里被白名单允许的表名改写为 {@code
     * <sandbox>.table}， 使写入只落在 COW 副本、绝不触碰主库。改写范围严格限定在 {@link #WRITABLE_TABLES}， 其它表（如系统的 memory
     * 表）不进沙箱、按原语句执行。
     */
    public Mono<Void> writeInSandboxIfAny(String sandboxDbName, String sql) {
        if (sandboxDbName == null || sandboxDbName.isBlank()) {
            return Mono.fromRunnable(() -> jdbc.execute(sql))
                    .subscribeOn(Schedulers.boundedElastic())
                    .then();
        }
        if (!isValidDbName(sandboxDbName)) {
            return Mono.error(new IllegalArgumentException("非法沙箱名，拒绝写入：" + sandboxDbName));
        }
        String targetSql = qualifyTables(sql, sandboxDbName);
        return Mono.fromRunnable(() -> jdbc.execute(targetSql))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /** 仅允许进沙箱的白名单表（与 ConversationRepository 实际写入的表对齐）。 */
    private static final java.util.Set<String> WRITABLE_TABLES =
            java.util.Set.of("conversation", "message");

    /** 把 SQL 中的白名单表名改写为 {@code <sandbox>.table} 形式。 用单词边界（\\b）匹配，避免把其它标识符里的子串误伤；只改白名单内的表。 */
    private static String qualifyTables(String sql, String sandbox) {
        String result = sql;
        for (String table : WRITABLE_TABLES) {
            result = result.replaceAll("(?i)\\b" + table + "\\b", sandbox + "." + table);
        }
        return result;
    }

    /**
     * 把 COW 沙箱里「白名单表」的增量合并回主库（提交）。 采用 UPSERT（ON DUPLICATE KEY UPDATE）幂等合并，主库已有行只更新、新行插入。 合并后应调用
     * {@link #dropSandbox} 清理沙箱。
     */
    public Mono<Void> mergeSandbox(String sandboxDbName) {
        if (sandboxDbName == null || sandboxDbName.isBlank()) {
            return Mono.empty();
        }
        if (!isValidDbName(sandboxDbName)) {
            return Mono.error(new IllegalArgumentException("非法沙箱名，拒绝合并：" + sandboxDbName));
        }
        return Mono.fromRunnable(
                        () -> {
                            // conversation：合并所有业务列
                            jdbc.execute(
                                    "INSERT INTO conversation "
                                            + "SELECT * FROM "
                                            + sandboxDbName
                                            + ".conversation "
                                            + "ON DUPLICATE KEY UPDATE "
                                            + "conversation.title = VALUES(title), "
                                            + "conversation.agent_state = VALUES(agent_state), "
                                            + "conversation.embedding = VALUES(embedding), "
                                            + "conversation.updated_at = VALUES(updated_at)");
                            // message：合并全部列（content / type / tool_name / arguments / created_at 等）
                            jdbc.execute(
                                    "INSERT INTO message "
                                            + "SELECT * FROM "
                                            + sandboxDbName
                                            + ".message "
                                            + "ON DUPLICATE KEY UPDATE "
                                            + "message.content = VALUES(content), "
                                            + "message.type = VALUES(type), "
                                            + "message.tool_name = VALUES(tool_name), "
                                            + "message.arguments = VALUES(arguments), "
                                            + "message.created_at = VALUES(created_at)");
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /** 清理 COW 沙箱库（丢弃或合并后调用）。库不存在也不报错。 */
    public Mono<Void> dropSandbox(String sandboxDbName) {
        if (sandboxDbName == null || sandboxDbName.isBlank()) {
            return Mono.empty();
        }
        if (!isValidDbName(sandboxDbName)) {
            return Mono.error(new IllegalArgumentException("非法沙箱名，拒绝清理：" + sandboxDbName));
        }
        return Mono.fromRunnable(() -> jdbc.execute("DROP DATABASE IF EXISTS " + sandboxDbName))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * 通用 SQL 问数（SELECT），返回结构化行（List&lt;Map&gt;），供图表类工具做聚合后产出 A2UI chart。
     *
     * <p>这里的 SQL 来自 LLM，属于不可信输入：先经 {@link SqlGuard} 做只读/单语句/表白名单校验 并强制包裹 LIMIT，再以 {@code
     * setQueryTimeout} + {@code setMaxRows} 双重兜底执行， 避免全表扫描打爆堆内存或慢查询占满连接池。
     */
    public Mono<List<Map<String, Object>>> queryForMaps(String sql) {
        return Mono.fromCallable(
                        () -> {
                            String safeSql = sqlGuard.guard(sql);
                            return jdbc.query(
                                    safeSql,
                                    ps -> {
                                        ps.setQueryTimeout(sqlGuard.queryTimeoutSeconds());
                                        ps.setMaxRows(sqlGuard.maxRows());
                                    },
                                    (rs, rowNum) -> {
                                        var md = rs.getMetaData();
                                        int cols = md.getColumnCount();
                                        Map<String, Object> row =
                                                new LinkedHashMap<>(Math.max(cols, 1));
                                        for (int i = 1; i <= cols; i++) {
                                            row.put(md.getColumnLabel(i), rs.getObject(i));
                                        }
                                        return row;
                                    });
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 通用 SQL 问数（SELECT），返回文本结果。同样经过 {@link SqlGuard} 校验。 */
    public Mono<String> runQuery(String sql) {
        return Mono.fromCallable(
                        () -> {
                            String safeSql = sqlGuard.guard(sql);
                            StringBuilder sb = new StringBuilder();
                            jdbc.query(
                                    safeSql,
                                    ps -> {
                                        ps.setQueryTimeout(sqlGuard.queryTimeoutSeconds());
                                        ps.setMaxRows(sqlGuard.maxRows());
                                    },
                                    rs -> {
                                        try {
                                            var md = rs.getMetaData();
                                            int cols = md.getColumnCount();
                                            while (rs.next()) {
                                                for (int i = 1; i <= cols; i++) {
                                                    sb.append(md.getColumnLabel(i))
                                                            .append("=")
                                                            .append(rs.getString(i))
                                                            .append(" | ");
                                                }
                                                sb.append("\n");
                                            }
                                        } catch (Exception ignored) {
                                        }
                                    });
                            return sb.toString();
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // ===================== 内部辅助 =====================

    private void insertMessage(String cid, Message m) {
        String sql =
                """
                INSERT INTO message (conversation_id, type, content, tool_name, arguments, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        String type;
        String content = null;
        String toolName = null;
        String args = null;
        if (m instanceof Message.UserMessage um) {
            type = "USER";
            content = um.content();
        } else if (m instanceof Message.AssistantMessage am) {
            type = "ASSISTANT";
            content = am.content();
        } else if (m instanceof Message.ToolCallMessage tm) {
            type = "TOOL_CALL";
            toolName = tm.toolName();
            args = tm.arguments();
        } else if (m instanceof Message.ToolResultMessage rm) {
            type = "TOOL_RESULT";
            toolName = rm.toolName();
            content = rm.result();
        } else {
            type = "UNKNOWN";
        }
        jdbc.update(sql, cid, type, content, toolName, args, Timestamp.from(m.timestamp()));
    }

    private void loadMessages(Conversation c) {
        jdbc.query(
                "SELECT type, content, tool_name, arguments, created_at FROM message WHERE conversation_id = ? ORDER BY created_at",
                (rs, i) -> {
                    String type = rs.getString("type");
                    Instant ts = rs.getTimestamp("created_at").toInstant();
                    Message m =
                            switch (type) {
                                case "USER" ->
                                        new Message.UserMessage(
                                                c.id().value(), rs.getString("content"), ts);
                                case "ASSISTANT" ->
                                        new Message.AssistantMessage(
                                                c.id().value(), rs.getString("content"), ts);
                                case "TOOL_CALL" ->
                                        new Message.ToolCallMessage(
                                                c.id().value(),
                                                rs.getString("tool_name"),
                                                rs.getString("arguments"),
                                                ts);
                                case "TOOL_RESULT" ->
                                        new Message.ToolResultMessage(
                                                c.id().value(),
                                                rs.getString("tool_name"),
                                                rs.getString("content"),
                                                ts);
                                default -> null;
                            };
                    if (m != null) c.addMessage(m);
                    return m;
                },
                c.id().value());
    }
}
