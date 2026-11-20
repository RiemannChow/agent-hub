package com.riemann.agent.infrastructure.toolkit.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riemann.agent.application.ApprovalService;
import com.riemann.agent.infrastructure.seekdb.SeekDbConversationRepository;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * SeekDB 的【沙箱写回】工具组：在 COW 沙箱里做实验性写操作，验证后再决定合并回主库或丢弃。
 *
 * <p><b>隔离前提：</b>必须先调用 {@code seek_fork_sandbox} 在本轮 {@link SandboxState} 持有者上
 * 挂一个活跃沙箱名。之后所有写操作（{@code seek_write_sandbox}）都会经 {@link
 * SeekDbConversationRepository#writeInSandboxIfAny} 改写表名为 {@code <sandbox>.table}， 只落在 COW
 * 副本、绝不碰主库。未 fork 就写会被拒绝——这是「默认不污染主库」的硬约束。
 *
 * <p><b>收口：</b>{@code seek_commit_sandbox} 不再由模型自主提交，而是返回一张 approval 卡片， 由登录用户显式批准后才真正 UPSERT
 * 回主库（issue #27 后续：人在回路）。{@code seek_discard_sandbox} 只丢沙箱、不碰主库，保持可自主调用。
 */
@Component
@Profile("seekdb")
public class SeekSandboxWriteTool {

    private final SeekDbConversationRepository seekDb;
    private final ApprovalService approvals;
    private final ObjectMapper objectMapper;

    public SeekSandboxWriteTool(
            SeekDbConversationRepository seekDb,
            ApprovalService approvals,
            ObjectMapper objectMapper) {
        this.seekDb = seekDb;
        this.approvals = approvals;
        this.objectMapper = objectMapper;
    }

    @Tool(
            name = "seek_write_sandbox",
            // 写库工具：非并发安全，避免并行写入时表名改写/提交顺序错乱
            concurrencySafe = false,
            description =
                    "在已 fork 的 SeekDB COW 沙箱里执行一条写 SQL（INSERT/UPDATE/DELETE，仅限 conversation、message 表）。"
                            + "写入只落在沙箱副本，不直接改主库；执行后可用 seek_commit_sandbox 申请合并回主库，或 seek_discard_sandbox 丢弃。"
                            + "若本轮尚未调用 seek_fork_sandbox，本工具会拒绝执行。")
    public Mono<String> seekWriteSandbox(
            @ToolParam(name = "sql", description = "要执行的写 SQL，仅限 conversation / message 表")
                    String sql,
            SandboxState sandboxState) {
        if (sandboxState == null || !sandboxState.isActive()) {
            return Mono.just("拒绝执行：本轮尚未 fork 沙箱（请先调用 seek_fork_sandbox）。写操作不会落到主库。");
        }
        String sandbox = sandboxState.activeSandbox();
        return seekDb.writeInSandboxIfAny(sandbox, sql)
                .thenReturn("已在沙箱 " + sandbox + " 执行写操作（主库未改动）：" + sql);
    }

    @Tool(
            name = "seek_commit_sandbox",
            concurrencySafe = false,
            description =
                    "申请把当前 COW 沙箱的增量合并回主库。调用后不会立刻写入，而是向用户弹出确认卡片；"
                            + "只有用户点「批准」才真正执行 UPSERT。未 fork 则提示无需提交。")
    public Mono<String> seekCommitSandbox(SandboxState sandboxState, AgentCallContext callContext) {
        if (sandboxState == null || !sandboxState.isActive()) {
            return Mono.just("无需提交：本轮没有活跃沙箱。");
        }
        if (callContext == null || callContext.userId().isBlank()) {
            return Mono.just("无法确定当前用户，已拒绝创建提交审批（主库未被修改）。");
        }
        String sandbox = sandboxState.activeSandbox();
        String description = "把沙箱 " + sandbox + " 的改动合并回主库（UPSERT conversation / message）";
        String id =
                approvals.request(
                        callContext.userId(),
                        callContext.conversationId(),
                        "seek_commit_sandbox",
                        description,
                        () ->
                                seekDb.mergeSandbox(sandbox)
                                        .then(seekDb.dropSandbox(sandbox))
                                        .doOnSuccess(v -> sandboxState.clear())
                                        .thenReturn("已把沙箱 " + sandbox + " 的改动合并回主库并清理沙箱。"));
        return Mono.just(card(id, description));
    }

    @Tool(
            name = "seek_discard_sandbox",
            concurrencySafe = false,
            description = "直接丢弃当前 COW 沙箱（DROP DATABASE），主库零影响。实验性写操作不想保留时调用。" + "未 fork 则提示无需丢弃。")
    public Mono<String> seekDiscardSandbox(SandboxState sandboxState) {
        if (sandboxState == null || !sandboxState.isActive()) {
            return Mono.just("无需丢弃：本轮没有活跃沙箱。");
        }
        String sandbox = sandboxState.activeSandbox();
        return seekDb.dropSandbox(sandbox)
                .doOnSuccess(v -> sandboxState.clear())
                .thenReturn("已丢弃沙箱 " + sandbox + "（主库未受影响）。");
    }

    private String card(String id, String description) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of(
                            "type", "approval",
                            "id", id,
                            "tool", "seek_commit_sandbox",
                            "title", "需要你确认写回主库",
                            "description", description));
        } catch (Exception e) {
            return "审批卡片生成失败，写回操作未提交。审批号：" + id;
        }
    }
}
