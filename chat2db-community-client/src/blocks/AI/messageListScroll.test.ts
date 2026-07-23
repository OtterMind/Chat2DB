import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  resetMessageContainerHorizontalScroll,
  scrollMessageContainerTo,
  scrollMessageContainerToBottom,
} from './messageListScroll';

const scrollCalls: ScrollToOptions[] = [];
const scrollOperations: string[] = [];
let scrollLeft = 48;
const container = {
  scrollHeight: 1800,
  get scrollLeft() {
    return scrollLeft;
  },
  set scrollLeft(value: number) {
    scrollLeft = value;
    scrollOperations.push(`left:${value}`);
  },
  scrollTo(options: ScrollToOptions) {
    scrollCalls.push(options);
    scrollOperations.push(`top:${options.top}:${options.behavior}`);
  },
} as unknown as HTMLElement;

scrollMessageContainerTo(container, 320, 'smooth');
assert.deepEqual(scrollCalls.shift(), { top: 320, left: 0, behavior: 'smooth' });
assert.deepEqual(scrollOperations, ['left:0', 'top:320:smooth']);
assert.equal(container.scrollLeft, 0);

scrollMessageContainerToBottom(container);
assert.deepEqual(scrollCalls.shift(), { top: 1800, left: 0, behavior: 'auto' });

container.scrollLeft = 24;
assert.equal(resetMessageContainerHorizontalScroll(container), true);
assert.equal(container.scrollLeft, 0);
assert.equal(resetMessageContainerHorizontalScroll(container), false);

const aiSource = readFileSync('src/blocks/AI/index.tsx', 'utf8');
const aiStyleSource = readFileSync('src/blocks/AI/style.ts', 'utf8');

assert.doesNotMatch(
  aiSource,
  /\.scrollIntoView\s*\(/,
  'AI message scrolling must not move scrollable ancestors or their horizontal axes',
);
assert.doesNotMatch(aiSource, /\bcontainer\.scrollTo\s*\(/, 'AI scrolling must stay behind the vertical helper');
assert.doesNotMatch(
  aiSource,
  /\bcontainer\.scrollTop\s*(?:\+=|-=|=)/,
  'AI scrolling must not preserve an unknown horizontal offset through direct scrollTop writes',
);
assert.equal(
  aiSource.match(/\bscrollMessageContainerTo(?:Bottom)?\(/g)?.length,
  3,
  'bottom follow, message anchoring, and alignment correction must all use the vertical helper',
);
assert.match(
  aiSource,
  /handleMessageListScroll[\s\S]{0,500}resetMessageContainerHorizontalScroll\(container\)/,
  'manual or external message-list horizontal scrolling must be normalized',
);
assert.match(
  aiStyleSource,
  /main: css`[\s\S]*?overflow: hidden;[\s\S]*?overflow: clip;/,
  'the AI root must reject programmatic overflow scrolling when the runtime supports overflow: clip',
);

console.log('AI message list scroll contract tests passed');
