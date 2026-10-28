package com.riemann.agent.infrastructure.context;

import io.agentscope.core.message.Msg;

import java.util.List;

/**
 * 一次组装好的模型上下文。
 *
 * @param messages           最终要喂给 {@code streamEvents} 的 Msg 列表（已含前置说明与本轮用户消息）
 * @param summarized         本轮是否用上了历史摘要
 * @param summarizedMessages 被摘要替代的历史消息条数（0 表示没有摘要）
 * @param windowMessages     原样保留在窗口里的历史消息条数
 */
public record ContextWindow(List<Msg> messages, boolean summarized, int summarizedMessages, int windowMessages) {
}
