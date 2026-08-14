export function resolveIndexedDbStorageKey(key: string, prefix: string) {
  return prefix ? `${prefix}:${key}` : key;
}
