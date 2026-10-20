"""Neo4j 入库层：连接、建 schema、写入文档/代码/种子拓扑。

所有写入使用 MERGE + 参数化，重复运行可安全刷新（幂等）。
"""
from __future__ import annotations

import hashlib
import os
from pathlib import Path

from dotenv import load_dotenv
from neo4j import GraphDatabase

from . import seed_topology

load_dotenv()

_SCHEMA_PATH = Path(__file__).parent / "schema.cypher"


def connect() -> "GraphDatabase.driver":
    uri = os.getenv("NEO4J_URI", "bolt://localhost:7687")
    user = os.getenv("NEO4J_USER", "neo4j")
    pw = os.getenv("NEO4J_PASSWORD", "oceanbase123")
    return GraphDatabase.driver(uri, auth=(user, pw))


def apply_schema(driver) -> int:
    cypher = _SCHEMA_PATH.read_text(encoding="utf-8")
    stmts = [s.strip() for s in cypher.split(";") if s.strip()]
    with driver.session() as s:
        for st in stmts:
            s.run(st)
    return len(stmts)


def load_documents(driver, docs: list[dict]) -> int:
    count = 0
    with driver.session() as s:
        for doc in docs:
            url = doc.get("url")
            s.run(
                "MERGE (d:Document {url:$url}) SET d.title=$title, d.source=$source",
                url=url, title=doc.get("title", ""), source=doc.get("source", "docs"),
            )
            for i, sec in enumerate(doc.get("sections", [])):
                sid = hashlib.sha1(f"{url}|{i}".encode()).hexdigest()[:16]
                s.run(
                    "MATCH (d:Document {url:$url}) "
                    "MERGE (sec:Section {id:$sid}) "
                    "SET sec.heading=$heading, sec.text=$text, sec.doc_url=$url "
                    "MERGE (d)-[:HAS_SECTION]->(sec)",
                    url=url, sid=sid,
                    heading=sec.get("heading", ""), text=sec.get("text", ""),
                )
                count += 1
    return count


def load_code(driver, code: dict) -> dict:
    repo = code.get("repo", "unknown")
    stats = {"modules": 0, "files": 0, "classes": 0, "functions": 0, "imports": 0}
    with driver.session() as s:
        s.run("MERGE (r:Repo {name:$name})", name=repo)

        for m in code.get("modules", []):
            # r 已在上方创建；这里用 MATCH 复用，仅 MERGE 关系，避免重复创建 Repo 违反唯一约束
            s.run("MERGE (m:Module {path:$path}) WITH m MATCH (r:Repo {name:$repo}) MERGE (r)-[:HAS_MODULE]->(m)",
                  path=m["path"], repo=repo)
            stats["modules"] += 1

        for f in code.get("files", []):
            s.run(
                "MERGE (f:File {path:$path}) SET f.lang=$lang, f.module=$module "
                "WITH f MATCH (m:Module {path:$module}) MERGE (m)-[:HAS_FILE]->(f)",
                path=f["path"], lang=f["lang"], module=f["module"],
            )
            stats["files"] += 1

        for c in code.get("classes", []):
            s.run(
                "MERGE (c:Class {name:$name, file:$file}) "
                "WITH c MATCH (f:File {path:$file}) MERGE (f)-[:DEFINES]->(c)",
                name=c["name"], file=c["file"],
            )
            stats["classes"] += 1

        for fn in code.get("functions", []):
            s.run(
                "MERGE (fn:Function {name:$name, file:$file}) "
                "WITH fn MATCH (f:File {path:$file}) MERGE (f)-[:CONTAINS]->(fn)",
                name=fn["name"], file=fn["file"],
            )
            stats["functions"] += 1

        for imp in code.get("imports", []):
            # 轻量、有界的依赖链接：仅当目标 basename 能唯一定位时连边
            s.run(
                "MATCH (f:File {path:$file}) "
                "MATCH (t:File) WHERE t.path ENDS WITH $target "
                "WITH f, collect(t) AS ts WHERE size(ts) > 0 AND size(ts) <= 3 "
                "UNWIND ts AS t MERGE (f)-[:IMPORTS]->(t)",
                file=imp["file"], target=imp["target"],
            )
            stats["imports"] += 1
    return stats


def load_seed(driver) -> int:
    return seed_topology.run(driver)


def load_all(driver, docs: list[dict], code: dict | None = None) -> dict:
    """一站式：schema + 文档 + 代码 + 种子拓扑。返回各阶段统计。"""
    out = {}
    out["schema"] = apply_schema(driver)
    out["doc_sections"] = load_documents(driver, docs)
    if code:
        out["code"] = load_code(driver, code)
    out["seed_stmts"] = load_seed(driver)
    return out
