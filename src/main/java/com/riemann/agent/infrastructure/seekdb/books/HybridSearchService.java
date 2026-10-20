package com.riemann.agent.infrastructure.seekdb.books;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索服务，对应 Python 版 hybrid_search.py 的 collection.hybrid_search(... rank={"rrf":{}})。
 *
 * <p>混合检索 = 向量近邻（KNN）+ 全文倒排（FULLTEXT）+ 标量过滤（WHERE）三路，通过
 * <b>RRF（Reciprocal Rank Fusion，倒数排名融合）</b> 合并：</p>
 * <pre>
 * score(id) = Σ 1 / (k + rank_in_list)，rank 从 0 开始；最终按融合分数降序取前 n。
 * </pre>
 *
 * <p>本实现把“一次查询融合三路”的<b>粗排 + 精排</b>全貌完整暴露给上层（{@link BookHybridResult}）：</p>
 * <ul>
 *   <li><b>粗排</b>：向量支路（按 L2 距离升序）与全文支路（按 MATCH 相关性降序）各自独立召回候选，
 *       候选集已在 SQL 层用标量条件过滤，保证进入精排的都是满足约束的书；</li>
 *   <li><b>精排</b>：把两支路候选按书名去重（同书多年份副本只留评分最高/年份最新一版），再 RRF 融合排序，
 *       并标注每本书由哪些支路贡献（前端打 badge）。</li>
 * </ul>
 *
 * <p>对应 Python 版：</p>
 * <pre>
 * collection.hybrid_search(
 *     query={"where_document": {...}, "where": {...}, "n_results": n},
 *     knn={"query_texts": [...], "where": {...}, "n_results": n},
 *     rank={"rrf": {}}, n_results=n)
 * </pre>
 * 其中 knn 支路由 {@link BookRepository#vectorHits} 完成，query（全文）支路由
 * {@link BookRepository#fulltextHits} 完成，两者在此融合。
 */
@Service
@Profile("!oceanbase")
public class HybridSearchService {

    /** RRF 阻尼系数 k，与常见实现（k=60）保持一致。 */
    private static final int RRF_K = 60;

    private final BookRepository repository;

    public HybridSearchService(BookRepository repository) {
        this.repository = repository;
    }

    /** 纯语义（向量）检索，对应 hybrid_search.py 的 collection.query(query_texts=...)。 */
    public List<Book> semanticSearch(String queryText, int n) {
        return repository.vectorSearch(queryText, n);
    }

    /** 仅标量/文档过滤检索（不做向量分支），供只想按条件筛选图书时调用。 */
    public List<Book> filterSearch(String contains, Map<String, Object> where, int n) {
        return repository.filterSearch(contains, where, n);
    }

    /**
     * 混合检索（粗排 + 精排）。对应 hybrid_search.py 的 hybrid_search。
     *
     * <p>三路在一次调用内融合：向量近邻 + 全文倒排 + 标量过滤。当 queryText 为空时向量支路不产生候选，
     * 当 keyword 为空时全文支路不产生候选；两者皆空则退化为纯标量过滤。</p>
     *
     * @param queryText 向量检索用的查询文本（Python 的 knn.query_texts）
     * @param contains  全文关键词（Python 的 where_document.$contains）
     * @param where     标量过滤条件（$gte/$lte/$and 等）
     * @param n         最终返回结果数（精排 TopN）
     */
    public BookHybridResult hybridSearch(String queryText, String contains, Map<String, Object> where, int n) {
        final int candidateWindow = Math.min(Math.max(n * 4, 24), 60);
        boolean hasVector = queryText != null && !queryText.isBlank();
        boolean hasFulltext = contains != null && !contains.isBlank();

        // ===== 粗排：两支路各自独立召回（候选已在 SQL 内被标量条件过滤） =====
        List<BookHit> vectorCandidates = hasVector
                ? repository.vectorHits(queryText, where, candidateWindow)
                : List.of();
        List<BookHit> fulltextCandidates = hasFulltext
                ? repository.fulltextHits(contains, where, candidateWindow)
                : List.of();

        // 纯标量（既无语义查询也无关键词）：退化为标量过滤支路，直接按评分排序作为精排
        if (!hasVector && !hasFulltext) {
            List<Book> scalar = repository.filterSearch(contains, where, candidateWindow);
            List<BookHit> scalarHits = scalar.stream()
                    .map(b -> new BookHit(b, null, null, true))
                    .toList();
            return new BookHybridResult(queryText, contains, where, n,
                    List.of(), List.of(), rrfFuse(scalarHits, n), true);
        }

        // ===== 跨支路按书名去重：同书多年份副本只留「评分最高 / 年份最新」一版 =====
        // 同时聚合两支路分数：向量取保留副本的距离，全文取各副本相关性最大值。
        Map<String, Agg> aggs = new LinkedHashMap<>();
        for (BookHit h : vectorCandidates) {
            mergeAgg(aggs, h.book(), h.vectorScore(), null, true, false);
        }
        for (BookHit h : fulltextCandidates) {
            mergeAgg(aggs, h.book(), null, h.fulltextScore(), false, true);
        }
        List<BookHit> deduped = aggs.values().stream().map(Agg::toHit).toList();

        // ===== 精排：RRF 融合去重后的候选，取 TopN =====
        List<FusedBook> fused = rrfFuse(deduped, n);
        return new BookHybridResult(queryText, contains, where, n,
                vectorCandidates, fulltextCandidates, fused, false);
    }

    // ===================== RRF 融合 =====================

    /**
     * RRF 融合：把带支路分数的命中列表按向量支（L2 升序）与全文支（相关性降序）各自排名，
     * 再用 1/(k+rank+1) 累加得到融合分数，按融合分数降序取前 n，并标注每本书的支路来源。
     */
    private List<FusedBook> rrfFuse(List<BookHit> hits, int n) {
        Map<String, Integer> vectorRank = rankByBranch(hits, true);
        Map<String, Integer> fulltextRank = rankByBranch(hits, false);

        Map<String, Double> fusedScore = new HashMap<>();
        Map<String, BookHit> byId = new HashMap<>();
        for (BookHit h : hits) {
            byId.put(h.book().id(), h);
            double s = 0.0;
            Integer vr = vectorRank.get(h.book().id());
            if (vr != null) {
                s += 1.0 / (RRF_K + vr + 1);
            }
            Integer fr = fulltextRank.get(h.book().id());
            if (fr != null) {
                s += 1.0 / (RRF_K + fr + 1);
            }
            fusedScore.merge(h.book().id(), s, Double::sum);
        }

        List<Map.Entry<String, Double>> entries = new ArrayList<>(fusedScore.entrySet());
        // 融合分数降序；分数相同（如纯标量退化情形）再按评分降序稳定排序
        entries.sort((x, y) -> {
            int c = Double.compare(y.getValue(), x.getValue());
            if (c != 0) {
                return c;
            }
            return Double.compare(byId.get(y.getKey()).book().userRating(),
                    byId.get(x.getKey()).book().userRating());
        });

        List<FusedBook> out = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<String, Double> e : entries) {
            if (out.size() >= n) {
                break;
            }
            BookHit h = byId.get(e.getKey());
            out.add(new FusedBook(h.book(), e.getValue(), rank++,
                    h.vectorScore() != null, h.fulltextScore() != null,
                    h.vectorScore(), h.fulltextScore()));
        }
        return out;
    }

    /** 计算各命中在某支路内的名次（从 0 开始）；该支路无分数的书不参与排名（返回 null）。 */
    private Map<String, Integer> rankByBranch(List<BookHit> hits, boolean vector) {
        List<BookHit> sorted = new ArrayList<>(hits);
        sorted.sort((a, b) -> {
            Double sa = vector ? a.vectorScore() : a.fulltextScore();
            Double sb = vector ? b.vectorScore() : b.fulltextScore();
            if (sa == null && sb == null) {
                return 0;
            }
            if (sa == null) {
                return 1; // 无该支路分数者排到末尾
            }
            if (sb == null) {
                return -1;
            }
            return vector ? Double.compare(sa, sb) : Double.compare(sb, sa);
        });
        Map<String, Integer> rank = new HashMap<>();
        int r = 0;
        for (BookHit h : sorted) {
            Double s = vector ? h.vectorScore() : h.fulltextScore();
            if (s == null) {
                break; // 其余都是无分数者，不再计名次
            }
            rank.put(h.book().id(), r++);
        }
        return rank;
    }

    // ===================== 去重聚合 =====================

    private void mergeAgg(Map<String, Agg> aggs, Book b, Double vScore, Double fScore,
                          boolean fromVector, boolean fromFulltext) {
        aggs.computeIfAbsent(b.name(), k -> new Agg(b, vScore, fScore, fromVector, fromFulltext))
                .merge(b, vScore, fScore, fromVector, fromFulltext);
    }

    /** 按书名聚合的临时结构：保留最优副本，并合并两支路分数与来源标记。 */
    private static final class Agg {
        Book book;
        Double vectorScore;
        Double fulltextScore;
        boolean fromVector;
        boolean fromFulltext;

        Agg(Book book, Double vectorScore, Double fulltextScore, boolean fromVector, boolean fromFulltext) {
            this.book = book;
            this.vectorScore = vectorScore;
            this.fulltextScore = fulltextScore;
            this.fromVector = fromVector;
            this.fromFulltext = fromFulltext;
        }

        void merge(Book b, Double vScore, Double fScore, boolean vv, boolean ff) {
            if (book == null || isBetter(b, book)) {
                book = b;
            }
            if (vScore != null) {
                vectorScore = vScore;
            }
            if (fScore != null) {
                fulltextScore = Math.max(fulltextScore == null ? Double.NEGATIVE_INFINITY : fulltextScore, fScore);
            }
            fromVector |= vv;
            fromFulltext |= ff;
        }

        BookHit toHit() {
            return new BookHit(book, vectorScore, fulltextScore, true);
        }

        /** 评分高者优先；评分相同则年份新者优先（与原始 dedup 策略一致）。 */
        static boolean isBetter(Book a, Book b) {
            if (a.userRating() != b.userRating()) {
                return a.userRating() > b.userRating();
            }
            return a.year() > b.year();
        }
    }
}
