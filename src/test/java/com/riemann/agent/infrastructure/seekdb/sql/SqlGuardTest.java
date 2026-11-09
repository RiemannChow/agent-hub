package com.riemann.agent.infrastructure.seekdb.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the LLM-SQL guard (issue #23). This guard is the last line of defence
 * against a generated query reading private conversation tables or scanning an unbounded number of
 * rows, so its rejection paths must not silently regress.
 */
class SqlGuardTest {

    private static SqlGuard guard() {
        return new SqlGuard(new SqlGuardProperties());
    }

    private static String upper(String sql) {
        return sql.toUpperCase(Locale.ROOT);
    }

    @Test
    void appendsRowLimitWhenMissing() {
        String guarded = guard().guard("SELECT * FROM products");
        assertThat(upper(guarded)).contains("LIMIT 200");
    }

    @Test
    void keepsAnExistingSmallerLimitUntouched() {
        String sql = "SELECT * FROM orders LIMIT 5";
        assertThat(guard().guard(sql)).isEqualTo(sql);
    }

    @Test
    void tightensAnExistingLimitAboveTheCap() {
        String guarded = guard().guard("SELECT * FROM orders LIMIT 9999");
        assertThat(upper(guarded)).contains("LIMIT 200").doesNotContain("9999");
    }

    @Test
    void rejectsNonAllowlistedPrivateTables() {
        assertThatThrownBy(() -> guard().guard("SELECT * FROM conversation"))
                .isInstanceOf(SqlGuardException.class)
                .hasMessageContaining("白名单");
    }

    @Test
    void rejectsMetadataSchemaAccess() {
        assertThatThrownBy(() -> guard().guard("SELECT * FROM information_schema.tables"))
                .isInstanceOf(SqlGuardException.class);
    }

    @Test
    void rejectsNonSelectStatements() {
        assertThatThrownBy(() -> guard().guard("DELETE FROM products"))
                .isInstanceOf(SqlGuardException.class)
                .hasMessageContaining("SELECT");
    }

    @Test
    void rejectsMultipleStatements() {
        assertThatThrownBy(() -> guard().guard("SELECT 1; DROP TABLE products"))
                .isInstanceOf(SqlGuardException.class);
    }

    @Test
    void rejectsDangerousFunctions() {
        assertThatThrownBy(() -> guard().guard("SELECT SLEEP(10) FROM products"))
                .isInstanceOf(SqlGuardException.class)
                .hasMessageContaining("SLEEP");
    }

    @Test
    void rejectsBlankSql() {
        assertThatThrownBy(() -> guard().guard("   ")).isInstanceOf(SqlGuardException.class);
    }

    @Test
    void returnsRawSqlWhenGuardIsDisabled() {
        SqlGuardProperties props = new SqlGuardProperties();
        props.setEnabled(false);
        String raw = "DELETE FROM products";
        assertThat(new SqlGuard(props).guard(raw)).isEqualTo(raw);
    }

    @Test
    void exposesExecutionLimitsToTheRepository() {
        SqlGuard sqlGuard = guard();
        assertThat(sqlGuard.maxRows()).isEqualTo(200);
        assertThat(sqlGuard.queryTimeoutSeconds()).isEqualTo(10);
    }
}
