package com.riemann.agent.presentation.a2a;

import com.riemann.agent.infrastructure.a2a.A2aClientProperties;
import com.riemann.agent.infrastructure.a2a.RemoteAgentToolRegistrar;
import com.riemann.agent.infrastructure.nacos.DiscoveredAgent;
import com.riemann.agent.infrastructure.nacos.NacosAgentRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A2A 服务发现与协作视图。
 *
 * <ul>
 *   <li>{@code GET /a2a/agents} —— 发现了哪些对端（Nacos 服务发现结果）。</li>
 *   <li>{@code GET /a2a/delegation} —— <b>哪些对端真的被注册成了模型可调用的工具</b>，
 *       以及当前的委托约束（最大跳数、单次超时）。</li>
 * </ul>
 *
 * <p>第二个端点存在的理由很实际：改造后「发现了」和「能协作」是两件事——对端可能因为
 * 超过 {@code max-peers}、不在 allowlist、或 Agent Card 解析失败而没被注册成工具。
 * 排查「为什么模型没有委托给某个 agent」时，先看这里，比翻日志快得多。</p>
 */
@RestController
public class A2aDiscoveryController {

    private final NacosAgentRegistry registry;
    private final RemoteAgentToolRegistrar registrar;
    private final A2aClientProperties clientProperties;

    public A2aDiscoveryController(NacosAgentRegistry registry,
                                  RemoteAgentToolRegistrar registrar,
                                  A2aClientProperties clientProperties) {
        this.registry = registry;
        this.registrar = registrar;
        this.clientProperties = clientProperties;
    }

    @GetMapping("/a2a/agents")
    public List<DiscoveredAgent> agents() {
        return registry.getAgents();
    }

    @GetMapping("/a2a/delegation")
    public Map<String, Object> delegation() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", clientProperties.isEnabled());
        out.put("maxPeers", clientProperties.getMaxPeers());
        out.put("maxDelegationDepth", clientProperties.getMaxDelegationDepth());
        out.put("timeoutSeconds", clientProperties.getTimeout().toSeconds());
        out.put("allowlist", clientProperties.getAllowlist());
        out.put("discovered", registry.getAgents().size());
        out.put("exposedTools", registrar.exposedTools());
        return out;
    }
}
