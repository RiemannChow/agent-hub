package com.riemann.agent.application;

import com.riemann.agent.domain.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 模型服务可用性探针（心跳）。
 *
 * <p>背景：能打开页面只说明 Web 层是活的，用户真正会卡住的是 LLM 网关（LKEAP / Hunyuan）—— key 过期、限流、模型下线都会让对话静默失败。因此用户输入一句「你好 /
 * hello」这类**精确问候** 时，真的打一次最小模型请求（{@code maxTokens=1}）确认模型链路可用，再回一段固定文案。
 *
 * <p>关键约束：
 *
 * <ul>
 *   <li>只认整条消息的精确问候（可带首尾标点），不误伤「你好，帮我查下 GMV」这类真实提问；
 *   <li>探针**不写会话历史、不扣用户配额、不计入业务 token**——它是一次系统自检， 用独立的 {@code agent.probe.*} 指标与 debug
 *       日志记录，避免污染会话和计费；
 *   <li>失败时仍以正常 SSE 文案告知「模型服务暂时不可用」，而不是抛错断开流， 这样前端不会卡在半截的流上。
 * </ul>
 */
@Service
public class ModelHealthProbe {

    private static final Logger log = LoggerFactory.getLogger(ModelHealthProbe.class);

    /** 归一化后精确匹配的问候语集合。带真实任务的话不会被匹配。 */
    private static final Set<String> GREETINGS =
            Set.of("你好", "hello", "hi", "hey", "您好", "嗨", "哈喽");

    /** 探针 prompt 本身极短，输出限制到 1 token，成本与延迟都接近最低。 */
    private static final String PROBE_PROMPT = "ping";

    private static final GenerateOptions PROBE_OPTIONS =
            GenerateOptions.builder().maxTokens(1).temperature(0.0).build();

    private final Model model;
    private final boolean enabled;
    private final Duration timeout;
    private final String availableText;
    private final String unavailableText;

    public ModelHealthProbe(
            Model model,
            @Value("${agent.probe.enabled:true}") boolean enabled,
            @Value("${agent.probe.timeout:5s}") Duration timeout,
            @Value("${agent.probe.available-text:服务正常，智能体已就绪，请继续提问吧。}") String availableText,
            @Value("${agent.probe.unavailable-text:模型服务暂时不可用，请稍后再试；若持续出现请联系管理员。}")
                    String unavailableText) {
        this.model = model;
        this.enabled = enabled;
        this.timeout = timeout;
        this.availableText = availableText;
        this.unavailableText = unavailableText;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 是否为纯问候（探针触发条件）。整条消息归一化后必须恰好等于某个问候词； 因此「你好，帮我查 GMV」「hello world」都不会被当成探针。 */
    public static boolean isGreeting(String message) {
        if (message == null) {
            return false;
        }
        String normalized = normalize(message);
        return GREETINGS.contains(normalized);
    }

    /** 去首尾空白与常见中英文标点，再转小写，用于精确匹配。 */
    private static String normalize(String s) {
        String v = s.trim();
        // 只剥离首尾标点，不碰中间内容
        while (!v.isEmpty() && isEdgePunctuation(v.charAt(0))) {
            v = v.substring(1).trim();
        }
        while (!v.isEmpty() && isEdgePunctuation(v.charAt(v.length() - 1))) {
            v = v.substring(0, v.length() - 1).trim();
        }
        return v.toLowerCase(Locale.ROOT);
    }

    private static boolean isEdgePunctuation(char c) {
        return c == '!' || c == '.' || c == '?' || c == '~' || c == '。' || c == '！' || c == '？'
                || c == '，' || c == ',' || c == '；' || c == ';';
    }

    /**
     * 执行一次真实的最小模型调用并返回探针事件流（一段文本 + 结束事件）。
     *
     * <p>不抛异常：任何失败（鉴权、网络、超时、空响应）都转成明确文案，保证 SSE 正常收尾。
     */
    public Flux<AgentEvent> probe(String conversationId) {
        Mono<Boolean> reachable =
                Mono.defer(
                                () -> {
                                    List<Msg> prompt = List.of(new UserMessage(PROBE_PROMPT));
                                    return model.stream(
                                                    prompt, List.<ToolSchema>of(), PROBE_OPTIONS)
                                            // 收到任意一个正常的 ChatResponse 即视为链路可达；
                                            // 不等流结束，避免个别网关挂起把探针拖到超时。
                                            .next()
                                            .map(resp -> Boolean.TRUE);
                                })
                        .timeout(timeout)
                        .onErrorResume(e -> Mono.just(Boolean.FALSE))
                        .defaultIfEmpty(Boolean.FALSE);

        return reachable.flatMapMany(
                ok -> {
                    if (Boolean.TRUE.equals(ok)) {
                        log.debug(
                                "[probe] model service reachable (conversation={})",
                                conversationId);
                        return Flux.just(
                                new AgentEvent.Token(availableText),
                                new AgentEvent.Completed(conversationId));
                    }
                    log.warn("[probe] model service UNREACHABLE (conversation={})", conversationId);
                    return Flux.just(
                            new AgentEvent.Token(unavailableText),
                            new AgentEvent.Completed(conversationId));
                });
    }
}
