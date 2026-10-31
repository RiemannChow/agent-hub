package com.riemann.agent.infrastructure.toolkit.tools;

import com.riemann.agent.infrastructure.seekdb.SeekDbConversationRepository;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 为当前轮次创建 SeekDB 的 COW 沙箱数据库快照，用于隔离实验性写操作。
 *
 * <p><b>沙箱名不来自模型输入</b>：早期版本把模型传来的 {@code agent_id} 直接拼进 {@code FORK/DROP DATABASE} 的 DDL，构成 DDL
 * 注入（Bug）。现改为由编排层确定的「userId + 本轮 sessionId」推导， 既杜绝注入，又保证<b>每轮每用户唯一</b>、不会跨轮/跨用户串味（另一 Bug）。
 */
@Component
@Profile("seekdb")
public class SeekForkSandboxTool {

    private final SeekDbConversationRepository seekDb;

    public SeekForkSandboxTool(SeekDbConversationRepository seekDb) {
        this.seekDb = seekDb;
    }

    @Tool(
            name = "seek_fork_sandbox",
            // 会创建数据库快照（有副作用），声明非并发安全，避免并行执行时同时 fork 出多个沙箱
            concurrencySafe = false,
            description =
                    "为当前轮次创建 SeekDB 的 COW 沙箱数据库快照，用于隔离实验性写操作（如改库、写回记忆）。"
                            + "仅在需要“先试后合并”的写操作前调用，普通问数/检索不需要。"
                            + "调用后本轮回的所有写操作都会落在沙箱副本、不直接改主库；可用 seek_commit_sandbox 合并回主库或 seek_discard_sandbox 丢弃。")
    public Mono<String> seekForkSandbox(
            AgentCallContext callContext,
            RuntimeContext runtimeContext,
            SandboxState sandboxState) {
        // 沙箱名 = userId + 本轮 sessionId（编排层每轮唯一），既防注入又防串味
        String sandbox = buildSandboxName(callContext.userId(), runtimeContext.getSessionId());
        return seekDb.forkSandbox(sandbox)
                // 写进整轮共享的可变持有者，后续 write/commit/discard 工具才能看到沙箱名
                .doOnSuccess(v -> sandboxState.activate(sandbox))
                .thenReturn("forked sandbox: " + sandbox + "（本轮回写操作将落在沙箱，不直接改主库）");
    }

    /** 由 userId + sessionId 推导合法、唯一的沙箱库名（仅含 [a-zA-Z0-9_]，无注入风险）。 */
    static String buildSandboxName(String userId, String sessionId) {
        String u = sanitize(userId);
        String s = sanitize(sessionId);
        return "sbx_" + u + "_" + s;
    }

    /** 只允许安全字符，超长截断，空则置 0，确保拼进 DDL 不会破坏语句。 */
    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "0";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_') {
                sb.append(c);
            }
        }
        String cleaned = sb.toString();
        return cleaned.isBlank()
                ? "0"
                : (cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned);
    }
}
