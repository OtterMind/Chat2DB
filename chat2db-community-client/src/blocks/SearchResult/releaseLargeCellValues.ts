import type { IManageResultData } from '@/typings/database';
import sqlService from '@/service/sql';

/**
 * Collects the large value handles a result set is holding, so they can be handed back when it is discarded.
 */
export function collectLargeCellValueIds(resultData?: IManageResultData | null): string[] {
  const ids = new Set<string>();
  (resultData?.dataList || []).forEach((row) => {
    (row || []).forEach((cell) => {
      const largeValueId = cell?.largeValueId;
      if (largeValueId) {
        ids.add(largeValueId);
      }
    });
  });
  return [...ids];
}

/**
 * Tells the server that the handles of a discarded result set are no longer needed, so the captured content is
 * released right away instead of waiting for the handle lifetime to run out. Best effort: a failure only means the
 * content is freed later by the regular cleanup.
 */
export function releaseLargeCellValues(largeValueIds?: string[]) {
  if (!largeValueIds?.length) {
    return;
  }
  void sqlService.releaseLargeCellValues({ largeValueIds }).catch(() => undefined);
}
