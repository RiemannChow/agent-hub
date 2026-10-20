package com.riemann.agent.infrastructure.seekdb.books;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * book_info 表的 JDBC 仓储，对应 Python 版 database/db_client.py + index_manager.py 的底层 SQL 操作。
 *
 * <p>所有检索均命中 demo_books.book_info：</p>
 * <ul>
 *   <li>{@link #vectorSearch}：向量近邻（l2_distance），对应 pyseekdb 的 KNN 查询；</li>
 *   <li>{@link #filterSearch}：标量过滤（where）+ 文档子串匹配（$contains），对应 hybrid_search 的 query 分支；</li>
 * </ul>
 * 混合检索的 RRF 融合在 {@link HybridSearchService} 中完成。
 */
@Repository
@Profile("!oceanbase")
public class BookRepository {

    private final JdbcTemplate jdbc;

    public BookRepository(JdbcTemplate demoBooksJdbcTemplate) {
        this.jdbc = demoBooksJdbcTemplate;
    }

    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM demo_books.book_info", Integer.class);
        return n == null ? 0 : n;
    }

    /** 确保目标表存在（与 seekdb-init/02_books.sql 结构一致），便于脱离初始化脚本独立运行。 */
    public void ensureTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS demo_books.book_info ("
                + "id VARCHAR(16) PRIMARY KEY, "
                + "name VARCHAR(500), author VARCHAR(200), user_rating FLOAT, reviews INT, "
                + "price FLOAT, year INT, genre VARCHAR(100), document TEXT, embedding VECTOR(256))");
    }

    /** 批量写入图书（含离线向量嵌入），对应 import_data.py 的分批 add。 */
    public void batchInsert(List<Book> books) {
        String sql = "INSERT INTO demo_books.book_info "
                + "(id, name, author, user_rating, reviews, price, year, genre, document, embedding) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbc.batchUpdate(sql, books, books.size(), (ps, book) -> {
            ps.setString(1, book.id());
            ps.setString(2, book.name());
            ps.setString(3, book.author());
            ps.setDouble(4, book.userRating());
            ps.setInt(5, book.reviews());
            ps.setDouble(6, book.price());
            ps.setInt(7, book.year());
            ps.setString(8, book.genre());
            ps.setString(9, book.document());
            ps.setString(10, BookEmbedder.embedToSql(book.document()));
        });
    }

    /** 向量近邻检索：按 L2 距离升序返回前 n 本。对应 hybrid_search 的 query(knn=...)。 */
    public List<Book> vectorSearch(String queryText, int n) {
        return vectorHits(queryText, null, n).stream().map(BookHit::book).collect(Collectors.toList());
    }

    /**
     * 带标量过滤的向量近邻检索：在 L2 距离排序前先用 where 过滤（genre/price/rating 等），
     * 避免“向量分支返回未过滤结果、再经 RRF 融合把不符合条件的书（如同为 Fiction）混进来”。
     */
    public List<Book> vectorSearch(String queryText, Map<String, Object> where, int n) {
        return vectorHits(queryText, where, n).stream().map(BookHit::book).collect(Collectors.toList());
    }

    /**
     * 向量近邻检索（带分）：与 {@link #vectorSearch} 同语义，但把 L2 距离作为 {@link BookHit#vectorScore}
     * 一并带回，供混合检索的 粗排 展示与 RRF 精排 融合使用。
     */
    public List<BookHit> vectorHits(String queryText, Map<String, Object> where, int n) {
        String vec = BookEmbedder.embedToSql(queryText);
        SqlWhere.Built w = SqlWhere.build(where);
        String sql = "SELECT id, name, author, user_rating, reviews, price, year, genre, document, "
                + "l2_distance(embedding, ?) AS distance "
                + "FROM demo_books.book_info";
        List<Object> params = new ArrayList<>(w.params);
        if (!w.clause.isEmpty()) {
            sql += " WHERE " + w.clause;
        }
        sql += " ORDER BY distance ASC LIMIT ?";
        params.add(0, vec);
        params.add(n);
        return jdbc.query(sql, (rs, i) -> {
            Book b = mapBook(rs, true);
            Double d = b.distance();
            return BookHit.vector(b, d == null ? 0.0 : d);
        }, params.toArray());
    }

    /**
     * 全文倒排检索（带分）：真正走 SeekDB 的 FULLTEXT 倒排索引（ft_document），
     * 用 {@code MATCH(document) AGAINST (? IN BOOLEAN MODE)} 取相关性得分，按相关性降序返回前 n 本。
     * 对应 Python hybrid_search 的 query(where_document={$contains: ...}) 支路，但底层从 LIKE 子串扫描
     * 升级为倒排索引检索。document = 书名 + 作者（拉丁文本），故 FULLTEXT 用默认 parser 即可。
     *
     * @param keyword 全文关键词（留空则直接返回空列表，不查全文支路）
     * @param where   标量过滤条件（与全文条件以 AND 叠加），可为空
     */
    public List<BookHit> fulltextHits(String keyword, Map<String, Object> where, int n) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        SqlWhere.Built w = SqlWhere.build(where);
        String match = "MATCH(document) AGAINST (? IN BOOLEAN MODE)";
        List<Object> params = new ArrayList<>();
        params.add(keyword); // 第一个 ?：SELECT 里的相关性得分
        params.addAll(w.params);
        params.add(keyword); // 第二个 ?：WHERE 里的命中条件
        params.add(n);       // LIMIT

        List<String> conds = new ArrayList<>();
        if (!w.clause.isEmpty()) {
            conds.add(w.clause);
        }
        conds.add(match);

        String sql = "SELECT id, name, author, user_rating, reviews, price, year, genre, document, "
                + match + " AS relevance "
                + "FROM demo_books.book_info";
        if (!conds.isEmpty()) {
            sql += " WHERE " + String.join(" AND ", conds);
        }
        sql += " ORDER BY relevance DESC LIMIT ?";

        return jdbc.query(sql, (rs, i) -> {
            Book b = mapBook(rs, false);
            Object o = rs.getObject("relevance");
            double rel = (o == null) ? 0.0 : ((Number) o).doubleValue();
            return BookHit.fulltext(b, rel);
        }, params.toArray());
    }

    /**
     * 标量 + 全文过滤检索（返回纯 Book，不含支路分数）：兼容语义检索回退路径与演示。
     * 关键词分支已升级为 FULLTEXT 倒排索引（见 {@link #fulltextHits}），不再用 LIKE 子串扫描。
     *
     * @param contains 全文关键词（$contains 语义），留空则只做标量过滤
     * @param where    标量过滤条件（嵌套 Map，含 $gte/$lte/$and 等），可为空
     */
    public List<Book> filterSearch(String contains, Map<String, Object> where, int n) {
        SqlWhere.Built w = SqlWhere.build(where);
        List<Object> params = new ArrayList<>(w.params);

        List<String> conds = new ArrayList<>();
        if (!w.clause.isEmpty()) {
            conds.add(w.clause);
        }
        boolean hasKeyword = contains != null && !contains.isBlank();
        if (hasKeyword) {
            conds.add("MATCH(document) AGAINST (? IN BOOLEAN MODE)");
            params.add(contains);
        }

        String sql = "SELECT id, name, author, user_rating, reviews, price, year, genre, document "
                + "FROM demo_books.book_info";
        if (!conds.isEmpty()) {
            sql += " WHERE " + String.join(" AND ", conds);
        }
        // 有关键词：先按全文相关性，再按评分/评论稳定排序；无关键词：按评分/评论排序
        if (hasKeyword) {
            sql += " ORDER BY MATCH(document) AGAINST (? IN BOOLEAN MODE) DESC, "
                    + "user_rating DESC, reviews DESC LIMIT ?";
            params.add(contains);
        } else {
            sql += " ORDER BY user_rating DESC, reviews DESC LIMIT ?";
        }
        params.add(n);

        return jdbc.query(sql, (rs, i) -> mapBook(rs, false), params.toArray());
    }

    // ===================== 内部辅助 =====================

    private Book mapBook(java.sql.ResultSet rs, boolean withDistance) throws java.sql.SQLException {
        Double distance = null;
        if (withDistance) {
            Object o = rs.getObject("distance");
            distance = (o == null) ? null : ((Number) o).doubleValue();
        }
        return new Book(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("author"),
                rs.getDouble("user_rating"),
                rs.getInt("reviews"),
                rs.getDouble("price"),
                rs.getInt("year"),
                rs.getString("genre"),
                rs.getString("document"),
                distance);
    }
}
