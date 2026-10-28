package com.riemann.agent.infrastructure.execution;

import io.agentscope.core.model.ExecutionConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@link AgentExecutionProperties} 翻译成 AgentScope 的 {@link ExecutionConfig}。
 *
 * <p>两个 Bean 分别在 {@code AgentConfig}（seekdb）与 {@code OceanBaseAgentConfig}（oceanbase）
 * 里注入到 {@code ReActAgent.Builder#modelExecutionConfig} / {@code #toolExecutionConfig}，
 * 从而让「超时 + 重试」对两条业务线一致生效。</p>
 */
@Configuration
public class AgentExecutionConfig {

    /**
     * 模型调用：显式超时 + 有限重试。
     *
     * <p>重试策略沿用框架的 {@link ExecutionConfig#RETRYABLE_ERRORS}——只对连接失败、
     * 5xx 等可重试错误生效，不会把「模型返回了内容但内容不对」这类情况也重跑一遍。</p>
     */
    @Bean
    public ExecutionConfig modelExecutionConfig(AgentExecutionProperties props) {
        AgentExecutionProperties.ModelSettings m = props.getModel();
        return ExecutionConfig.builder()
                .timeout(m.getTimeout())
                .maxAttempts(m.getMaxAttempts())
                .initialBackoff(m.getInitialBackoff())
                .maxBackoff(m.getMaxBackoff())
                .backoffMultiplier(m.getBackoffMultiplier())
                .retryOn(ExecutionConfig.RETRYABLE_ERRORS)
                .build();
    }

    /**
     * 工具调用：显式超时，默认不重试。
     *
     * <p>工具可能带副作用（{@code save_memory} 落库、{@code seek_fork_sandbox} 建快照），
     * 自动重试会造成重复写入，因此默认 {@code max-attempts=1}；确需重试再按工具场景调大。</p>
     */
    @Bean
    public ExecutionConfig toolExecutionConfig(AgentExecutionProperties props) {
        AgentExecutionProperties.ToolSettings t = props.getTool();
        return ExecutionConfig.builder()
                .timeout(t.getTimeout())
                .maxAttempts(t.getMaxAttempts())
                .build();
    }
}
