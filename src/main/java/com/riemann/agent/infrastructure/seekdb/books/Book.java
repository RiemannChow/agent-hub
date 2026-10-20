package com.riemann.agent.infrastructure.seekdb.books;

/**
 * 图书领域模型（对应 Python 版 book_metadata.BookMetadata）。
 *
 * <p>字段与图书元数据（book_info 表）一一对应：
 * name / author / user_rating / reviews / price / year / genre。
 * document 为用于向量嵌入与 $contains 文本匹配的“书名 + 作者”拼接文本；
 * distance 仅在向量检索后填充，表示与查询向量的 L2 距离（越小越相似）。</p>
 */
public record Book(
        String id,
        String name,
        String author,
        double userRating,
        int reviews,
        double price,
        int year,
        String genre,
        String document,
        Double distance
) {

    /** 无向量距离时的构造（标量/过滤检索结果）。 */
    public static Book withoutDistance(String id, String name, String author, double userRating,
                                       int reviews, double price, int year, String genre, String document) {
        return new Book(id, name, author, userRating, reviews, price, year, genre, document, null);
    }
}
