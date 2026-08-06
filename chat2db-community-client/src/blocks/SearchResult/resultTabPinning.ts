interface PinnableResult {
  uuid?: string;
}

export function retainPinnedResults<T extends PinnableResult>(
  incomingResults: T[],
  existingResults: T[],
  pinnedKeys: ReadonlySet<string>,
): T[] {
  const incomingKeys = new Set(incomingResults.map((item) => item.uuid).filter((key): key is string => !!key));
  const retainedKeys = new Set<string>();
  const retainedResults = existingResults.filter((item) => {
    const key = item.uuid;
    if (!key || !pinnedKeys.has(key) || incomingKeys.has(key) || retainedKeys.has(key)) {
      return false;
    }
    retainedKeys.add(key);
    return true;
  });

  return [...retainedResults, ...incomingResults];
}
