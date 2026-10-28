package com.riemann.agent.infrastructure.seekdb.sql;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LLM 生成 SQL 的静态守卫。
 *
 * <p><b>为什么需要它：</b>{@code seek_sql_query} 工具把模型产出的 SQL 直接交给 JDBC 执行。
 * 模型输出属于不可信外部输入，而 JDBC 驱动只保证「{@code executeQuery} 不接受 DML」这一条，
 * 既不限制查询范围，也不限制返回体量，更不限制执行时长。因此这里做四层校验 + 一层改写：</p>
 *
 * <ol>
 *   <li><b>单语句</b>：解析后必须恰好一条，杜绝 {@code SELECT 1; DROP TABLE x} 这类多语句注入；</li>
 *   <li><b>只读</b>：AST 顶层必须是 {@code Select}，且不允许 {@code INTO OUTFILE/DUMPFILE}、
 *       {@code FOR UPDATE} 等写文件/加锁读语法；</li>
 *   <li><b>表白名单</b>：从 AST 抽取全部真实表名，逐一比对白名单，
 *       拦住 {@code information_schema} 与存放其他用户隐私的会话表；</li>
 *   <li><b>危险函数</b>：拒绝 {@code SLEEP/BENCHMARK/LOAD_FILE} 等可被用来打挂库或读文件的函数；</li>
 *   <li><b>强制行数上限</b>：没有 LIMIT 就补一个，超过上限就收紧到上限，且不破坏原查询的
 *       {@code ORDER BY} 语义（详见 {@link #enforceRowLimit}）。</li>
 * </ol>
 *
 * <p>执行侧的兜底（{@code setQueryTimeout} / {@code setMaxRows}）在仓储层设置，与本类互为纵深。</p>
 */
@Component
public class SqlGuard {

    private static final Logger log = LoggerFactory.getLogger(SqlGuard.class);

    /**
     * 危险关键字/函数（在归一化后的大写文本上做包含判断）。
     * 采用「宁可误拒」策略：命中即拒绝，让模型改写后重试，而不是放行后再补救。
     */
    private static final List<String> DANGEROUS_TOKENS = List.of(
            "INTO OUTFILE",
            "INTO DUMPFILE",
            "FOR UPDATE",
            "LOCK IN SHARE MODE",
            "LOAD_FILE(",
            "SLEEP(",
            "BENCHMARK(",
            "INFORMATION_SCHEMA",
            "PERFORMANCE_SCHEMA",
            "MYSQL.USER",
            "MYSQL.DB",
            "MYSQL.PASSWORD"
    );

    private final SqlGuardProperties props;

    public SqlGuard(SqlGuardProperties props) {
        this.props = props;
    }

    /**
     * 校验并改写待执行的 SQL。
     *
     * @param rawSql 模型生成的原始 SQL
     * @return 可直接下发的安全 SQL（已按上限约束行数）
     * @throws SqlGuardException 校验不通过；message 可直接回给模型作为纠错提示
     */
    public String guard(String rawSql) {
        if (!props.isEnabled()) {
            log.warn("SQL 守卫已被关闭（agent.sql-guard.enabled=false），本次 SQL 未经校验直接执行");
            return rawSql;
        }
        if (rawSql == null || rawSql.isBlank()) {
            throw new SqlGuardException("SQL 为空，请给出一条完整的 SELECT 语句。");
        }

        String sql = stripTrailingSemicolons(rawSql);
        if (sql.length() > props.getMaxLength()) {
            throw new SqlGuardException(
                    "SQL 过长（" + sql.length() + " 字符，上限 " + props.getMaxLength() + "），请精简后重试。");
        }

        Statement statement = parseSingleStatement(sql);

        if (!(statement instanceof Select select)) {
            throw new SqlGuardException("只允许 SELECT 查询，当前语句类型为 " + statement.getClass().getSimpleName() + "。");
        }

        // 写文件 / 加锁读：这些语法属于 Select 但会带来副作用，直接拒绝
        if (select instanceof PlainSelect plain) {
            if (plain.getIntoTables() != null && !plain.getIntoTables().isEmpty()) {
                throw new SqlGuardException("不允许 SELECT ... INTO OUTFILE/DUMPFILE（写文件）。");
            }
            if (plain.getIntoTempTable() != null) {
                throw new SqlGuardException("不允许 SELECT ... INTO TEMPORARY TABLE（建临时表）。");
            }
            if (plain.getForUpdateTable() != null) {
                throw new SqlGuardException("不允许加锁读（FOR UPDATE）。");
            }
        }
        if (select.getForClause() != null) {
            throw new SqlGuardException("不允许加锁读（FOR UPDATE / LOCK IN SHARE MODE）。");
        }

        rejectDangerousTokens(sql);
        checkTableWhitelist(statement);

        String limited = enforceRowLimit(select, sql);
        log.debug("SQL 守卫通过：{} -> {}", abbreviate(sql), abbreviate(limited));
        return limited;
    }

    /**
     * 强制行数上限。
     *
     * <p>刻意<b>不用</b>「外层包一层 {@code SELECT * FROM (...)}」的写法：派生表会丢掉原查询的
     * {@code ORDER BY} 语义（MySQL 不保证派生表输出的顺序），"按年份排序的图表""Top N" 这类查询
     * 会因此错序。改为按情况就地处理：</p>
     *
     * <ul>
     *   <li>原 SQL 没有 LIMIT：在末尾<b>另起一行</b>追加 {@code LIMIT n}——
     *       换行是必要的，否则原 SQL 末尾的 {@code -- 行注释} 会把新加的 LIMIT 一起注释掉；</li>
     *   <li>已有 LIMIT 且不超过上限：原样返回；</li>
     *   <li>已有 LIMIT 且超过上限（或无法静态求值）：用 AST 改写成上限值，并保留 OFFSET。</li>
     * </ul>
     */
    private String enforceRowLimit(Select select, String sql) {
        int max = props.getMaxRows();
        Limit existing = select.getLimit();
        if (existing == null) {
            return sql + "\nLIMIT " + max;
        }
        long declared = staticRowCount(existing);
        if (declared >= 0 && declared <= max) {
            return sql;
        }
        Limit tightened = new Limit()
                .withRowCount(new LongValue(max))
                .withOffset(existing.getOffset());
        select.setLimit(tightened);
        return select.toString();
    }

    /** 尝试静态求出 LIMIT 的行数；无法求值（如写成表达式/占位符）时返回 -1，表示必须收紧。 */
    private static long staticRowCount(Limit limit) {
        if (limit.getRowCount() instanceof LongValue lv) {
            return lv.getValue();
        }
        return -1;
    }

    /** 单次查询返回的最大行数（仓储层据此设置 {@code Statement.setMaxRows}）。 */
    public int maxRows() {
        return props.getMaxRows();
    }

    /** 单条 SQL 的执行超时秒数（仓储层据此设置 {@code Statement.setQueryTimeout}）。 */
    public int queryTimeoutSeconds() {
        return props.getQueryTimeoutSeconds();
    }

    // ===================== 内部步骤 =====================

    private Statement parseSingleStatement(String sql) {
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException e) {
            throw new SqlGuardException(
                    "SQL 无法解析，请只使用标准 MySQL SELECT 语法（不支持存储过程、注释块等）："
                            + rootMessage(e));
        }
        List<Statement> list = statements.getStatements();
        if (list == null || list.isEmpty()) {
            throw new SqlGuardException("SQL 解析结果为空，请给出一条完整的 SELECT 语句。");
        }
        if (list.size() > 1) {
            throw new SqlGuardException("只允许单条语句，检测到 " + list.size() + " 条（不允许用分号拼接多条）。");
        }
        return list.get(0);
    }

    private void rejectDangerousTokens(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        for (String token : DANGEROUS_TOKENS) {
            if (upper.contains(token)) {
                throw new SqlGuardException("SQL 含有被禁止的语法/函数：" + token
                        + "。请只做普通的 SELECT 聚合查询。");
            }
        }
    }

    private void checkTableWhitelist(Statement statement) {
        List<String> tables;
        try {
            tables = new TablesNamesFinder().getTableList(statement);
        } catch (RuntimeException e) {
            throw new SqlGuardException("无法识别 SQL 中引用的表，已拒绝执行：" + rootMessage(e));
        }
        if (tables == null || tables.isEmpty()) {
            throw new SqlGuardException("未能从 SQL 中识别出任何表名，请显式写明 FROM 的表。");
        }
        List<String> allowed = props.normalizedAllowedTables();
        // CTE（WITH x AS ...）里的 x 会被当成表名扫出来，需先收集起来豁免，否则合法查询会被误拒
        List<String> cteNames = collectCteNames(statement);
        for (String rawTable : tables) {
            String table = rawTable == null ? "" : rawTable.trim().toLowerCase(Locale.ROOT);
            if (cteNames.contains(table)) continue;
            if (!isAllowed(table, allowed)) {
                throw new SqlGuardException("表 `" + rawTable + "` 不在可查询白名单内。可查询的表："
                        + String.join(", ", allowed) + "。");
            }
        }
    }

    /** 收集 WITH 子句定义的临时结果集名（小写），用于表白名单豁免。 */
    private List<String> collectCteNames(Statement statement) {
        if (!(statement instanceof Select select)) return List.of();
        List<WithItem> withItems = select.getWithItemsList();
        if (withItems == null || withItems.isEmpty()) return List.of();
        List<String> names = new ArrayList<>(withItems.size());
        for (WithItem item : withItems) {
            if (item.getAlias() != null && item.getAlias().getName() != null) {
                names.add(item.getAlias().getName().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private boolean isAllowed(String table, List<String> allowed) {
        if (table.isEmpty()) return false;
        if (allowed.contains(table)) return true;
        // 白名单写成裸表名时，允许带任意库名前缀（如 demo.products / demo_books.book_info）
        int dot = table.lastIndexOf('.');
        if (dot < 0) return false;
        String bare = table.substring(dot + 1);
        return allowed.contains(bare);
    }

    private static String stripTrailingSemicolons(String sql) {
        String s = sql.trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        if (msg == null || msg.isBlank()) {
            return cur.getClass().getSimpleName();
        }
        // 解析器报错可能带换行/超长上下文，截断后回给模型
        String flat = msg.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
    }

    private static String abbreviate(String sql) {
        String flat = sql.replaceAll("\\s+", " ").trim();
        return flat.length() > 120 ? flat.substring(0, 120) + "..." : flat;
    }
}
