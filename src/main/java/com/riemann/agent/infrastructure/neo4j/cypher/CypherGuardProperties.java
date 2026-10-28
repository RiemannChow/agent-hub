package com.riemann.agent.infrastructure.neo4j.cypher;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 只读 Cypher 守卫配置（{@code agent.neo4j.guard.*}）。
 *
 * <p>改造前 {@code Neo4jRepository} 只用一个正则黑名单拦写操作，有两个真实问题：</p>
 * <ol>
 *   <li><b>漏</b>：{@code CALL apoc.load.json('http://169.254.169.254/latest/meta-data/')} 不含任何写关键字，
 *       正则完全放行 —— 这是一条从「模型能写 Cypher」直达「内网 SSRF」的路径；
 *       同理 {@code CALL apoc.cypher.run('...')} / {@code CALL apoc.periodic.iterate(...)}
 *       可以把任意语句藏在字符串里动态执行。</li>
 *   <li><b>误杀</b>：正则不区分字符串字面量与代码，{@code WHERE n.name = 'SET'} 会被当成写操作拒掉。</li>
 * </ol>
 *
 * <p>因此守卫改为「先剥掉字符串/注释，再按<b>过程白名单</b>判定」，白名单默认只含内置只读过程，
 * {@code apoc.*} 一律不在其中（要开必须显式配置，且应自行评估风险）。</p>
 */
@Component
@ConfigurationProperties(prefix = "agent.neo4j.guard")
public class CypherGuardProperties {

    /** 是否启用守卫。关掉仅建议本地排障——等于把任意 Cypher 执行权交给模型。 */
    private boolean enabled = true;

    /** 单条 Cypher 的字符上限，防止模型粘贴超长文本。 */
    private int maxLength = 4000;

    /** 结果最多回填多少行（超出截断并标注）。 */
    private int maxRows = 50;

    /**
     * 允许调用的过程白名单（小写、全名匹配）。
     *
     * <p>只放内置只读过程：查标签/关系类型/属性键/全文索引/库信息。
     * 刻意<b>不含</b> {@code apoc.*}：{@code apoc.load.*} 可发起外网请求（SSRF），
     * {@code apoc.cypher.run} / {@code apoc.periodic.iterate} 可绕过静态检查执行任意语句。</p>
     */
    private List<String> allowedProcedures = new ArrayList<>(List.of(
            "db.labels",
            "db.relationshiptypes",
            "db.propertykeys",
            "db.index.fulltext.querynodes",
            "db.index.fulltext.queryrelationships",
            "db.schema.visualization",
            "dbms.components",
            "dbms.procedures",
            "dbms.functions"
    ));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = maxLength;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public List<String> getAllowedProcedures() {
        return allowedProcedures;
    }

    public void setAllowedProcedures(List<String> allowedProcedures) {
        this.allowedProcedures = allowedProcedures == null ? new ArrayList<>() : allowedProcedures;
    }
}
