package com.riemann.agent.infrastructure.neo4j;

import com.riemann.agent.infrastructure.neo4j.cypher.CypherGuard;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Neo4j 只读检索仓储（OceanBase 知识图谱）。
 *
 * <p>所有方法都是只读查询，返回格式化后的文本（供 LLM 当作上下文）；
 * 阻塞型 Driver 调用统一用 {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())}
 * 包裹，与 SeekDbRepository 保持一致的响应式风格。</p>
 */
public class Neo4jRepository {

    private static final Logger log = LoggerFactory.getLogger(Neo4jRepository.class);

    private static final int FULLTEXT_LIMIT = 10;
    private static final int TOPO_LIMIT = 60;
    private static final int CODE_LIMIT = 25;
    private static final int CONCEPT_LIMIT = 30;

    private final Driver driver;
    /** 只读守卫：静态判定 + 读事务，两层防线（见 {@link CypherGuard}）。 */
    private final CypherGuard guard;
    private final int maxRows;

    public Neo4jRepository(Driver driver, CypherGuard guard, int maxRows) {
        this.driver = driver;
        this.guard = guard;
        this.maxRows = maxRows <= 0 ? 50 : maxRows;
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
     * 受限的只读 Cypher 执行。
     *
     * <p>两层防线：</p>
     * <ol>
     *   <li>{@link CypherGuard#check(String)} 静态判定——剥壳后查写关键字、强制单条语句、
     *       过程白名单（{@code apoc.*} 默认全禁，堵住 {@code apoc.load.json} 的 SSRF 与
     *       {@code apoc.cypher.run} 的动态执行绕过）；</li>
     *   <li>{@code session.executeRead(...)} 走读事务，即便静态检查被绕过，集群也会把语句
     *       路由到 follower 并拒绝写入。</li>
     * </ol>
     *
     * <p>拦截原因会原样回给模型（而不是笼统的「不允许」），这样它能据此改写成合法查询。</p>
     */
    public Mono<String> runReadOnly(String cypher) {
        CypherGuard.Verdict verdict = guard.check(cypher);
        if (!verdict.allowed()) {
            return Mono.just("Cypher 被只读守卫拦截：" + verdict.reason()
                    + "。请改写成单条 MATCH ... RETURN ... 查询后重试。");
        }
        return Mono.fromCallable(() -> {
                    try (Session s = driver.session()) {
                        // executeRead：读事务（AccessMode.READ），第二道防线
                        return s.executeRead(tx -> {
                            Result r = tx.run(cypher);
                            StringBuilder sb = new StringBuilder();
                            int rows = 0;
                            while (r.hasNext() && rows < maxRows) {
                                var rec = r.next();
                                sb.append(rec.asMap(v -> v.asObject()).toString()).append("\n");
                                rows++;
                            }
                            if (rows == 0) {
                                return "查询无结果。";
                            }
                            if (rows >= maxRows) {
                                sb.append("（结果已达上限 ").append(maxRows).append(" 行，已截断）\n");
                            }
                            return sb.toString();
                        });
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                // 数据库报错也要回给模型看（它才知道是语法错还是属性不存在），但不能把长堆栈塞进上下文
                .onErrorResume(e -> {
                    log.debug("[Neo4j] 只读 Cypher 执行失败：{}", e.toString());
                    return Mono.just("Cypher 执行失败：" + brief(e) + "。请修正后重试。");
                });
    }

    /** 只取错误信息的首行并截断，避免把驱动堆栈写进模型上下文。 */
    private static String brief(Throwable e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        msg = msg.replaceAll("\\s+", " ").trim();
        return msg.length() > 240 ? msg.substring(0, 240) + "..." : msg;
    }
}
