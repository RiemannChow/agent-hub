package com.riemann.agent.infrastructure.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;

import com.riemann.agent.application.AgentPersonaProvider;
import com.riemann.agent.domain.AgentPersona;
import com.riemann.agent.infrastructure.observability.AgentScopeMiddlewareChain;
import com.riemann.agent.infrastructure.toolkit.ToolResultGovernor;
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
     * 工具容器 —— 单独暴露成 Bean，而不是在 {@code reactAgent()} 里 new 一个局部变量。
     *
     * <p><b>为什么必须是 Bean：</b>A2A 协作需要把「运行时从 Nacos 发现到的对端智能体」动态注册成工具
     * （见 {@code RemoteAgentToolRegistrar}）。如果 Toolkit 是方法内局部变量，外部拿不到引用，
     * 动态增删就无从下手——这也是改造前「发现得到但协作不了」的结构性原因之一。</p>
     *
     * <p>{@code allowToolDeletion(true)} 是动态注册的前提：对端下线时要能把工具摘掉，
     * 否则模型会一直看到一个调用必然失败的工具。</p>
     */
    @Bean
    public Toolkit agentToolkit(SeekHybridSearchTool seekHybridSearchTool,
                                SeekForkSandboxTool seekForkSandboxTool,
                                SeekSqlQueryTool seekSqlQueryTool,
                                SeekBookSearchTool seekBookSearchTool,
                                SeekBookCountByYearTool seekBookCountByYearTool,
                                SaveMemoryTool saveMemoryTool,
                                @Qualifier("toolExecutionConfig") ExecutionConfig toolExecutionConfig,
                                @Value("${agent.toolkit.parallel:true}") boolean parallel) {
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
                .allowToolDeletion(true)
                // 并行执行：模型一次返回多个 tool_use 时并发跑。
                // 前提是工具如实声明 readOnly / concurrencySafe —— 写库工具（save_memory、
                // seek_fork_sandbox）已显式标 concurrencySafe=false，框架不会并发调度它们。
                .parallel(parallel)
                // 工具层超时/重试沿用 agent.execution.tool.*（单次超时、默认不重试）
                .executionConfig(toolExecutionConfig)
                .build());
        // 把带有 @Tool 方法的 POJO 逐个注册进 AgentScope 的 Toolkit
        toolkit.registerTool(seekHybridSearchTool);
        toolkit.registerTool(seekForkSandboxTool);
        toolkit.registerTool(seekSqlQueryTool);
        toolkit.registerTool(seekBookSearchTool);
        toolkit.registerTool(seekBookCountByYearTool);
        toolkit.registerTool(saveMemoryTool);
        return toolkit;
    }

    /**
     * 构建 ReActAgent：sysPrompt 由 AgentPersona 渲染得到，
     * 因此“切换行业”只需更换 AgentPersona（来自 yml 或数据库）。
     * 底层 LLM 客户端复用无 profile 限制的 {@link ModelConfig#agentModel}。
     *
     * <p><b>三个框架原生能力做成开关（默认关）：</b>它们都会改变模型可见的工具集/提示词，
     * 属于「会显著影响行为」的改动，因此不在代码里替使用者拍板，而是交给配置 + 评测决定：</p>
     * <ul>
     *   <li>{@code agent.native.task-list}：注入待办清单工具与任务提醒中间件，
     *       让模型在多步任务里显式维护「已做/待做」。适合复杂多步问数，简单问答会被噪声干扰。</li>
     *   <li>{@code agent.native.meta-tool}：暴露元工具，让模型能自查可用工具。</li>
     *   <li>{@code agent.native.pending-tool-recovery}：工具执行中被中断后的恢复能力，
     *       与「停止生成」配合使用。语义依赖框架内部状态机，建议在真实模型环境验证后再开。</li>
     * </ul>
     */
    @Bean
    public ReActAgent reactAgent(AgentPersonaProvider personaProvider, Model agentModel,
                                 Toolkit agentToolkit,
                                 ObjectProvider<AgentScopeMiddlewareChain> middlewareChain,
                                 ObjectProvider<ToolResultGovernor> toolGovernor,
                                 @Qualifier("modelExecutionConfig") ExecutionConfig modelExecutionConfig,
                                 @Qualifier("toolExecutionConfig") ExecutionConfig toolExecutionConfig,
                                 FallbackModelHolder fallbackModelHolder,
                                 @Value("${agent.native.task-list:false}") boolean enableTaskList,
                                 @Value("${agent.native.meta-tool:false}") boolean enableMetaTool,
                                 @Value("${agent.native.pending-tool-recovery:false}") boolean enablePendingToolRecovery) {
        AgentPersona persona = personaProvider.getPersona();

        ReActAgent.Builder builder = ReActAgent.builder()
                .name("seek-react-agent")
                .sysPrompt(persona.renderSystemPrompt())
                .model(agentModel)
                .toolkit(agentToolkit)
                .maxIters(30)
                // 超时/重试：显式下发，避免沿用框架「模型 5 分钟、工具 5 分钟」的宽兜底默认值
                .modelExecutionConfig(modelExecutionConfig)
                .toolExecutionConfig(toolExecutionConfig)
                // 原生能力开关（默认关，见方法注释）
                .enableTaskList(enableTaskList)
                .enableMetaTool(enableMetaTool)
                .enablePendingToolRecovery(enablePendingToolRecovery);
        // 工具结果治理：单次截断 + 单轮预算 + 连续失败熔断。
        // 同一个实例既做 Hook（改写 PostActingEvent 里的结果）又做 Middleware
        // （onActing 里拿到本轮 RuntimeContext，把 toolCallId 与轮次绑定）。
        toolGovernor.ifAvailable(governor -> builder.hook(governor).middleware(governor));
        // 降级：仅在配置了 agent.model.fallback.name 时启用
        if (fallbackModelHolder != null && fallbackModelHolder.isPresent()) {
            builder.fallbackModel(fallbackModelHolder.model());
        }
        // 可观测性（Langfuse）：仅 langfuse.enabled=true 时容器里才有该链，否则保持零开销
        middlewareChain.ifAvailable(chain -> chain.middlewares().forEach(builder::middleware));
        return builder.build();
    }
}
