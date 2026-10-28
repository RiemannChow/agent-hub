package com.riemann.agent.infrastructure.observability;

import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Langfuse 可观测性装配。
 *
 * <h3>链路全貌</h3>
 * <pre>
 *   ReActAgent
 *     └─ OtelTracingMiddleware            ← AgentScope 内置：agent / model / tool 三层 span
 *          └─ LangfuseEnrichmentMiddleware ← 本项目：补 langfuse.* 语义属性
 *               └─ 真实推理 / 工具执行
 *   span ──BatchSpanProcessor──▶ OtlpHttpSpanExporter ──HTTP/protobuf──▶ Langfuse /api/public/otel
 * </pre>
 *
 * <p>AgentScope 内核通过 {@code GlobalOpenTelemetry.getTracer("io.agentscope")} 取 Tracer，
 * 所以只要把自建的 {@link OpenTelemetrySdk} 注册进 {@link GlobalOpenTelemetry}，
 * 内置中间件产出的 span 就会自动流到 Langfuse，无需改造 AgentScope。</p>
 *
 * <p>仅在 {@code langfuse.enabled=true} 时生效；关闭时容器里不存在任何中间件，
 * AgentScope 拿到的仍是 noop tracer。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "langfuse", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(LangfuseProperties.class)
public class LangfuseObservabilityConfig {

    private static final Logger log = LoggerFactory.getLogger(LangfuseObservabilityConfig.class);

    /** Langfuse 要求携带该请求头才走 v4 实时摄取，否则数据可能延迟数分钟。 */
    private static final String INGESTION_VERSION_HEADER = "x-langfuse-ingestion-version";
    private static final String INGESTION_VERSION = "4";

    /**
     * 构建并注册全局 OpenTelemetry SDK。
     *
     * <p>{@code destroyMethod = "close"} 让 Spring 在停机时关闭 tracer provider，
     * 从而把 {@link BatchSpanProcessor} 队列里剩余的 span 刷出去——否则最后几秒的调用会丢。</p>
     */
    @Bean(destroyMethod = "close")
    public OpenTelemetrySdk langfuseOpenTelemetry(LangfuseProperties props) {
        if (!props.hasCredentials()) {
            // 宁可少报也不能让应用起不来：缺凭据时只降级并大声告警
            log.error("langfuse.enabled=true 但缺少 LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY，"
                    + "本次启动不会向 Langfuse 上报任何 trace。请补齐凭据后重启。");
            return OpenTelemetrySdk.builder().build();
        }

        String endpoint = props.resolveTracesEndpoint();
        String auth = Base64.getEncoder().encodeToString(
                (props.getPublicKey() + ":" + props.getSecretKey()).getBytes(StandardCharsets.UTF_8));

        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                // 注意：setEndpoint 需要「完整路径」，SDK 不会再自动拼 /v1/traces
                .setEndpoint(endpoint)
                .setTimeout(Duration.ofMillis(props.getExportTimeoutMillis()))
                .addHeader("Authorization", "Basic " + auth)
                .addHeader(INGESTION_VERSION_HEADER, INGESTION_VERSION)
                .build();

        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.builder()
                .put("service.name", props.getServiceName())
                .put("deployment.environment", props.getEnvironment())
                .build()));

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                        .setScheduleDelay(Duration.ofSeconds(2))
                        .setMaxQueueSize(props.getMaxQueueSize())
                        .build())
                .build();

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

        try {
            GlobalOpenTelemetry.set(sdk);
            log.info("Langfuse 可观测性已启用：endpoint={}，environment={}，contentCapture={}",
                    endpoint, props.getEnvironment(), props.isCaptureContent());
        } catch (IllegalStateException alreadySet) {
            // 已被别的组件（如 javaagent 或测试框架）初始化，此时本实例的 span 不会上报
            log.warn("GlobalOpenTelemetry 已被其他组件初始化，Langfuse 上报可能不生效：{}",
                    alreadySet.getMessage());
        }
        return sdk;
    }

    /**
     * 中间件链：外层建 span，内层补 Langfuse 语义。
     *
     * <p>方法参数里显式依赖 {@link OpenTelemetrySdk}，是为了保证「先注册全局 SDK，
     * 再构造 {@link OtelTracingMiddleware}」——后者构造时会安装 Reactor 的上下文传播 hook。</p>
     */
    @Bean
    public AgentScopeMiddlewareChain agentScopeMiddlewareChain(OpenTelemetrySdk sdk,
                                                               LangfuseProperties props) {
        List<MiddlewareBase> chain = List.of(
                new OtelTracingMiddleware(),
                new LangfuseEnrichmentMiddleware(props));
        return new AgentScopeMiddlewareChain(chain);
    }
}
