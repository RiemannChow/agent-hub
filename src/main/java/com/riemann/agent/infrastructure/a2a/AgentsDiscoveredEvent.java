package com.riemann.agent.infrastructure.a2a;

import com.riemann.agent.infrastructure.nacos.DiscoveredAgent;

import java.util.List;

/**
 * 服务发现快照更新事件。
 *
 * <p>由 {@link com.riemann.agent.infrastructure.nacos.NacosAgentRegistry} 在每次刷新后发布，
 * {@link RemoteAgentToolRegistrar} 据此增量维护「对端工具」集合。</p>
 *
 * <p>用事件而不是让注册器轮询 registry，是为了让「发现」与「注册工具」解耦：
 * 发现层只关心 Nacos 与 Agent Card，注册层只关心 Toolkit，两边都不需要知道对方的存在。</p>
 */
public record AgentsDiscoveredEvent(List<DiscoveredAgent> agents) {
}
