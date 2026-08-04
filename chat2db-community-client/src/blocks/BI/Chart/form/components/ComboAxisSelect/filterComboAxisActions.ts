interface ComboAxisAction {
  key: string;
}

export const filterComboAxisActions = <T extends ComboAxisAction>(
  actions: readonly T[],
  isFirst: boolean,
  isLast: boolean,
): T[] => {
  return actions.filter((action) => {
    if (isFirst && action.key === 'move-up') return false;
    if (isLast && action.key === 'move-down') return false;
    return true;
  });
};
