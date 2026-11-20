package com.riemann.agent.infrastructure.toolkit.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riemann.agent.application.ApprovalService;
import com.riemann.agent.application.MemoryService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 把值得跨会话记住的稳定事实写入【长期记忆】。
 *
 * <p>当前用户通过工具方法的第二个参数 {@link AgentCallContext} 按请求注入：该参数不带 {@code @ToolParam}，因此不会出现在给 LLM 的 tool
 * schema 里（模型看不到、也无法伪造），而是由 AgentScope 在调用时从本轮 {@code RuntimeContext} 中按类型取出。本类保持无状态单例，不存在并发串号。
 *
 * <p>写记忆属于对持久状态的副作用，因此与沙箱提交一样走【人在回路】：工具只返回 approval 卡片， 用户点批准后才真正写入（issue #27 后续）。
 */
@Component
@Profile("seekdb")
public class SaveMemoryTool {

    private final MemoryService memoryService;
    private final ApprovalService approvals;
    private final ObjectMapper objectMapper;

    public SaveMemoryTool(
            MemoryService memoryService, ApprovalService approvals, ObjectMapper objectMapper) {
        this.memoryService = memoryService;
        this.approvals = approvals;
        this.objectMapper = objectMapper;
    }

    @Tool(
            name = "save_memory",
            // 写库工具：明确声明「非并发安全」。@Tool 的 concurrencySafe 默认是 true，
            // 若不改，开启并行工具执行后模型一次返回多个 save_memory 会被同时执行。
            concurrencySafe = false,
            description =
                    "申请把值得跨会话记住的稳定事实写入【长期记忆】：用户偏好/背景、本轮得出的重要结论。"
                            + "调用后会向用户弹出确认卡片，用户批准后才真正写入；拒绝则不保存。"
                            + "短期记忆已自动提供，无需保存。")
    public Mono<String> saveMemory(
            @ToolParam(name = "content", description = "要记住的事实，一句话，如“用户偏好 Non Fiction 类图书，预算<15美元”")
                    String content,
            AgentCallContext callContext) {
        if (callContext == null || callContext.userId() == null || callContext.userId().isBlank()) {
            return Mono.just("未识别当前用户，记忆未保存（本轮调用上下文缺失）。");
        }
        if (content == null || content.isBlank()) {
            return Mono.just("记忆内容为空，未保存。");
        }
        String fact = content.trim();
        String description = "保存长期记忆：" + fact;
        String id =
                approvals.request(
                        callContext.userId(),
                        callContext.conversationId(),
                        "save_memory",
                        description,
                        () ->
                                memoryService
                                        .save(callContext.userId(), fact)
                                        .thenReturn("已保存到长期记忆：" + fact));
        try {
            return Mono.just(
                    objectMapper.writeValueAsString(
                            Map.of(
                                    "type", "approval",
                                    "id", id,
                                    "tool", "save_memory",
                                    "title", "需要你确认保存记忆",
                                    "description", description)));
        } catch (Exception e) {
            return Mono.just("审批卡片生成失败，记忆未保存。审批号：" + id);
        }
    }
}
