/*
 * agent-hub 轻量 Markdown 渲染器。
 *
 * 设计约束（issue #32）：
 *   1. 先 HTML 转义再解析，绝不把模型输出里的 <script> 当作标签执行；
 *   2. 链接协议白名单：只允许 http / https / mailto，javascript: 等直接降级为纯文本；
 *   3. 自包含、无第三方依赖，既能在浏览器 <script> 里用，也能在 Node 单测里 require。
 *
 * 不解析嵌套列表或复杂表格——A2UI 结构化卡片由 chat.html 单独渲染，
 * 这里只负责助手正文的「读得舒服」和「渲染安全」。
 */
(function (root, factory) {
    const api = factory();
    if (typeof module === 'object' && module.exports) {
        module.exports = api;
    } else {
        root.MarkdownRenderer = api;
    }
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
    'use strict';

    const ALLOWED_SCHEMES = ['http:', 'https:', 'mailto:'];
    const PLACEHOLDER = '\u0000MD';
    const CODE_KEY = /^\u0000MDCODE(\d+)\u0000$/;
    const LINK_KEY = /^\u0000MDLINK(\d+)\u0000$/;

    function escapeHtml(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            '"': '&quot;',
            "'": '&#39;'
        })[c]);
    }

    /** 只放行白名单协议；相对链接与 javascript:/data: 一律返回 null。 */
    function safeHref(raw) {
        const value = String(raw == null ? '' : raw).trim().replace(/&amp;/g, '&');
        try {
            const protocol = new URL(value).protocol.toLowerCase();
            return ALLOWED_SCHEMES.includes(protocol) ? value : null;
        } catch (_) {
            return null;
        }
    }

    function mdCells(line) {
        return line.slice(1, -1).split('|').map(x => x.trim());
    }

    function mdIsTableSep(line) {
        return /^\|[\s\-:|]+\|$/.test(line) && line.includes('-');
    }

    function mdHighlight(code) {
        const kw = 'SELECT|FROM|WHERE|GROUP BY|ORDER BY|LIMIT|JOIN|LEFT|RIGHT|INNER|ON|AS|AND|OR|'
                 + 'INSERT|UPDATE|DELETE|CREATE|DROP|SUM|COUNT|AVG|MAX|MIN|ROUND|CASE|WHEN|THEN|ELSE|END|WITH|DESC|ASC';
        return code.replace(new RegExp('\\b(' + kw + ')\\b', 'g'), '<span class="k">$1</span>');
    }

    /**
     * 行内语法：代码、链接、粗体、斜体。
     * 代码与链接先换成占位符，避免其中的 `*`、`_` 被后续规则误伤。
     */
    function mdInline(s) {
        const stash = [];
        let text = String(s == null ? '' : s);

        text = text.replace(/`([^`]+)`/g, (_m, code) => {
            const key = PLACEHOLDER + 'CODE' + stash.length + '\u0000';
            stash.push('<code class="md-inline">' + code + '</code>');
            return key;
        });

        // [label](url)：非法协议只渲染 label，绝不生成 href。
        text = text.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_m, label, url) => {
            const href = safeHref(url);
            const key = PLACEHOLDER + 'LINK' + stash.length + '\u0000';
            stash.push(href
                ? '<a href="' + escapeHtml(href) + '" target="_blank" rel="noopener noreferrer">' + label + '</a>'
                : label);
            return key;
        });

        // 裸链接：当前实现只自动识别 http(s)；先转义 & 再交给 safeHref 校验。
        text = text.replace(/(https?:\/\/[^\s<]+)/g, (_m, url) => {
            const href = safeHref(url);
            if (!href) return url;
            const key = PLACEHOLDER + 'LINK' + stash.length + '\u0000';
            stash.push('<a href="' + escapeHtml(href) + '" target="_blank" rel="noopener noreferrer">' + escapeHtml(href) + '</a>');
            return key;
        });

        text = text
            .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
            .replace(/(^|[^*])\*([^*]+)\*/g, '$1<em>$2</em>')
            .replace(/__([^_]+)__/g, '<strong>$1</strong>');

        return text.replace(/\u0000MD(CODE|LINK)(\d+)\u0000/g, (_m, kind, idx) => stash[Number(idx)]);
    }

    /** 支持 - [ ] / - [x] 任务列表；返回 null 表示该行不是任务项。 */
    function mdTaskItem(body) {
        const m = body.match(/^\[([ xX])\]\s*(.*)$/);
        if (!m) return null;
        const done = m[1].toLowerCase() === 'x';
        return '<li class="md-task">'
             + '<span class="md-check" aria-hidden="true">' + (done ? '☑' : '☐') + '</span>'
             + '<span class="md-task-text">' + (done ? '<s>' + m[2] + '</s>' : m[2]) + '</span>'
             + '</li>';
    }

    function renderMarkdown(src) {
        if (!src) return '';
        const escaped = escapeHtml(src);
        const codeBlocks = [];
        const text = escaped.replace(/```([a-zA-Z0-9_+#.-]*)\n([\s\S]*?)```/g, (_m, lang, code) => {
            const key = PLACEHOLDER + 'CODE' + codeBlocks.length + '\u0000';
            const label = lang ? '<span class="md-lang">' + escapeHtml(lang) + '</span>' : '';
            codeBlocks.push(
                '<pre class="md-code">' + label + '<code>'
                    + mdHighlight(code.replace(/\n$/, '')) + '</code></pre>');
            return key;
        });

        const lines = text.split('\n');
        let out = '', para = [], list = null, items = [], i = 0;
        const flushPara = () => {
            if (para.length) { out += '<p>' + mdInline(para.join('<br>')) + '</p>'; para = []; }
        };
        const flushList = () => {
            if (list) { out += '<' + list + '>' + items.join('') + '</' + list + '>'; list = null; items = []; }
        };

        while (i < lines.length) {
            const line = lines[i], t = line.trim();
            const codeHit = t.match(CODE_KEY);
            if (codeHit) { flushPara(); flushList(); out += codeBlocks[Number(codeHit[1])]; i++; continue; }
            if (!t) { flushPara(); flushList(); i++; continue; }
            // 水平线放在表格之前：|---| 这类表格分隔行不会误判为 hr。
            if (/^(?:-{3,}|\*{3,}|_{3,})$/.test(t)) { flushPara(); flushList(); out += '<hr/>'; i++; continue; }
            if (/^\|.*\|$/.test(t) && i + 1 < lines.length && mdIsTableSep(lines[i + 1].trim())) {
                flushPara(); flushList();
                const head = mdCells(t), rows = [];
                i += 2;
                while (i < lines.length && /^\|.*\|$/.test(lines[i].trim())) {
                    rows.push(mdCells(lines[i].trim())); i++;
                }
                out += '<table><thead><tr>'
                     + head.map(c => '<th>' + mdInline(c) + '</th>').join('')
                     + '</tr></thead><tbody>'
                     + rows.map(r => '<tr>' + r.map(c => '<td>' + mdInline(c) + '</td>').join('') + '</tr>').join('')
                     + '</tbody></table>';
                continue;
            }
            const h = t.match(/^(#{1,3})\s+(.*)$/);
            if (h) { flushPara(); flushList(); out += '<h' + h[1].length + '>' + mdInline(h[2]) + '</h' + h[1].length + '>'; i++; continue; }
            const ul = t.match(/^[-*]\s+(.*)$/), ol = t.match(/^\d+\.\s+(.*)$/);
            if (ul || ol) {
                flushPara();
                const want = ul ? 'ul' : 'ol';
                if (list !== want) { flushList(); list = want; }
                const body = (ul || ol)[1];
                const task = ul ? mdTaskItem(body) : null;
                items.push(task || '<li>' + mdInline(body) + '</li>');
                i++; continue;
            }
            if (t.startsWith('&gt;')) { flushPara(); flushList(); out += '<blockquote>' + mdInline(t.slice(4).trim()) + '</blockquote>'; i++; continue; }
            flushList(); para.push(t); i++;
        }
        flushPara(); flushList();
        return out;
    }

    return { renderMarkdown, escapeHtml, mdInline, safeHref, mdIsTableSep, mdHighlight };
});
