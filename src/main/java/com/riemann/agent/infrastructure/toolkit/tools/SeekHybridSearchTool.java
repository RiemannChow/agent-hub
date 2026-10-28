package com.riemann.agent.infrastructure.toolkit.tools;

import com.riemann.agent.domain.ScoredConversation;
import com.riemann.agent.infrastructure.seekdb.LocalEmbedder;
import com.riemann.agent.infrastructure.seekdb.SeekDbConversationRepository;
import com.riemann.agent.infrastructure.toolkit.ScoredResult;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 在 SeekDB 中做【语义混合检索】：向量近邻 + 全文 + 标量过滤，
 * 从电商客服/知识库对话中找出与用户问题最相关的片段。
 */
@Component
@Profile("seekdb")
public class SeekHybridSearchTool {

    private final SeekDbConversationRepository seekDb;

    public SeekHybridSearchTool(SeekDbConversationRepository seekDb) {
        this.seekDb = seekDb;
    }

    @Tool(name = "seek_hybrid_search",
          readOnly = true,
          description = "在 SeekDB 中做【语义混合检索】：向量近邻 + 全文 + 标量过滤，从电商客服/知识库对话中找出与用户问题最相关的片段。"
                  + "当用户问“之前有没有人问过XX”“知识库里关于退款/物流/保修怎么说的”“找找关于优惠的对话”等需要语义检索时用。")
    public Mono<List<ScoredResult>> seekHybridSearch(
            @ToolParam(name = "query_text", description = "用户的自然语言检索问题，如“苹果手机怎么退款”") String queryText,
            @ToolParam(name = "keyword", description = "可选：全文关键词过滤（不传则纯向量检索），如“退款”", required = false) String keyword,
            @ToolParam(name = "threshold", description = "可选：向量距离阈值，越小越相似，默认 1.4（取值 1.0~2.0）", required = false) Double threshold) {
        double th = (threshold == null || threshold < 1.0 || threshold > 2.0) ? 1.4 : threshold;
        String embedding = LocalEmbedder.embedToSql(queryText);
        return seekDb.hybridSearch(embedding, keyword, th, 5)
                     .map(list -> list.stream().map(ScoredResult::from).toList());
    }
}
