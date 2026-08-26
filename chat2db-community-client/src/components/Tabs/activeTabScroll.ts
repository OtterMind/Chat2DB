export interface ProcessedTabScrollRequest {
  activeKey?: string | number | null;
  scrollKey?: string | number;
}

export function shouldProcessTabScrollRequest(
  previousRequest: ProcessedTabScrollRequest | undefined,
  activeKey: string | number | null | undefined,
  scrollKey: string | number | undefined,
) {
  if (!previousRequest || !Object.is(previousRequest.activeKey, activeKey)) {
    return true;
  }
  return scrollKey !== undefined && !Object.is(previousRequest.scrollKey, scrollKey);
}
