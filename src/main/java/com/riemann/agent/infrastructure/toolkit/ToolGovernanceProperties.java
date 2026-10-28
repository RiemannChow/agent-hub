package com.riemann.agent.infrastructure.toolkit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工具结果治理配置（{@code agent.toolkit.governance.*}）。
 *
 * <p>解决两个「不致命但持续放血」的问题：</p>
 * <ol>
 *   <li><b>结果体积无上限</b>：一次 SQL/检索返回几万字符就整段塞进上下文，
 *       下一轮模型调用直接被顶到窗口边界，前面几轮的历史被迫丢弃。</li>
 *   <li><b>失败无上限</b>：工具报错后模型会反复重试同一调用，直到 {@code maxIters=30} 耗尽，
 *       每一轮重试都是一次完整的模型调用 —— token 全烧在同一个错误上。</li>
 * </ol>
 */
@Component
@ConfigurationProperties(prefix = "agent.toolkit.governance")
public class ToolGovernanceProperties {

    /** 是否启用治理。关闭后工具结果原样回填（等同改造前行为）。 */
    private boolean enabled = true;

    /** 单次工具结果的字符上限，超出截断并标注。 */
    private int maxResultChars = 8000;

    /** 单轮对话内所有工具结果累计的字符上限，超出后只回填一句说明。 */
    private int turnBudgetChars = 60000;

    /** 同一工具在本轮内连续失败多少次后熔断（后续结果只回填一句「禁止重试」）。 */
    private int maxConsecutiveFailures = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxResultChars() {
        return maxResultChars;
    }

    public void setMaxResultChars(int maxResultChars) {
        this.maxResultChars = maxResultChars;
    }

    public int getTurnBudgetChars() {
        return turnBudgetChars;
    }

    public void setTurnBudgetChars(int turnBudgetChars) {
        this.turnBudgetChars = turnBudgetChars;
    }

    public int getMaxConsecutiveFailures() {
        return maxConsecutiveFailures;
    }

    public void setMaxConsecutiveFailures(int maxConsecutiveFailures) {
        this.maxConsecutiveFailures = maxConsecutiveFailures;
    }
}
