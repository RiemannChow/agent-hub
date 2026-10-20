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
}
