package com.riemann.agent.presentation.a2a;

import com.riemann.agent.infrastructure.nacos.DiscoveredAgent;
import com.riemann.agent.infrastructure.nacos.NacosAgentRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A2A 服务发现视图端点：GET /a2a/agents。
 *
 * <p>返回经由 Nacos 发现的对端 A2A 智能体列表，直观体现「智能体服务发现依赖 Nacos」。
 * 本实例自身会被 {@link NacosAgentRegistry} 排除。</p>
 */
@RestController
public class A2aDiscoveryController {

    private final NacosAgentRegistry registry;

    public A2aDiscoveryController(NacosAgentRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/a2a/agents")
    public List<DiscoveredAgent> agents() {
        return registry.getAgents();
    }
}
