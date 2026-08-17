export type TreeSwitcherAction = 'ignore' | 'collapse' | 'load';

export function resolveTreeSwitcherAction(isLoading: boolean, isExpanded: boolean): TreeSwitcherAction {
  if (isLoading) {
    return 'ignore';
  }
  return isExpanded ? 'collapse' : 'load';
}
