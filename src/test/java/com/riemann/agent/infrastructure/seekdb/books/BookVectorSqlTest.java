package com.riemann.agent.infrastructure.seekdb.books;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 向量支路 SQL 计划的单元测试：不连数据库即可验证「默认行为不变」与「开启保护后 SQL 有界」。
 *
 * <p>项目此前没有 src/test，这组测试同时补齐了 issue #15 的回归网。
 */
class BookVectorSqlTest {

    private static final String VEC = "[0.1,0.2]";

    private static final String EXACT_SQL =
            "SELECT id, name, author, user_rating, reviews, price, year, genre, document, "
                    + "l2_distance(embedding, ?) AS distance "
                    + "FROM demo_books.book_info ORDER BY distance ASC LIMIT ?";

    private static BookVectorSearchProperties props(int maxScanRows, String approximate) {
        BookVectorSearchProperties p = new BookVectorSearchProperties();
        p.setMaxScanRows(maxScanRows);
        p.setApproximateClause(approximate);
        return p;
    }

    @Test
    void defaultModeKeepsOriginalExactScanSql() {
        BookVectorSql.Plan plan = BookVectorSql.build(VEC, SqlWhere.build(null), 10, props(0, ""));

        assertThat(plan.sql()).isEqualTo(EXACT_SQL);
        assertThat(plan.params()).containsExactly(VEC, 10);
    }

    @Test
    void scalarWhereIsPreservedInDefaultMode() {
        SqlWhere.Built where = SqlWhere.build(Map.of("genre", "Fiction"));
        BookVectorSql.Plan plan = BookVectorSql.build(VEC, where, 5, props(0, ""));

        assertThat(plan.sql()).contains("WHERE genre = ?");
        assertThat(plan.params()).containsExactly(VEC, "Fiction", 5);
    }

    @Test
    void boundedModeLimitsRowsParticipatingInDistanceCalculation() {
        SqlWhere.Built where = SqlWhere.build(Map.of("year", 2019));
        BookVectorSql.Plan plan = BookVectorSql.build(VEC, where, 5, props(1000, ""));

        assertThat(plan.sql()).contains("FROM (SELECT");
        assertThat(plan.sql()).contains("ORDER BY id LIMIT ?) t ORDER BY distance ASC LIMIT ?");
        assertThat(plan.params()).containsExactly(VEC, 2019, 1000, 5);
    }

    @Test
    void boundedModeWithoutScalarFilterStillLimitsScanRows() {
        BookVectorSql.Plan plan = BookVectorSql.build(VEC, SqlWhere.build(null), 5, props(500, ""));

        assertThat(plan.sql()).contains("ORDER BY id LIMIT ?) t");
        assertThat(plan.params()).containsExactly(VEC, 500, 5);
    }

    @Test
    void approximateClauseIsAppendedAfterOrderBy() {
        BookVectorSql.Plan plan =
                BookVectorSql.build(VEC, SqlWhere.build(null), 5, props(0, "APPROXIMATE"));

        assertThat(plan.sql())
                .isEqualTo(
                        "SELECT id, name, author, user_rating, reviews, price, year, genre, document, "
                                + "l2_distance(embedding, ?) AS distance "
                                + "FROM demo_books.book_info ORDER BY distance ASC APPROXIMATE LIMIT ?");
    }

    @Test
    void rejectsNegativeScanBudget() {
        assertThatThrownBy(() -> BookVectorSql.build(VEC, SqlWhere.build(null), 5, props(-1, "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-scan-rows");
    }

    @Test
    void rejectsSqlInjectionInApproximateClause() {
        assertThatThrownBy(
                        () ->
                                BookVectorSql.build(
                                        VEC,
                                        SqlWhere.build(null),
                                        5,
                                        props(0, "APPROXIMATE; DROP TABLE book_info")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approximate-clause");
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThatThrownBy(() -> BookVectorSql.build(VEC, SqlWhere.build(null), 0, props(0, "")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
