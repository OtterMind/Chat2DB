export const DEFAULT_SPLIT = 55;
export const SPLITTER_WIDTH = 16;

export const clampSplit = (percent: number) => Math.max(25, Math.min(75, percent));

export const splitAtPointer = (clientX: number, left: number, width: number) =>
  width > SPLITTER_WIDTH
    ? clampSplit(((clientX - left - SPLITTER_WIDTH / 2) / (width - SPLITTER_WIDTH)) * 100)
    : DEFAULT_SPLIT;

export const splitForKey = (percent: number, key: string): number | undefined => {
  switch (key) {
    case 'ArrowLeft': return clampSplit(percent - 2.5);
    case 'ArrowRight': return clampSplit(percent + 2.5);
    case 'Home': return 25;
    case 'End': return 75;
    default: return undefined;
  }
};
