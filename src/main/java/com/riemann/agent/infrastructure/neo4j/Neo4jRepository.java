package com.riemann.agent.infrastructure.neo4j;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.regex.Pattern;

/**
 * Neo4j 只读检索仓储（OceanBase 知识图谱）。
 *
 * <p>所有方法都是只读查询，返回格式化后的文本（供 LLM 当作上下文）；
 * 阻塞型 Driver 调用统一用 {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())}
 * 包裹，与 SeekDbRepository 保持一致的响应式风格。</p>
 */
public class Neo4jRepository {

    private static final int FULLTEXT_LIMIT = 10;
    private static final int TOPO_LIMIT = 60;
    private static final int CODE_LIMIT = 25;
    private static final int CONCEPT_LIMIT = 30;
    private static final Pattern WRITE_OP = Pattern.compile(
            "(?i)\\b(CREATE|MERGE|SET|DELETE|DETACH|REMOVE|DROP|INSERT|UPDATE|GRANT|LOAD|CALL\\s+db\\.schema|CALL\\s+db\\.index)\\b");

    private final Driver driver;

    public Neo4jRepository(Driver driver) {
        this.driver = driver;
    }

    /** 全文检索：文档/章节/概念/文件/类。 */
    public Mono<String> docSearch(String q) {
        return Mono.fromCallable(() -> {
                    try (Session s = driver.session()) {
                        Result r = s.run(
                                "CALL db.index.fulltext.queryNodes('obFulltext', $q) YIELD node, score "
                                        + "RETURN labels(node)[0] AS label, "
                                        + "coalesce(node.title, node.name, node.heading, node.path) AS title, "
                                        + "node.text AS text, score "
                                        + "ORDER BY score DESC LIMIT " + FULLTEXT_LIMIT,
                                Values.parameters("q", q));
                        StringBuilder sb = new StringBuilder();
                        int n = 0;
                        while (r.hasNext()) {
                            var rec = r.next();
                            n++;
                            String label = rec.get("label").asString("?");
                            String title = rec.get("title").asString("");
                            String text = rec.get("text").isNull() ? "" : rec.get("text").asString();
                            sb.append(String.format("[%d] (%s) %s%n", n, label, title));
                            if (!text.isBlank()) {
                                String snippet = text.length() > 400 ? text.substring(0, 400) : text;
                                sb.append("    ").append(snippet.replace("\n", " ")).append("\n");
                            }
                        }
                        return n == 0 ? "未检索到相关内容。" : sb.toString();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 拓扑子图：以某实体（按 name 包含匹配）为中心，展开其直接邻居关系。 */
    public Mono<String> topologyAround(String name) {
        return Mono.fromCallable(() -> {
                    try (Session s = driver.session()) {
                        Result r = s.run(
                                "MATCH (n) WHERE n.name CONTAINS $name "
                                        + "WITH n MATCH (n)-[rel]-(m) "
                                        + "RETURN n.name AS src, type(rel) AS rel, labels(m)[0] AS mlabel, m.name AS dst "
                                        + "LIMIT " + TOPO_LIMIT,
                                Values.parameters("name", name));
                        StringBuilder sb = new StringBuilder();
                        int n = 0;
                        while (r.hasNext()) {
                            var rec = r.next();
                            n++;
                            sb.append(String.format("%s -[%s]-> %s (%s)%n",
                                    rec.get("src").asString(),
                                    rec.get("rel").asString(),
                                    rec.get("dst").asString(),
                                    rec.get("mlabel").asString()));
                        }
                        return n == 0 ? "图谱中未找到名为包含「" + name + "」的实体。" : sb.toString();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 代码检索：按名称/路径匹配 文件/类/函数。 */
    public Mono<String> codeSearch(String name) {
        return Mono.fromCallable(() -> {
                    try (Session s = driver.session()) {
                        Result r = s.run(
                                "MATCH (n:File|Class|Function) "
                                        + "WHERE n.name CONTAINS $name OR n.path CONTAINS $name "
                                        + "RETURN labels(n)[0] AS label, coalesce(n.name, n.path) AS name, "
                                        + "n.path AS path, n.lang AS lang "
                                        + "LIMIT " + CODE_LIMIT,
                                Values.parameters("name", name));
                        StringBuilder sb = new StringBuilder();
                        int n = 0;
                        while (r.hasNext()) {
                            var rec = r.next();
                            n++;
                            String lang = rec.get("lang").isNull() ? "" : rec.get("lang").asString();
                            sb.append(String.format("[%d] (%s) %s  path=%s %s%n",
                                    n, rec.get("label").asString(), rec.get("name").asString(),
                                    rec.get("path").asString(""), lang));
                        }
                        return n == 0 ? "代码库中未找到匹配「" + name + "」的符号。" : sb.toString();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 概念展开：某概念节点的关系邻居（RELATED_TO / MAPS_TO / IMPLEMENTS / MENTIONS 等）。 */
    public Mono<String> conceptExpand(String name) {
        return Mono.fromCallable(() -> {
                    try (Session s = driver.session()) {
                        Result r = s.run(
                                "MATCH (c:Concept {name:$name})-[rel]-(x) "
                                        + "RETURN type(rel) AS rel, labels(x)[0] AS xlabel, x.name AS xname "
                                        + "LIMIT " + CONCEPT_LIMIT,
                                Values.parameters("name", name));
                        StringBuilder sb = new StringBuilder();
                        int n = 0;
                        while (r.hasNext()) {
                            var rec = r.next();
                            n++;
                            sb.append(String.format("%s -[%s]-> %s%n",
                                    name, rec.get("rel").asString(), rec.get("xname").asString()));
                        }
                        if (n == 0) {
                            // 概念不存在时，尝试做全文检索兜底
                            return "图谱中没有名为「" + name + "」的概念节点。可改用 ob_doc_search 做全文检索。";
                        }
                        return sb.toString();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 受限的只读 Cypher 执行。拦截所有写操作（CREATE/MERGE/SET/DELETE/DROP 等），
     * 仅允许 MATCH/RETURN/WITH/ORDER/SKIP/LIMIT/CALL(只读过程) 等读语句。
     */
    public Mono<String> runReadOnly(String cypher) {
        if (WRITE_OP.matcher(cypher).find()) {
            return Mono.just("出于安全考虑，仅允许只读 Cypher（已拦截写操作）。");
        }
        return Mono.fromCallable(() -> {
                    try (Session s = driver.session()) {
                        Result r = s.run(cypher);
                        StringBuilder sb = new StringBuilder();
                        int rows = 0;
                        while (r.hasNext() && rows < 50) {
                            var rec = r.next();
                            sb.append(rec.asMap(v -> v.asObject()).toString()).append("\n");
                            rows++;
                        }
                        return rows == 0 ? "查询无结果。" : sb.toString();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
