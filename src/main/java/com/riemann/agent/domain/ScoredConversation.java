package com.riemann.agent.domain;

/**
 * 混合检索的打分结果 —— 值对象。
 * 由 SeekDbConversationRepository.hybridSearch 产出。
 */
public record ScoredConversation(
        String conversationId,
        String title,
        String snippet,
        double score
) {}
