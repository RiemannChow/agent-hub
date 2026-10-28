package com.riemann.agent.infrastructure.nacos;

import com.riemann.agent.presentation.a2a.protocol.AgentCard;

/**
 * 经由 Nacos 服务发现得到的对端智能体视图：服务名、名称、基础地址、A2A 端点与 Agent Card。
 *
 * <p>{@code serviceName} 单独保留的原因：Agent Card 里的 {@code name} 是给人看的中文名
 * （如「OceanBase 技术助手」），而工具名必须是 ASCII 标识符。工具注册时优先用
 * {@code serviceName}（通常是 {@code spring.application.name}，天然是 ASCII）派生工具名，
 * 拿不到时才回退到对 name 做清洗 / 哈希。</p>
 */
public record DiscoveredAgent(String serviceName, String name, String baseUrl, String a2aEndpoint, AgentCard card) {
}
