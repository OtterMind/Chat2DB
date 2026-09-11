import assert from 'node:assert/strict';
import test from 'node:test';
import { JSDOM } from 'jsdom';

const globalObj = globalThis as unknown as Record<string, unknown>;
globalObj.__RUNTIME_ENV__ = 'community';
globalObj.__ENV__ = 'test';

// A realistic DDL sample: multi-line, indented, comments (incl. CJK),
// a very long line (wraps in the UI), and quoted identifiers.
const DDL_SAMPLE = `-- 建表语句 with comment, 注释
CREATE TABLE \`user_info\` (
  \`id\` bigint(20) NOT NULL AUTO_INCREMENT,
  \`user_name\` varchar(255) DEFAULT NULL COMMENT 'user name with a very very very very long comment',
  PRIMARY KEY (\`id\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;`;

function installDomGlobals(dom: JSDOM) {
  globalObj.window = dom.window;
  globalObj.document = dom.window.document;
  globalObj.NodeFilter = dom.window.NodeFilter;
  globalObj.HTMLElement = dom.window.HTMLElement;
  globalObj.Node = dom.window.Node;
  globalObj.Range = dom.window.Range;
}

test('findDdlSearchMatches: case-insensitive, non-overlapping, full-SQL coverage', async () => {
  const { findDdlSearchMatches } = await import('./ddlSearch');

  assert.deepEqual(findDdlSearchMatches(DDL_SAMPLE, ''), []);
  assert.deepEqual(findDdlSearchMatches('', 'id'), []);

  const idMatches = findDdlSearchMatches(DDL_SAMPLE, 'user_info');
  assert.equal(idMatches.length, 1);
  assert.equal(DDL_SAMPLE.slice(idMatches[0].start, idMatches[0].end), 'user_info');

  // Case-insensitive.
  const createMatches = findDdlSearchMatches(DDL_SAMPLE, 'create table');
  assert.equal(createMatches.length, 1);
  assert.equal(DDL_SAMPLE.slice(createMatches[0].start, createMatches[0].end), 'CREATE TABLE');

  // Non-overlapping: "aa" in "aaaa" matches at 0 and 2, not 1.
  assert.deepEqual(findDdlSearchMatches('aaaa', 'aa'), [
    { start: 0, end: 2 },
    { start: 2, end: 4 },
  ]);

  // Matches hidden in folded/collapsed regions still count: matching runs on
  // the full SQL text, not on the visible subset.
  const commentMatches = findDdlSearchMatches(DDL_SAMPLE, '注释');
  assert.equal(commentMatches.length, 1);

  // Multiple matches across lines.
  const pkMatches = findDdlSearchMatches(DDL_SAMPLE, '`id`');
  assert.equal(pkMatches.length, 2);
});

test('feature detection: jsdom lacks CSS Custom Highlight API', async () => {
  const dom = new JSDOM('<div></div>');
  installDomGlobals(dom);
  const { isDdlSearchHighlightSupported } = await import('./ddlSearch');
  assert.equal(
    isDdlSearchHighlightSupported(),
    false,
    'jsdom has no CSS.highlights - detection must report unsupported instead of throwing',
  );
});

test('highlight names and stylesheet are instance-scoped and consistent', async () => {
  const { buildDdlSearchHighlightStyleText, getDdlSearchHighlightNames, nextDdlSearchInstanceId } = await import(
    './ddlSearch'
  );
  const idA = nextDdlSearchInstanceId();
  const idB = nextDdlSearchInstanceId();
  assert.notEqual(idA, idB);

  const names = getDdlSearchHighlightNames(idA);
  const cssText = buildDdlSearchHighlightStyleText(idA);
  assert.match(cssText, new RegExp(`::highlight\\(${names.all}\\)`));
  assert.match(cssText, new RegExp(`::highlight\\(${names.active}\\)`));
  assert.ok(cssText.includes('rgba(255, 255, 0, 0.2)'), 'all-match style follows the FESearch #ff0/20% tone');
  assert.ok(cssText.includes('rgba(255, 255, 0, 0.6)'), 'active-match style follows the FESearch #ff0/60% tone');
  const namesB = getDdlSearchHighlightNames(idB);
  assert.ok(!cssText.includes(`::highlight(${namesB.all})`), 'stylesheet must not leak into other instances');
  assert.ok(!cssText.includes(`::highlight(${namesB.active})`), 'stylesheet must not leak into other instances');
});

test('createMatchRanges maps source SQL offsets onto real Shiki output', async () => {
  // Spike gate 1: verify against the real highlighting pipeline (shikiji is
  // what @chat2db/ui CodeHighlighter uses), not a hand-written fixture.
  const { getHighlighter } = await import('shikiji');
  const highlighter = await getHighlighter({ langs: ['sql'], themes: ['dark-plus'] });
  const html = highlighter.codeToHtml(DDL_SAMPLE, { lang: 'sql', theme: 'dark-plus' });

  const dom = new JSDOM(html);
  installDomGlobals(dom);
  const pre = dom.window.document.querySelector('pre')!;
  assert.ok(pre.className.includes('shiki'), 'shiki pre expected');

  // Critical invariant: rendered text equals the source SQL byte-for-byte,
  // including newlines, indentation, comments and long wrapped lines.
  assert.equal(pre.textContent, DDL_SAMPLE);

  const { findDdlSearchMatches, createMatchRanges } = await import('./ddlSearch');
  const queries = ['user_info', 'CREATE TABLE', '`id`', 'very very long', 'utf8mb4', '注释'];
  const matches = queries.flatMap((q) => findDdlSearchMatches(DDL_SAMPLE, q)).sort((a, b) => a.start - b.start);
  assert.ok(matches.length >= 6);

  const ranges = createMatchRanges(pre, DDL_SAMPLE, matches);
  assert.ok(ranges, 'ranges must be created when textContent is in sync');
  assert.equal(ranges!.length, matches.length);
  ranges!.forEach((range, i) => {
    assert.equal(
      range.toString(),
      DDL_SAMPLE.slice(matches[i].start, matches[i].end),
      `range #${i} must cover exactly the matched text (token-split safe)`,
    );
  });

  // A match spanning multiple Shiki token spans resolves to a single range
  // whose boundary points land inside different text nodes.
  const spanning = findDdlSearchMatches(DDL_SAMPLE, 'CREATE TABLE `user_info`');
  const spanningRanges = createMatchRanges(pre, DDL_SAMPLE, spanning)!;
  assert.equal(spanningRanges.length, 1);
  assert.equal(spanningRanges[0].toString(), 'CREATE TABLE `user_info`');
  assert.notEqual(spanningRanges[0].startContainer, spanningRanges[0].endContainer);

  // SQL update: stale ranges are dropped and re-mapped against the new text.
  const updatedSql = DDL_SAMPLE.replace('user_info', 'account');
  const updatedHtml = highlighter.codeToHtml(updatedSql, { lang: 'sql', theme: 'dark-plus' });
  const updatedDom = new JSDOM(updatedHtml);
  installDomGlobals(updatedDom);
  const updatedPre = updatedDom.window.document.querySelector('pre')!;
  const updatedMatches = findDdlSearchMatches(updatedSql, 'account');
  const updatedRanges = createMatchRanges(updatedPre, updatedSql, updatedMatches)!;
  assert.equal(updatedRanges.length, 1);
  assert.equal(updatedRanges[0].toString(), 'account');

  // Out-of-sync DOM (e.g. async highlight not painted yet) must not guess.
  const staleDom = new JSDOM('<pre class="shiki"></pre>');
  installDomGlobals(staleDom);
  const stalePre = staleDom.window.document.querySelector('pre')!;
  assert.equal(createMatchRanges(stalePre, DDL_SAMPLE, matches), null, 'stale DOM returns null for retry');
  stalePre.textContent = DDL_SAMPLE.replace('user_info', 'test_info');
  assert.equal(createMatchRanges(stalePre, DDL_SAMPLE, matches), null, 'same-length old object must not receive highlights');
});

test('createMatchRanges tolerates the highlighter fallback markup', async () => {
  // useHighlight falls back to a plain <pre><code> on highlight errors.
  const dom = new JSDOM(`<pre><code></code></pre>`);
  installDomGlobals(dom);
  const code = dom.window.document.querySelector('code')!;
  code.textContent = DDL_SAMPLE;
  const pre = dom.window.document.querySelector('pre')!;

  const { findDdlSearchMatches, createMatchRanges } = await import('./ddlSearch');
  const matches = findDdlSearchMatches(DDL_SAMPLE, 'user_name');
  const ranges = createMatchRanges(pre, DDL_SAMPLE, matches);
  assert.equal(ranges?.length, 1);
  assert.equal(ranges![0].toString(), 'user_name');
});

test('createMatchRanges accepts Shikiji CRLF rendering without losing offsets', async () => {
  const { getHighlighter } = await import('shikiji');
  const highlighter = await getHighlighter({ langs: ['sql'], themes: ['dark-plus'] });
  const sql = 'CREATE TABLE t (\r\n id INT\r\n); -- 中文';
  const dom = new JSDOM(highlighter.codeToHtml(sql, { lang: 'sql', theme: 'dark-plus' }));
  installDomGlobals(dom);
  const { findDdlSearchMatches, createMatchRanges } = await import('./ddlSearch');
  const ranges = createMatchRanges(dom.window.document.querySelector('pre')!, sql, findDdlSearchMatches(sql, '中文'));
  assert.equal(ranges?.[0].toString(), '中文');
});

test('pickVisibleRect skips zero-sized rects from folded (height:0) content', async () => {
  const { pickVisibleRect } = await import('./ddlSearch');
  const zero = { width: 0, height: 0 } as DOMRect;
  const visible = { width: 40, height: 14 } as DOMRect;
  assert.equal(pickVisibleRect([zero, visible] as unknown as ArrayLike<DOMRect>), visible);
  const flat = { width: 30, height: 0 } as DOMRect;
  assert.equal(pickVisibleRect([flat] as unknown as ArrayLike<DOMRect>), flat, 'falls back to any non-empty axis');
  assert.equal(pickVisibleRect([zero] as unknown as ArrayLike<DOMRect>), null);
  assert.equal(pickVisibleRect([] as unknown as ArrayLike<DOMRect>), null);
});

test('computeCenteredScrollDelta and isRectFullyVisible', async () => {
  const { computeCenteredScrollDelta, isRectFullyVisible } = await import('./ddlSearch');
  const container = { top: 0, bottom: 200, height: 200 };
  const below = { top: 400, bottom: 420, height: 20 };
  assert.equal(computeCenteredScrollDelta(below, container), 410 - 100);
  assert.equal(isRectFullyVisible(below, container), false);
  const inside = { top: 50, bottom: 80, height: 30 };
  assert.equal(isRectFullyVisible(inside, container), true);
  assert.equal(computeCenteredScrollDelta(inside, container), 65 - 100);
});

test('nextActiveIndex wraps around in both directions', async () => {
  const { nextActiveIndex } = await import('./ddlSearch');
  assert.equal(nextActiveIndex(-1, 3, 1), 0);
  assert.equal(nextActiveIndex(-1, 3, -1), 2);
  assert.equal(nextActiveIndex(2, 3, 1), 0);
  assert.equal(nextActiveIndex(0, 3, -1), 2);
  assert.equal(nextActiveIndex(0, 0, 1), -1);
});

test('findScrollContainer skips overflow ancestors without a scroll range', async () => {
  const dom = new JSDOM(
    '<div id="outer" style="overflow-y: auto"><div id="inner" style="overflow-y: auto"><pre class="shiki">abc</pre></div></div>',
  );
  installDomGlobals(dom);
  const pre = dom.window.document.querySelector('pre')!;
  const text = pre.firstChild!;
  const outer = dom.window.document.getElementById('outer')!;
  const inner = dom.window.document.getElementById('inner')!;
  Object.defineProperties(outer, { clientHeight: { value: 200 }, scrollHeight: { value: 1200 } });
  Object.defineProperties(inner, { clientHeight: { value: 1200 }, scrollHeight: { value: 1200, configurable: true } });
  const { findScrollContainer } = await import('./ddlSearch');
  const found = findScrollContainer(text);
  assert.equal(found?.id, 'outer');
  Object.defineProperty(inner, 'scrollHeight', { value: 1800 });
  assert.equal(findScrollContainer(text), inner, 'use the inner viewport when it really scrolls');
  assert.equal(findScrollContainer(null), null);
});
