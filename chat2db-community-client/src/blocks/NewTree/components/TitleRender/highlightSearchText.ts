export interface SearchHighlightSegment {
  text: string;
  highlighted: boolean;
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export function splitSearchHighlight(value: string, search: string): SearchHighlightSegment[] {
  if (!search) {
    return [{ text: value, highlighted: false }];
  }

  const segments: SearchHighlightSegment[] = [];
  const matcher = new RegExp(escapeRegExp(search), 'gi');
  let cursor = 0;

  for (const match of value.matchAll(matcher)) {
    const index = match.index ?? 0;
    if (index > cursor) {
      segments.push({ text: value.slice(cursor, index), highlighted: false });
    }
    segments.push({ text: match[0], highlighted: true });
    cursor = index + match[0].length;
  }

  if (cursor < value.length) {
    segments.push({ text: value.slice(cursor), highlighted: false });
  }

  return segments.length ? segments : [{ text: value, highlighted: false }];
}
