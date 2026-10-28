package com.riemann.agent.infrastructure.nacos;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ListView;
import com.riemann.agent.infrastructure.a2a.AgentsDiscoveredEvent;
import com.riemann.agent.presentation.a2a.protocol.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 Nacos 的 A2A 智能体服务发现。
 *
 * <p>从 Nacos 列出全部已注册服务，取各服务的实例，读取实例元数据中的
 * {@code a2a-card}（Agent Card 地址，缺省为 {@code http://ip:port/.well-known/agent.json}），
 * 拉取并解析为 {@link AgentCard}，构建对端智能体视图。排除自身实例。</p>
 *
 * <h3>相比早期实现的三处修正</h3>
 * <ol>
 *   <li><b>不再串行阻塞</b>：原实现对每个实例 {@code webClient...block(2s)}，N 个实例最坏 2N 秒
 *       占着一个调度线程，且 Nacos 一慢就把调度池拖住。现在用 {@code flatMap} 并发拉取，
 *       单个实例 2s 超时、整体 6s 封顶。</li>
 *   <li><b>不再有「瞬时空目录」</b>：原实现 {@code agents.clear(); agents.putAll(snapshot)} 之间
 *       存在窗口，此刻 {@code GET /a2a/agents} 会返回空、工具注册器也会以为对端全下线。
 *       现在整份快照是一次 {@link AtomicReference#set} 原子替换。</li>
 *   <li><b>失败不清空</b>：Nacos 不可用时保留上一份快照，避免「发现失败 = 对端全部消失」。</li>
 * </ol>
 *
 * <p>刷新完成后发布 {@link AgentsDiscoveredEvent}，由 {@code RemoteAgentToolRegistrar}
 * 增量维护「对端工具」，发现层与工具层互不感知。</p>
 */
@Component
public class NacosAgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(NacosAgentRegistry.class);

    /** 单个 Agent Card 拉取超时。 */
    private static final Duration CARD_TIMEOUT = Duration.ofSeconds(2);
    /** 整轮发现的总超时：避免 Nacos 慢的时候把一次刷新拖成几十秒。 */
    private static final Duration REFRESH_TIMEOUT = Duration.ofSeconds(6);

    private final NacosServiceManager nacosServiceManager;
    private final NacosDiscoveryProperties nacosDiscoveryProperties;
    private final ApplicationEventPublisher events;
    private final String selfCardUrl;
    private final Duration refreshInterval;

    private final WebClient webClient = WebClient.create();
    /** 当前对端快照：整体原子替换，读侧永远看到一致的一份。 */
    private final AtomicReference<List<DiscoveredAgent>> snapshot = new AtomicReference<>(List.of());
    /** 轮询句柄，关闭时释放（否则定时线程会拖住 JVM 退出）。 */
    private reactor.core.Disposable ticker;

    public NacosAgentRegistry(NacosServiceManager nacosServiceManager,
                              NacosDiscoveryProperties nacosDiscoveryProperties,
                              ApplicationEventPublisher events,
                              @Value("${agent.a2a.public-url:http://localhost:8080}") String publicUrl,
                              @Value("${agent.a2a.discovery.refresh-interval:15s}") Duration refreshInterval) {
        this.nacosServiceManager = nacosServiceManager;
        this.nacosDiscoveryProperties = nacosDiscoveryProperties;
        this.events = events;
        this.selfCardUrl = publicUrl + "/.well-known/agent.json";
        this.refreshInterval = refreshInterval == null || refreshInterval.isNegative() || refreshInterval.isZero()
                ? Duration.ofSeconds(15) : refreshInterval;
    }

    @PostConstruct
    public void init() {
        // 启动时异步发现一次：不阻塞应用启动（Nacos 未就绪也不影响本机功能）
        refresh();
        // 之后按配置周期轮询。用 Flux.interval 而不是 @Scheduled(fixedDelayString)：
        // 后者只认「纯毫秒」或 ISO-8601，配成人类友好的 "15s" 会在启动时抛 NumberFormatException。
        this.ticker = Flux.interval(refreshInterval, refreshInterval)
                .subscribe(tick -> refresh());
    }

    @PreDestroy
    public void stop() {
        if (ticker != null) {
            ticker.dispose();
        }
    }

    /** 立即刷新一次对端快照（异步；失败保留上一份）。 */
    public void refresh() {
        discover()
                .subscribe(
                        agents -> {
                            snapshot.set(agents);
                            log.info("[NacosAgentRegistry] 发现 {} 个对端 A2A 智能体", agents.size());
                            events.publishEvent(new AgentsDiscoveredEvent(agents));
                        },
                        e -> log.warn("[NacosAgentRegistry] 从 Nacos 刷新智能体列表失败（保留上一份快照）: {}",
                                e.toString()));
    }

    /** 当前在 Nacos 中发现的对端 A2A 智能体列表（不可变快照）。 */
    public List<DiscoveredAgent> getAgents() {
        return snapshot.get();
    }

    // ===================== 内部 =====================

    /** 一次完整发现：列实例 → 并发拉 Agent Card → 组装。失败以 onError 信号结束。 */
    private Mono<List<DiscoveredAgent>> discover() {
        return Mono.fromCallable(this::listInstances)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .flatMap(this::toDiscoveredAgent)
                .collectList()
                .timeout(REFRESH_TIMEOUT);
    }

    /** 从 Nacos 列出所有服务的所有实例（排除自身）。这一步是阻塞 API，放在 boundedElastic 上跑。 */
    private List<InstanceRef> listInstances() throws Exception {
        NamingService ns = nacosServiceManager.getNamingService(nacosDiscoveryProperties.getNacosProperties());
        ListView<String> services = ns.getServicesOfServer(1, Integer.MAX_VALUE);
        List<InstanceRef> refs = new ArrayList<>();
        for (String serviceName : services.getData()) {
            for (Instance inst : ns.getAllInstances(serviceName)) {
                String cardUrl = inst.getMetadata().getOrDefault("a2a-card",
                        "http://" + inst.getIp() + ":" + inst.getPort() + "/.well-known/agent.json");
                if (cardUrl.equals(selfCardUrl)) {
                    continue; // 排除自身
                }
                String endpoint = inst.getMetadata().getOrDefault("a2a-endpoint",
                        "http://" + inst.getIp() + ":" + inst.getPort() + "/a2a");
                refs.add(new InstanceRef(serviceName, cardUrl, endpoint));
            }
        }
        return refs;
    }

    /** 拉一个实例的 Agent Card。非 A2A 服务（拉不到卡片）静默跳过，不影响其它实例。 */
    private Mono<DiscoveredAgent> toDiscoveredAgent(InstanceRef ref) {
        return webClient.get().uri(ref.cardUrl()).retrieve()
                .bodyToMono(AgentCard.class)
                .timeout(CARD_TIMEOUT)
                .map(card -> new DiscoveredAgent(ref.serviceName(), card.name(), card.url(), ref.endpoint(), card))
                .onErrorResume(e -> {
                    log.debug("[NacosAgentRegistry] 跳过无法解析 Agent Card 的实例 {}: {}", ref.cardUrl(), e.toString());
                    return Mono.empty();
                });
    }

    /** 待探测的一个实例。 */
    private record InstanceRef(String serviceName, String cardUrl, String endpoint) {
    }
}
