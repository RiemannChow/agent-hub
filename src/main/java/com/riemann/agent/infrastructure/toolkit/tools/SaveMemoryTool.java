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
 * <p>当前用户由编排层在每轮对话前后通过 {@link CurrentUserAware} 注入/清空，
 * 避免 AgentScope 工具方法签名无法携带请求上下文导致的 userId 错乱。</p>
 */
@Component
@Profile("seekdb")
public class SaveMemoryTool implements CurrentUserAware {

    private final MemoryService memoryService;

    /**
     * 当前正在处理的用户标识。save_memory 工具需要按用户维度落库，但 AgentScope 工具方法签名
     * 无法携带请求上下文，故由编排层在每轮 streamEvents 前 set、结束后 clear。
     * 演示场景下单浏览器标签串行对话，无并发写竞争；生产应改为 Reactor Context 透传。
     */
    private volatile String currentUserId;

    public SaveMemoryTool(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public void setCurrentUser(String userId) {
        this.currentUserId = userId;
    }

    @Override
    public void clearCurrentUser() {
        this.currentUserId = null;
    }

    @Tool(name = "save_memory",
          description = "把值得跨会话记住的稳定事实写入【长期记忆】：用户偏好/背景（如“用户偏好科幻小说”“预算低于15美元”"
                  + "“用户是电商分析师”）、或本轮得出的重要结论。短期记忆（当前对话历史）已自动提供，无需保存；"
                  + "只保存会跨会话复用的内容。写入后下一轮对话会自动召回注入上下文。")
    public Mono<String> saveMemory(
            @ToolParam(name = "content", description = "要记住的事实，一句话，如“用户偏好 Non Fiction 类图书，预算<15美元”") String content) {
        String uid = currentUserId;
        if (uid == null) {
            return Mono.just("未识别当前用户，记忆未保存（可能是编排层未注入 userId）。");
        }
        return memoryService.save(uid, content).thenReturn("已保存到长期记忆：" + content);
    }
}
