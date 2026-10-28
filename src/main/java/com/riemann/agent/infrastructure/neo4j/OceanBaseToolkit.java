package com.riemann.agent.infrastructure.neo4j;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * OceanBase 知识图谱工具集（@Profile("oceanbase")）。
 *
 * <p>每个方法都是对 Neo4j 的只读检索，返回格式化文本供 ReActAgent 的 LLM 综合作答。
 * 这便是“管理 OceanBase 任何问题”的能力入口：Agent 自行决定调用哪个工具、如何组合，
 * 而非在工具里硬编码问答逻辑。</p>
 */
@Component
@Profile("oceanbase")
public class OceanBaseToolkit {

    private final Neo4jRepository neo4j;

    public OceanBaseToolkit(Neo4jRepository neo4j) {
        this.neo4j = neo4j;
    }

    @Tool(name = "ob_doc_search",
          readOnly = true,
          description = "在 OceanBase 知识图谱中做【全文检索】：覆盖官方文档、章节、概念、代码文件与类名。"
                  + "当用户问“XX 是什么/怎么配置/报错怎么处理”“有没有关于资源池的文档”等需要查资料时优先用。")
    public Mono<String> obDocSearch(
            @ToolParam(name = "query", description = "检索关键词或短语，如“创建资源池”“locality 设置”“租户扩容") String query) {
        return neo4j.docSearch(query);
    }

    @Tool(name = "ob_topology",
          readOnly = true,
          description = "查看 OceanBase 的【部署/架构拓扑】：给定实体名（如 Tenant / Zone / OBServer / 资源池），"
                  + "返回它在图谱中的直接关系（HAS_TENANT、HOSTS、LOCATED_ON、REPLICATED_AS 等）。"
                  + "当用户问“Tenant 和 Cluster 什么关系”“一个 Zone 里有哪些 Server”“副本分布在哪”等拓扑问题时用。")
    public Mono<String> obTopology(
            @ToolParam(name = "name", description = "拓扑实体名（支持包含匹配），如“Tenant”“zone1”“server-1”“business_tenant”") String name) {
        return neo4j.topologyAround(name);
    }

    @Tool(name = "ob_code_search",
          readOnly = true,
          description = "在 OceanBase 主仓库代码中按名称/路径检索【文件/类/函数】。"
                  + "当用户问“XX 功能在哪个类/文件实现”“ObTenant 这个类在哪”“某个函数在哪定义”时用。")
    public Mono<String> obCodeSearch(
            @ToolParam(name = "name", description = "符号名或路径片段，如“ObTenant”“partition”“src/rootserver”") String name) {
        return neo4j.codeSearch(name);
    }

    @Tool(name = "ob_concept",
          readOnly = true,
          description = "展开某个【概念节点】在图谱中的关系（RELATED_TO / MAPS_TO 到部署实体 / IMPLEMENTS 由哪些类实现）。"
                  + "当用户想理解“Paxos 在 OceanBase 里怎么用”“租户对应哪些代码/实体”等概念关联问题时用。")
    public Mono<String> obConcept(
            @ToolParam(name = "name", description = "概念名，如“Tenant”“Paxos”“Partition”“LogStream”") String name) {
        return neo4j.conceptExpand(name);
    }

    @Tool(name = "ob_cypher",
          readOnly = true,
          description = "对 OceanBase 知识图谱执行一条【只读 Cypher】，返回结果文本。"
                  + "适合需要灵活查询图谱的场景；仅允许 SELECT 类只读语句，写操作会被拦截。")
    public Mono<String> obCypher(
            @ToolParam(name = "cypher", description = "只读 Cypher，如 MATCH (c:Cluster)-[:HAS_TENANT]->(t) RETURN c,t") String cypher) {
        return neo4j.runReadOnly(cypher);
    }
}
