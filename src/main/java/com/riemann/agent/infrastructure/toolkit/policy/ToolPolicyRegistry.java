package com.riemann.agent.infrastructure.toolkit.policy;

import com.riemann.agent.infrastructure.toolkit.policy.ToolPolicy.Decision;
import com.riemann.agent.infrastructure.toolkit.policy.ToolPolicy.Risk;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工具策略注册表。内置策略覆盖项目自带工具；{@code agent.constrain.policies.*} 可覆盖或新增。
 *
 * <p>默认决策为 {@link Decision#DENY}：未声明策略的工具不允许执行，这是最小权限的落点。 项目自带工具全部有声明，因此默认配置不改变现有 golden-set 行为。
 */
@Component
@ConfigurationProperties(prefix = "agent.constrain")
public class ToolPolicyRegistry {

    /** 总开关。关闭后门禁放行一切（等同改造前行为）。 */
    private boolean enabled = true;

    /** 额外/覆盖策略。 键为工具名，值格式：{@code decision=ALLOW|DENY|ASK}。 未提供时使用内置策略；完全未知的工具一律 DENY。 */
    private final Map<String, PolicyOverride> policies = new LinkedHashMap<>();

    private static final Map<String, ToolPolicy> BUILT_IN = builtIn();

    private static Map<String, ToolPolicy> builtIn() {
        Map<String, ToolPolicy> m = new LinkedHashMap<>();
        m.put("seek_sql_query", ToolPolicy.readOnly("seek_sql_query", List.of("sql")));
        m.put(
                "seek_hybrid_search",
                ToolPolicy.readOnly("seek_hybrid_search", List.of("query_text")));
        m.put("seek_book_search", ToolPolicy.readOnly("seek_book_search", List.of("query_text")));
        // genre / kind 都是可选参数（golden-set 的 fiction-count 用例带 genre），
        // 因此这里不声明任何必填项，否则会误拒合法调用。
        m.put("seek_book_count_by_year", ToolPolicy.readOnly("seek_book_count_by_year", List.of()));
        m.put(
                "seek_fork_sandbox",
                new ToolPolicy(
                        "seek_fork_sandbox",
                        Decision.ALLOW,
                        Risk.MEDIUM,
                        List.of(),
                        Map.of(),
                        2000,
                        0));
        // 写操作与记忆写入沿用现有 ApprovalService：门禁只负责识别并转交，不直接执行
        m.put("seek_write_sandbox", ToolPolicy.sensitive("seek_write_sandbox", List.of("sql")));
        m.put("seek_commit_sandbox", ToolPolicy.sensitive("seek_commit_sandbox", List.of()));
        m.put("seek_discard_sandbox", ToolPolicy.sensitive("seek_discard_sandbox", List.of()));
        m.put("save_memory", ToolPolicy.sensitive("save_memory", List.of("content")));
        return Map.copyOf(m);
    }

    /** 返回工具策略；未声明时返回空，由门禁按默认 DENY 处理。 */
    public Optional<ToolPolicy> policyFor(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        ToolPolicy base = BUILT_IN.get(toolName);
        PolicyOverride override = policies.get(toolName);
        if (base == null && override == null) {
            return Optional.empty();
        }
        if (override == null) {
            return Optional.of(base);
        }
        if (base == null) {
            return Optional.of(
                    new ToolPolicy(
                            toolName,
                            override.decision == null ? Decision.ALLOW : override.decision,
                            Risk.MEDIUM,
                            List.of(),
                            Map.of(),
                            4000,
                            3));
        }
        return Optional.of(override.decision == null ? base : base.withDecision(override.decision));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, PolicyOverride> getPolicies() {
        return policies;
    }

    /** yml 绑定用的覆盖项。 */
    public static class PolicyOverride {
        private Decision decision;

        public Decision getDecision() {
            return decision;
        }

        public void setDecision(Decision decision) {
            this.decision = decision;
        }
    }
}
