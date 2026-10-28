package com.riemann.agent.infrastructure.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Langfuse（OpenTelemetry OTLP）接入配置。
 *
 * <p>Langfuse 官方只接受 <b>OTLP over HTTP</b>（protobuf / JSON），不支持 gRPC。
 * 上报地址形如 {@code https://cloud.langfuse.com/api/public/otel/v1/traces}，
 * 鉴权用 Basic Auth：{@code base64(publicKey:secretKey)}。</p>
 *
 * <p>{@code enabled=false}（默认）时整个可观测性链路完全不装配，
 * AgentScope 侧拿到的仍是 noop tracer，零额外开销。</p>
 */
@ConfigurationProperties(prefix = "langfuse")
public class LangfuseProperties {

    /** 是否启用 Langfuse 上报。 */
    private boolean enabled = false;

    /** Langfuse 项目公钥，形如 pk-lf-xxx。 */
    private String publicKey = "";

    /** Langfuse 项目私钥，形如 sk-lf-xxx。 */
    private String secretKey = "";

    /** Langfuse 实例地址：云端 EU 为 https://cloud.langfuse.com，US 为 https://us.cloud.langfuse.com。 */
    private String host = "https://cloud.langfuse.com";

    /**
     * 完整上报地址覆盖项（可选）。留空则由 {@link #host} 推导出
     * {@code {host}/api/public/otel/v1/traces}。
     */
    private String endpoint = "";

    /** 部署环境，写入 Langfuse 的 environment 字段，便于区分 local / staging / prod。 */
    private String environment = "local";

    /** 版本号，写入 Langfuse 的 release 字段。 */
    private String release = "";

    /** 服务名，写入 OTel Resource 的 service.name。 */
    private String serviceName = "agent-hub";

    /**
     * 是否采集输入/输出内容（prompt、completion、工具入参出参）。
     * 关掉后只上报链路结构与 token 用量，适合含敏感数据的生产环境。
     */
    private boolean captureContent = true;

    /** 单条 input/output 载荷的最大字符数，超出截断，避免撑爆 Langfuse 单条记录上限。 */
    private int maxPayloadChars = 4000;

    /** OTLP 导出超时（毫秒）。 */
    private long exportTimeoutMillis = 10_000L;

    /** 批量上报的最大排队 span 数。 */
    private int maxQueueSize = 2048;

    /**
     * 推导出 OTLP/HTTP traces 的完整上报地址。
     *
     * <p>已显式配置 {@code endpoint} 时直接使用；否则在 {@code host} 后拼接
     * {@code /api/public/otel/v1/traces}。已带 {@code /v1/traces} 后缀的地址不会重复拼接。</p>
     */
    public String resolveTracesEndpoint() {
        if (endpoint != null && !endpoint.isBlank()) {
            return endpoint.trim();
        }
        String base = (host == null || host.isBlank()) ? "https://cloud.langfuse.com" : host.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/v1/traces")) {
            return base;
        }
        if (base.endsWith("/api/public/otel")) {
            return base + "/v1/traces";
        }
        return base + "/api/public/otel/v1/traces";
    }

    /** 是否具备上报所需的最小凭据。 */
    public boolean hasCredentials() {
        return publicKey != null && !publicKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getRelease() {
        return release;
    }

    public void setRelease(String release) {
        this.release = release;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public boolean isCaptureContent() {
        return captureContent;
    }

    public void setCaptureContent(boolean captureContent) {
        this.captureContent = captureContent;
    }

    public int getMaxPayloadChars() {
        return maxPayloadChars;
    }

    public void setMaxPayloadChars(int maxPayloadChars) {
        this.maxPayloadChars = maxPayloadChars;
    }

    public long getExportTimeoutMillis() {
        return exportTimeoutMillis;
    }

    public void setExportTimeoutMillis(long exportTimeoutMillis) {
        this.exportTimeoutMillis = exportTimeoutMillis;
    }

    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    public void setMaxQueueSize(int maxQueueSize) {
        this.maxQueueSize = maxQueueSize;
    }
}
