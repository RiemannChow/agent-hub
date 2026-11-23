package com.riemann.agent.infrastructure.toolkit.verify;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 结果验证 Hook：在工具结果写回模型上下文之前做契约校验与数字观察。
 *
 * <p>与 {@code ToolResultGovernor} 一样挂在 {@code PostActingEvent}：这是框架提供的
 * 「结果已产出、尚未回填」窗口，改写后模型下一轮看到的就是带验证标注的结果。
 */
@Component
public class VerificationHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(VerificationHook.class);

    private final VerificationProperties props;
    private final VerificationGate gate;

    public VerificationHook(VerificationProperties props, VerificationGate gate) {
        this.props = props;
        this.gate = gate;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!props.isEnabled() || !(event instanceof PostActingEvent post)) {
            return Mono.just(event);
        }
        try {
            verify(post);
        } catch (RuntimeException e) {
            // 验证是旁路能力：出错绝不能把这一轮搞挂
            log.debug("[verify] 结果验证失败，已跳过：{}", e.toString());
        }
        return Mono.just(event);
    }

    private void verify(PostActingEvent post) {
        ToolResultBlock result = post.getToolResult();
        if (result == null) {
            return;
        }
        String tool = result.getName() == null ? "unknown" : result.getName();
        String text = textOf(result);
        String state = result.getState() == null ? "" : result.getState().name();
        VerificationGate.Result verdict = gate.verify(tool, text, state);

        // 数字观察由编排层基于领域 ToolResult 事件按 conversationId 完成；
        // Hook 拿不到 RuntimeContext，用它做 key 会让所有用户串号。
        if (!verdict.passed() && props.isAnnotateResults() && !isTextOnly(result)) {
            return;
        }
        if (!verdict.passed() && props.isAnnotateResults()) {
            String annotated = text + "\n" + VerificationGate.annotation(verdict);
            post.setToolResult(replace(result, annotated));
        }
    }

    private static boolean isTextOnly(ToolResultBlock block) {
        if (block.getOutput() == null || block.getOutput().isEmpty()) {
            return false;
        }
        for (ContentBlock c : block.getOutput()) {
            if (!(c instanceof TextBlock)) {
                return false;
            }
        }
        return true;
    }

    private static String textOf(ToolResultBlock block) {
        if (block.getOutput() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        block.getOutput()
                .forEach(
                        c -> {
                            if (c instanceof TextBlock t && t.getText() != null) {
                                sb.append(t.getText());
                            }
                        });
        return sb.toString();
    }

    private static ToolResultBlock replace(ToolResultBlock origin, String text) {
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(TextBlock.builder().text(text).build());
        return new ToolResultBlock(
                origin.getId(),
                origin.getName(),
                blocks,
                origin.getMetadata() == null ? java.util.Map.of() : origin.getMetadata(),
                origin.getState());
    }
}
