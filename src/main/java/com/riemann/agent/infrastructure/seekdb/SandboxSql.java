package com.riemann.agent.infrastructure.seekdb;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * 沙箱写 SQL 的校验与表名改写（AST 级）。
 *
 * <p>改造前 {@code SeekDbConversationRepository.qualifyTables} 用 {@code
 * replaceAll("\\b(conversation|message)\\b", ...)} 做文本替换：既无法区分字符串字面量与
 * 真实表名，也只改写白名单里的表，其它表会按原语句落到主库。这里改为先用项目已有的 JSQLParser 解析成 AST，校验通过后再改 {@link Table#setSchemaName}，由
 * {@code toString()} 重新生成 SQL —— 字面量里的同名词不会被误改，非白名单表 / 非写语句 / 多语句全部显式拒绝。
 *
 * <p>与 {@code SqlGuard} 共用同一套解析约定（单语句 + 表名抽取），保持行为一致。
 */
final class SandboxSql {

    /** 允许进入沙箱的表；与 {@code WRITABLE_TABLES} 旧实现保持一致。 */
    static final Set<String> WRITABLE_TABLES = Set.of("conversation", "message");

    private SandboxSql() {}

    /**
     * 校验写 SQL 并改写为 {@code <sandbox>.<table>} 形式。
     *
     * @param sql 工具传入的原始写 SQL
     * @param sandbox 已通过 {@code isValidDbName} 校验的沙箱库名
     * @return 可直接在沙库执行的 SQL
     * @throws IllegalArgumentException 语句非法、表不在白名单、或涉及多张表
     */
    static String qualify(String sql, String sandbox) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("沙箱写 SQL 为空。");
        }
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException e) {
            throw new IllegalArgumentException(
                    "沙箱写 SQL 无法解析，只允许单条 INSERT / UPDATE / DELETE：" + rootMessage(e), e);
        }
        List<Statement> list = statements.getStatements();
        if (list == null || list.size() != 1) {
            throw new IllegalArgumentException(
                    "沙箱只允许单条写语句，检测到 " + (list == null ? 0 : list.size()) + " 条（不允许用分号拼接多条）。");
        }
        Statement statement = list.get(0);

        // 抽取 AST 中全部真实表名：既校验白名单，也拒绝 INSERT...SELECT 等多表语句
        // （多表时只改主表会留下落在主库的旁路）。
        List<String> tables = new TablesNamesFinder().getTableList(statement);
        if (tables.isEmpty() || tables.size() > 1) {
            throw new IllegalArgumentException("沙箱写 SQL 必须且只能涉及一张表，检测到：" + tables);
        }
        for (String table : tables) {
            if (!WRITABLE_TABLES.contains(normalize(table))) {
                throw new IllegalArgumentException(
                        "表 " + table + " 不在沙箱白名单 " + WRITABLE_TABLES + " 内，拒绝执行。");
            }
        }

        Table target;
        if (statement instanceof Insert insert) {
            target = insert.getTable();
        } else if (statement instanceof Update update) {
            target = update.getTable();
        } else if (statement instanceof Delete delete) {
            target = delete.getTable();
        } else {
            throw new IllegalArgumentException(
                    "沙箱只允许 INSERT / UPDATE / DELETE，收到 "
                            + statement.getClass().getSimpleName()
                            + "。");
        }
        target.setSchemaName(sandbox);
        return statement.toString();
    }

    /** 去掉引号与库名前缀，只留裸表名做白名单比对。 */
    private static String normalize(String table) {
        String v = table == null ? "" : table.trim().toLowerCase(Locale.ROOT);
        v = v.replace("`", "").replace("\"", "");
        int dot = v.lastIndexOf('.');
        if (dot >= 0) {
            v = v.substring(dot + 1);
        }
        return v;
    }

    private static String rootMessage(Throwable e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
