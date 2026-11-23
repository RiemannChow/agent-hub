package com.riemann.agent.infrastructure.toolkit.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.riemann.agent.infrastructure.toolkit.tools.AgentCallContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolExecutionContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ConstrainedAgentToolTest {

    private final ToolPolicyRegistry registry = new ToolPolicyRegistry();
    private final ConstrainGate gate = new ConstrainGate(registry);
    private final ConstrainAudit audit = mock(ConstrainAudit.class);

    @Test
    @SuppressWarnings("unchecked")
    void deniedCallNeverInvokesDelegate() {
        AgentTool delegate = mock(AgentTool.class);
        when(delegate.getName()).thenReturn("seek_sql_query");
        ToolExecutionContext exec = mock(ToolExecutionContext.class);
        when(exec.get(AgentCallContext.class)).thenReturn(new AgentCallContext("u", "c"));
        io.agentscope.core.tool.ToolCallParam param =
                mock(io.agentscope.core.tool.ToolCallParam.class);
        when(param.getInput()).thenReturn(Map.of());
        when(param.getContext()).thenReturn(exec);

        ConstrainedAgentTool wrapped = new ConstrainedAgentTool(delegate, gate, audit);
        ToolResultBlock result = wrapped.callAsync(param).block();

        assertThat(result).isNotNull();
        assertThat(result.getState()).isEqualTo(ToolResultState.DENIED);
        assertThat(textOf(result)).contains("策略拒绝").contains("missing required parameter");
        verify(delegate, never()).callAsync(any());
        verify(audit).denied(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void allowedCallInvokesDelegate() {
        AgentTool delegate = mock(AgentTool.class);
        when(delegate.getName()).thenReturn("seek_sql_query");
        ToolResultBlock ok =
                new ToolResultBlock(
                        "id", "seek_sql_query", List.of(TextBlock.builder().text("ok").build()));
        when(delegate.callAsync(any())).thenReturn(Mono.just(ok));
        ToolExecutionContext exec = mock(ToolExecutionContext.class);
        when(exec.get(AgentCallContext.class)).thenReturn(new AgentCallContext("u", "c"));
        io.agentscope.core.tool.ToolCallParam param =
                mock(io.agentscope.core.tool.ToolCallParam.class);
        when(param.getInput()).thenReturn(Map.of("sql", "select 1"));
        when(param.getContext()).thenReturn(exec);

        ConstrainedAgentTool wrapped = new ConstrainedAgentTool(delegate, gate, audit);
        ToolResultBlock result = wrapped.callAsync(param).block();

        assertThat(result).isSameAs(ok);
        verify(delegate).callAsync(param);
        verify(audit).allowed(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingContextFailsClosed() {
        AgentTool delegate = mock(AgentTool.class);
        when(delegate.getName()).thenReturn("seek_sql_query");
        io.agentscope.core.tool.ToolCallParam param =
                mock(io.agentscope.core.tool.ToolCallParam.class);
        when(param.getInput()).thenReturn(Map.of("sql", "select 1"));
        when(param.getContext()).thenReturn(null);

        ConstrainedAgentTool wrapped = new ConstrainedAgentTool(delegate, gate, audit);
        ToolResultBlock result = wrapped.callAsync(param).block();

        assertThat(result.getState()).isEqualTo(ToolResultState.DENIED);
        verify(delegate, never()).callAsync(any());
    }

    private static String textOf(ToolResultBlock block) {
        StringBuilder sb = new StringBuilder();
        block.getOutput()
                .forEach(
                        b -> {
                            if (b instanceof TextBlock t && t.getText() != null) {
                                sb.append(t.getText());
                            }
                        });
        return sb.toString();
    }
}
