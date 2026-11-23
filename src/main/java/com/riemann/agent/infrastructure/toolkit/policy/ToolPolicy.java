package com.riemann.agent.infrastructure.toolkit.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 执行前的工具策略。描述「谁能以什么参数调用哪个工具」，由 {@link ToolPolicyRegistry} 持有。
 *
 * <p>策略刻意做成不可变值对象：门禁在每个 tool call 上读它，绝不能在运行时被模型或请求改写。
 *
 * @param toolName 工具名（与 {@code @Tool(name=...)} 一致）
 * @param decision 默认决策；{@code ASK} 表示转人工审批
 * @param risk 风险等级，仅用于展示与审计
 * @param requiredParams 必须存在且非空的 parameters
 * @param allowedValues 参数白名单；key 不在 map 中表示该参数不受限
 * @param maxStringLength 字符串参数长度上限，防止超长内容灌入上下文
 * @param maxDelegationDepth 允许的 A2A 委托深度上限；负数表示不限制
 */
public record ToolPolicy(
        String toolName,
        Decision decision,
        Risk risk,
        List<String> requiredParams,
        Map<String, List<String>> allowedValues,
        int maxStringLength,
        int maxDelegationDepth) {

    public enum Decision {
        ALLOW,
        DENY,
        ASK
    }

    public enum Risk {
        LOW,
        MEDIUM,
        HIGH
    }

    public ToolPolicy {
        requiredParams = List.copyOf(requiredParams == null ? List.of() : requiredParams);
        allowedValues =
                Map.copyOf(
                        allowedValues == null
                                ? Map.of()
                                : allowedValues.entrySet().stream()
                                        .collect(
                                                java.util.stream.Collectors.toMap(
                                                        Map.Entry::getKey,
                                                        e -> List.copyOf(e.getValue()))));
    }

    /** 只读低风险工具：默认放行，仅做基本参数校验。 */
    public static ToolPolicy readOnly(String toolName, List<String> requiredParams) {
        return new ToolPolicy(
                toolName, Decision.ALLOW, Risk.LOW, requiredParams, Map.of(), 4000, 3);
    }

    /** 有副作用或涉及用户数据的工具：默认转人工审批。 */
    public static ToolPolicy sensitive(String toolName, List<String> requiredParams) {
        return new ToolPolicy(toolName, Decision.ASK, Risk.HIGH, requiredParams, Map.of(), 2000, 0);
    }

    /** 明确禁止的工具（例如被临时下线的远程工具）。 */
    public static ToolPolicy denied(String toolName) {
        return new ToolPolicy(toolName, Decision.DENY, Risk.HIGH, List.of(), Map.of(), 0, 0);
    }

    /** 返回该参数的白名单，未配置时返回 null。 */
    public List<String> allowedValuesFor(String param) {
        return allowedValues.get(param);
    }

    /** 策略的防御性副本，供配置层构造变体时使用。 */
    public ToolPolicy withDecision(Decision newDecision) {
        List<String> values = new ArrayList<>(requiredParams);
        return new ToolPolicy(
                toolName,
                newDecision,
                risk,
                values,
                allowedValues,
                maxStringLength,
                maxDelegationDepth);
    }
}
