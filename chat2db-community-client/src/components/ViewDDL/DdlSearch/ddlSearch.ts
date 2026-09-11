/**
 * Pure logic for the scoped DDL search (Issue #2748, plan B1).
 *
 * Highlighting uses the CSS Custom Highlight API (CSS.highlights + Range),
 * which never mutates the React/Shiki-owned DOM. Everything in this file is
 * side-effect free except where a DOM node is explicitly passed in, so the
 * logic stays unit-testable under plain Node + jsdom.
 */

export interface DdlSearchMatch {
  /** Start offset (inclusive) inside the source SQL string. */
  start: number;
  /** End offset (exclusive) inside the source SQL string. */
  end: number;
}

/**
 * Case-insensitive, non-overlapping substring search over the full SQL text.
 */
export function findDdlSearchMatches(sql: string, query: string): DdlSearchMatch[] {
  const matches: DdlSearchMatch[] = [];
  const trimmedQuery = query ?? '';
  if (!sql || !trimmedQuery) {
    return matches;
  }
  const haystack = sql.toLowerCase();
  const needle = trimmedQuery.toLowerCase();
  let fromIndex = 0;
  while (fromIndex <= haystack.length - needle.length) {
    const found = haystack.indexOf(needle, fromIndex);
    if (found === -1) {
      break;
    }
    matches.push({ start: found, end: found + needle.length });
    fromIndex = found + needle.length;
  }
  return matches;
}

/** Feature detection for the CSS Custom Highlight API. */
export function isDdlSearchHighlightSupported(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof CSS !== 'undefined' &&
    'highlights' in CSS &&
    typeof (window as any).Highlight === 'function'
  );
}

export interface DdlSearchHighlightNames {
  /** Highlight registry name for every match. */
  all: string;
  /** Highlight registry name for the active (focused) match. */
  active: string;
}

export function getDdlSearchHighlightNames(instanceId: string): DdlSearchHighlightNames {
  return {
    all: `ddl-search-${instanceId}`,
    active: `ddl-search-active-${instanceId}`,
  };
}

/** Match colors mirror FESearch: #ff0 at 20% for matches, 60% for the active one. */
export const DDL_SEARCH_MATCH_BACKGROUND = 'rgba(255, 255, 0, 0.2)';
export const DDL_SEARCH_ACTIVE_BACKGROUND = 'rgba(255, 255, 0, 0.6)';

/**
 * Instance-scoped ::highlight() rules. Registering a Highlight object under a
 * name is not enough - the stylesheet must reference the same name.
 */
export function buildDdlSearchHighlightStyleText(instanceId: string): string {
  const names = getDdlSearchHighlightNames(instanceId);
  return [
    `::highlight(${names.all}) { background-color: ${DDL_SEARCH_MATCH_BACKGROUND}; }`,
    `::highlight(${names.active}) { background-color: ${DDL_SEARCH_ACTIVE_BACKGROUND}; }`,
  ].join('\n');
}

export const DDL_SEARCH_STYLE_ATTRIBUTE = 'data-ddl-search-highlight-style';

/**
 * Walk the rendered Shiki DOM and build one Range per match.
 *
 * Returns null when the rendered text content does not exactly match the
 * source SQL (e.g. the async highlighter has not painted yet), in which case
 * the caller should retry on the next DOM mutation instead of guessing.
 */
export function createMatchRanges(root: Node, sql: string, matches: DdlSearchMatch[]): Range[] | null {
  if (!matches.length) {
    return [];
  }
  const doc = root.ownerDocument ?? (root as Document);
  const walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const textNodes: Text[] = [];
  let totalLength = 0;
  let current = walker.nextNode() as Text | null;
  while (current) {
    textNodes.push(current);
    totalLength += current.data.length;
    current = walker.nextNode() as Text | null;
  }
  // Shikiji renders each CR and LF as a newline, retaining their offsets.
  // Compare content as well as length so an old same-length object is rejected.
  if (totalLength !== sql.length || root.textContent !== sql.replace(/\r/g, '\n')) {
    return null;
  }

  const ranges: Range[] = [];
  let nodeIndex = 0;
  let nodeOffsetStart = 0; // absolute offset of textNodes[nodeIndex].data[0]
  for (const match of matches) {
    let range: Range | null = null;
    let completed = false;
    // Matches are ordered and non-overlapping, so a shared forward cursor is enough.
    while (nodeIndex < textNodes.length) {
      const node = textNodes[nodeIndex];
      const nodeStart = nodeOffsetStart;
      const nodeEnd = nodeStart + node.data.length;
      if (!range && match.start < nodeEnd) {
        range = doc.createRange();
        range.setStart(node, match.start - nodeStart);
      }
      if (range && match.end <= nodeEnd) {
        range.setEnd(node, match.end - nodeStart);
        completed = true;
        break;
      }
      nodeIndex += 1;
      nodeOffsetStart = nodeEnd;
    }
    if (!range || !completed) {
      // The match extends past the rendered text - bail out and let the caller retry.
      return null;
    }
    ranges.push(range);
  }
  return ranges;
}

/** First rect that actually occupies space; folded content (height: 0) is skipped. */
export function pickVisibleRect(rects: ArrayLike<DOMRect>): DOMRect | null {
  for (let i = 0; i < rects.length; i += 1) {
    const rect = rects[i];
    if (rect.width > 0 && rect.height > 0) {
      return rect;
    }
  }
  for (let i = 0; i < rects.length; i += 1) {
    const rect = rects[i];
    if (rect.width > 0 || rect.height > 0) {
      return rect;
    }
  }
  return null;
}

export interface RectLike {
  top: number;
  bottom: number;
  height: number;
}

/**
 * Delta needed on container.scrollTop so that the target rect lands on the
 * vertical center of the scroll container. No DOM access - unit testable.
 */
export function computeCenteredScrollDelta(target: RectLike, container: RectLike): number {
  const targetCenter = target.top + target.height / 2;
  const containerCenter = container.top + container.height / 2;
  return targetCenter - containerCenter;
}

/** True when the target rect is already fully visible inside the container. */
export function isRectFullyVisible(target: RectLike, container: RectLike): boolean {
  return target.top >= container.top && target.bottom <= container.bottom;
}

/** Nearest scrollable ancestor (overflow-y auto/scroll). Falls back to null. */
export function findScrollContainer(start: Node | null): HTMLElement | null {
  let node: HTMLElement | null = start instanceof HTMLElement ? start : start?.parentElement ?? null;
  while (node) {
    const overflowY = node.ownerDocument.defaultView?.getComputedStyle(node).overflowY;
    if ((overflowY === 'auto' || overflowY === 'scroll') && node.scrollHeight > node.clientHeight) {
      return node;
    }
    node = node.parentElement;
  }
  return null;
}

/** Wrap-around index navigation for prev/next. */
export function nextActiveIndex(current: number, count: number, direction: 1 | -1): number {
  if (count <= 0) {
    return -1;
  }
  if (current < 0) {
    return direction === 1 ? 0 : count - 1;
  }
  return (current + direction + count) % count;
}

let instanceCounter = 0;

/** Process-unique, CSS-ident-safe instance id (useId() output contains colons). */
export function nextDdlSearchInstanceId(): string {
  instanceCounter += 1;
  return `${instanceCounter}`;
}
