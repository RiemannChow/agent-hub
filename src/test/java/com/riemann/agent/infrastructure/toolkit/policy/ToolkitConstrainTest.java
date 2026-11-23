package com.riemann.agent.infrastructure.toolkit.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.riemann.agent.infrastructure.config.AgentConfig;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

class ToolkitConstrainTest {

    @Test
    void allRegisteredToolsAreWrappedAndKeepTheirSchemas() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(fakeTool("seek_sql_query"));
        toolkit.registerAgentTool(fakeTool("save_memory"));

        AgentConfig.constrainAll(
                toolkit,
                new ConstrainGate(new ToolPolicyRegistry()),
                Mockito.mock(ConstrainAudit.class));

        assertThat(toolkit.getToolNames())
                .containsExactlyInAnyOrder("seek_sql_query", "save_memory");
        assertThat(toolkit.getTool("seek_sql_query")).isInstanceOf(ConstrainedAgentTool.class);
        assertThat(toolkit.getTool("save_memory")).isInstanceOf(ConstrainedAgentTool.class);
        // schema 不变：模型看到的工具描述与包装前一致
        Map<String, Object> params = toolkit.getTool("seek_sql_query").getParameters();
        assertThat(params).containsKey("sql");
    }

    @Test
    void wrappingIsIdempotent() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(fakeTool("seek_sql_query"));
        ConstrainGate gate = new ConstrainGate(new ToolPolicyRegistry());
        ConstrainAudit audit = Mockito.mock(ConstrainAudit.class);
        AgentConfig.constrainAll(toolkit, gate, audit);
        AgentTool first = toolkit.getTool("seek_sql_query");
        AgentConfig.constrainAll(toolkit, gate, audit);
        assertThat(toolkit.getTool("seek_sql_query")).isSameAs(first);
    }

    @Test
    void callerContextFlowsFromRuntimeContextIntoGate() {
        com.riemann.agent.infrastructure.toolkit.tools.AgentCallContext expected =
                new com.riemann.agent.infrastructure.toolkit.tools.AgentCallContext(
                        "user-x", "conv-x");
        io.agentscope.core.agent.RuntimeContext rc =
                io.agentscope.core.agent.RuntimeContext.builder()
                        .userId("user-x")
                        .sessionId("conv-x")
                        .build();
        rc.put(com.riemann.agent.infrastructure.toolkit.tools.AgentCallContext.class, expected);
        io.agentscope.core.tool.ToolExecutionContext exec = rc.asToolExecutionContext();
        assertThat(exec.get(com.riemann.agent.infrastructure.toolkit.tools.AgentCallContext.class))
                .isSameAs(expected);
    }

    private static AgentTool fakeTool(String name) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "test tool";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("sql", Map.of("type", "string"));
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(
                        new ToolResultBlock(
                                "id", name, List.of(TextBlock.builder().text("ok").build())));
            }
        };
    }
}
