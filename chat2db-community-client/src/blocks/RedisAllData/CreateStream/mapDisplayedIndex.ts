import { ActionType } from '@/constants/redis';

interface ValueListItem {
  action: ActionType;
  [key: string]: unknown;
}

/**
 * Maps a displayed (filtered) row index back to the valueList index.
 *
 * The displayed table filters out DELETE rows, so the displayed index
 * must be mapped by counting non-DELETE rows (mirroring handleDelete's
 * accumulation approach).
 *
 * @returns the valueList index, or -1 if the displayed index is out of range.
 */
export function mapDisplayedIndexToValueListIndex(
  valueList: ValueListItem[],
  displayedIndex: number,
): number {
  let nonDeleteCount = 0;
  for (let i = 0; i < valueList.length; i++) {
    if (valueList[i].action !== ActionType.DELETE) {
      if (nonDeleteCount === displayedIndex) {
        return i;
      }
      nonDeleteCount++;
    }
  }
  return -1;
}
