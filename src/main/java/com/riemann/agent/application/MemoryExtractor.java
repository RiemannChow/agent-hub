package com.riemann.agent.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riemann.agent.infrastructure.metrics.TurnTelemetry;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 自动记忆抽取器（应用层）。
 *
 * <p>过去长期记忆完全依赖 Agent 在对话中“自觉”调用 save_memory 工具写入，导致：
 * (1) 记忆条数极少（全库仅 2 条）；(2) 写入时 userId 常错乱（出现过 t3 / raw-411468 等非 web-user 的 id），
 * 正常 web 会话根本召回不到。本服务在<b>每轮对话结束后</b>，用本轮“用户提问 + 助手答复”让 LLM 抽取出
 * 跨会话可复用的稳定事实（用户偏好/背景），再逐条经 {@link MemoryService#save}（已带去重/合并/遗忘）落库，
 * 且使用 {@code chat()} 传入的<b>正确 userId</b>，彻底修复错乱问题。</p>
 *
 * <p>抽取是<b>异步、非阻塞</b>的（在 boundedElastic 调度器上执行阻塞式模型调用），不阻塞 SSE 响应流；
 * 抽取结果于下一轮对话被语义召回注入上下文。</p>
 *
 * <p>计费：抽取本身是一次独立的模型调用，其 token 用量通过 {@link TurnTelemetry#recordModelCall} 入账，
 * 否则 {@code agent.tokens} / 每日配额会系统性漏算（与上下文摘要同一类问题）。</p>
 */
@Service
public class MemoryExtractor {

    private static final String EXTRACT_SYS =
            "你是一个记忆抽取器。阅读下面一段“用户提问 + 助手答复”的对话片段，"
                    + "抽取其中【跨会话可复用】的<b>稳定事实</b>：用户的偏好、背景、身份、长期目标、反复出现的诉求。\n"
                    + "规则：\n"
                    + "1. 只输出 JSON 数组，元素是简短的中文陈述句（一句一个事实），例如 [\"用户偏好用图表展示数据\",\"用户关注非虚构类图书\"]。\n"
                    + "2. 不要抽取一次性任务细节、临时问数结果、或本就可从对话历史推导的内容。\n"
                    + "3. 若没有任何值得长期记住的事实，输出空数组 []。\n"
                    + "4. 不要输出任何解释、不要使用代码块标记，只输出纯 JSON。";

    private final Model model;
    private final ObjectMapper json = new ObjectMapper();
    private final TurnTelemetry telemetry;

    public MemoryExtractor(Model model, TurnTelemetry telemetry) {
        this.model = model;
        this.telemetry = telemetry;
    }

    /**
     * 从一轮对话中抽样稳定事实。无有效输入或解析失败时返回空列表（不抛异常，保证主链路不受影响）。
     *
     * @param userId 调用归属用户，用于把抽取调用的 token 计入其每日配额
     */
    public Mono<List<String>> extract(String userMsg, String assistantMsg, String userId) {
        if (userMsg == null || userMsg.isBlank()) {
            return Mono.just(List.of());
        }
        String prompt = EXTRACT_SYS + "\n\n【用户提问】\n" + userMsg
                + "\n\n【助手答复】\n" + (assistantMsg == null ? "" : assistantMsg);
        UserMessage msg = new UserMessage(prompt);
        return Mono.fromCallable(() -> {
                    StringBuilder sb = new StringBuilder();
                    // 复用已验证可用的 AgentScope Model；在弹性线程上阻塞收集流式响应
                    // 同时累计最后一个响应携带的 token 用量，交给 TurnTelemetry 入账
                    io.agentscope.core.model.ChatUsage[] usage = new io.agentscope.core.model.ChatUsage[1];
                    model.stream(List.<Msg>of(msg), List.<ToolSchema>of(), GenerateOptions.builder().build())
                            .toIterable()
                            .forEach(resp -> {
                                appendText(resp, sb);
                                if (resp.getUsage() != null) {
                                    usage[0] = resp.getUsage();
                                }
                            });
                    telemetry.recordModelCall(usage[0], userId);
                    return sb.toString();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::parseFacts);
    }

    private void appendText(ChatResponse resp, StringBuilder sb) {
        if (resp.getContent() == null) {
            return;
        }
        for (ContentBlock b : resp.getContent()) {
            if (b instanceof TextBlock tb) {
                sb.append(tb.getText());
            }
        }
    }

    private List<String> parseFacts(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        // 去掉可能的 ```json ... ``` 围栏
        String t = raw.trim();
        if (t.startsWith("```")) {
            int first = t.indexOf('[');
            int last = t.lastIndexOf(']');
            if (first >= 0 && last > first) {
                t = t.substring(first, last + 1);
            } else {
                t = t.replaceAll("```[a-zA-Z]*", "").replaceAll("```", "").trim();
            }
        }
        try {
            JsonNode node = json.readTree(t);
            if (node.isArray()) {
                for (JsonNode n : node) {
                    if (n.isTextual() && !n.asText().isBlank()) {
                        out.add(n.asText().strip());
                    }
                }
            }
        } catch (Exception ignored) {
            // 非 JSON：忽略，不阻断主链路
        }
        return out;
    }
}
