export const RESULT_SEARCH_VISIBLE_BY_DEFAULT = false;

export type ResultSearchVisibilityAction = 'open' | 'close';

interface ResultSearchVisibilityEffects {
  close: () => void;
  defer: (callback: () => void) => void;
  focus: () => void;
  open: () => void;
  preventDefault: () => void;
}

export function getResultSearchVisibility(action: ResultSearchVisibilityAction): boolean {
  return action === 'open';
}

export function applyResultSearchVisibilityAction(
  action: ResultSearchVisibilityAction,
  effects: ResultSearchVisibilityEffects,
) {
  if (action === 'close') {
    effects.close();
    return;
  }

  effects.open();
  effects.preventDefault();
  effects.defer(effects.focus);
}
