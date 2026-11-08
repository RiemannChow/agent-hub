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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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

    /** 沙箱合并需要把两条 UPSERT 放进同一个本地事务。 */
    private final TransactionTemplate transactionTemplate;

    public SeekDbConversationRepository(
            JdbcTemplate jdbcTemplate,
            SqlGuard sqlGuard,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbcTemplate;
        this.sqlGuard = sqlGuard;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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
     * 把一条写 SQL 路由到「当前活跃的 COW 沙箱」。
     *
     * <p><b>没有活跃沙箱时拒绝执行，绝不落到主库。</b>工具层（{@code SeekSandboxWriteTool}） 已有一道 {@code isActive()}
     * 检查，这里是仓储层的最后一道防线：任何调用方都不能绕过 「先 fork 再写」的隔离前提。表名改写交给 {@link SandboxSql} 做 AST 级校验与前缀化，
     * 不再用文本正则，也不再放行白名单之外的表。
     */
    public Mono<Void> writeInSandboxIfAny(String sandboxDbName, String sql) {
        if (sandboxDbName == null || sandboxDbName.isBlank()) {
            return Mono.error(
                    new IllegalStateException("拒绝执行：本轮没有活跃沙箱，禁止直接写主库（请先调用 seek_fork_sandbox）。"));
        }
        if (!isValidDbName(sandboxDbName)) {
            return Mono.error(new IllegalArgumentException("非法沙箱名，拒绝写入：" + sandboxDbName));
        }
        String targetSql;
        try {
            targetSql = SandboxSql.qualify(sql, sandboxDbName);
        } catch (IllegalArgumentException e) {
            return Mono.error(e);
        }
        return Mono.fromRunnable(() -> jdbc.execute(targetSql))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * 把 COW 沙箱里白名单表的改动合并回主库（提交）。
     *
     * <p><b>单事务</b>：conversation 与 message 两条 UPSERT 包在同一个 {@link TransactionTemplate}
     * 里，中途失败整体回滚，不再留下「会话合并了、消息没合并」的半提交状态。
     *
     * <p><b>条件 UPSERT</b>：沙箱是 {@code FORK DATABASE} 出来的全量快照，若直接 {@code INSERT ... SELECT *} 会把 fork
     * 时刻的旧行写回主库、覆盖 fork 之后其它请求 的更新。这里用 {@code IF(VALUES(ts) > 主库.ts, ...)} 只在沙箱行更新时才覆盖。
     *
     * <p>已知限制：仍是全表扫描合并（未做行级变更追踪），且依赖 MySQL 兼容的 {@code IF()} / {@code VALUES()}；行级增量与跨实例状态外置另开
     * issue。
     */
    public Mono<Void> mergeSandbox(String sandboxDbName) {
        if (sandboxDbName == null || sandboxDbName.isBlank()) {
            return Mono.empty();
        }
        if (!isValidDbName(sandboxDbName)) {
            return Mono.error(new IllegalArgumentException("非法沙箱名，拒绝合并：" + sandboxDbName));
        }
        String conversationUpsert =
                "INSERT INTO conversation SELECT * FROM "
                        + sandboxDbName
                        + ".conversation "
                        + "ON DUPLICATE KEY UPDATE "
                        + "conversation.title = IF(VALUES(updated_at) > conversation.updated_at, VALUES(title), conversation.title), "
                        + "conversation.agent_state = IF(VALUES(updated_at) > conversation.updated_at, VALUES(agent_state), conversation.agent_state), "
                        + "conversation.embedding = IF(VALUES(updated_at) > conversation.updated_at, VALUES(embedding), conversation.embedding), "
                        + "conversation.updated_at = GREATEST(conversation.updated_at, VALUES(updated_at))";
        String messageUpsert =
                "INSERT INTO message SELECT * FROM "
                        + sandboxDbName
                        + ".message "
                        + "ON DUPLICATE KEY UPDATE "
                        + "message.content = IF(VALUES(created_at) > message.created_at, VALUES(content), message.content), "
                        + "message.type = IF(VALUES(created_at) > message.created_at, VALUES(type), message.type), "
                        + "message.tool_name = IF(VALUES(created_at) > message.created_at, VALUES(tool_name), message.tool_name), "
                        + "message.arguments = IF(VALUES(created_at) > message.created_at, VALUES(arguments), message.arguments), "
                        + "message.created_at = GREATEST(message.created_at, VALUES(created_at))";
        return Mono.fromRunnable(
                        () ->
                                transactionTemplate.execute(
                                        status -> {
                                            jdbc.execute(conversationUpsert);
                                            jdbc.execute(messageUpsert);
                                            return null;
                                        }))
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
