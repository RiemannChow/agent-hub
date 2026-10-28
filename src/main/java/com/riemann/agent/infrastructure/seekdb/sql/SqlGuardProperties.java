package com.riemann.agent.infrastructure.seekdb.sql;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SQL 守卫配置。
 *
 * <p>LLM 生成的 SQL 属于「不可信外部输入」，因此在真正下发数据库前必须经过静态校验与改写。
 * 全部阈值都可配置，便于按环境收紧（生产）或放宽（本地演示）。</p>
 */
@Component
@ConfigurationProperties(prefix = "agent.sql-guard")
public class SqlGuardProperties {

    /** 是否启用守卫。默认开启；仅建议在本地排障时临时关闭，生产必须为 true。 */
    private boolean enabled = true;

    /**
     * 单次查询返回的最大行数。守卫会在最外层强制包一层 {@code LIMIT}，
     * 无论原 SQL 是否自带 LIMIT，都不会突破该上限（防止全表扫描把堆打爆）。
     */
    private int maxRows = 200;

    /** 单条 SQL 的执行超时（秒）。由 {@code PreparedStatement.setQueryTimeout} 下发到驱动。 */
    private int queryTimeoutSeconds = 10;

    /** 允许的 SQL 文本最大长度，防止超长语句拖垮解析器。 */
    private int maxLength = 4000;

    /**
     * 允许查询的表白名单（小写，可写成 {@code 表名} 或 {@code 库名.表名}）。
     * 未列出的一律拒绝——包括 {@code information_schema} 这类元数据表，
     * 以及 {@code conversation/message/memory} 这类存放其他用户隐私的会话表。
     */
    private List<String> allowedTables = new ArrayList<>(
            List.of("products", "users", "orders", "book_info"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = maxLength;
    }

    public List<String> getAllowedTables() {
        return allowedTables;
    }

    public void setAllowedTables(List<String> allowedTables) {
        this.allowedTables = allowedTables == null ? new ArrayList<>() : allowedTables;
    }

    /** 归一化后的白名单（去空白 + 小写），供 {@link SqlGuard} 比对。 */
    List<String> normalizedAllowedTables() {
        List<String> out = new ArrayList<>(allowedTables.size());
        for (String t : allowedTables) {
            if (t == null) continue;
            String v = t.trim().toLowerCase(Locale.ROOT);
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }
}
