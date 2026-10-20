package com.riemann.agent.infrastructure.seekdb.books;

import com.riemann.agent.infrastructure.seekdb.LocalEmbedder;

/**
 * 图书文本 -> 向量 的嵌入器，对应 Python 版 pyseekdb 的 DefaultEmbeddingFunction。
 *
 * <p>SeekDB 官方 Python SDK 的 DefaultEmbeddingFunction 在客户端把文本转成 384 维余弦向量；
 * 在 Java 侧没有等价 SDK，因此这里复用本仓库已有的 {@link LocalEmbedder}（确定性、离线、无外部依赖的
 * 词频哈希嵌入，256 维 L2 归一化），保证“导入时嵌入”与“查询时嵌入”使用同一算法，
 * 从而向量近邻检索结果可复现、可验证。</p>
 *
 * <p>若接入真实语义嵌入服务（腾讯混元 embedding / OpenAI text-embedding 等），只需替换本类的
 * {@link #embedToSql(String)} 实现即可，其余检索逻辑无需改动。</p>
 */
public final class BookEmbedder {

    private BookEmbedder() {
    }

    /** 文本 -> SeekDB 接受的向量字面量字符串，如 "[0.12,-0.34,...]"。 */
    public static String embedToSql(String text) {
        return LocalEmbedder.embedToSql(text);
    }
}
