package com.riemann.agent.infrastructure.context;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 上下文窗口与摘要配置（{@code agent.context.*}）。
 *
 * <p><b>为什么不再用「硬截断最近 8 条」：</b>按条数截断有两个硬伤——</p>
 * <ol>
 *   <li>条数与 token 不成比例：一条粘贴了长表格的用户消息可能就占满整个窗口，
 *       而 8 条寒暄却几乎不占；按条数切会让「窗口实际大小」在几百到几万 token 之间乱跳。</li>
 *   <li>被切掉的历史直接消失：用户在第 3 轮说过的口径（“只看 2015 年以后”“按季度拆”）
 *       到第 9 轮就没了，模型只能重新猜或者再问一遍。</li>
 * </ol>
 *
 * <p>本配置把窗口拆成两个正交的旋钮：<b>保留多少</b>（条数 + 字符双上限）
 * 与<b>丢掉的部分怎么办</b>（滚动摘要）。摘要只覆盖被挤出窗口的部分，
 * 且带缓存——同一段历史不会每轮重复摘要。</p>
 */
@Component
@ConfigurationProperties(prefix = "agent.context")
public class ContextWindowProperties {

    /** 是否启用老历史摘要。关闭后退化为「只保留最近窗口」，即改造前的行为。 */
    private boolean summarizeEnabled = true;

    /** 注入模型的最近历史条数上限（不含本轮用户消息）。 */
    private int recentMessages = 8;

    /**
     * 最近窗口的字符预算。与 {@link #recentMessages} 同时生效，先到者为准。
     *
     * <p>字符数不等于 token 数（中文约 1 字 ≈ 1 token，英文约 4 字符 ≈ 1 token），
     * 但作为「防止单条超长消息挤爆窗口」的粗粒度闸门足够可靠，且无需引入 tokenizer 依赖。</p>
     */
    private int recentCharBudget = 6000;

    /**
     * 触发摘要的最小溢出条数：被挤出窗口的老历史达到这么多条，才值得花一次模型调用。
     * 只有一两条溢出时直接丢弃更划算。
     */
    private int summaryTriggerMessages = 4;

    /**
     * 增量摘要的最小新增条数：距上次摘要新增的老消息不足这么多条时，
     * 复用旧摘要并把这一小段留在窗口里，避免「每轮都摘要一次」的额外开销。
     */
    private int summaryMinNewMessages = 4;

    /** 摘要正文的最大字符数。超出截断。 */
    private int summaryMaxChars = 1200;

    /** 单次摘要模型调用的超时。超时/失败一律降级为「无摘要」，不阻塞本轮对话。 */
    private Duration summaryTimeout = Duration.ofSeconds(20);

    /** 摘要缓存的会话数上限（LRU）。多用户长跑时防止无界增长。 */
    private int summaryCacheSize = 512;

    public boolean isSummarizeEnabled() {
        return summarizeEnabled;
    }

    public void setSummarizeEnabled(boolean summarizeEnabled) {
        this.summarizeEnabled = summarizeEnabled;
    }

    public int getRecentMessages() {
        return recentMessages;
    }

    public void setRecentMessages(int recentMessages) {
        this.recentMessages = recentMessages;
    }

    public int getRecentCharBudget() {
        return recentCharBudget;
    }

    public void setRecentCharBudget(int recentCharBudget) {
        this.recentCharBudget = recentCharBudget;
    }

    public int getSummaryTriggerMessages() {
        return summaryTriggerMessages;
    }

    public void setSummaryTriggerMessages(int summaryTriggerMessages) {
        this.summaryTriggerMessages = summaryTriggerMessages;
    }

    public int getSummaryMinNewMessages() {
        return summaryMinNewMessages;
    }

    public void setSummaryMinNewMessages(int summaryMinNewMessages) {
        this.summaryMinNewMessages = summaryMinNewMessages;
    }

    public int getSummaryMaxChars() {
        return summaryMaxChars;
    }

    public void setSummaryMaxChars(int summaryMaxChars) {
        this.summaryMaxChars = summaryMaxChars;
    }

    public Duration getSummaryTimeout() {
        return summaryTimeout;
    }

    public void setSummaryTimeout(Duration summaryTimeout) {
        this.summaryTimeout = summaryTimeout;
    }

    public int getSummaryCacheSize() {
        return summaryCacheSize;
    }

    public void setSummaryCacheSize(int summaryCacheSize) {
        this.summaryCacheSize = summaryCacheSize;
    }
}
