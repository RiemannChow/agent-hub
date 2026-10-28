package com.riemann.agent.infrastructure.neo4j.cypher;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只读 Cypher 守卫：把「模型生成的一段文本」判定为「可以安全执行的只读查询」。
 *
 * <h3>为什么不能只靠一条正则</h3>
 * <p>原实现是一条 {@code \b(CREATE|MERGE|SET|...)\b} 黑名单，两个方向都不对：</p>
 * <ul>
 *   <li><b>拦不住</b>：{@code CALL apoc.load.json('http://169.254.169.254/...')} 一个写关键字都没有，
 *       却能让数据库替模型发一次内网请求（SSRF）；{@code apoc.cypher.run('MATCH (n) DETACH DELETE n')}
 *       把写操作藏在字符串里动态执行，也能绕过任何「看关键字」的检查。</li>
 *   <li><b>误杀</b>：正则不区分代码与字符串，{@code WHERE n.name = 'SET'} 会被当成写操作拒绝，
 *       模型只能反复改写、反复被拒。</li>
 * </ul>
 *
 * <h3>本实现的三层判定</h3>
 * <ol>
 *   <li><b>剥壳</b>：把字符串字面量、反引号标识符、{@code //} 与 {@code /* *}{@code /} 注释
 *       统一替换为占位符，得到一份「只剩代码骨架」的文本。既避免误杀，也避免把关键字藏进字符串。</li>
 *   <li><b>结构</b>：必须是单条语句；必须以读子句开头；骨架里不得出现写关键字。</li>
 *   <li><b>过程白名单</b>：任何 {@code CALL <proc>} 的 {@code proc} 必须在白名单里，
 *       且 {@code CALL} 总数必须能被「过程调用 + 内联子查询 {@code CALL {}」完全解释——
 *       出现无法识别的 CALL 形式直接拒绝，不给「用新语法绕过去」留口子。</li>
 * </ol>
 *
 * <p>守卫之外还有第二道防线：{@code Neo4jRepository} 用 {@code session.executeRead(...)} 执行，
 * 走读事务（{@code AccessMode.READ}）。静态检查 + 读事务，任一失效都还有另一层。</p>
 */
public class CypherGuard {

    /** 剥壳后的骨架里，写关键字一律拒绝。前面加 {@code (?<![.\w])} 避免误伤 {@code n.set} 这类属性名。 */
    private static final Pattern WRITE_KEYWORD = Pattern.compile(
            "(?i)(?<![.\\w])(CREATE|MERGE|SET|DELETE|DETACH|REMOVE|DROP|FOREACH|LOAD|INSERT|UPDATE|"
                    + "GRANT|REVOKE|ALTER|USING\\s+PERIODIC)\\b");

    /** 允许作为语句开头的读子句。{@code CALL} 允许开头，但其过程仍要过白名单。 */
    private static final Pattern READ_START = Pattern.compile(
            "(?i)^(MATCH|OPTIONAL\\s+MATCH|WITH|RETURN|UNWIND|CALL)\\b");

    /** {@code CALL proc.name} —— 只认标识符形式，捕获过程全名。 */
    private static final Pattern CALL_PROC = Pattern.compile(
            "(?i)\\bCALL\\s+([A-Za-z_][A-Za-z0-9_.]*)");

    /** 内联子查询 {@code CALL { ... }}。 */
    private static final Pattern CALL_SUBQUERY = Pattern.compile("(?i)\\bCALL\\s*\\{");

    /** 任意 {@code CALL} 词元，用来做「数量对账」。 */
    private static final Pattern CALL_TOKEN = Pattern.compile("(?i)\\bCALL\\b");

    /** 判定结果：{@code allowed=false} 时 {@code reason} 是给模型看的可执行建议。 */
    public record Verdict(boolean allowed, String reason) {

        static Verdict ok() {
            return new Verdict(true, null);
        }

        static Verdict deny(String reason) {
            return new Verdict(false, reason);
        }
    }

    private final CypherGuardProperties props;
    private final Set<String> allowedProcedures;

    public CypherGuard(CypherGuardProperties props) {
        this.props = props;
        Set<String> set = new LinkedHashSet<>();
        for (String p : props.getAllowedProcedures()) {
            if (p != null && !p.isBlank()) {
                set.add(p.trim().toLowerCase(Locale.ROOT));
            }
        }
        this.allowedProcedures = set;
    }

    /** 判定一条 Cypher 是否可以安全执行（纯函数，不碰数据库）。 */
    public Verdict check(String cypher) {
        if (!props.isEnabled()) {
            return Verdict.ok();
        }
        if (cypher == null || cypher.isBlank()) {
            return Verdict.deny("Cypher 为空");
        }
        if (cypher.length() > props.getMaxLength()) {
            return Verdict.deny("Cypher 过长（" + cypher.length() + " 字符，上限 " + props.getMaxLength() + "）");
        }

        String skeleton = skeleton(cypher).trim();
        if (skeleton.isEmpty()) {
            return Verdict.deny("Cypher 剥掉注释/字符串后为空");
        }

        // 1) 单条语句：允许结尾一个分号，中间不允许
        String body = skeleton.replaceAll(";+\\s*$", "");
        if (body.contains(";")) {
            return Verdict.deny("只允许单条语句（检测到分号分隔的多条语句）");
        }

        // 2) 写关键字（先判这个：直接告诉模型「你写了 CREATE」，比「必须以 MATCH 开头」更有指导性）
        Matcher w = WRITE_KEYWORD.matcher(body);
        if (w.find()) {
            return Verdict.deny("检测到写操作关键字 " + w.group(1).toUpperCase(Locale.ROOT)
                    + "，本工具只接受只读查询");
        }

        // 3) 必须从读子句开始
        if (!READ_START.matcher(body).find()) {
            return Verdict.deny("只允许以 MATCH / OPTIONAL MATCH / WITH / RETURN / UNWIND / CALL 开头的只读查询");
        }

        // 4) 过程白名单 + CALL 数量对账
        List<String> procs = new ArrayList<>();
        Matcher p = CALL_PROC.matcher(body);
        while (p.find()) {
            procs.add(p.group(1).toLowerCase(Locale.ROOT));
        }
        int callTokens = count(CALL_TOKEN, body);
        int subqueries = count(CALL_SUBQUERY, body);
        if (callTokens != procs.size() + subqueries) {
            return Verdict.deny("无法识别的 CALL 形式（CALL 出现 " + callTokens + " 次，仅解析出 "
                    + procs.size() + " 个过程调用与 " + subqueries + " 个内联子查询）");
        }
        for (String proc : procs) {
            if (!allowedProcedures.contains(proc)) {
                return Verdict.deny("过程 " + proc + " 不在只读白名单内（可用：" + String.join(", ", allowedProcedures)
                        + "）。apoc.* 默认全部禁用：apoc.load.* 可发起外部请求，apoc.cypher.run 可绕过静态检查");
            }
        }
        return Verdict.ok();
    }

    /**
     * 剥壳：字符串 → {@code ''}、反引号标识符 → {@code ``}、注释 → 空格。
     *
     * <p>保留占位符而不是直接删掉，是为了不把 {@code n.name = 'x' AND m.y = 'z'} 粘成
     * {@code n.name = AND m.y =} 这种会误判的文本。</p>
     */
    static String skeleton(String cypher) {
        StringBuilder out = new StringBuilder(cypher.length());
        int i = 0;
        int n = cypher.length();
        while (i < n) {
            char c = cypher.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(cypher, i, c);
                out.append("''");
                continue;
            }
            if (c == '`') {
                i = skipQuoted(cypher, i, '`');
                out.append("``");
                continue;
            }
            if (c == '/' && i + 1 < n && cypher.charAt(i + 1) == '/') {
                while (i < n && cypher.charAt(i) != '\n') {
                    i++;
                }
                out.append(' ');
                continue;
            }
            if (c == '/' && i + 1 < n && cypher.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(cypher.charAt(i) == '*' && cypher.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                out.append(' ');
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 从起始引号位置跳到闭合引号之后；支持反斜杠转义与 Cypher 的成对引号转义。 */
    private static int skipQuoted(String s, int start, char quote) {
        int i = start + 1;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                // Cypher 里 '' 表示一个转义的单引号，属于字符串内容，不能当闭合
                if (i + 1 < n && s.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    private static int count(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int c = 0;
        while (m.find()) {
            c++;
        }
        return c;
    }
}
