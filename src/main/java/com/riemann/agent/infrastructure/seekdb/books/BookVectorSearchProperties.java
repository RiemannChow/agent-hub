package com.riemann.agent.infrastructure.seekdb.books;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 向量支路检索的可选保护配置（{@code agent.books.vector.*}）。
 *
 * <p>背景：{@link BookRepository#vectorHits} 目前对全表逐行计算 {@code l2_distance} 后排序，
 * 没有向量索引也没有扫描上限，数据量上来会直接把 DB 打慢。这里提供两个**默认关闭**的出口， 让部署者可以在不改代码的前提下收紧：
 *
 * <ul>
 *   <li>{@code max-scan-rows}：把参与距离计算的行数限制在有界候选集内（保护 / 降级模式）；
 *   <li>{@code approximate-clause}：追加 DB 特定的近似近邻提示，配合 {@code sql/vector-index-seekdb.sql} 建的向量索引走
 *       ANN。语法随 SeekDB / OceanBase 版本而异，因此做成配置而不是硬编码。
 * </ul>
 *
 * <p>两者默认都为空 / 0，即**完全保持修复前的行为**，不影响现有 demo 与 CI。
 */
@Component
@ConfigurationProperties(prefix = "agent.books.vector")
public class BookVectorSearchProperties {

    /** 配置值直接拼进 SQL，必须限制长度，避免误配置注入超长语句。 */
    private static final int MAX_CLAUSE_LENGTH = 64;

    /** 单次向量检索允许参与精确距离计算的最大行数；0（默认）= 不限制，保持全表精确语义。 */
    private int maxScanRows = 0;

    /** DB 特定的近似近邻提示，追加在 {@code ORDER BY distance ASC} 之后；留空 = 精确检索。 */
    private String approximateClause = "";

    public int getMaxScanRows() {
        return maxScanRows;
    }

    public void setMaxScanRows(int maxScanRows) {
        this.maxScanRows = maxScanRows;
    }

    public String getApproximateClause() {
        return approximateClause;
    }

    public void setApproximateClause(String approximateClause) {
        this.approximateClause = approximateClause;
    }

    /**
     * 归一化并校验近似提示：只允许字母 / 数字 / 下划线 / 空格。
     *
     * <p>该值会被拼进 SQL 的 {@code ORDER BY} 子句，属于「配置即代码」的输入， 必须白名单校验，否则一条带分号的配置就能变成 SQL 注入。
     */
    String normalizedApproximateClause() {
        String v = approximateClause == null ? "" : approximateClause.trim();
        if (v.isEmpty()) {
            return "";
        }
        if (v.length() > MAX_CLAUSE_LENGTH || !v.matches("[A-Za-z0-9_ ]+")) {
            throw new IllegalArgumentException(
                    "agent.books.vector.approximate-clause 只允许字母/数字/下划线/空格，"
                            + "且长度不超过 "
                            + MAX_CLAUSE_LENGTH
                            + "："
                            + v);
        }
        return v;
    }
}
