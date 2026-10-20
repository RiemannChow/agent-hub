"""OceanBase 官网 / 文档站 爬虫。

BFS 限深抓取同域页面，提取标题、章节（h1-h4 分组正文）、站内链接，做 URL 去重。
返回结构化 Document 列表，供后续写入 Neo4j。
"""
from __future__ import annotations

import hashlib
import re
import time
from collections import deque
from dataclasses import dataclass, field
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup

_HEADING_RE = re.compile(r"^h[1-4]$", re.I)
_NOISE_TAGS = {"script", "style", "nav", "footer", "header", "aside", "form"}
_ASSET_EXT = (".png", ".jpg", ".jpeg", ".gif", ".svg", ".css", ".js", ".ico", ".woff", ".woff2", ".pdf")


@dataclass
class Document:
    url: str
    title: str
    source: str
    sections: list[dict] = field(default_factory=list)  # [{"heading", "text"}]
    links: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "url": self.url,
            "title": self.title,
            "source": self.source,
            "sections": self.sections,
            "links": self.links,
        }


def _norm_url(url: str) -> str:
    p = urlparse(url)
    # 去掉 fragment，query 保留（文档锚点外的参数有时区分页面）
    path = p.path.rstrip("/") or "/"
    return f"{p.scheme}://{p.netloc}{path}"


def _same_domain(a: str, b: str) -> bool:
    # b 已经是 netloc 字符串（如 "www.oceanbase.com"），直接比较，不要再次 urlparse
    return urlparse(a).netloc == b


def _extract_sections(soup: BeautifulSoup) -> list[dict]:
    """按 h1-h4 将正文切成章节。"""
    main = soup.find("article") or soup.find("main") or soup.body
    if main is None:
        return []
    # 删除噪音节点
    for tag in main.find_all(_NOISE_TAGS):
        tag.decompose()

    sections: list[dict] = []
    current_heading = ""
    buf: list[str] = []

    def flush():
        if current_heading or buf:
            text = " ".join(x.strip() for x in buf if x.strip())
            if text:
                sections.append({"heading": current_heading, "text": text[:8000]})

    for el in main.find_all(["h1", "h2", "h3", "h4", "p", "li", "pre", "td", "code"]):
        if _HEADING_RE.match(el.name):
            flush()
            current_heading = el.get_text(" ", strip=True)
            buf = []
        else:
            txt = el.get_text(" ", strip=True)
            if txt:
                buf.append(txt)
    flush()
    if not sections:
        # 退化：整页正文
        text = main.get_text("\n", strip=True)
        if text:
            sections.append({"heading": "", "text": text[:8000]})
    return sections


def _extract_links(soup: BeautifulSoup, base: str) -> list[str]:
    out = []
    for a in soup.find_all("a", href=True):
        href = a["href"].strip()
        if not href or href.startswith(("javascript:", "mailto:", "tel:", "#")):
            continue
        full = urljoin(base, href)
        if any(full.lower().endswith(ext) for ext in _ASSET_EXT):
            continue
        out.append(_norm_url(full))
    # 去重保序
    seen, uniq = set(), []
    for u in out:
        if u not in seen:
            seen.add(u)
            uniq.append(u)
    return uniq


_BROWSER = None  # Playwright 单例（懒加载）；未安装则为 False


def _get_browser():
    """懒加载 Playwright 无头浏览器（可选）。未安装时返回 False，回退到 bs4。"""
    global _BROWSER
    if _BROWSER is None:
        try:
            from playwright.sync_api import sync_playwright
            _BROWSER = sync_playwright().start().chromium.launch(headless=True)
        except Exception:
            _BROWSER = False
    return _BROWSER


def _fetch_html(url: str, timeout: int = 20) -> str | None:
    """获取页面 HTML。优先用 Playwright 渲染（能处理 SPA / JS 注入内容），
    失败或未安装时回退到 requests。"""
    browser = _get_browser()
    if browser:
        try:
            page = browser.new_page()
            page.goto(url, wait_until="networkidle", timeout=timeout * 1000)
            html = page.content()
            page.close()
            return html
        except Exception:
            pass
    try:
        resp = requests.get(
            url,
            timeout=timeout,
            headers={"User-Agent": "Mozilla/5.0 (compatible; OceanBaseKG/1.0; +crawler)"},
        )
        if resp.status_code != 200:
            return None
        ctype = resp.headers.get("Content-Type", "")
        if "html" not in ctype.lower():
            return None
        return resp.text
    except requests.RequestException:
        return None


def _fetch(url: str, timeout: int = 20) -> BeautifulSoup | None:
    html = _fetch_html(url, timeout)
    if not html:
        return None
    return BeautifulSoup(html, "html.parser")


def crawl(
    seeds: list[str],
    *,
    source: str = "docs",
    max_depth: int = 3,
    delay: float = 1.0,
    max_pages: int = 400,
) -> list[Document]:
    """BFS 抓取，返回 Document 列表。"""
    visited: set[str] = set()
    docs: list[Document] = []
    q: deque[tuple[str, int]] = deque()

    domain = urlparse(seeds[0]).netloc if seeds else ""
    for s in seeds:
        n = _norm_url(s)
        if n not in visited:
            visited.add(n)
            q.append((n, 0))

    while q and len(docs) < max_pages:
        url, depth = q.popleft()
        soup = _fetch(url)
        if soup is None:
            continue
        title = (soup.title.get_text(strip=True) if soup.title else "") or url
        links = _extract_links(soup, url)
        docs.append(Document(url=url, title=title, source=source, sections=_extract_sections(soup), links=links))

        if depth < max_depth:
            for link in links:
                if link not in visited and _same_domain(link, domain):
                    visited.add(link)
                    q.append((link, depth + 1))
        if delay > 0:
            time.sleep(delay)

    return docs
