package com.riemann.agent.domain;

import java.util.List;

/**
 * 分享快照聚合：将某次对话固化为可公开访问的只读副本。
 * 与具体持久化方式（文件 / 数据库）无关，由 Infrastructure 层落地。
 */
public record SharedConversation(
        String shareId,
        String title,
        long createdAt,
        List<ShareMessage> messages
) {
}
