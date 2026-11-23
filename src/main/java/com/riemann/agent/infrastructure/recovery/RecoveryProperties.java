package com.riemann.agent.infrastructure.recovery;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 恢复策略配置（{@code agent.correct.*}）。 */
@Component
@ConfigurationProperties(prefix = "agent.correct")
public class RecoveryProperties {

    /** 总开关。关闭后所有错误按框架原样处理。 */
    private boolean enabled = true;

    /** 单轮内所有工具累计的恢复次数硬上限，超限强制 ESCALATE。 */
    private int maxRecoveriesPerTurn = 6;

    /** 同一错误类别单轮内的尝试上限。 */
    private int maxAttemptsPerCategory = 3;

    /** 同一工具单轮内的尝试上限。 */
    private int maxAttemptsPerTool = 3;

    /** TRANSIENT 退避基数（指数退避）。 */
    private Duration initialBackoff = Duration.ofMillis(300);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxRecoveriesPerTurn() {
        return maxRecoveriesPerTurn;
    }

    public void setMaxRecoveriesPerTurn(int maxRecoveriesPerTurn) {
        this.maxRecoveriesPerTurn = maxRecoveriesPerTurn;
    }

    public int getMaxAttemptsPerCategory() {
        return maxAttemptsPerCategory;
    }

    public void setMaxAttemptsPerCategory(int maxAttemptsPerCategory) {
        this.maxAttemptsPerCategory = maxAttemptsPerCategory;
    }

    public int getMaxAttemptsPerTool() {
        return maxAttemptsPerTool;
    }

    public void setMaxAttemptsPerTool(int maxAttemptsPerTool) {
        this.maxAttemptsPerTool = maxAttemptsPerTool;
    }

    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    public void setInitialBackoff(Duration initialBackoff) {
        this.initialBackoff = initialBackoff;
    }
}
