package com.riemann.agent.infrastructure.nacos;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ListView;
import com.riemann.agent.presentation.a2a.protocol.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Nacos 的 A2A 智能体服务发现。
 *
 * <p>从 Nacos 列出全部已注册服务，取各服务的实例，读取实例元数据中的
 * {@code a2a-card}（Agent Card 地址，缺省为 {@code http://ip:port/.well-known/agent.json}），
 * 拉取并解析为 {@link AgentCard}，构建对端智能体视图。排除自身实例。
 * 每 15s 刷新一次（应用启动也会立即刷新一次）。</p>
 */
@Component
public class NacosAgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(NacosAgentRegistry.class);

    private final NacosServiceManager nacosServiceManager;
    private final NacosDiscoveryProperties nacosDiscoveryProperties;
    private final String selfCardUrl;

    private final WebClient webClient = WebClient.create();
    private final Map<String, DiscoveredAgent> agents = new ConcurrentHashMap<>();

    public NacosAgentRegistry(NacosServiceManager nacosServiceManager,
                              NacosDiscoveryProperties nacosDiscoveryProperties,
                              @Value("${agent.a2a.public-url:http://localhost:8080}") String publicUrl) {
        this.nacosServiceManager = nacosServiceManager;
        this.nacosDiscoveryProperties = nacosDiscoveryProperties;
        this.selfCardUrl = publicUrl + "/.well-known/agent.json";
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    @Scheduled(fixedDelay = 15_000)
    public void refresh() {
        try {
            NamingService ns = nacosServiceManager.getNamingService(nacosDiscoveryProperties.getNacosProperties());
            ListView<String> services = ns.getServicesOfServer(1, Integer.MAX_VALUE);
            Map<String, DiscoveredAgent> snapshot = new ConcurrentHashMap<>();
            for (String serviceName : services.getData()) {
                for (Instance inst : ns.getAllInstances(serviceName)) {
                    String cardUrl = inst.getMetadata().getOrDefault("a2a-card",
                            "http://" + inst.getIp() + ":" + inst.getPort() + "/.well-known/agent.json");
                    if (cardUrl.equals(selfCardUrl)) {
                        continue; // 排除自身
                    }
                    try {
                        AgentCard card = webClient.get().uri(cardUrl).retrieve()
                                .bodyToMono(AgentCard.class)
                                .block(Duration.ofSeconds(2));
                        if (card == null) {
                            continue;
                        }
                        String key = serviceName + "@" + inst.getIp() + ":" + inst.getPort();
                        String a2aEndpoint = inst.getMetadata().getOrDefault("a2a-endpoint",
                                "http://" + inst.getIp() + ":" + inst.getPort() + "/a2a");
                        snapshot.put(key, new DiscoveredAgent(card.name(), card.url(), a2aEndpoint, card));
                    } catch (Exception e) {
                        // 非 A2A 服务（拉不到 Agent Card）静默跳过
                        log.debug("跳过无法解析 Agent Card 的实例 {}: {}", cardUrl, e.getMessage());
                    }
                }
            }
            agents.clear();
            agents.putAll(snapshot);
            log.info("[NacosAgentRegistry] 发现 {} 个对端 A2A 智能体", agents.size());
        } catch (Exception e) {
            log.warn("[NacosAgentRegistry] 从 Nacos 刷新智能体列表失败（Nacos 未启动？）: {}", e.getMessage());
        }
    }

    /** 当前在 Nacos 中发现的对端 A2A 智能体列表。 */
    public List<DiscoveredAgent> getAgents() {
        return new ArrayList<>(agents.values());
    }
}
