"""社区 / 博客 / Ask 站 爬虫。

复用 docs_crawler 的抽取逻辑，仅换种子与 source 标签。
"""
from __future__ import annotations

from .docs_crawler import crawl as _crawl_docs
from .docs_crawler import Document


def crawl_community(
    seeds: list[str],
    *,
    max_depth: int = 2,
    delay: float = 1.0,
    max_pages: int = 200,
) -> list[Document]:
    return _crawl_docs(
        seeds,
        source="community",
        max_depth=max_depth,
        delay=delay,
        max_pages=max_pages,
    )
