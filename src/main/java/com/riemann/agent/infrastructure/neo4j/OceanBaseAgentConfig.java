package com.riemann.agent.infrastructure.neo4j;

import com.riemann.agent.application.AgentPersonaProvider;
import com.riemann.agent.domain.AgentPersona;
import com.riemann.agent.infrastructure.config.FallbackModelHolder;
import com.riemann.agent.infrastructure.observability.AgentScopeMiddlewareChain;
import com.riemann.agent.infrastructure.toolkit.ToolResultGovernor;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * OceanBase 知识图谱问答的 Agent 装配（@Profile("oceanbase")）。
 *
 * <p>复用 {@code ModelConfig} 提供的共享 {@code agentModel}（混元网关）， 用 OceanBase 专属 persona 构建
 * ReActAgent，并注册 {@link OceanBaseToolkit} 中的 ob_* 工具。
 */
@Configuration
@Profile("oceanbase")
public class OceanBaseAgentConfig {

    @Bean
    public OceanBaseToolkit oceanBaseToolkit(Neo4jRepository neo4j) {
        return new OceanBaseToolkit(neo4j);
    }

    /**
     * 工具容器 —— 必须是 Bean，不能是 {@code oceanBaseReactAgent()} 里的局部变量。
     *
     * <p>原因：{@code RemoteAgentToolRegistrar}（A2A 出站协作）需要往 Toolkit 里动态注册对端工具， 它按类型注入 Toolkit。若这里不暴露
     * Bean，oceanbase profile 启动会直接失败—— 这是「Toolkit 只在 seekdb profile 有 Bean」留下的缺口，本次一并补上。
     */
    @Bean
    public Toolkit agentToolkit(
            OceanBaseToolkit oceanBaseToolkit,
            @Qualifier("toolExecutionConfig") ExecutionConfig toolExecutionConfig,
            @Value("${agent.toolkit.parallel:true}") boolean parallel) {
        Toolkit toolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .parallel(parallel)
                                .executionConfig(toolExecutionConfig)
                                .build());
        toolkit.registerTool(oceanBaseToolkit);
        return toolkit;
    }

    @Bean
    public ReActAgent oceanBaseReactAgent(
            Toolkit agentToolkit,
            AgentPersonaProvider personaProvider,
            Model agentModel,
            ObjectProvider<AgentScopeMiddlewareChain> middlewareChain,
            ObjectProvider<ToolResultGovernor> toolGovernor,
            @Qualifier("modelExecutionConfig") ExecutionConfig modelExecutionConfig,
            @Qualifier("toolExecutionConfig") ExecutionConfig toolExecutionConfig,
            FallbackModelHolder fallbackModelHolder) {
        AgentPersona persona = personaProvider.getPersona();

        ReActAgent.Builder builder =
                ReActAgent.builder()
                        .name("ob-react-agent")
                        .sysPrompt(persona.renderSystemPrompt())
                        .model(agentModel)
                        .toolkit(agentToolkit)
                        .maxIters(30)
                        // 超时/重试：与 seekdb 侧共用同一份 agent.execution.* 配置
                        .modelExecutionConfig(modelExecutionConfig)
                        .toolExecutionConfig(toolExecutionConfig);
        // 工具结果治理：ob_cypher 最容易返回大结果集，更需要单次截断 + 单轮预算 + 失败熔断
        toolGovernor.ifAvailable(governor -> builder.hook(governor).middleware(governor));
        if (fallbackModelHolder != null && fallbackModelHolder.isPresent()) {
            builder.fallbackModel(fallbackModelHolder.model());
        }
        // 可观测性（Langfuse）：仅 langfuse.enabled=true 时容器里才有该链
        middlewareChain.ifAvailable(chain -> chain.middlewares().forEach(builder::middleware));
        return builder.build();
    }
}
