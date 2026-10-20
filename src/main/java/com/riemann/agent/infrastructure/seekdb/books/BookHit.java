package com.riemann.agent.infrastructure.seekdb.books;

/**
 * 混合检索中单本书的「带分命中」：记录它在各召回支路上的原始分数，供 粗排 展示与 RRF 精排 融合。
 *
 * <ul>
 *   <li>{@link #vectorScore}：向量近邻支路的 L2 距离（越小越相似；未走向量支路为 {@code null}）；</li>
 *   <li>{@link #fulltextScore}：全文倒排支路的 MATCH...AGAINST 相关性得分（越大越相关；未走全文支路为 {@code null}）；</li>
 *   <li>{@link #passesScalar}：是否通过标量过滤（genre/price/rating/year 等，SQL 内已应用，恒为 true）。</li>
 * </ul>
 */
public record BookHit(Book book, Double vectorScore, Double fulltextScore, boolean passesScalar) {

    /** 仅向量支路命中：记录 L2 距离，全文相关性未知。 */
    public static BookHit vector(Book book, double vectorScore) {
        return new BookHit(book, vectorScore, null, true);
    }

    /** 仅全文支路命中：记录相关性得分，向量距离未知。 */
    public static BookHit fulltext(Book book, double fulltextScore) {
        return new BookHit(book, null, fulltextScore, true);
    }
}
