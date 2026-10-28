package com.riemann.agent.infrastructure.config;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 与 profile 无关的共享 LLM 客户端配置。
 *
 * <p>原先 {@code agentModel} Bean 定义在 {@link AgentConfig}（@Profile("seekdb")）中，
 * 但 oceanbase profile 也需要同一个混元网关客户端。为避免重复配置鉴权/网关，
 * 抽到此处（无 profile 限制），供所有 profile 共用。</p>
 */
@Configuration
public class ModelConfig {

    @Bean
    public Model agentModel(@Value("${agent.model.api-key}") String apiKey,
                            @Value("${agent.model.base-url}") String baseUrl,
                            @Value("${agent.model.name:hy4}") String modelName) {
        // ANTHROPIC_BASE_URL=https://api.lkeap.cloud.tencent.com/plan/anthropic
        // 该网关是 Anthropic Messages 协议，baseUrl 直接填完整网关地址。
        return AnthropicChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .stream(true)
                .build();
    }

    /**
     * 可选的兜底模型：主模型在重试耗尽后仍失败时，AgentScope 会切到它继续本轮。
     *
     * <p>未配置 {@code agent.model.fallback.name} 时返回一个空持有者
     * （{@link FallbackModelHolder#isPresent()} 为 false），行为与改造前完全一致。
     * 配置后可以指向同一网关的另一个模型（例如更稳但更贵的型号），
     * 也可以指向另一家供应商的 Anthropic 兼容端点（用 {@code base-url} / {@code api-key} 覆盖）。</p>
     */
    @Bean
    public FallbackModelHolder fallbackAgentModel(
            @Value("${agent.model.api-key}") String apiKey,
            @Value("${agent.model.base-url}") String baseUrl,
            @Value("${agent.model.fallback.name:}") String fallbackName,
            @Value("${agent.model.fallback.base-url:}") String fallbackBaseUrl,
            @Value("${agent.model.fallback.api-key:}") String fallbackApiKey) {
        if (fallbackName == null || fallbackName.isBlank()) {
            return new FallbackModelHolder(null);
        }
        String key = (fallbackApiKey == null || fallbackApiKey.isBlank()) ? apiKey : fallbackApiKey;
        String url = (fallbackBaseUrl == null || fallbackBaseUrl.isBlank()) ? baseUrl : fallbackBaseUrl;
        Model model = AnthropicChatModel.builder()
                .apiKey(key)
                .baseUrl(url)
                .modelName(fallbackName)
                .stream(true)
                .build();
        return new FallbackModelHolder(model);
    }
}
