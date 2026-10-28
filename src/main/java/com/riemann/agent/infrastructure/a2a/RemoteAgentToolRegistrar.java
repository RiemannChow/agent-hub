package com.riemann.agent.infrastructure.a2a;

import com.riemann.agent.infrastructure.nacos.DiscoveredAgent;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把「发现到的对端智能体」增量同步进 {@link Toolkit}，让模型能像调用本地工具一样委托对端。
 *
 * <h3>为什么是增量同步，而不是每次重建</h3>
 * <p>Nacos 刷新是 15s 一次，若每次都清空重注册，会造成两个问题：① 工具在两次刷新之间短暂消失，
 * 正在进行的推理可能拿到空的 schema；② {@code registerAgentTool} 会产生大量临时对象。
 * 因此这里维护一份 {@code toolName → RemoteAgentTool} 的注册表，只对「新增 / 端点变化 / 消失」
 * 三种情况做动作，并用 {@code fingerprint()} 判断是否需要替换。</p>
 *
 * <h3>工具名的来源</h3>
 * <p>优先用 Nacos 的 {@code serviceName}（通常等于 {@code spring.application.name}，天然 ASCII），
 * 因为 Agent Card 里的 name 是中文（如「电商数据分析助手」），直接清洗会变成一串下划线，
 * 模型看到一个 {@code call_agent____} 根本不知道该不该调。</p>
 */
@Component
public class RemoteAgentToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(RemoteAgentToolRegistrar.class);

    private final Toolkit toolkit;
    private final A2aClient client;
    private final A2aClientProperties props;

    /** 当前已注册的对端工具：toolName → tool。仅由事件线程与同步方法访问，用 synchronized 保护。 */
    private final Map<String, RemoteAgentTool> registered = new LinkedHashMap<>();

    public RemoteAgentToolRegistrar(Toolkit toolkit, A2aClient client, A2aClientProperties props) {
        this.toolkit = toolkit;
        this.client = client;
        this.props = props;
    }

    /** 服务发现刷新后同步对端工具。 */
    @EventListener
    public void onAgentsDiscovered(AgentsDiscoveredEvent event) {
        if (!props.isEnabled()) {
            return;
        }
        try {
            sync(event.agents());
        } catch (Exception e) {
            // 注册失败不能影响主链路（这一轮照常用本地工具跑）
            log.warn("[A2A] 同步对端工具失败：{}", e.toString());
        }
    }

    private synchronized void sync(List<DiscoveredAgent> agents) {
        Map<String, RemoteAgentTool> desired = select(agents);

        // 1) 下线：本轮快照里已不存在的对端
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, RemoteAgentTool> e : registered.entrySet()) {
            if (!desired.containsKey(e.getKey())) {
                stale.add(e.getKey());
            }
        }
        for (String name : stale) {
            RemoteAgentTool old = registered.remove(name);
            // removeToolIfSame：只有当前注册的确实是这个实例才删，避免误删同名新工具
            toolkit.removeToolIfSame(name, old);
            log.info("[A2A] 对端下线，已注销工具 {}", name);
        }

        // 2) 上线 / 替换：新出现的，或同一工具名但端点变了
        int added = 0;
        for (Map.Entry<String, RemoteAgentTool> e : desired.entrySet()) {
            RemoteAgentTool next = e.getValue();
            RemoteAgentTool existing = registered.get(e.getKey());
            if (existing != null && existing.fingerprint().equals(next.fingerprint())) {
                continue;
            }
            if (existing != null) {
                toolkit.removeToolIfSame(e.getKey(), existing);
            }
            toolkit.registerAgentTool(next);
            registered.put(e.getKey(), next);
            added++;
            log.info("[A2A] 已把对端「{}」注册为工具 {}（端点 {}）",
                    next.peer().name(), e.getKey(), next.peer().a2aEndpoint());
        }

        if (added > 0 || !stale.isEmpty()) {
            log.info("[A2A] 对端工具同步完成：当前 {} 个（新增 {}，下线 {}）",
                    registered.size(), added, stale.size());
        }
    }

    /**
     * 从发现快照里挑出要暴露成工具的对端。
     *
     * <p>排序 + 去重 + 截断都为了让结果<b>稳定</b>：同样的快照必须得到同样的工具集合，
     * 否则每次刷新都可能换一批工具，模型的选择行为会跟着抖。</p>
     */
    private Map<String, RemoteAgentTool> select(List<DiscoveredAgent> agents) {
        Map<String, RemoteAgentTool> desired = new LinkedHashMap<>();
        if (agents == null || agents.isEmpty()) {
            return desired;
        }
        List<DiscoveredAgent> sorted = new ArrayList<>(agents);
        sorted.sort(Comparator
                .comparing((DiscoveredAgent a) -> Objects.toString(a.serviceName(), ""))
                .thenComparing(a -> Objects.toString(a.a2aEndpoint(), "")));

        int max = Math.max(1, props.getMaxPeers());
        for (DiscoveredAgent peer : sorted) {
            if (peer == null || peer.a2aEndpoint() == null || peer.a2aEndpoint().isBlank()) {
                continue;
            }
            if (!allowed(peer)) {
                continue;
            }
            String name = toolName(peer);
            if (desired.containsKey(name)) {
                // 同一服务的多实例：只保留排序后的第一个（避免同名工具互相覆盖）
                continue;
            }
            if (desired.size() >= max) {
                log.debug("[A2A] 对端数量超过 max-peers={}，忽略 {}", max, peer.name());
                continue;
            }
            desired.put(name, new RemoteAgentTool(peer, name, client, props));
        }
        return desired;
    }

    private boolean allowed(DiscoveredAgent peer) {
        List<String> allow = props.getAllowlist();
        if (allow == null || allow.isEmpty()) {
            return true;
        }
        return allow.contains(peer.name()) || allow.contains(peer.serviceName());
    }

    /** {@code toolPrefix + ASCII 化的服务名}。 */
    public static String toolName(DiscoveredAgent peer, String prefix) {
        String base = sanitize(peer.serviceName());
        if (base.isBlank()) {
            base = sanitize(peer.name());
        }
        if (base.isBlank()) {
            // 中文名清洗后为空：用哈希保证稳定且唯一
            base = "peer_" + Integer.toHexString(Objects.hashCode(peer.name()));
        }
        return prefix + base;
    }

    private String toolName(DiscoveredAgent peer) {
        return toolName(peer, props.getToolPrefix());
    }

    /** 只保留 {@code [A-Za-z0-9_]}，压缩连续下划线，截断到 40 字符（工具名上限）。 */
    public static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        String cleaned = s.replaceAll("[^A-Za-z0-9_]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    /** 当前暴露给模型的对端工具（供 /a2a/agents 展示，便于排查「为什么模型没委托」）。 */
    public synchronized List<Map<String, String>> exposedTools() {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map.Entry<String, RemoteAgentTool> e : registered.entrySet()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("toolName", e.getKey());
            item.put("agentName", Objects.toString(e.getValue().peer().name(), ""));
            item.put("endpoint", e.getValue().peer().a2aEndpoint());
            out.add(item);
        }
        return out;
    }
}
