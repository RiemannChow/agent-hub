"""把爬到的文档 / 代码与种子拓扑连通。

策略（确定性，无需 LLM 即可跑；LLM 增强为可选扩展点）：
  - 文档章节若命中「核心概念词典」(ANCHORS)，则 (Section)-[:MENTIONS]->(Concept)。
  - 同一 Document 内共现的概念互连 (Concept)-[:RELATED_TO]->(Concept)。
  - 类名若包含某概念英文词（如 ObTenant → Tenant），则 (Class)-[:IMPLEMENTS]->(Concept)。
  - Concept→拓扑类型锚点的 MAPS_TO 已在 seed_topology 中建立，这里复用同名 Concept 即可。
"""
from __future__ import annotations

import re
from itertools import combinations
from typing import Iterable, Optional

from .seed_topology import ANCHORS


def _mentions(text: str, term: str, aliases: list[str]) -> bool:
    if re.search(r"\b" + re.escape(term) + r"\b", text, re.I):
        return True
    if re.search(r"\b" + re.escape(term) + r"s\b", text, re.I):  # 复数
        return True
    for al in aliases:
        if al and al in text:
            return True
    return False


def run(driver, docs: Iterable[dict], code_graph: Optional[dict] = None, llm=None) -> dict:
    """执行链接，返回统计 {mentions, related, implements}。"""
    stats = {"mentions": 0, "related": 0, "implements": 0}
    with driver.session() as s:
        # ---- 文档 → 概念 ----
        for doc in docs:
            rows = list(s.run(
                "MATCH (d:Document {url:$url})-[:HAS_SECTION]->(sec:Section) "
                "RETURN sec.id AS id, sec.text AS text",
                url=doc.get("url"),
            ))
            doc_terms: set[str] = set()
            for r in rows:
                text = r["text"] or ""
                for term, aliases in ANCHORS.items():
                    if _mentions(text, term, aliases):
                        s.run(
                            "MATCH (sec:Section {id:$sid}) "
                            "MERGE (c:Concept {name:$term}) SET c.aliases=$aliases "
                            "MERGE (sec)-[:MENTIONS]->(c)",
                            sid=r["id"], term=term, aliases="|".join(aliases),
                        )
                        stats["mentions"] += 1
                        doc_terms.add(term)
            for a, b in combinations(sorted(doc_terms), 2):
                s.run(
                    "MATCH (x:Concept {name:$a}), (y:Concept {name:$b}) "
                    "MERGE (x)-[:RELATED_TO]->(y)",
                    a=a, b=b,
                )
                stats["related"] += 1

        # ---- 代码 → 概念（IMPLEMENTS）----
        if code_graph:
            for cls in code_graph.get("classes", []):
                name = cls.get("name", "")
                low = name.lower()
                matched = next((t for t in ANCHORS if t.lower() in low), None)
                if matched:
                    s.run(
                        "MATCH (cl:Class {name:$cname, file:$file}) "
                        "MERGE (c:Concept {name:$term}) "
                        "MERGE (cl)-[:IMPLEMENTS]->(c)",
                        cname=name, file=cls.get("file"), term=matched,
                    )
                    stats["implements"] += 1
    return stats
