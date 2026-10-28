package com.riemann.agent.infrastructure.a2a;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 出站 A2A 协作配置（{@code agent.a2a.client.*}）。
 *
 * <p><b>为什么需要这一层：</b>改造前本智能体只有「入站」——能被对端通过 {@code POST /a2a} 调用，
 * 也能通过 Nacos 发现对端，但没有任何代码去<b>调用</b>对端，所以「多 Agent 协作」实际上只是
 * 一个目录展示。这里把发现到的对端包装成模型可调用的工具（见 {@link RemoteAgentTool}），
 * 让「要不要委托给别的智能体」变成模型的一次工具选择。</p>
 *
 * <p><b>为什么要限流与限深：</b>开放委托等于给模型一根「打电话给别的 agent」的线。
 * 不加约束会出两类事故：① 工具 schema 爆炸——发现 20 个对端就往上下文里塞 20 个工具，
 * 模型选择准确率直接崩；② 委托环——A 委托 B、B 又委托 A，互相 ping-pong 直到各自
 * turn-timeout。因此 {@code max-peers} 限制工具数量，{@code max-delegation-depth} 限制跳数。</p>
 */
@Component
@ConfigurationProperties(prefix = "agent.a2a.client")
public class A2aClientProperties {

    /** 是否把发现到的对端注册成可调用工具。关闭后退回「只发现不协作」。 */
    private boolean enabled = true;

    /** 工具名前缀：{@code call_agent_<peer>}。 */
    private String toolPrefix = "call_agent_";

    /** 最多注册多少个对端工具（按名称排序取前 N，保证结果稳定）。 */
    private int maxPeers = 5;

    /** 单次委托（一次 message/send）的超时。 */
    private Duration timeout = Duration.ofSeconds(90);

    /** 委托结果回填给模型的字符上限，超出截断（防止对端返回长文挤爆上下文）。 */
    private int maxResultChars = 4000;

    /**
     * 最大委托跳数。A→B 是 1 跳；B 再委托给 C 是 2 跳。
     * 达到上限时拒绝继续委托，避免跨实例的无限 ping-pong。
     */
    private int maxDelegationDepth = 3;

    /** 只允许委托给这些对端（按 Agent Card 的 name 匹配，留空表示不限制）。 */
    private List<String> allowlist = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getToolPrefix() {
        return toolPrefix;
    }

    public void setToolPrefix(String toolPrefix) {
        this.toolPrefix = toolPrefix;
    }

    public int getMaxPeers() {
        return maxPeers;
    }

    public void setMaxPeers(int maxPeers) {
        this.maxPeers = maxPeers;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxResultChars() {
        return maxResultChars;
    }

    public void setMaxResultChars(int maxResultChars) {
        this.maxResultChars = maxResultChars;
    }

    public int getMaxDelegationDepth() {
        return maxDelegationDepth;
    }

    public void setMaxDelegationDepth(int maxDelegationDepth) {
        this.maxDelegationDepth = maxDelegationDepth;
    }

    public List<String> getAllowlist() {
        return allowlist;
    }

    public void setAllowlist(List<String> allowlist) {
        this.allowlist = allowlist == null ? new ArrayList<>() : allowlist;
    }
}
