import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const previewSource = readFileSync('src/components/SQLPreview/index.tsx', 'utf8');
const styleSource = readFileSync('src/components/SQLPreview/style.ts', 'utf8');
const aiSource = readFileSync('src/blocks/AI/index.tsx', 'utf8');

assert.match(previewSource, /useStyles\(\{ wrap \}\)/, 'SQLPreview must apply its wrap mode to local styles');
assert.match(
  previewSource,
  /data-sql-preview-wrap=\{wrap \? 'wrap' : 'scroll'\}/,
  'SQLPreview must expose its active layout mode for desktop diagnostics',
);

assert.match(styleSource, /max-width: 100%;[\s\S]*min-width: 0;/, 'SQLPreview must stay within its parent width');
assert.match(styleSource, /overflow-x: hidden;/, 'the SQLPreview wrapper must contain horizontal overflow');
assert.match(styleSource, /pre \{[\s\S]*overflow-x: auto;/, 'the pre element must own horizontal scrolling');
assert.match(
  styleSource,
  /&& pre code \{[\s\S]*width: \$\{wrap \? '100%' : 'max-content'\};/,
  'unwrapped SQL must preserve its intrinsic width inside the scrolling pre element',
);
assert.match(
  styleSource,
  /white-space: \$\{wrap \? 'pre-wrap' : 'pre'\};/,
  'wrap must switch between wrapped and single-line SQL rendering',
);

assert.match(
  aiSource,
  /source="ai-markdown-sql-code-block"[\s\S]*?type="block"[\s\S]*?wrap=\{false\}/,
  'AI SQL fences must use the contained horizontal-scroll layout',
);

console.log('SQLPreview layout contract tests passed');
