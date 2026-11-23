package com.riemann.agent.infrastructure.toolkit.policy;

import com.riemann.agent.infrastructure.toolkit.tools.AgentCallContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * 工具执行前门禁的包装层：任何工具调用都必须先通过 {@link ConstrainGate}， 拒绝时直接返回带原因的 DENIED 结果，真实工具方法<b>根本不会被调用</b>。
 *
 * <p>为什么用包装器而不是 Hook：{@code PreActingEvent.setToolUse} 只改上下文里的副本， 框架的实际执行列表在它之前已确定；而 {@code
 * AgentTool.callAsync} 是框架唯一真正调用工具体的入口， 在这里拦截才是不可绕过的执行前检查。
 *
 * <p>ASK 决策不在包装器里等待人工：当前工具（写沙箱 / save_memory）本身已有 {@code ApprovalService} 审批流程，门禁只识别并标注风险，不重复发卡。ASK
 * 的语义是 「允许进入工具，但工具内部必须自行完成审批」，因此这里对 ASK 放行并在结果中标注。
 */
public class ConstrainedAgentTool implements AgentTool {

    private final AgentTool delegate;
    private final ConstrainGate gate;
    private final ConstrainAudit audit;

    public ConstrainedAgentTool(AgentTool delegate, ConstrainGate gate, ConstrainAudit audit) {
        this.delegate = delegate;
        this.gate = gate;
        this.audit = audit;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public Map<String, Object> getParameters() {
        return delegate.getParameters();
    }

    @Override
    public Boolean getStrict() {
        return delegate.getStrict();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return delegate.getOutputSchema();
    }

    @Override
    public boolean isReadOnly() {
        return delegate.isReadOnly();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String tool = getName();
        Map<String, Object> args = param.getInput() == null ? Map.of() : param.getInput();
        AgentCallContext ctx = contextOf(param);
        ConstrainGate.Decision decision;
        try {
            decision =
                    gate.policyExists(tool)
                            ? gate.check(tool, args, ctx)
                            : gate.checkUnknown(tool, ctx);
        } catch (RuntimeException e) {
            decision = ConstrainGate.Decision.deny("policy evaluation failed: " + e.getMessage());
        }

        if (decision.decision() == ToolPolicy.Decision.DENY) {
            audit.denied(tool, decision.reason(), ctx);
            ToolResultBlock blocked =
                    new ToolResultBlock(
                            idOf(param),
                            tool,
                            List.of(
                                    TextBlock.builder()
                                            .text(
                                                    "【策略拒绝】工具 "
                                                            + tool
                                                            + " 未执行："
                                                            + decision.reason()
                                                            + "。请改用其它合规工具，或向用户说明限制。")
                                            .build()),
                            Map.of("constrain", "denied", "reason", decision.reason()),
                            ToolResultState.DENIED);
            return Mono.just(blocked);
        }
        if (decision.decision() == ToolPolicy.Decision.ASK) {
            audit.asked(tool, decision.reason(), ctx);
        } else {
            audit.allowed(tool, ctx);
        }
        return delegate.callAsync(param);
    }

    /** 供测试直接调用决策逻辑，不触发真实工具。 */
    public ConstrainGate.Decision decide(Map<String, Object> args, AgentCallContext ctx) {
        return gate.policyExists(getName())
                ? gate.check(getName(), args, ctx)
                : gate.checkUnknown(getName(), ctx);
    }

    private static AgentCallContext contextOf(ToolCallParam param) {
        try {
            return param.getContext() == null
                    ? null
                    : param.getContext().get(AgentCallContext.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String idOf(ToolCallParam param) {
        return param.getToolUseBlock() == null ? null : param.getToolUseBlock().getId();
    }
}
