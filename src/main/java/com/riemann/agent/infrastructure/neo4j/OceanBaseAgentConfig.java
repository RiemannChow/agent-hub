package com.riemann.agent.infrastructure.neo4j;

import com.riemann.agent.application.AgentEventMapper;
import com.riemann.agent.application.AgentPersonaProvider;
import com.riemann.agent.domain.AgentPersona;
import com.riemann.agent.infrastructure.config.FallbackModelHolder;
import com.riemann.agent.infrastructure.observability.AgentScopeMiddlewareChain;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * OceanBase 知识图谱问答的 Agent 装配（@Profile("oceanbase")）。
 *
 * <p>复用 {@code ModelConfig} 提供的共享 {@code agentModel}（混元网关），
 * 用 OceanBase 专属 persona 构建 ReActAgent，并注册 {@link OceanBaseToolkit} 中的 ob_* 工具。</p>
 */
@Configuration
@Profile("oceanbase")
public class OceanBaseAgentConfig {

    @Bean
    public OceanBaseToolkit oceanBaseToolkit(Neo4jRepository neo4j) {
        return new OceanBaseToolkit(neo4j);
    }

    @Bean
    public ReActAgent oceanBaseReactAgent(OceanBaseToolkit toolkit,
                                          AgentPersonaProvider personaProvider,
                                          Model agentModel,
                                          ObjectProvider<AgentScopeMiddlewareChain> middlewareChain,
                                          @Qualifier("modelExecutionConfig") ExecutionConfig modelExecutionConfig,
                                          @Qualifier("toolExecutionConfig") ExecutionConfig toolExecutionConfig,
                                          FallbackModelHolder fallbackModelHolder) {
        AgentPersona persona = personaProvider.getPersona();
        Toolkit agentToolkit = new Toolkit();
        agentToolkit.registerTool(toolkit);

        ReActAgent.Builder builder = ReActAgent.builder()
                .name("ob-react-agent")
                .sysPrompt(persona.renderSystemPrompt())
                .model(agentModel)
                .toolkit(agentToolkit)
                .maxIters(30)
                // 超时/重试：与 seekdb 侧共用同一份 agent.execution.* 配置
                .modelExecutionConfig(modelExecutionConfig)
                .toolExecutionConfig(toolExecutionConfig);
        if (fallbackModelHolder != null && fallbackModelHolder.isPresent()) {
            builder.fallbackModel(fallbackModelHolder.model());
        }
        // 可观测性（Langfuse）：仅 langfuse.enabled=true 时容器里才有该链
        middlewareChain.ifAvailable(chain -> chain.middlewares().forEach(builder::middleware));
        return builder.build();
    }
}
