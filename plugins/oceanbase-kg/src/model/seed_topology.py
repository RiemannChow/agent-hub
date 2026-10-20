"""手工录入的 OceanBase 标准架构拓扑骨架。

目的：即使文档爬取不完整，图谱也自带一份“核心关系”保底。
产出：一组 (cypher, params) 语句，由 neo4j_loader 执行。

设计：
  - 每个核心概念既有「类型锚点节点」(label=Concept 同名，如 (:Tenant {name:"Tenant", kind:"type"}))
    又有「示例实例节点」(如 (:Tenant {name:"business_tenant"}))。
  - 文档里抽取出的 Concept 通过 MAPS_TO 指向类型锚点，从而把“文字概念”和“部署实体”连通。
  - 概念之间的 RELATED_TO 由拓扑边自动推导，使概念子图天然连通。
"""
from __future__ import annotations

from typing import Iterable

# 核心概念：英文术语 -> 别名（中英文）
ANCHORS: dict[str, list[str]] = {
    "Cluster": ["集群"],
    "Tenant": ["租户"],
    "Zone": ["可用区", "Zone"],
    "OBServer": ["OBServer", "Observer", "observer"],
    "ResourcePool": ["资源池"],
    "Unit": ["Unit", "资源单元"],
    "Database": ["数据库"],
    "Table": ["表"],
    "Partition": ["分区"],
    "LogStream": ["日志流", "Log Stream", "LS"],
    "OBProxy": ["OBProxy", "代理", "Proxy"],
    "Replica": ["副本"],
    "Paxos": ["Paxos", "多副本一致性"],
}

# 实例 (label, name)
_INSTANCES = [
    ("Cluster", "obcluster-1"),
    ("Zone", "zone1"), ("Zone", "zone2"), ("Zone", "zone3"),
    ("OBServer", "server-1"), ("OBServer", "server-2"), ("OBServer", "server-3"),
    ("Tenant", "sys"), ("Tenant", "business_tenant"),
    ("ResourcePool", "pool_biz"),
    ("Unit", "unit_biz_1"),
    ("Database", "test_db"),
    ("Table", "orders"),
    ("Partition", "orders_p0"),
    ("LogStream", "ls_orders"),
    ("OBProxy", "obproxy-1"),
    ("Replica", "replica_orders_p0_leader"),
    ("Replica", "replica_orders_p0_follower"),
    ("Paxos", "paxos-consensus"),
]

# 边 (a_label, a_name, rel_type, b_label, b_name)
_EDGES = [
    ("Cluster", "obcluster-1", "HAS_TENANT", "Tenant", "sys"),
    ("Cluster", "obcluster-1", "HAS_TENANT", "Tenant", "business_tenant"),
    ("Cluster", "obcluster-1", "HAS_ZONE", "Zone", "zone1"),
    ("Cluster", "obcluster-1", "HAS_ZONE", "Zone", "zone2"),
    ("Cluster", "obcluster-1", "HAS_ZONE", "Zone", "zone3"),
    ("Zone", "zone1", "HOSTS", "OBServer", "server-1"),
    ("Zone", "zone2", "HOSTS", "OBServer", "server-2"),
    ("Zone", "zone3", "HOSTS", "OBServer", "server-3"),
    ("Tenant", "business_tenant", "OWNS", "ResourcePool", "pool_biz"),
    ("ResourcePool", "pool_biz", "IN_ZONE", "Zone", "zone1"),
    ("ResourcePool", "pool_biz", "IN_ZONE", "Zone", "zone2"),
    ("ResourcePool", "pool_biz", "IN_ZONE", "Zone", "zone3"),
    ("ResourcePool", "pool_biz", "CONTAINS_UNIT", "Unit", "unit_biz_1"),
    ("Unit", "unit_biz_1", "LOCATED_ON", "OBServer", "server-1"),
    ("Tenant", "business_tenant", "HAS_DATABASE", "Database", "test_db"),
    ("Database", "test_db", "HAS_TABLE", "Table", "orders"),
    ("Table", "orders", "PARTITIONED_INTO", "Partition", "orders_p0"),
    ("Partition", "orders_p0", "REPLICATED_AS", "Replica", "replica_orders_p0_leader"),
    ("Partition", "orders_p0", "REPLICATED_AS", "Replica", "replica_orders_p0_follower"),
    ("Replica", "replica_orders_p0_leader", "ON_SERVER", "OBServer", "server-1"),
    ("Replica", "replica_orders_p0_follower", "ON_SERVER", "OBServer", "server-2"),
    ("Table", "orders", "LOGGED_BY", "LogStream", "ls_orders"),
    ("OBProxy", "obproxy-1", "ROUTES_TO", "Tenant", "business_tenant"),
    ("OBProxy", "obproxy-1", "ROUTES_TO", "Tenant", "sys"),
    ("Replica", "replica_orders_p0_leader", "USES_PAXOS", "Paxos", "paxos-consensus"),
]


def _node(label: str, name: str, **props) -> tuple[str, dict]:
    cypher = f"MERGE (n:{label} {{name:$name}}) SET n += $props"
    return cypher, {"name": name, "props": props}


def _rel(a: tuple, rel: str, b: tuple) -> tuple[str, dict]:
    a_label, a_name = a
    b_label, b_name = b
    cypher = (
        f"MATCH (a:{a_label} {{name:$a}}), (b:{b_label} {{name:$b}}) "
        f"MERGE (a)-[:{rel}]->(b)"
    )
    return cypher, {"a": a_name, "b": b_name}


def build_statements() -> list[tuple[str, dict]]:
    stmts: list[tuple[str, dict]] = []

    # 1) 类型锚点 + 概念节点
    for term, aliases in ANCHORS.items():
        stmts.append(_node(term, term, kind="type"))
        stmts.append(_node("Concept", term, aliases="|".join(aliases)))
        # Concept -> 类型锚点
        stmts.append((
            "MATCH (c:Concept {name:$c}), (t:" + term + " {name:$t}) MERGE (c)-[:MAPS_TO]->(t)",
            {"c": term, "t": term},
        ))

    # 2) 实例节点
    for label, name in _INSTANCES:
        stmts.append(_node(label, name))

    # 3) 拓扑边
    for a_label, a_name, rel, b_label, b_name in _EDGES:
        stmts.append(_rel((a_label, a_name), rel, (b_label, b_name)))

    # 4) 概念 RELATED_TO（由拓扑边推导：同一条边的两端概念相连）
    seen_pairs = set()
    for a_label, _a, _reltype, b_label, _b in _EDGES:
        if a_label == b_label:
            continue
        key = tuple(sorted((a_label, b_label)))
        if key in seen_pairs:
            continue
        seen_pairs.add(key)
        stmts.append((
            "MATCH (a:Concept {name:$a}), (b:Concept {name:$b}) MERGE (a)-[:RELATED_TO]->(b)",
            {"a": a_label, "b": b_label},
        ))

    return stmts


def run(driver) -> int:
    """直接执行种子拓扑，返回语句数。"""
    stmts = build_statements()
    with driver.session() as s:
        for cypher, params in stmts:
            s.run(cypher, **params)
    return len(stmts)
