package com.riemann.agent.infrastructure.a2a;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * A2A 任务存储配置（{@code agent.a2a.tasks.*}）。
 *
 * <p>改造前 {@code A2aTaskStore} 是一个裸 {@code ConcurrentHashMap}：永不清理、无上限。
 * 长期运行时每个任务都带着完整 artifacts（一轮对话的全文），内存只涨不跌；
 * 而且 {@code tasks/cancel} 只改状态、拿不到「这个任务对应哪一轮对话」，无法真正中断。</p>
 */
@Component
@ConfigurationProperties(prefix = "agent.a2a.tasks")
public class A2aTaskStoreProperties {

    /** 任务保留时长。到期后 {@code tasks/get} 视为不存在（符合「任务可回查但非永久」的语义）。 */
    private Duration ttl = Duration.ofMinutes(30);

    /** 最多保留多少个任务，超出后按创建时间淘汰最旧的。 */
    private int maxEntries = 2000;

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int maxEntries) {
        this.maxEntries = maxEntries;
    }
}
