package com.riemann.agent.infrastructure.nacos;

import com.riemann.agent.presentation.a2a.protocol.AgentCard;

/**
 * 经由 Nacos 服务发现得到的对端智能体视图：名称、基础地址、A2A 端点与 Agent Card。
 */
public record DiscoveredAgent(String name, String baseUrl, String a2aEndpoint, AgentCard card) {
}
