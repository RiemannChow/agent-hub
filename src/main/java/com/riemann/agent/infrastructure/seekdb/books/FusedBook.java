package com.riemann.agent.infrastructure.seekdb.books;

/**
 * 精排（RRF 融合）后的最终结果条目：在 粗排 双支路基础上融合排序，并标注每本书由哪些支路贡献。
 *
 * <ul>
 *   <li>{@link #fusedScore}：RRF 融合分数 = Σ 1/(k+rank+1)，分数越高排名越靠前；</li>
 *   <li>{@link #rank}：精排最终名次（从 1 开始）；</li>
 *   <li>{@link #fromVector}/{@link #fromFulltext}：该书分别是否出现在向量/全文支路（用于前端打 badge）；</li>
 *   <li>{@link #vectorScore}/{@link #fulltextScore}：该书在对应支路的原始分数（未命中该支路为 {@code null}）。</li>
 * </ul>
 */
public record FusedBook(Book book, double fusedScore, int rank,
                        boolean fromVector, boolean fromFulltext,
                        Double vectorScore, Double fulltextScore) {
}
