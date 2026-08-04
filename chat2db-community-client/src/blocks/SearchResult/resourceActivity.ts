export function isResultResourceActive(
  containerActive: boolean | undefined,
  activeTabId: string | undefined,
  resultTabId: string | undefined,
) {
  return containerActive !== false && !!activeTabId && activeTabId === resultTabId;
}
