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
 * 对 SeekDB 执行通用 SELECT 问数 SQL，返回查询结果。
 * 结果为「两列：类别 + 数值」的聚合时，自动输出 A2UI type=chart（柱状图）结构。
 */
@Component
@Profile("seekdb")
public class SeekSqlQueryTool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SeekDbConversationRepository seekDb;

    public SeekSqlQueryTool(SeekDbConversationRepository seekDb) {
        this.seekDb = seekDb;
    }

    @Tool(name = "seek_sql_query",
          // 只读：SqlGuard 保证只放行单条 SELECT，因此可安全并行调度
          readOnly = true,
          description = "对 SeekDB 执行通用 SELECT 问数 SQL，返回查询结果。"
                  + "当用户要算结构化指标（如“各城市GMV”“最畅销商品”“各品类销量”“用户数”）等基于 products/users/orders 表的问数时用。"
                  + "若结果是「两列：类别 + 数值」（如 GROUP BY 聚合），会自动输出图表结构（前端渲染成柱状图）；"
                  + "多列明细仍以文本返回。图书按年份分布请用 seek_book_count_by_year（图书专属图表）。"
                  + "【约束】只能写单条标准 MySQL SELECT；可查询的表仅限 products / users / orders / book_info，"
                  + "禁止多语句、写操作、information_schema、FOR UPDATE 与 SLEEP/BENCHMARK 等函数；"
                  + "单次最多返回 200 行、执行超时 10 秒，因此聚合时请自行加 WHERE / GROUP BY 收敛结果集。")
    public Mono<String> seekSqlQuery(
            @ToolParam(name = "sql", description = "要执行的 SELECT SQL") String sql) {
        return seekDb.queryForMaps(sql).map(rows -> {
            // 两列 + 第二列为数值 -> 视为可图表化的聚合，输出 type=chart A2UI（柱状图）
            if (isCategoricalChart(rows)) {
                List<String> labels = new ArrayList<>();
                List<Number> values = new ArrayList<>();
                for (Map<String, Object> r : rows) {
                    List<String> keys = new ArrayList<>(r.keySet());
                    if (keys.size() < 2) continue;
                    labels.add(String.valueOf(r.get(keys.get(0))));
                    Object v = r.get(keys.get(1));
                    values.add(v instanceof Number ? (Number) v : 0);
                }
                Map<String, Object> ui = new LinkedHashMap<>();
                ui.put("type", "chart");
                ui.put("kind", "bar");
                ui.put("title", "SQL 聚合结果（" + labels.size() + " 组）");
                ui.put("labels", labels);
                ui.put("values", values);
                ui.put("unit", "");
                ui.put("note", "以上数据前端已渲染成图表卡片；如需补充，只用一句话结论即可。");
                try {
                    return JSON.writeValueAsString(ui);
                } catch (JsonProcessingException ignored) {
                    // 序列化失败则退化为文本
                }
            }
            // 否则：文本结果（保持 col=val | 格式）
            return formatRowsAsText(rows);
        }).onErrorResume(e -> Mono.just(describeFailure(sql, e)));
    }

    /**
     * 把执行/校验失败转成「模型能据此自我纠错」的提示。
     *
     * <p>SQL 守卫的拒绝原因（如「表 x 不在白名单内」「只允许单条语句」）本身就是给模型看的，
     * 直接回传能显著提高二次尝试的成功率；DB 报错则截断，避免把过长堆栈塞进上下文。</p>
     */
    private String describeFailure(String sql, Throwable e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        msg = msg.replaceAll("\\s+", " ").trim();
        if (msg.length() > 300) {
            msg = msg.substring(0, 300) + "...";
        }
        return "SQL 执行失败：" + msg + "。请修正后重试（原 SQL：" + sql + "）。";
    }

    /** 判断查询结果是否为「两列 + 第二列数值」的可图表化聚合。 */
    private boolean isCategoricalChart(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return false;
        for (Map<String, Object> r : rows) {
            if (r.size() != 2) return false;
            Object v = r.values().toArray()[1];
            if (!(v instanceof Number)) return false;
        }
        return true;
    }

    /** 把结构化结果格式化为文本样式（col=val | col=val，每行一条）。 */
    private String formatRowsAsText(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return "查询成功，但无结果返回。";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> r : rows) {
            for (Map.Entry<String, Object> e : r.entrySet()) {
                sb.append(e.getKey()).append("=").append(e.getValue()).append(" | ");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
