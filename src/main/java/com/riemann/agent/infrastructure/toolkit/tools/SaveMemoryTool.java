package com.riemann.agent.infrastructure.toolkit.tools;

import com.riemann.agent.application.MemoryService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 把值得跨会话记住的稳定事实写入【长期记忆】。
 *
 * <p>当前用户通过工具方法的第二个参数 {@link AgentCallContext} 按请求注入：
 * 该参数不带 {@code @ToolParam}，因此不会出现在给 LLM 的 tool schema 里（模型看不到、也无法伪造），
 * 而是由 AgentScope 在调用时从本轮 {@code RuntimeContext} 中按类型取出。</p>
 *
 * <p>这样本类可以安全地做成<b>无状态单例</b>：不再有「同一用户并发多标签页写错 userId」的问题。
 * 早期实现用 {@code volatile String currentUserId} 承接，属于共享可变状态，
 * 在并发请求下必然串号。</p>
 */
@Component
@Profile("seekdb")
public class SaveMemoryTool {

    private final MemoryService memoryService;

    public SaveMemoryTool(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Tool(name = "save_memory",
          // 写库工具：明确声明「非并发安全」。@Tool 的 concurrencySafe 默认是 true，
          // 若不改，开启并行工具执行后模型一次返回多个 save_memory 会被同时执行。
          concurrencySafe = false,
          description = "把值得跨会话记住的稳定事实写入【长期记忆】：用户偏好/背景（如“用户偏好科幻小说”“预算低于15美元”"
                  + "“用户是电商分析师”）、或本轮得出的重要结论。短期记忆（当前对话历史）已自动提供，无需保存；"
                  + "只保存会跨会话复用的内容。写入后下一轮对话会自动召回注入上下文。")
    public Mono<String> saveMemory(
            @ToolParam(name = "content", description = "要记住的事实，一句话，如“用户偏好 Non Fiction 类图书，预算<15美元”") String content,
            AgentCallContext callContext) {
        // callContext 由框架按 RuntimeContext 注入；为空说明编排层没挂上下文，属于编码错误而非模型错误
        if (callContext == null || callContext.userId() == null || callContext.userId().isBlank()) {
            return Mono.just("未识别当前用户，记忆未保存（本轮调用上下文缺失）。");
        }
        if (content == null || content.isBlank()) {
            return Mono.just("记忆内容为空，未保存。");
        }
        return memoryService.save(callContext.userId(), content)
                .thenReturn("已保存到长期记忆：" + content);
    }
}
