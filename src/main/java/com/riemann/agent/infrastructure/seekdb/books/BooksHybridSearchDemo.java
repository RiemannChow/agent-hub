package com.riemann.agent.infrastructure.seekdb.books;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 混合检索演示，对应 Python 版 hybrid_search.py 的 main()。
 *
 * <p>在 books-demo 环境下随应用启动运行，依次演示：</p>
 * <ol>
 *   <li>纯语义（向量）检索；</li>
 *   <li>6 组混合检索：评分过滤 / 类型过滤 / 多条件组合 / 评论数过滤 / 价格区间过滤，
 *       均通过「向量近邻 + 全文倒排 + 标量过滤」三路 RRF 融合，展示 粗排 + 精排 结果。</li>
 * </ol>
 *
 * <p>需与 seekdb 配置一同启用（例如 --spring.profiles.active=seekdb,books-demo），
 * 以保证数据已导入 demo_books.book_info。</p>
 */
@Component
@Profile("books-demo")
@Order(2)
public class BooksHybridSearchDemo implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BooksHybridSearchDemo.class);

    private final HybridSearchService search;

    public BooksHybridSearchDemo(HybridSearchService search) {
        this.search = search;
    }

    @Override
    public void run(String... args) {
        log.info("===== SeekDB 图书混合检索演示（Java 版） =====");

        // 1) 纯语义检索
        printResults("语义搜索", search.semanticSearch("self improvement motivation success", 5));

        // 2) 混合检索：评分 >= 4.5
        printHybrid("混合搜索 (评分>=4.5)",
                search.hybridSearch("inspirational life advice", "inspirational",
                        Map.of("user_rating", Map.of("$gte", 4.5)), 5), 5);

        // 3) 混合检索：Non Fiction
        printHybrid("混合搜索 (Non Fiction)",
                search.hybridSearch("business entrepreneurship leadership", "business",
                        Map.of("genre", "Non Fiction"), 5), 5);

        // 4) 混合检索：Fiction + 2015 年后 + 评分 >= 4.0
        printHybrid("混合搜索 (Fiction, 2015年后, 评分>=4.0)",
                search.hybridSearch("fiction story novel", "fiction",
                        Map.of("$and", List.of(
                                Map.of("year", Map.of("$gte", 2015)),
                                Map.of("user_rating", Map.of("$gte", 4.0)),
                                Map.of("genre", "Fiction"))), 5), 5);

        // 5) 混合检索：评论数 >= 10000
        printHybrid("混合搜索 (评论数>=10000)",
                search.hybridSearch("popular bestseller", "popular",
                        Map.of("reviews", Map.of("$gte", 10000)), 10), 10);

        // 6) 混合检索：$5-$15 + 评分 >= 4.5
        printHybrid("混合搜索 ($5-$15, 评分>=4.5)",
                search.hybridSearch("good book recommendation", "good book",
                        Map.of("price", Map.of("$gte", 5, "$lte", 15),
                                "user_rating", Map.of("$gte", 4.5)), 5), 5);

        log.info("===== 演示结束 =====");
    }

    private void printResults(String title, List<Book> books) {
        log.info("--- {}：找到 {} 条 ---", title, books.size());
        for (int i = 0; i < books.size(); i++) {
            Book b = books.get(i);
            String sim = b.distance() != null
                    ? String.format("  (L2距离=%.4f)", b.distance())
                    : "";
            log.info("  [{}] {} | 作者:{} | 评分:{} | 评论:{} | 价格:${} | 年份:{} | 类型:{}",
                    i + 1, b.name(), b.author(), b.userRating(), b.reviews(),
                    b.price(), b.year(), b.genre());
        }
    }

    private void printHybrid(String title, BookHybridResult r, int topN) {
        log.info("--- {}（粗排+精排，Top{}）---", title, topN);
        log.info("  输入: 语义={} | 关键词={} | 标量={}",
                r.queryText().isBlank() ? "(无)" : r.queryText(),
                r.keyword().isBlank() ? "(无)" : r.keyword(),
                r.scalarFilter().isEmpty() ? "(无)" : r.scalarFilter());
        log.info("  粗排-向量支路候选数={}，粗排-全文支路候选数={}",
                r.vectorCandidates().size(), r.fulltextCandidates().size());
        log.info("  精排结果：");
        for (FusedBook f : r.fused()) {
            StringBuilder badges = new StringBuilder();
            if (f.fromVector()) {
                badges.append("V");
            }
            if (f.fromFulltext()) {
                badges.append("F");
            }
            log.info("    [{}] (融合分={}, 支路={}) {} | 作者:{} | 评分:{} | 年份:{}",
                    f.rank(), String.format("%.4f", f.fusedScore()), badges,
                    f.book().name(), f.book().author(), f.book().userRating(), f.book().year());
        }
    }
}
