package com.riemann.agent.infrastructure.toolkit.verify;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 工具结果验证配置（{@code agent.verify.*}）。 */
@Component
@ConfigurationProperties(prefix = "agent.verify")
public class VerificationProperties {

    /** 总开关。关闭后不做任何验证，等同改造前行为。 */
    private boolean enabled = true;

    /** 是否把验证失败标注进回填给模型的结果。 默认 true：让模型知道自己拿到的数据不可信，能主动换工具或说明。 */
    private boolean annotateResults = true;

    /** 数字合理性上限；超过视为可疑（防止把错位的亿/万当成真实值）。 */
    private double maxReasonableNumber = 1_000_000_000_000d;

    /** 单个结果里最多抽取多少个数字用于 grounding。 */
    private int maxNumbersPerResult = 200;

    /** 可疑内容关键字（提示注入 / 凭据泄露）。命中即在结果上标注。 */
    private List<String> suspiciousPatterns =
            new ArrayList<>(
                    List.of(
                            "ignore previous instructions",
                            "system prompt",
                            "api[_-]?key",
                            "password[=:]",
                            "-----begin private key-----"));

    /** 结果为空时是否视为验证失败。 */
    private boolean emptyResultIsFailure = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAnnotateResults() {
        return annotateResults;
    }

    public void setAnnotateResults(boolean annotateResults) {
        this.annotateResults = annotateResults;
    }

    public double getMaxReasonableNumber() {
        return maxReasonableNumber;
    }

    public void setMaxReasonableNumber(double maxReasonableNumber) {
        this.maxReasonableNumber = maxReasonableNumber;
    }

    public int getMaxNumbersPerResult() {
        return maxNumbersPerResult;
    }

    public void setMaxNumbersPerResult(int maxNumbersPerResult) {
        this.maxNumbersPerResult = maxNumbersPerResult;
    }

    public List<String> getSuspiciousPatterns() {
        return suspiciousPatterns;
    }

    public void setSuspiciousPatterns(List<String> suspiciousPatterns) {
        this.suspiciousPatterns =
                suspiciousPatterns == null ? new ArrayList<>() : suspiciousPatterns;
    }

    public boolean isEmptyResultIsFailure() {
        return emptyResultIsFailure;
    }

    public void setEmptyResultIsFailure(boolean emptyResultIsFailure) {
        this.emptyResultIsFailure = emptyResultIsFailure;
    }
}
