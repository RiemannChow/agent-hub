package com.riemann.agent.application;

import com.riemann.agent.domain.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import org.springframework.stereotype.Component;

/**
 * 将 AgentScope 2.0 的真实事件（io.agentscope.core.event.AgentEvent 抽象类）
 * 映射为领域层 AgentEvent。Application 层只消费领域事件。
 *
 * AgentScope 2.0 提供 31 种强类型事件；这里抽取与对话式问数最相关的几类：
 *   ThinkingBlockDeltaEvent -> Thinking
 *   ToolCallStartEvent      -> ToolCalling
 *   ToolResultTextDeltaEvent-> ToolResult
 *   TextBlockDeltaEvent     -> Token（助手流式文本）
 *   AgentEndEvent           -> Completed
 * 其余事件类型映射为 null，由编排层过滤掉。
 */
@Component
public class AgentEventMapper {

    public AgentEvent toDomain(io.agentscope.core.event.AgentEvent e) {
        return switch (e) {
            case ThinkingBlockDeltaEvent t  -> new AgentEvent.Thinking(t.getDelta());
            case ToolCallStartEvent t       -> new AgentEvent.ToolCalling(t.getToolCallName(), "");
            case ToolResultTextDeltaEvent t -> new AgentEvent.ToolResult(t.getToolCallName(), t.getDelta());
            case TextBlockDeltaEvent t      -> new AgentEvent.Token(t.getDelta());
            // AgentEndEvent 不再映射：会话结束统一由编排层 concatWithValues(Completed(realId)) 发出，
            // 避免与编排层重复产生两条 completed（且前者 conversationId 为空）。
            default                         -> null;
        };
    }
}
