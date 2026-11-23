'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
    renderMarkdown,
    escapeHtml,
    safeHref,
    mdIsTableSep
} = require('../../main/resources/static/js/markdown.js');

test('escapes raw HTML and script tags before rendering', () => {
    const html = renderMarkdown('hello <script>alert(1)</script> & <b>world</b>');
    assert.ok(!html.includes('<script>'));
    assert.ok(html.includes('&lt;script&gt;'));
    assert.ok(html.includes('&lt;b&gt;'));
});

test('allows only http, https and mailto link schemes', () => {
    assert.equal(safeHref('https://example.com/a?x=1'), 'https://example.com/a?x=1');
    assert.equal(safeHref('http://example.com'), 'http://example.com');
    assert.equal(safeHref('mailto:ops@example.com'), 'mailto:ops@example.com');
    assert.equal(safeHref('javascript:alert(1)'), null);
    assert.equal(safeHref('data:text/html;base64,xxx'), null);
    assert.equal(safeHref('/relative/path'), null);
});

test('unsafe markdown links render as plain label without href', () => {
    const html = renderMarkdown('[click me](javascript:alert(1))');
    assert.ok(!html.includes('href'));
    assert.ok(html.includes('click me'));
});

test('safe markdown links carry rel and target', () => {
    const html = renderMarkdown('[docs](https://example.com/docs)');
    assert.match(html, /href="https:\/\/example\.com\/docs"/);
    assert.match(html, /target="_blank"/);
    assert.match(html, /rel="noopener noreferrer"/);
});

test('renders task lists with checked and unchecked markers', () => {
    const html = renderMarkdown('- [x] shipped\n- [ ] todo');
    assert.match(html, /class="md-task"/);
    assert.match(html, /☑/);
    assert.match(html, /☐/);
    assert.match(html, /<s>shipped<\/s>/);
});

test('renders horizontal rules but keeps table separators as tables', () => {
    assert.match(renderMarkdown('before\n\n---\n\nafter'), /<hr\/>/);
    const table = renderMarkdown('| a | b |\n|---|---|\n| 1 | 2 |');
    assert.match(table, /<table>/);
    assert.match(table, /<th>a<\/th>/);
    assert.match(table, /<td>2<\/td>/);
    assert.ok(!table.includes('<hr/>'));
    assert.equal(mdIsTableSep('|---|---|'), true);
    assert.equal(mdIsTableSep('| a | b |'), false);
});

test('fenced code blocks show a language label and keep highlighting', () => {
    const html = renderMarkdown('```sql\nSELECT count(*) FROM book_info\n```');
    assert.match(html, /class="md-lang">sql</);
    assert.match(html, /class="k">SELECT</);
    assert.match(html, /<pre class="md-code">/);
});

test('inline code is protected from emphasis rules', () => {
    const html = renderMarkdown('use `**not bold**` here');
    assert.match(html, /<code class="md-inline">\*\*not bold\*\*<\/code>/);
    assert.ok(!html.includes('<strong>not bold</strong>'));
});

test('headings, quotes and emphasis still render', () => {
    const html = renderMarkdown('# Title\n\n> quoted\n\n**bold** and *em*');
    assert.match(html, /<h1>Title<\/h1>/);
    assert.match(html, /<blockquote>quoted<\/blockquote>/);
    assert.match(html, /<strong>bold<\/strong>/);
    assert.match(html, /<em>em<\/em>/);
});

test('escapeHtml covers the five dangerous characters', () => {
    assert.equal(escapeHtml(`&<>"'`), '&amp;&lt;&gt;&quot;&#39;');
});

test('empty input renders nothing', () => {
    assert.equal(renderMarkdown(''), '');
    assert.equal(renderMarkdown(null), '');
});
