package com.riemann.agent.infrastructure.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Agent 执行层的超时/重试配置（{@code agent.execution.*}）。
 *
 * <p><b>为什么要显式配置：</b>AgentScope 内部对模型调用与工具调用都有默认的
 * {@code ExecutionConfig}（模型 5 分钟 / 3 次重试，工具 5 分钟 / 1 次重试），
 * 但这些默认值偏「兜底」而非「可运营」——5 分钟的模型超时会让 SSE 长时间空转，
 * 而 {@code maxIters(30)} 又意味着单轮理论上可以累积出很长的总耗时。
 * 因此这里把三个关键旋钮显式化：</p>
 *
 * <ul>
 *   <li><b>模型单次调用</b>：超时 + 失败重试（仅对连接类可重试错误生效）；</li>
 *   <li><b>工具单次调用</b>：超时 + 重试（默认不重试，避免有副作用的工具被重复执行）；</li>
 *   <li><b>整轮对话</b>：总时长上限，到点即截断，保证前端一定能收到终止信号。</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "agent.execution")
public class AgentExecutionProperties {

    /**
     * 单轮对话的总时长上限。到点后不再等待后续事件，直接补一条说明并结束该轮流。
     *
     * <p>它<b>优先于</b>「单次调用超时 × 重试次数」的理论最坏值：即使模型侧还在重试，
     * 整轮也会被准时切断，避免前端无限转圈。</p>
     */
    private Duration turnTimeout = Duration.ofSeconds(300);

    private ModelSettings model = new ModelSettings();

    private ToolSettings tool = new ToolSettings();

    public Duration getTurnTimeout() {
        return turnTimeout;
    }

    public void setTurnTimeout(Duration turnTimeout) {
        this.turnTimeout = turnTimeout;
    }

    public ModelSettings getModel() {
        return model;
    }

    public void setModel(ModelSettings model) {
        this.model = model;
    }

    public ToolSettings getTool() {
        return tool;
    }

    public void setTool(ToolSettings tool) {
        this.tool = tool;
    }

    /** 模型调用（含流式）的单次超时与重试。 */
    public static class ModelSettings {

        private Duration timeout = Duration.ofSeconds(120);
        private Integer maxAttempts = 3;
        private Duration initialBackoff = Duration.ofMillis(500);
        private Duration maxBackoff = Duration.ofSeconds(5);
        private Double backoffMultiplier = 2.0;

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Integer getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(Integer maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }

        public Double getBackoffMultiplier() {
            return backoffMultiplier;
        }

        public void setBackoffMultiplier(Double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
        }
    }

    /** 工具调用的单次超时与重试。默认 1 次（不重试），避免副作用工具被重复执行。 */
    public static class ToolSettings {

        private Duration timeout = Duration.ofSeconds(30);
        private Integer maxAttempts = 1;

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Integer getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(Integer maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }
}
