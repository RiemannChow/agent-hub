"""OceanBase 知识图谱构建入口。

用法：
  cd oceanbase-kg
  pip install -r requirements.txt && cp .env.example .env   # 填写 NEO4J 密码
  python -m src.main --docs --github --community
  python -m src.main --seed-only        # 仅加载标准拓扑骨架（无需联网）

阶段：爬取(可选) → 建 schema → 写文档/代码 → 写种子拓扑 → 链接(文档↔概念↔代码↔拓扑)
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

# 让 src 成为可导入根（crawl / model 作为顶层包）
PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT / "src"))

import yaml  # noqa: E402

from model.neo4j_loader import connect, load_all  # noqa: E402
from model.linker import run as run_linker  # noqa: E402


def _load_cfg() -> dict:
    p = PROJECT_ROOT / "config" / "sources.yaml"
    if not p.exists():
        return {}
    return yaml.safe_load(p.read_text(encoding="utf-8")) or {}


def main() -> None:
    ap = argparse.ArgumentParser(description="Build OceanBase knowledge graph in Neo4j")
    ap.add_argument("--docs", action="store_true", help="爬取官方文档")
    ap.add_argument("--community", action="store_true", help="爬取社区/博客")
    ap.add_argument("--github", action="store_true", help="爬取 GitHub 主仓库代码")
    ap.add_argument("--seed-only", action="store_true", help="只加载标准拓扑骨架（不联网）")
    args = ap.parse_args()

    cfg = _load_cfg()
    driver = connect()

    docs: list = []
    code = None

    if not args.seed_only:
        if args.docs:
            from crawl.docs_crawler import crawl as crawl_docs  # lazy: 避免无网络库时也能 --seed-only
            d = cfg.get("docs", {})
            print(f"[crawl] docs seeds={d.get('seeds')}")
            docs += [x.to_dict() for x in crawl_docs(
                d.get("seeds", []), max_depth=d.get("max_depth", 3),
                delay=d.get("delay", 1.0), max_pages=d.get("max_pages", 400))]
            print(f"[crawl] docs done: {len(docs)} pages")

        if args.community:
            from crawl.community_crawler import crawl_community  # lazy
            c = cfg.get("community", {})
            docs += [x.to_dict() for x in crawl_community(
                c.get("seeds", []), max_depth=c.get("max_depth", 2),
                delay=c.get("delay", 1.0), max_pages=c.get("max_pages", 200))]
            print(f"[crawl] community done: total docs={len(docs)}")

        if args.github:
            from crawl.github_crawler import crawl_repo  # lazy
            g = cfg.get("github", {})
            print(f"[crawl] github repo={g.get('repo')} (首次克隆稍慢) ...")
            cg = crawl_repo(
                repo=g.get("repo", "oceanbase/oceanbase"),
                clone_dir=g.get("clone_dir", ".cache/oceanbase_repo"),
                token=os.getenv("GITHUB_TOKEN") or None,
                max_files=g.get("max_files", 1500),
                include_dirs=g.get("include_dirs", []),
                skip_dirs=g.get("skip_dirs", []),
                skip_large_files_kb=g.get("skip_large_files_kb", 512),
            )
            code = cg.to_dict()
            print(f"[crawl] github done: files={len(code['files'])} classes={len(code['classes'])}")

    print("[load] applying schema + writing documents/code/seed ...")
    out = load_all(driver, docs, code)
    print(f"[load] stats: {out}")

    print("[link] connecting docs <-> concepts <-> code <-> topology ...")
    link_stats = run_linker(driver, docs, code)
    print(f"[link] stats: {link_stats}")

    # 概览
    with driver.session() as s:
        rows = s.run(
            "MATCH (n) RETURN labels(n)[0] AS label, count(*) AS c ORDER BY c DESC"
        ).data()
    print("[graph] node overview:")
    for r in rows:
        print(f"   {r['label']:>14}: {r['c']}")

    driver.close()
    print("[done]")


if __name__ == "__main__":
    main()
