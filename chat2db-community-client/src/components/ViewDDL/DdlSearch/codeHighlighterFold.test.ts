/**
 * Gate-2 verification (Issue #2748, plan B1): how does CodeHighlighter folding
 * actually behave on the two DDL surfaces?
 *
 * The real component cannot be mounted in Node (@chat2db/ui's barrel imports
 * .webp assets and type-only ESM re-exports that Node refuses to link), so
 * this test pins the exact runtime behaviour by asserting against the
 * installed package source - the same readFileSync guard pattern used by
 * resultSetUi.test.ts. Conclusions drawn from the measured source:
 *
 *  1. type="pure" (used by ViewDDL and the tree "view DDL" modal) renders no
 *     fold control at all - content can never be folded, so "search expands
 *     the DDL" is a no-op on these paths and full-SQL matching is stable.
 *  2. Fold is internal useState(false) with no controlled prop: dropping the
 *     `foldable` prop would NOT unfold; only a remount (key change) resets it.
 *  3. Folding hides via inline height:0 + overflow:hidden - text nodes stay
 *     in the DOM (textContent intact) but produce zero-size client rects,
 *     which pickVisibleRect() deliberately skips.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

test('CodeHighlighter fold behaviour pinned from installed source', () => {
  const source = readFileSync(require.resolve('@chat2db/ui/es/CodeHighlighter/index.js'), 'utf8');

  // Fold is internal, uncontrolled state, initially expanded.
  assert.match(source, /useState\(false\)/, 'fold state is internal useState(false) - no controlled prop exists');
  assert.match(source, /setFold\(!fold\)/, 'the only state transition is the fold button click');

  // The fold toggle exists only in the block bar; pure mode cannot fold.
  const blockBarStart = source.indexOf('blockDom');
  const pureDomStart = source.indexOf('pureDom');
  const returnStart = source.indexOf('return /*#__PURE__*/React.createElement("div"');
  assert.ok(blockBarStart > -1 && pureDomStart > -1 && returnStart > -1);
  const pureSection = source.slice(pureDomStart, returnStart);
  assert.ok(!pureSection.includes('setFold'), 'pure mode renders no fold toggle: nothing to auto-expand');

  // Folding keeps the DOM (height:0), so offsets still map but rects are zero.
  assert.match(
    source,
    /fold\s*\?\s*\{\s*height:\s*0,\s*overflow:\s*['"]hidden['"]\s*\}\s*:\s*\{\}/,
    'fold collapses via height:0 while preserving text nodes',
  );

  // renderAddons is honoured in pure mode - the search bar mount point.
  assert.match(pureSection, /renderAddons\s*&&\s*renderAddons\(/, 'pure mode renders renderAddons');

  // The addon slot is hover-only (opacity 0 until wrapper hover).
  const styleSource = readFileSync(require.resolve('@chat2db/ui/es/CodeHighlighter/style.js'), 'utf8');
  assert.match(styleSource, /opacity:\s*0/, 'pure addon slot is hidden until hover');
  assert.match(styleSource, /:hover/, 'wrapper hover reveals the addon slot');
});
