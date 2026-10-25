package com.riemann.agent.presentation.a2a;

import com.riemann.agent.application.AgentPersonaProvider;
import com.riemann.agent.presentation.a2a.protocol.AgentCapabilities;
import com.riemann.agent.presentation.a2a.protocol.AgentCard;
import com.riemann.agent.presentation.a2a.protocol.AgentSkill;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 构造本智能体的 A2A Agent Card。
 *
 * <p>姓名/描述取自 {@link AgentPersonaProvider}（随 profile 切换而变，如电商问数 / OceanBase 知识图谱）；
 * 技能列表为与后端工具对应的静态描述；能力声明 streaming=true（支持 message/stream）。</p>
 */
@Component
public class A2aAgentCardProvider {

    private final AgentPersonaProvider personaProvider;
    private final String publicUrl;
    private final String version;

    public A2aAgentCardProvider(AgentPersonaProvider personaProvider,
                                @Value("${agent.a2a.public-url:http://localhost:8080}") String publicUrl,
                                @Value("${agent.a2a.version:1.0.0}") String version) {
        this.personaProvider = personaProvider;
        this.publicUrl = publicUrl;
        this.version = version;
    }

    public AgentCard build() {
        var persona = personaProvider.getPersona();
        return new AgentCard(
                "0.2.0",                                  // A2A 协议版本
                persona.roleName(),
                persona.domainDescription(),
                publicUrl,
                version,
                AgentCapabilities.defaults(),             // streaming=true
                List.of("text"),
                List.of("text"),
                defaultSkills()
        );
    }

    /** 与后端 @Tool 对应的技能描述（便于对端理解本智能体能做什么）。 */
    private List<AgentSkill> defaultSkills() {
        return List.of(
                new AgentSkill("sql_query", "电商问数 (SQL)",
                        "在 SeekDB 上对业务数据执行 SELECT 聚合查询，返回结果或图表。",
                        List.of("sql", "analytics", "ecommerce"),
                        List.of("本月 GMV 是多少？"),
                        List.of("text"), List.of("text")),
                new AgentSkill("book_search", "图书混合检索",
                        "对图书元数据做向量 + 全文 + 标量的混合检索。",
                        List.of("book", "hybrid-search", "rag"),
                        List.of("2010 年后评分大于 4.5 的书有哪些？"),
                        List.of("text"), List.of("text")),
                new AgentSkill("book_count_by_year", "按年统计图书",
                        "按出版年份统计图书数量，返回柱状/饼图。",
                        List.of("book", "chart"),
                        List.of("各年份图书数量分布？"),
                        List.of("text"), List.of("text")),
                new AgentSkill("hybrid_search", "历史/知识库语义检索",
                        "对历史对话或知识库做语义混合检索。",
                        List.of("memory", "hybrid-search"),
                        List.of("我们之前聊过什么？"),
                        List.of("text"), List.of("text"))
        );
    }
}
