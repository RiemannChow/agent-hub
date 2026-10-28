package com.riemann.agent.presentation;

import com.riemann.agent.domain.AgentEvent;
import com.riemann.agent.domain.ConversationId;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按会话维护一个 Sink：POST 端点写入事件流，GET 端点（或晚到的订阅者）订阅同一事件流。
 *
 * 采用 Sinks.many().replay().all() 而非 multicast()：replay 会缓存已发出的事件，
 * 保证 HTTP 订阅者即使晚于上游发射一两个 tick 也能收到完整事件流（避免 multicast
 * 在“零订阅者”阶段丢事件），同时也让 GET /stream 的迟到订阅者能回放本轮历史。
 *
 * <p><b>关键修复（多轮“同一连接后续回答都等于第一个回答”）</b>：原本用
 * {@code computeIfAbsent} 按 conversationId 复用同一个 sink，而该 sink 在每轮结束时会被
 * {@code tryEmitComplete()} 置为已完成态。下一轮（同一 conversationId）再 {@code register}
 * 时拿到的是<b>已完成的旧 sink</b>，replay 会立刻把“第一轮的答案”又推给新订阅者、随后立即
 * complete，于是客户端在 0.0s 内收到第一轮的回答——这就是“后续回答都等于第一个回答”的直接原因。
 * 因此 <b>每一轮（每次 POST /chat）都创建全新的 sink 并替换掉旧的</b>，旧 sink 在此刻被
 * tryEmitComplete 关闭（结束可能正在其上订阅的 GET /stream，影响不大，历史可走 /conversations 接口）。
 * GET /stream 在某一轮期间订阅时拿到的是“本轮”的 sink，可正常回放本轮事件。</p>
 */
@Component
public class ConversationEventBus {

    private final Map<String, Sinks.Many<AgentEvent>> sinks = new ConcurrentHashMap<>();

    public Sinks.Many<AgentEvent> register(ConversationId id) {
        // 每一轮都新建独立 sink，避免复用上一轮已 complete 的 sink 导致首轮答案被回放。
        Sinks.Many<AgentEvent> sink = Sinks.many().replay().all();
        Sinks.Many<AgentEvent> prev = sinks.put(id.value(), sink);
        if (prev != null) {
            prev.tryEmitComplete();
        }
        return sink;
    }

    public Flux<AgentEvent> flux(ConversationId id) {
        Sinks.Many<AgentEvent> sink = sinks.get(id.value());
        if (sink == null) {
            return Flux.error(new IllegalStateException("no active stream for " + id.value()));
        }
        return sink.asFlux();
    }

    /**
     * 注销某个 sink。
     *
     * <p>客户端中断（关标签页/点停止）时调用：只删掉「确实还是这个 sink」的那条记录，
     * 避免把同会话新一轮刚注册的 sink 误删。</p>
     */
    public void remove(ConversationId id, Sinks.Many<AgentEvent> sink) {
        if (sink != null) {
            sinks.remove(id.value(), sink);
        }
    }
}
