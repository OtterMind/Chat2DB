export const CONTEXT_MENU_DIVIDER = { type: 'divider' as const };

export function isResultHeaderContext(row: number, col: number): boolean {
  return row === 0 && col > 0;
}

export function joinContextMenuGroups<T>(
  ...groups: ReadonlyArray<ReadonlyArray<T | null | undefined>>
): Array<T | typeof CONTEXT_MENU_DIVIDER> {
  const populatedGroups = groups
    .map((group) => group.filter((item): item is T => item !== null && item !== undefined))
    .filter((group) => group.length > 0);

  return populatedGroups.flatMap((group, index) =>
    index === 0 ? group : [CONTEXT_MENU_DIVIDER, ...group],
  );
}
