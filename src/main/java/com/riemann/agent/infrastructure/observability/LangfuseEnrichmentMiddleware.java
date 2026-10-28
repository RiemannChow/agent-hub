package com.riemann.agent.infrastructure.observability;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ChatUsage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import reactor.core.publisher.Flux;
import reactor.util.context.ContextView;

import java.util.function.Function;

/**
 * 把 AgentScope 的 span 翻译成 Langfuse 语义的中间件。
 *
 * <h3>为什么需要它</h3>
 * <p>AgentScope 内核自带的 {@code io.agentscope.core.tracing.OtelTracingMiddleware} 负责
 * <b>骨架</b>：为 agent / model call / tool call 分别开 span，并写标准 {@code gen_ai.*} 属性
 * （{@code gen_ai.request.model}、{@code gen_ai.usage.input_tokens} 等）。
 * 但它不知道 Langfuse 的私有属性，于是 Langfuse 里只能看到一串没有类型、没有输入输出、
 * 模型调用也不会被识别成 generation（因此不显示 token 成本）。</p>
 *
 * <p>本中间件挂在它<b>内层</b>（中间件链按装配顺序由外到内），在 span 仍然 recording 时
 * 补上 {@code langfuse.observation.type} / {@code input} / {@code output} /
 * {@code model.name} / {@code usage_details}，以及 trace 级的 session、user、release 等。</p>
 *
 * <h3>为什么用 doOnComplete 而不是 doFinally</h3>
 * <p>外层（内置中间件）用 {@code doOnComplete(span::end)} 结束 span。{@code doFinally}
 * 在下游收到终止信号之后才回调，此时 span 已经 end、属性写入无效；而 {@code doOnComplete}
 * 在信号向下游传播之前执行，正好赶在 span 关闭前落盘。</p>
 */
public class LangfuseEnrichmentMiddleware implements MiddlewareBase {

    /** 单个流内累积文本的上限，避免长回答把堆内存顶上去。 */
    private static final int ACCUMULATE_LIMIT = 32_000;

    private final LangfuseProperties props;

    public LangfuseEnrichmentMiddleware(LangfuseProperties props) {
        this.props = props;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext runtimeContext, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(ctxView -> {
            Span span = currentSpan(ctxView);
            if (span.isRecording()) {
                // 根 span 决定 Langfuse 里这条 trace 的名字、环境、版本
                span.setAttribute(LangfuseSpanAttributes.KEY_OBSERVATION_TYPE,
                        LangfuseSpanAttributes.TYPE_AGENT);
                if (agent != null && agent.getName() != null) {
                    span.setAttribute(LangfuseSpanAttributes.KEY_TRACE_NAME, agent.getName());
                }
                setIfPresent(span, LangfuseSpanAttributes.KEY_RELEASE, props.getRelease());
                setIfPresent(span, LangfuseSpanAttributes.KEY_ENVIRONMENT, props.getEnvironment());
                stampTraceContext(span, runtimeContext);
                if (props.isCaptureContent()) {
                    setIfPresent(span, LangfuseSpanAttributes.KEY_OBSERVATION_INPUT,
                            LangfusePayloads.messagesToJson(input.msgs(), props.getMaxPayloadChars()));
                }
            }

            TextCollector collector = new TextCollector();
            return next.apply(input)
                    .doOnNext(event -> {
                        if (event instanceof TextBlockDeltaEvent delta) {
                            collector.append(delta.getDelta());
                        } else if (event instanceof ThinkingBlockDeltaEvent thinking) {
                            collector.append(thinking.getDelta());
                        } else if (event instanceof AgentResultEvent result && result.getResult() != null) {
                            // 有最终结果时以它为准，比拼接流式增量更干净
                            collector.reset(result.getResult().getTextContent());
                        }
                    })
                    .doOnComplete(() -> writeOutput(span, collector))
                    .doOnCancel(() -> writeOutput(span, collector))
                    .doOnError(error -> markError(span, error));
        });
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext runtimeContext, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(ctxView -> {
            Span span = currentSpan(ctxView);
            if (span.isRecording()) {
                // 标成 generation，Langfuse 才会解析模型、token、成本
                span.setAttribute(LangfuseSpanAttributes.KEY_OBSERVATION_TYPE,
                        LangfuseSpanAttributes.TYPE_GENERATION);
                Model model = input.model();
                if (model != null && model.getModelName() != null) {
                    span.setAttribute(LangfuseSpanAttributes.KEY_OBSERVATION_MODEL_NAME,
                            model.getModelName());
                }
                stampTraceContext(span, runtimeContext);
                if (props.isCaptureContent()) {
                    setIfPresent(span, LangfuseSpanAttributes.KEY_OBSERVATION_INPUT,
                            LangfusePayloads.messagesToJson(input.messages(), props.getMaxPayloadChars()));
                }
            }

            TextCollector collector = new TextCollector();
            return next.apply(input)
                    .doOnNext(event -> {
                        if (event instanceof TextBlockDeltaEvent delta) {
                            collector.append(delta.getDelta());
                        } else if (event instanceof ThinkingBlockDeltaEvent thinking) {
                            collector.append(thinking.getDelta());
                        } else if (event instanceof ModelCallEndEvent end) {
                            applyUsage(span, end.getUsage());
                        }
                    })
                    .doOnComplete(() -> writeOutput(span, collector))
                    .doOnCancel(() -> writeOutput(span, collector))
                    .doOnError(error -> markError(span, error));
        });
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext runtimeContext, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(ctxView -> {
            Span span = currentSpan(ctxView);
            if (span.isRecording()) {
                span.setAttribute(LangfuseSpanAttributes.KEY_OBSERVATION_TYPE,
                        LangfuseSpanAttributes.TYPE_TOOL);
                stampTraceContext(span, runtimeContext);
                if (props.isCaptureContent()) {
                    setIfPresent(span, LangfuseSpanAttributes.KEY_OBSERVATION_INPUT,
                            LangfusePayloads.toolCallsToJson(input.toolCalls(), props.getMaxPayloadChars()));
                }
            }

            TextCollector collector = new TextCollector();
            return next.apply(input)
                    .doOnNext(event -> {
                        if (event instanceof ToolResultTextDeltaEvent delta) {
                            collector.append(delta.getDelta());
                        }
                    })
                    .doOnComplete(() -> writeOutput(span, collector))
                    .doOnCancel(() -> writeOutput(span, collector))
                    .doOnError(error -> markError(span, error));
        });
    }

    // ==================== 内部工具 ====================

    /**
     * 取当前 span：内置中间件把 OTel Context 通过 {@code ContextPropagationOperator}
     * 写进了 Reactor Context，这里顺着读出来即可。
     *
     * <p>缺省值刻意用 {@link Context#root()} 而不是 {@code Context.current()}：
     * 后者是线程本地变量，在 WebFlux 的复用线程上可能残留上一条请求的 span，
     * 造成属性写到别的 trace 上。</p>
     */
    private static Span currentSpan(ContextView ctxView) {
        Context otelContext = ContextPropagationOperator
                .getOpenTelemetryContextFromContextView(ctxView, Context.root());
        return Span.fromContext(otelContext);
    }

    /**
     * 把 session / user 打到当前 span 上。
     *
     * <p>Langfuse 的按会话、按用户筛选依赖这些属性出现在 trace 内每个 span 上，
     * 只写根 span 会导致过滤结果不全。</p>
     */
    private void stampTraceContext(Span span, RuntimeContext runtimeContext) {
        if (runtimeContext == null) {
            return;
        }
        String sessionId = null;
        try {
            Object stashed = runtimeContext.get(LangfuseSpanAttributes.RUNTIME_SESSION_ID);
            if (stashed != null) {
                sessionId = String.valueOf(stashed);
            }
        } catch (RuntimeException ignored) {
            // RuntimeContext 未持有该键时按无会话处理
        }
        if (sessionId == null || sessionId.isBlank()) {
            // 兜底：本项目的 RuntimeContext.sessionId 是「每轮唯一」，仅在没有显式会话 id 时使用
            sessionId = runtimeContext.getSessionId();
        }
        if (sessionId != null && !sessionId.isBlank()) {
            span.setAttribute(LangfuseSpanAttributes.KEY_SESSION_ID, sessionId);
        }
        String userId = runtimeContext.getUserId();
        if (userId != null && !userId.isBlank()) {
            span.setAttribute(LangfuseSpanAttributes.KEY_USER_ID, userId);
        }
    }

    private void applyUsage(Span span, ChatUsage usage) {
        if (!span.isRecording() || usage == null) {
            return;
        }
        String json = LangfusePayloads.usageDetailsJson(usage);
        if (json != null) {
            span.setAttribute(LangfuseSpanAttributes.KEY_OBSERVATION_USAGE_DETAILS, json);
        }
    }

    private void writeOutput(Span span, TextCollector collector) {
        if (!span.isRecording() || !props.isCaptureContent()) {
            return;
        }
        String text = collector.text();
        if (text == null || text.isBlank()) {
            return;
        }
        span.setAttribute(LangfuseSpanAttributes.KEY_OBSERVATION_OUTPUT,
                LangfusePayloads.truncate(text, props.getMaxPayloadChars()));
    }

    private void markError(Span span, Throwable error) {
        if (!span.isRecording()) {
            return;
        }
        span.setAttribute(LangfuseSpanAttributes.KEY_OBSERVATION_LEVEL, "ERROR");
        if (error != null) {
            span.setAttribute(LangfuseSpanAttributes.KEY_OBSERVATION_STATUS_MESSAGE,
                    String.valueOf(error.getMessage()));
            span.recordException(error);
            span.setStatus(StatusCode.ERROR, String.valueOf(error.getMessage()));
        }
    }

    private static void setIfPresent(Span span, io.opentelemetry.api.common.AttributeKey<String> key, String value) {
        if (value != null && !value.isBlank()) {
            span.setAttribute(key, value);
        }
    }

    /** 流式文本累加器：只保留前 {@link #ACCUMULATE_LIMIT} 个字符。 */
    private static final class TextCollector {

        private final StringBuilder buffer = new StringBuilder();

        void append(String delta) {
            if (delta == null || delta.isEmpty() || buffer.length() >= ACCUMULATE_LIMIT) {
                return;
            }
            int room = ACCUMULATE_LIMIT - buffer.length();
            buffer.append(delta.length() <= room ? delta : delta.substring(0, room));
        }

        void reset(String text) {
            buffer.setLength(0);
            append(text);
        }

        String text() {
            return buffer.toString();
        }
    }
}
