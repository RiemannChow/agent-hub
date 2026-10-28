package com.riemann.agent.infrastructure.execution;

import com.riemann.agent.domain.AgentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 给单轮对话的事件流加「总时长上限」。
 *
 * <p><b>问题：</b>AgentScope 的 {@code streamEvents} 是冷流，只要模型侧不返回、工具不返回，
 * 流就会一直挂着；前端 SSE 表现为「永远转圈」，用户既看不到错误也无法结束。
 * 单次调用的超时只能兜住「某一次调用卡死」，兜不住「多次调用累加起来的长时间」。</p>
 *
 * <p><b>做法：</b>用 {@code takeUntilOther} 与一个定时器赛跑，到点就正常结束流，
 * 并在末尾补一条 {@link AgentEvent.Token} 说明「已截断」，这样：</p>
 * <ul>
 *   <li>前端一定能收到 {@code completed}，UI 不会卡死；</li>
 *   <li>已生成的内容不会丢（保留截断前的增量）；</li>
 *   <li>用户知道发生了什么，而不是看到一个静默中断的回答。</li>
 * </ul>
 */
public final class TurnTimeout {

    private static final Logger log = LoggerFactory.getLogger(TurnTimeout.class);

    private TurnTimeout() {
    }

    /**
     * @param events 领域事件流
     * @param limit  整轮上限；{@code null} 或非正数表示不限制
     */
    public static Flux<AgentEvent> apply(Flux<AgentEvent> events, Duration limit) {
        if (limit == null || limit.isZero() || limit.isNegative()) {
            return events;
        }
        AtomicBoolean truncated = new AtomicBoolean(false);
        long seconds = Math.max(1, limit.toSeconds());
        return events
                .takeUntilOther(Mono.delay(limit).doOnNext(tick -> {
                    truncated.set(true);
                    log.warn("单轮对话超过上限 {}s，已截断输出", seconds);
                }))
                .concatWith(Flux.defer(() -> truncated.get()
                        ? Flux.just(new AgentEvent.Token(
                                "\n\n（本轮处理已超过 " + seconds + " 秒，输出被截断。"
                                        + "可以缩小问题范围或稍后重试。）"))
                        : Flux.<AgentEvent>empty()));
    }
}
