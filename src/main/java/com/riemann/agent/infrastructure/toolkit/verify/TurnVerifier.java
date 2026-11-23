package com.riemann.agent.infrastructure.toolkit.verify;

import com.riemann.agent.domain.AgentEvent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单轮验证编排器：消费领域事件流，记录工具结果与助手文本，轮末产出 {@link AgentEvent.Verification}。
 *
 * <p>每轮一个独立实例（和 TurnTokenBudget 一样），不存在跨用户共享状态； 编排层只需在事件流上调用 {@link #observe(AgentEvent)}，最后调用
 * {@link #conclusion()}。
 */
public class TurnVerifier {

    private final VerificationProperties props;
    private final VerificationGate gate;
    private final Set<String> observedNumbers = new LinkedHashSet<>();
    private final StringBuilder answer = new StringBuilder();
    private int toolCalls;

    public TurnVerifier(VerificationProperties props, VerificationGate gate) {
        this.props = props;
        this.gate = gate;
    }

    /** 观察一条领域事件。 */
    public void observe(AgentEvent event) {
        if (!props.isEnabled() || event == null) {
            return;
        }
        if (event instanceof AgentEvent.ToolResult t) {
            toolCalls++;
            observedNumbers.addAll(gate.extractNumbers(t.result()));
        } else if (event instanceof AgentEvent.Token t) {
            answer.append(t.delta());
        }
    }

    /** 轮末结论。没有工具结果时不判 ungrounded（探针/寒暄没有数值声明）。 */
    public AgentEvent.Verification conclusion() {
        if (!props.isEnabled()) {
            return new AgentEvent.Verification(true, toolCalls, "verification disabled");
        }
        Set<String> answerNumbers = gate.extractNumbers(answer.toString());
        List<String> ungrounded =
                answerNumbers.stream()
                        .filter(n -> !observedNumbers.contains(n))
                        .filter(n -> n.length() > 1)
                        .toList();
        boolean grounded = toolCalls == 0 || ungrounded.isEmpty();
        String reason =
                grounded
                        ? (toolCalls == 0
                                ? "no tool calls; nothing to ground"
                                : "all numeric claims traceable")
                        : "numbers not found in tool results: " + String.join(", ", ungrounded);
        return new AgentEvent.Verification(grounded, toolCalls, reason);
    }
}
