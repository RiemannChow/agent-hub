package com.riemann.agent.infrastructure.toolkit.tools;

import com.riemann.agent.infrastructure.seekdb.books.BookHit;
import com.riemann.agent.infrastructure.seekdb.books.BookHybridResult;
import com.riemann.agent.infrastructure.seekdb.books.FusedBook;
import com.riemann.agent.infrastructure.seekdb.books.HybridSearchService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在 SeekDB 的 demo_books.book_info 中做【图书混合检索】：
 * 向量近邻 + 全文关键词 + 标量过滤，返回 A2UI type=hybrid 结构供前端渲染。
 */
@Component
@Profile("seekdb")
public class SeekBookSearchTool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HybridSearchService bookSearch;

    public SeekBookSearchTool(HybridSearchService bookSearch) {
        this.bookSearch = bookSearch;
    }

    @Tool(name = "seek_book_search",
          readOnly = true,
          description = "在 SeekDB 的 demo_books.book_info 中做【图书混合检索】：向量近邻 + 全文关键词 + 标量过滤"
                  + "（评分/价格/年份/类型/评论数），从 550 本 Amazon 畅销书中找出用户感兴趣的书。"
                  + "当用户问“找本关于XX的书”“有没有科普书”“评分高的书”“便宜的非虚构书”“XX年之后的小说”"
                  + "“评论数>=10000的书籍”“评论多的书”等涉及图书检索时用。"
                  + "返回结果是结构化 JSON（前端会直接渲染成表格卡片），你只需基于结果用一句中文总结结论，不要重复罗列表格内容。")
    public Mono<String> seekBookSearch(
            @ToolParam(name = "query_text", description = "用于向量语义检索的自然语言，如“self improvement motivation success”；不想做语义检索时可留空")
            String queryText,
            @ToolParam(name = "keyword", description = "可选：书名/作者全文关键词过滤，如“novel”", required = false)
            String keyword,
            @ToolParam(name = "min_rating", description = "可选：最低评分过滤（0~5）。不传或 <=0 表示不限制", required = false)
            Double minRating,
            @ToolParam(name = "max_price", description = "可选：最高价格过滤。不传或 <=0 表示不限制", required = false)
            Double maxPrice,
            @ToolParam(name = "min_year", description = "可选：最早出版年份过滤。不传或 <=0 表示不限制", required = false)
            Integer minYear,
            @ToolParam(name = "min_reviews", description = "可选：最少评论数过滤（评论数 >= 该值）。不传或 <=0 表示不限制，如 10000", required = false)
            Double minReviews,
            @ToolParam(name = "genre", description = "可选：类型过滤，只能是 'Fiction' 或 'Non Fiction'，不传表示不限制", required = false)
            String genre,
            @ToolParam(name = "top_n", description = "可选：返回条数，默认 5，上限 20", required = false)
            Integer topN) {
        Map<String, Object> where = new LinkedHashMap<>();
        // 全部可选参数统一按「null 或非正数 = 不限制」处理：
        // 这样模型少传、传 0、传 null 都不会改变语义，也就不会因为「参数没填全」被框架拒掉。
        if (minRating != null && minRating > 0) {
            where.put("user_rating", Map.of("$gte", minRating));
        }
        if (maxPrice != null && maxPrice > 0) {
            where.put("price", Map.of("$lte", maxPrice));
        }
        if (minYear != null && minYear > 0) {
            where.put("year", Map.of("$gte", minYear));
        }
        if (minReviews != null && minReviews > 0) {
            where.put("reviews", Map.of("$gte", minReviews.intValue()));
        }
        if (genre != null && !genre.isBlank()) {
            where.put("genre", genre);
        }
        int n = (topN == null || topN <= 0) ? 5 : Math.min(topN, 20);
        String kw = (keyword == null) ? "" : keyword;
        String qt = (queryText == null) ? "" : queryText;

        // 一次调用内融合「向量 + 全文 + 标量」三路，返回粗排双支路候选 + RRF 精排结果。
        int fetch = Math.max(n * 4, 50);
        BookHybridResult result = bookSearch.hybridSearch(qt, kw, where, fetch);

        // 组装 A2UI type=hybrid 结构：前端识别后渲染成“输入摘要 + 粗排双面板 + 精排列表”卡片，
        // 模型只需读 note 写一句结论，不再自己拼 markdown 表格或罗列分数。
        try {
            return Mono.just(JSON.writeValueAsString(buildHybridUi(result, n)));
        } catch (JsonProcessingException e) {
            return Mono.just("检索到 " + result.fused().size() + " 本图书。");
        }
    }

    /** 把混合检索结果组装成 A2UI type=hybrid 卡片结构。 */
    private Map<String, Object> buildHybridUi(BookHybridResult r, int n) {
        List<Map<String, Object>> vectorPanel = coarsePanel(r.vectorCandidates(), false);
        List<Map<String, Object>> fulltextPanel = coarsePanel(r.fulltextCandidates(), true);

        List<Map<String, Object>> fused = new ArrayList<>();
        for (FusedBook f : r.fused()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", f.rank());
            m.put("name", f.book().name());
            m.put("author", f.book().author());
            m.put("rating", f.book().userRating());
            m.put("reviews", f.book().reviews());
            m.put("price", f.book().price());
            m.put("year", f.book().year());
            m.put("genre", f.book().genre());
            m.put("fused_score", round(f.fusedScore(), 4));
            List<String> branches = new ArrayList<>();
            if (f.fromVector()) {
                branches.add("vector");
            }
            if (f.fromFulltext()) {
                branches.add("fulltext");
            }
            m.put("branches", branches);
            if (f.vectorScore() != null) {
                m.put("vector_score", round(f.vectorScore(), 4));
            }
            if (f.fulltextScore() != null) {
                m.put("fulltext_score", round(f.fulltextScore(), 4));
            }
            fused.add(m);
        }

        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("type", "hybrid");
        ui.put("title", "图书混合检索 · 粗排 + 精排（" + fused.size() + " 本）");
        ui.put("input", buildInputSummary(r, n));
        ui.put("coarse", Map.of("vector", vectorPanel, "fulltext", fulltextPanel));
        ui.put("fused", fused);
        ui.put("note", "以上混合检索卡片（输入摘要 + 粗排双支路 + RRF 精排列表）前端已渲染，"
                + "严格禁止在你的回答里再用 markdown 罗列分数或书名；只输出一句自然语言结论即可。");
        return ui;
    }

    /** 粗排面板：取某支路前 8 条（按书名去重），标注该支路的分数（L2 距离 / 全文相关性）。 */
    private List<Map<String, Object>> coarsePanel(List<BookHit> hits, boolean fulltext) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (BookHit h : hits) {
            if (out.size() >= 8) {
                break;
            }
            if (!seen.add(h.book().name())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", h.book().name());
            m.put("author", h.book().author());
            m.put("rating", h.book().userRating());
            if (fulltext && h.fulltextScore() != null) {
                m.put("relevance", round(h.fulltextScore(), 4));
            }
            if (!fulltext && h.vectorScore() != null) {
                m.put("l2_distance", round(h.vectorScore(), 4));
            }
            out.add(m);
        }
        return out;
    }

    /** 输入摘要：把一次混合查询的三路输入（语义/全文/标量）结构化，供卡片顶部展示。 */
    private Map<String, Object> buildInputSummary(BookHybridResult r, int n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("query_text", r.queryText().isBlank() ? "(无，纯标量/全文)" : r.queryText());
        m.put("keyword", r.keyword().isBlank() ? "(无)" : r.keyword());
        m.put("scalar_filter", formatWhere(r.scalarFilter()));
        m.put("top_n", n);
        return m;
    }

    /** 把标量过滤 Map 渲染成中文可读条件串，如“评分≥4.5 · 类型=Fiction”。 */
    private String formatWhere(Map<String, Object> where) {
        if (where == null || where.isEmpty()) {
            return "(无)";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> e : where.entrySet()) {
            if ("$and".equals(e.getKey()) && e.getValue() instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> sub = (Map<String, Object>) m;
                        parts.add(formatWhere(sub));
                    }
                }
            } else {
                parts.add(formatScalar(e.getKey(), e.getValue()));
            }
        }
        return String.join(" · ", parts);
    }

    private String formatScalar(String field, Object cond) {
        if (cond instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) m;
            List<String> ops = new ArrayList<>();
            for (Map.Entry<String, Object> op : map.entrySet()) {
                switch (op.getKey()) {
                    case "$gte" -> ops.add(field + "≥" + op.getValue());
                    case "$lte" -> ops.add(field + "≤" + op.getValue());
                    case "$gt"  -> ops.add(field + ">" + op.getValue());
                    case "$lt"  -> ops.add(field + "<" + op.getValue());
                    case "$eq"  -> ops.add(field + "=" + op.getValue());
                    case "$ne"  -> ops.add(field + "≠" + op.getValue());
                    default     -> ops.add(field + " " + op.getKey() + " " + op.getValue());
                }
            }
            return String.join(",", ops);
        }
        return field + "=" + cond;
    }

    private double round(double v, int scale) {
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }
}
