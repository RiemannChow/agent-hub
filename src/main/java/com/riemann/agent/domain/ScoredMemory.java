package com.riemann.agent.domain;

/**
 * 带相似度距离的回忆结果：用于长期记忆的“语义召回”与“去重合并”判断。
 * distance 为向量 L2 距离，越小越相似。
 */
public record ScoredMemory(Memory memory, double distance) {
}
