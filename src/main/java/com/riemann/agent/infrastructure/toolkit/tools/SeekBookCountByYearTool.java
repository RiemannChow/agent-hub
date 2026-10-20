package com.riemann.agent.infrastructure.toolkit.tools;

import com.riemann.agent.infrastructure.seekdb.SeekDbConversationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计各年份图书数量并以【图表】结构返回（前端渲染柱状图/饼图）。
 */
@Component
@Profile("seekdb")
public class SeekBookCountByYearTool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SeekDbConversationRepository seekDb;

    public SeekBookCountByYearTool(SeekDbConversationRepository seekDb) {
        this.seekDb = seekDb;
    }

    @Tool(name = "seek_book_count_by_year",
          description = "统计 demo_books.book_info 中各年份的图书数量，并以【图表】结构返回（前端会渲染成柱状图或饼图）。"
                  + "当用户想“按年份看图书数量分布”“用图表/图形展示每年的书”“可视化各年书籍数”“年份分布”“各年分别多少本”"
                  + "等需要“聚合 + 可视化”时用，不要再用 seek_book_search（那是明细检索，不是分组统计）。"
                  + "返回 type=chart 结构化数据，你只需基于图表写一句结论（如哪年最多/最少、整体趋势），"
                  + "禁止在回答里再用 markdown 罗列每年的数字。")
    public Mono<String> seekBookCountByYear(
            @ToolParam(name = "genre", description = "可选：仅统计某类型 'Fiction' 或 'Non Fiction'，留空则统计全部") String genre,
            @ToolParam(name = "kind", description = "图表类型：'bar'（柱状图，默认）或 'pie'（饼图）") String kind) {
        // 仅允许受限取值，杜绝 SQL 注入；模型误传其它字符串时退化为统计全部。
        String raw = (genre == null) ? "" : genre.trim();
        boolean valid = !raw.isBlank() && (raw.equalsIgnoreCase("Fiction") || raw.equalsIgnoreCase("Non Fiction"));
        final String genreLabel = valid ? raw : "";
        final String genreSql = valid
                ? (" WHERE genre = '" + (raw.equalsIgnoreCase("Non Fiction") ? "Non Fiction" : "Fiction") + "'")
                : "";
        String sql = "SELECT year, COUNT(*) AS cnt FROM demo_books.book_info"
                + genreSql
                + " GROUP BY year ORDER BY year";
        return seekDb.queryForMaps(sql).map(rows -> {
            List<String> labels = new ArrayList<>();
            List<Long> values = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                labels.add(String.valueOf(r.get("year")));
                Object v = r.get("cnt");
                long n = (v instanceof Number) ? ((Number) v).longValue() : 0L;
                values.add(n);
            }
            String kindNorm = (kind != null && kind.trim().equalsIgnoreCase("pie")) ? "pie" : "bar";
            Map<String, Object> ui = new LinkedHashMap<>();
            ui.put("type", "chart");
            ui.put("kind", kindNorm);
            ui.put("title", "各年份图书数量" + (genreLabel.isBlank() ? "（全部）" : "（" + genreLabel + "）"));
            ui.put("labels", labels);
            ui.put("values", values);
            ui.put("unit", "本");
            ui.put("note", "以上数据前端已渲染成图表卡片，禁止在回答里再用 markdown 罗列每年数字；"
                    + "只输出一句结论（哪年最多/最少、整体趋势即可）。");
            try {
                return JSON.writeValueAsString(ui);
            } catch (JsonProcessingException e) {
                return "{\"type\":\"chart\",\"error\":\"序列化失败\"}";
            }
        }).onErrorReturn("{\"type\":\"chart\",\"error\":\"统计失败\"}");
    }
}
