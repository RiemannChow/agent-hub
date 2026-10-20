package com.riemann.agent.infrastructure.config;

import com.riemann.agent.application.AgentEventMapper;
import com.riemann.agent.application.AgentPersonaProvider;
import com.riemann.agent.application.MemoryService;
import com.riemann.agent.domain.AgentPersona;
import com.riemann.agent.infrastructure.seekdb.SeekDbConversationRepository;
import com.riemann.agent.infrastructure.seekdb.books.HybridSearchService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.riemann.agent.infrastructure.toolkit.tools.SaveMemoryTool;
import com.riemann.agent.infrastructure.toolkit.tools.SeekBookCountByYearTool;
import com.riemann.agent.infrastructure.toolkit.tools.SeekBookSearchTool;
import com.riemann.agent.infrastructure.toolkit.tools.SeekForkSandboxTool;
import com.riemann.agent.infrastructure.toolkit.tools.SeekHybridSearchTool;
import com.riemann.agent.infrastructure.toolkit.tools.SeekSqlQueryTool;

/**
 * Agent 装配配置。
 * 通过 @Profile("seekdb") 限定：SeekDB 工具与 ReActAgent 仅在 seekdb 环境下存在。
 */
@Configuration
@Profile("seekdb")
public class AgentConfig {

    /**
     * 构建 ReActAgent：sysPrompt 由 AgentPersona 渲染得到，
     * 因此“切换行业”只需更换 AgentPersona（来自 yml 或数据库）。
     * 底层 LLM 客户端复用无 profile 限制的 {@link ModelConfig#agentModel}。
     * 各工具拆分为独立 @Component（见 infrastructure.toolkit.tools 包），这里统一注册进 AgentScope 的 Toolkit。
     */
    @Bean
    public ReActAgent reactAgent(AgentPersonaProvider personaProvider, Model agentModel,
                                 SeekHybridSearchTool seekHybridSearchTool,
                                 SeekForkSandboxTool seekForkSandboxTool,
                                 SeekSqlQueryTool seekSqlQueryTool,
                                 SeekBookSearchTool seekBookSearchTool,
                                 SeekBookCountByYearTool seekBookCountByYearTool,
                                 SaveMemoryTool saveMemoryTool) {
        AgentPersona persona = personaProvider.getPersona();
        // 把带有 @Tool 方法的 POJO 逐个注册进 AgentScope 的 Toolkit
        Toolkit agentToolkit = new Toolkit();
        agentToolkit.registerTool(seekHybridSearchTool);
        agentToolkit.registerTool(seekForkSandboxTool);
        agentToolkit.registerTool(seekSqlQueryTool);
        agentToolkit.registerTool(seekBookSearchTool);
        agentToolkit.registerTool(seekBookCountByYearTool);
        agentToolkit.registerTool(saveMemoryTool);
        return ReActAgent.builder()
                .name("seek-react-agent")
                .sysPrompt(persona.renderSystemPrompt())
                .model(agentModel)
                .toolkit(agentToolkit)
                .maxIters(30)
                .build();
    }
}
