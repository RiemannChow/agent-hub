package com.riemann.agent.infrastructure.toolkit.policy;

import com.riemann.agent.infrastructure.toolkit.tools.AgentCallContext;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 执行前门禁：把「工具名 + 参数 + 调用者上下文」变成 allow / deny / ask 决策。
 *
 * <p>纯函数，不碰 Spring 上下文、不做 IO，因此可以被单测完整覆盖。 真正阻止工具执行的是 {@link ConstrainHook}，本类只回答「该不该执行」。
 */
@Component
public class ConstrainGate {

    private final ToolPolicyRegistry registry;

    public ConstrainGate(ToolPolicyRegistry registry) {
        this.registry = registry;
    }

    /** 门禁结论。 */
    public record Decision(ToolPolicy.Decision decision, String reason, ToolPolicy.Risk risk) {
        public static Decision allow(ToolPolicy policy) {
            return new Decision(ToolPolicy.Decision.ALLOW, "allowed", policy.risk());
        }

        public static Decision deny(String reason) {
            return new Decision(ToolPolicy.Decision.DENY, reason, ToolPolicy.Risk.HIGH);
        }

        public static Decision ask(ToolPolicy policy, String reason) {
            return new Decision(ToolPolicy.Decision.ASK, reason, policy.risk());
        }
    }

    /**
     * 对一次工具调用做出决策。
     *
     * @param toolName 工具名
     * @param args 模型给出的参数
     * @param ctx 本轮调用者上下文；{@code null} 表示拿不到（拒绝，fail closed）
     */
    public Decision check(String toolName, Map<String, Object> args, AgentCallContext ctx) {
        if (!registry.isEnabled()) {
            return new Decision(
                    ToolPolicy.Decision.ALLOW, "constrain disabled", ToolPolicy.Risk.LOW);
        }
        if (ctx == null) {
            return Decision.deny("missing caller context; refusing to execute tool");
        }
        ToolPolicy policy =
                registry.policyFor(toolName)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "unknown tool policy: " + toolName));
        Map<String, Object> safeArgs = args == null ? Map.of() : args;

        if (policy.decision() == ToolPolicy.Decision.DENY) {
            return Decision.deny("tool " + toolName + " is denied by policy");
        }
        if (policy.maxDelegationDepth() >= 0
                && ctx.delegationDepth() > policy.maxDelegationDepth()) {
            return Decision.deny(
                    "delegation depth "
                            + ctx.delegationDepth()
                            + " exceeds policy limit "
                            + policy.maxDelegationDepth());
        }
        for (String required : policy.requiredParams()) {
            if (!present(safeArgs.get(required))) {
                return Decision.deny("missing required parameter: " + required);
            }
        }
        for (Map.Entry<String, Object> entry : safeArgs.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            List<String> allowed = policy.allowedValuesFor(key);
            if (allowed != null && value != null && !allowed.contains(String.valueOf(value))) {
                return Decision.deny("parameter " + key + "=" + value + " is not allowed");
            }
            if (value instanceof String s && s.length() > policy.maxStringLength()) {
                return Decision.deny(
                        "parameter " + key + " exceeds max length " + policy.maxStringLength());
            }
        }
        if (policy.decision() == ToolPolicy.Decision.ASK) {
            return Decision.ask(policy, "tool " + toolName + " requires human approval");
        }
        return Decision.allow(policy);
    }

    /** 该工具是否有声明的策略（含配置覆盖）。 */
    public boolean policyExists(String toolName) {
        return registry.policyFor(toolName).isPresent();
    }

    /** 未声明策略的工具：默认拒绝（最小权限）。 */
    public Decision checkUnknown(String toolName, AgentCallContext ctx) {
        if (!registry.isEnabled()) {
            return new Decision(
                    ToolPolicy.Decision.ALLOW, "constrain disabled", ToolPolicy.Risk.LOW);
        }
        return Decision.deny("tool " + toolName + " has no policy; default deny");
    }

    private static boolean present(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String s) {
            return !s.isBlank();
        }
        if (value instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }
}
