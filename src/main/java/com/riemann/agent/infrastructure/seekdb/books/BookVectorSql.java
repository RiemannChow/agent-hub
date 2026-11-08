package com.riemann.agent.infrastructure.seekdb.books;

import java.util.ArrayList;
import java.util.List;

/**
 * 组装 {@link BookRepository#vectorHits} 的 SQL 与绑定参数。
 *
 * <p>把 SQL 拼装从 JDBC 执行里抽出来有两个目的：一是可以在没有 SeekDB 的环境下做单元测试， 二是把「全表精确扫描」与「有界候选保护」两种模式集中在一处，便于对照与评审。
 *
 * <h3>两种模式</h3>
 *
 * <ul>
 *   <li><b>默认（maxScanRows = 0）</b>：与修复前完全一致的精确全表检索，行为不变；
 *   <li><b>有界（maxScanRows &gt; 0）</b>：内层子查询按主键确定性取最多 maxScanRows 行， 外层只在这批候选上算精确 L2 并排序。这是**保护 /
 *       降级**模式而不是全库精确检索 —— 候选集之外的近邻可能被漏掉，换来的是 DB 扫描行数有上界、不会被大表打爆。
 * </ul>
 *
 * <p>真正的语义等价解法是给 embedding 建向量索引并走 ANN（见 {@code sql/vector-index-seekdb.sql} 与 {@code
 * approximate-clause} 配置），本类只提供「先保命」的兜底。
 */
final class BookVectorSql {

    private static final String COLUMNS =
            "id, name, author, user_rating, reviews, price, year, genre, document";

    private BookVectorSql() {}

    /** 一条可直接交给 {@code JdbcTemplate.query} 的计划。 */
    record Plan(String sql, List<Object> params) {}

    /**
     * @param vec 查询向量的 SQL 字面量（形如 {@code [0.1,0.2,...]}）
     * @param where 已经过白名单校验的标量过滤条件
     * @param n 最终返回条数
     * @param props 向量检索保护配置
     */
    static Plan build(String vec, SqlWhere.Built where, int n, BookVectorSearchProperties props) {
        if (n <= 0) {
            throw new IllegalArgumentException("向量检索返回条数必须为正数: " + n);
        }
        int maxScanRows = props.getMaxScanRows();
        if (maxScanRows < 0) {
            throw new IllegalArgumentException(
                    "agent.books.vector.max-scan-rows 不能为负数: " + maxScanRows);
        }
        String approximate = props.normalizedApproximateClause();
        // 默认是一个空格，拼回修复前的 "ORDER BY distance ASC LIMIT ?"；
        // 配置了近似提示时变成 "ORDER BY distance ASC <提示> LIMIT ?"。
        String orderSuffix = approximate.isEmpty() ? " " : " " + approximate + " ";

        List<Object> params = new ArrayList<>();
        params.add(vec);
        params.addAll(where.params);

        StringBuilder sql = new StringBuilder();
        if (maxScanRows > 0) {
            sql.append("SELECT ")
                    .append(COLUMNS)
                    .append(", ")
                    .append("l2_distance(embedding, ?) AS distance ")
                    .append("FROM (SELECT ")
                    .append(COLUMNS)
                    .append(", embedding ")
                    .append("FROM demo_books.book_info");
            if (!where.clause.isEmpty()) {
                sql.append(" WHERE ").append(where.clause);
            }
            sql.append(" ORDER BY id LIMIT ?) t ");
            params.add(maxScanRows);
            sql.append("ORDER BY distance ASC").append(orderSuffix).append("LIMIT ?");
        } else {
            sql.append("SELECT ")
                    .append(COLUMNS)
                    .append(", ")
                    .append("l2_distance(embedding, ?) AS distance ")
                    .append("FROM demo_books.book_info");
            if (!where.clause.isEmpty()) {
                sql.append(" WHERE ").append(where.clause);
            }
            sql.append(" ORDER BY distance ASC").append(orderSuffix).append("LIMIT ?");
        }
        params.add(n);
        return new Plan(sql.toString(), params);
    }
}
