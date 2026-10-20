"""OceanBase 主仓库代码库爬虫。

采用 blobless 浅克隆（--filter=blob:none）把目录树拉到本地，再按扩展名扫描源码文件，
用轻量正则抽取 模块/文件/类/函数 以及 include/import 依赖，输出结构化 code graph。

不依赖 GitHub API 配额；首次克隆稍慢，后续复用本地缓存目录。
"""
from __future__ import annotations

import os
import re
import subprocess
import tempfile
from dataclasses import dataclass, field

_LANG_BY_EXT = {
    ".cc": "cpp", ".cpp": "cpp", ".cxx": "cpp", ".h": "cpp", ".hpp": "cpp",
    ".py": "python", ".go": "go", ".java": "java", ".js": "js", ".ts": "ts",
}
_SOURCE_EXT = set(_LANG_BY_EXT.keys())

# 类/结构体
_RE_CLASS = {
    "cpp": re.compile(r'\b(?:class|struct)\s+(\w+)'),
    "python": re.compile(r'\bclass\s+(\w+)'),
    "java": re.compile(r'\b(?:public\s+|final\s+|abstract\s+)*(?:class|interface|enum)\s+(\w+)'),
    "go": re.compile(r'\btype\s+(\w+)\s+struct'),
    "js": re.compile(r'\bclass\s+(\w+)'),
    "ts": re.compile(r'\bclass\s+(\w+)'),
}
# 函数/方法
_RE_FUNC = {
    "cpp": re.compile(r'\b\w+(?:::(\w+))\s*\('),
    "python": re.compile(r'\bdef\s+(\w+)'),
    "java": re.compile(r'\b(?:public|private|protected|static|\s)*?\b\w+\s+(\w+)\s*\([^;]*\)\s*\{'),
    "go": re.compile(r'\bfunc\s+(?:\([^)]*\)\s+)?(\w+)'),
    "js": re.compile(r'\bfunction\s+(\w+)|(\w+)\s*=\s*\(?[^=]*\)?\s*=>'),
    "ts": re.compile(r'\b(?:function\s+(\w+)|(\w+)\s*\([^)]*\)\s*:)'),
}
# include / import
_RE_INCLUDE = re.compile(r'#\s*include\s+["<]([^">]+)[">]')
_RE_IMPORT_PY = re.compile(r'\bfrom\s+([\w.]+)\s+import|import\s+([\w.]+)')
_RE_IMPORT_GO = re.compile(r'\bimport\s+(?:\([^)]*\)|"([^"]+)"|\w+\s+"([^"]+)"|\.\w+\s+"([^"]+)")')


@dataclass
class CodeGraph:
    repo: str
    modules: list[dict] = field(default_factory=list)   # [{"path"}]
    files: list[dict] = field(default_factory=list)     # [{"path","lang","module"}]
    classes: list[dict] = field(default_factory=list)   # [{"name","file"}]
    functions: list[dict] = field(default_factory=list) # [{"name","file","cls"}]
    imports: list[dict] = field(default_factory=list)    # [{"file","target"}]  target=被引用路径/模块

    def to_dict(self) -> dict:
        return {
            "repo": self.repo,
            "modules": self.modules,
            "files": self.files,
            "classes": self.classes,
            "functions": self.functions,
            "imports": self.imports,
        }


def _clone(repo: str, clone_dir: str, token: str | None) -> str:
    os.makedirs(os.path.dirname(clone_dir) or ".", exist_ok=True)
    if os.path.isdir(os.path.join(clone_dir, ".git")):
        return clone_dir
    url = f"https://github.com/{repo}.git"
    if token:
        url = f"https://{token}@github.com/{repo}.git"
    subprocess.run(
        ["git", "clone", "--depth", "1", "--filter=blob:none", url, clone_dir],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return clone_dir


def _in_scope(rel: str, include_dirs, skip_dirs) -> bool:
    parts = rel.split(os.sep)
    top = parts[0] if parts else ""
    if any(s in parts for s in skip_dirs):
        return False
    if include_dirs:
        return top in include_dirs
    return True


def crawl_repo(
    repo: str = "oceanbase/oceanbase",
    clone_dir: str = ".cache/oceanbase_repo",
    *,
    token: str | None = None,
    max_files: int = 1500,
    include_dirs: list[str] | None = None,
    skip_dirs: list[str] | None = None,
    skip_large_files_kb: int = 512,
) -> CodeGraph:
    include_dirs = include_dirs or []
    skip_dirs = skip_dirs or []
    clone_dir = os.path.abspath(clone_dir)
    _clone(repo, clone_dir, token)

    graph = CodeGraph(repo=repo)
    file_count = 0
    seen_file: set[str] = set()
    seen_mod: set[str] = set()
    seen_cls: set[tuple] = set()
    seen_fn: set[tuple] = set()

    for root, dirs, files in os.walk(clone_dir):
        dirs[:] = [d for d in dirs if d not in skip_dirs]
        for fn in files:
            if file_count >= max_files:
                break
            ext = os.path.splitext(fn)[1].lower()
            if ext not in _SOURCE_EXT:
                continue
            full = os.path.join(root, fn)
            rel = os.path.relpath(full, clone_dir)
            if not _in_scope(rel, include_dirs, skip_dirs):
                continue
            try:
                size_kb = os.path.getsize(full) / 1024.0
            except OSError:
                continue
            if size_kb > skip_large_files_kb:
                continue

            lang = _LANG_BY_EXT[ext]
            module = os.path.dirname(rel) or "."
            if module not in seen_mod:
                seen_mod.add(module)
                graph.modules.append({"path": module})
            if rel not in seen_file:
                seen_file.add(rel)
                graph.files.append({"path": rel, "lang": lang, "module": module})
                file_count += 1

            try:
                with open(full, "r", encoding="utf-8", errors="ignore") as fh:
                    text = fh.read()
            except OSError:
                continue

            for m in _RE_CLASS[lang].finditer(text):
                name = m.group(1)
                key = (name, rel)
                if key not in seen_cls:
                    seen_cls.add(key)
                    graph.classes.append({"name": name, "file": rel})

            for m in _RE_FUNC[lang].finditer(text):
                name = m.group(1) or m.group(2) or m.group(3)
                if not name or name in ("if", "for", "while", "switch", "catch", "return"):
                    continue
                key = (name, rel)
                if key not in seen_fn:
                    seen_fn.add(key)
                    graph.functions.append({"name": name, "file": rel, "cls": None})

            # import / include 依赖
            for inc in _RE_INCLUDE.finditer(text):
                graph.imports.append({"file": rel, "target": inc.group(1)})
            if lang == "python":
                for m in _RE_IMPORT_PY.finditer(text):
                    tgt = m.group(1) or m.group(2)
                    if tgt:
                        graph.imports.append({"file": rel, "target": tgt.replace(".", os.sep)})

    return graph
