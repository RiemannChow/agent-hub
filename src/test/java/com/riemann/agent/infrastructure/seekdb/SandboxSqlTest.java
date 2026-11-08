package com.riemann.agent.infrastructure.seekdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 沙箱写 SQL 的 AST 级校验 / 改写测试（不连数据库）。
 *
 * <p>覆盖 issue #16 中「正则改写不可靠」与「非白名单表不隔离」两项的回归网。
 */
class SandboxSqlTest {

    private static final String SANDBOX = "sbx_web_user_s1";

    @Test
    void insertIsQualifiedAndStringLiteralIsNotTouched() {
        String sql =
                "INSERT INTO conversation (conversation_id, title) VALUES ('c1', 'conversation message')";
        String rewritten = SandboxSql.qualify(sql, SANDBOX);

        assertThat(rewritten).contains(SANDBOX + ".conversation");
        // AST 改写不会碰字符串字面量里的同名词（旧的正则 replaceAll 会误改）
        assertThat(rewritten).contains("'conversation message'");
    }

    @Test
    void updateIsQualified() {
        String sql = "UPDATE message SET content = 'conversation' WHERE conversation_id = 'c1'";
        String rewritten = SandboxSql.qualify(sql, SANDBOX);

        assertThat(rewritten).contains(SANDBOX + ".message");
        assertThat(rewritten).contains("'conversation'");
    }

    @Test
    void deleteIsQualified() {
        String sql = "DELETE FROM conversation WHERE conversation_id = 'c1'";
        assertThat(SandboxSql.qualify(sql, SANDBOX)).contains(SANDBOX + ".conversation");
    }

    @Test
    void rejectsTableOutsideWhitelist() {
        assertThatThrownBy(
                        () ->
                                SandboxSql.qualify(
                                        "UPDATE memory SET content = 'x' WHERE id = '1'", SANDBOX))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("白名单");
    }

    @Test
    void rejectsNonWriteStatement() {
        assertThatThrownBy(() -> SandboxSql.qualify("SELECT * FROM conversation", SANDBOX))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INSERT / UPDATE / DELETE");
    }

    @Test
    void rejectsMultipleStatements() {
        assertThatThrownBy(
                        () ->
                                SandboxSql.qualify(
                                        "DELETE FROM conversation WHERE id = '1'; DROP TABLE conversation",
                                        SANDBOX))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("单条");
    }

    @Test
    void rejectsMultiTableStatement() {
        // 多表语句若只改主表，副表会落在主库 —— 直接拒绝而不是留下旁路
        assertThatThrownBy(
                        () ->
                                SandboxSql.qualify(
                                        "INSERT INTO conversation SELECT * FROM message", SANDBOX))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("一张表");
    }

    @Test
    void rejectsBlankSql() {
        assertThatThrownBy(() -> SandboxSql.qualify("  ", SANDBOX))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("为空");
    }
}
