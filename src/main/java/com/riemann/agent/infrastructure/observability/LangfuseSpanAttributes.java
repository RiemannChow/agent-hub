package com.riemann.agent.infrastructure.observability;

import io.opentelemetry.api.common.AttributeKey;

/**
 * Langfuse 语义属性键。
 *
 * <p>Langfuse 消费的是标准 OTLP trace，但它的 UI（Trace / Session / Generation / Cost）
 * 依赖一组 {@code langfuse.*} 私有属性来还原语义：</p>
 * <ul>
 *   <li>trace 级：{@code langfuse.trace.name}、{@code langfuse.session.id}、
 *       {@code langfuse.user.id}、{@code langfuse.release}、{@code langfuse.environment}</li>
 *   <li>observation 级：{@code langfuse.observation.type}（generation / tool / agent / span）、
 *       {@code langfuse.observation.input}、{@code langfuse.observation.output}、
 *       {@code langfuse.observation.model.name}、{@code langfuse.observation.usage_details}</li>
 * </ul>
 *
 * <p>注意：sessionId / userId 这类需要被 Langfuse 用于筛选聚合的属性，
 * 必须出现在 trace 内<b>每一个</b> span 上，否则过滤结果会缺失。</p>
 */
public final class LangfuseSpanAttributes {

    private LangfuseSpanAttributes() {
    }

    // ===== Trace 级 =====
    public static final String TRACE_NAME = "langfuse.trace.name";
    public static final String SESSION_ID = "langfuse.session.id";
    public static final String USER_ID = "langfuse.user.id";
    public static final String RELEASE = "langfuse.release";
    public static final String ENVIRONMENT = "langfuse.environment";

    // ===== Observation 级 =====
    public static final String OBSERVATION_TYPE = "langfuse.observation.type";
    public static final String OBSERVATION_INPUT = "langfuse.observation.input";
    public static final String OBSERVATION_OUTPUT = "langfuse.observation.output";
    public static final String OBSERVATION_MODEL_NAME = "langfuse.observation.model.name";
    public static final String OBSERVATION_USAGE_DETAILS = "langfuse.observation.usage_details";
    public static final String OBSERVATION_LEVEL = "langfuse.observation.level";
    public static final String OBSERVATION_STATUS_MESSAGE = "langfuse.observation.status_message";

    // ===== Observation 类型取值 =====
    public static final String TYPE_AGENT = "agent";
    public static final String TYPE_GENERATION = "generation";
    public static final String TYPE_TOOL = "tool";

    // ===== RuntimeContext 传递键 =====
    /**
     * 通过 {@code RuntimeContext#put} 传递「稳定的会话 id」时使用的键。
     *
     * <p>AgentScope 的 {@code RuntimeContext.sessionId} 在本项目里是「每轮唯一」的
     * （{@code conversationId + "-" + UUID}，用于隔离轮次状态），直接拿它当 Langfuse session
     * 会导致每轮一个新会话。因此编排层额外把真正的会话 id 挂在这个键上。</p>
     */
    public static final String RUNTIME_SESSION_ID = "langfuse.session.id";

    // ===== 强类型键（setAttribute 用） =====
    public static final AttributeKey<String> KEY_TRACE_NAME = AttributeKey.stringKey(TRACE_NAME);
    public static final AttributeKey<String> KEY_SESSION_ID = AttributeKey.stringKey(SESSION_ID);
    public static final AttributeKey<String> KEY_USER_ID = AttributeKey.stringKey(USER_ID);
    public static final AttributeKey<String> KEY_RELEASE = AttributeKey.stringKey(RELEASE);
    public static final AttributeKey<String> KEY_ENVIRONMENT = AttributeKey.stringKey(ENVIRONMENT);
    public static final AttributeKey<String> KEY_OBSERVATION_TYPE = AttributeKey.stringKey(OBSERVATION_TYPE);
    public static final AttributeKey<String> KEY_OBSERVATION_INPUT = AttributeKey.stringKey(OBSERVATION_INPUT);
    public static final AttributeKey<String> KEY_OBSERVATION_OUTPUT = AttributeKey.stringKey(OBSERVATION_OUTPUT);
    public static final AttributeKey<String> KEY_OBSERVATION_MODEL_NAME =
            AttributeKey.stringKey(OBSERVATION_MODEL_NAME);
    public static final AttributeKey<String> KEY_OBSERVATION_USAGE_DETAILS =
            AttributeKey.stringKey(OBSERVATION_USAGE_DETAILS);
    public static final AttributeKey<String> KEY_OBSERVATION_LEVEL =
            AttributeKey.stringKey(OBSERVATION_LEVEL);
    public static final AttributeKey<String> KEY_OBSERVATION_STATUS_MESSAGE =
            AttributeKey.stringKey(OBSERVATION_STATUS_MESSAGE);
}
