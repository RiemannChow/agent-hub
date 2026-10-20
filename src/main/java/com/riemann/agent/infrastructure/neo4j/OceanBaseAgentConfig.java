package com.riemann.agent.infrastructure.neo4j;

import com.riemann.agent.application.AgentEventMapper;
import com.riemann.agent.application.AgentPersonaProvider;
import com.riemann.agent.domain.AgentPersona;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
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
                                          Model agentModel) {
        AgentPersona persona = personaProvider.getPersona();
        Toolkit agentToolkit = new Toolkit();
        agentToolkit.registerTool(toolkit);
        return ReActAgent.builder()
                .name("ob-react-agent")
                .sysPrompt(persona.renderSystemPrompt())
                .model(agentModel)
                .toolkit(agentToolkit)
                .maxIters(30)
                .build();
    }
}
