package com.riemann.agent.infrastructure.seekdb.books;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将 Python 版 hybrid_search 的元数据过滤条件（嵌套 dict，含 $gte/$lte/$and 等操作符）
 * 翻译成参数化 SQL WHERE 子句，对应 import_data / index_manager 中的标量过滤语义。
 *
 * <p>支持：
 * <ul>
 *   <li>等值：{"genre": "Fiction"}            -> genre = ?</li>
 *   <li>比较：{"user_rating": {"$gte": 4.5}}  -> user_rating >= ?</li>
 *   <li>组合：{"$and": [ {...}, {...} ]}       -> (...) AND (...)</li>
 * </ul>
 * 字段名通过白名单校验，值一律走绑定参数，杜绝 SQL 注入。</p>
 */
public final class SqlWhere {

    private static final Set<String> ALLOWED = Set.of(
            "id", "name", "author", "user_rating", "reviews", "price", "year", "genre");

    private static final Map<String, String> OPS = Map.of(
            "$eq", "=", "$ne", "<>", "$gt", ">", "$gte", ">=",
            "$lt", "<", "$lte", "<=");

    private SqlWhere() {
    }

    public static final class Built {
        final String clause;
        final List<Object> params;

        private Built(String clause, List<Object> params) {
            this.clause = clause;
            this.params = params;
        }
    }

    @SuppressWarnings("unchecked")
    public static Built build(Map<String, Object> where) {
        if (where == null || where.isEmpty()) {
            return new Built("", new ArrayList<>());
        }
        List<Object> params = new ArrayList<>();
        String clause = buildNode(where, params);
        return new Built(clause, params);
    }

    @SuppressWarnings("unchecked")
    private static String buildNode(Map<String, Object> node, List<Object> params) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> e : node.entrySet()) {
            String key = e.getKey();
            Object val = e.getValue();
            if ("$and".equals(key)) {
                List<String> sub = new ArrayList<>();
                for (Object o : (List<Object>) val) {
                    sub.add("(" + buildNode((Map<String, Object>) o, params) + ")");
                }
                parts.add("(" + String.join(" AND ", sub) + ")");
            } else if ("$or".equals(key)) {
                List<String> sub = new ArrayList<>();
                for (Object o : (List<Object>) val) {
                    sub.add("(" + buildNode((Map<String, Object>) o, params) + ")");
                }
                parts.add("(" + String.join(" OR ", sub) + ")");
            } else {
                if (!ALLOWED.contains(key)) {
                    throw new IllegalArgumentException("不允许的过滤字段: " + key);
                }
                parts.add(buildCondition(key, val, params));
            }
        }
        return String.join(" AND ", parts);
    }

    private static String buildCondition(String field, Object val, List<Object> params) {
        if (val instanceof Map) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<?, ?> en : ((Map<?, ?>) val).entrySet()) {
                String op = en.getKey().toString();
                String sqlOp = OPS.get(op);
                if (sqlOp == null) {
                    throw new IllegalArgumentException("不支持的过滤操作符: " + op);
                }
                parts.add(field + " " + sqlOp + " ?");
                params.add(en.getValue());
            }
            return "(" + String.join(" AND ", parts) + ")";
        }
        params.add(val);
        return field + " = ?";
    }
}
