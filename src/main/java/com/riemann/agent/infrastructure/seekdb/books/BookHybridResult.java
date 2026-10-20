package com.riemann.agent.infrastructure.seekdb.books;

import java.util.List;
import java.util.Map;

/**
 * 图书混合检索的完整结果，承载「一次查询 = 向量 + 全文 + 标量 三路融合」的 粗排 + 精排 全貌，
 * 供图书检索工具组装成 A2UI {@code type=hybrid} 卡片展示给用户。
 *
 * <ul>
 *   <li>{@link #vectorCandidates}：粗排-向量支路候选（按 L2 距离升序，未提供语义查询时为空）；</li>
 *   <li>{@link #fulltextCandidates}：粗排-全文支路候选（按相关性降序，未提供关键词时为空）；</li>
 *   <li>{@link #fused}：精排-RRF 融合后的 TopN 最终列表；</li>
 *   <li>{@link #scalarOnly}：是否退化为纯标量过滤（既无语义查询也无关键词）。</li>
 * </ul>
 */
public record BookHybridResult(
        String queryText,
        String keyword,
        Map<String, Object> scalarFilter,
        int topN,
        List<BookHit> vectorCandidates,
        List<BookHit> fulltextCandidates,
        List<FusedBook> fused,
        boolean scalarOnly) {
}
