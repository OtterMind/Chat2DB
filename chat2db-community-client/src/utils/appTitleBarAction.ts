export const APP_TITLE_BAR_ACTION_EVENT = 'app:titleBarAction';

export const APP_TITLE_BAR_ACTIONS = ['stream', 'workspace', 'dashboard', 'settings'] as const;

export type AppTitleBarAction = (typeof APP_TITLE_BAR_ACTIONS)[number];

export interface AppTitleBarActionEventDetail {
  action: AppTitleBarAction;
}

export function isAppTitleBarAction(value: unknown): value is AppTitleBarAction {
  return typeof value === 'string' && APP_TITLE_BAR_ACTIONS.includes(value as AppTitleBarAction);
}

export function requestAppTitleBarAction(
  action: AppTitleBarAction,
  target: Pick<EventTarget, 'dispatchEvent'> = window,
): boolean {
  const event = new CustomEvent<AppTitleBarActionEventDetail>(APP_TITLE_BAR_ACTION_EVENT, {
    cancelable: true,
    detail: { action },
  });
  target.dispatchEvent(event);
  return event.defaultPrevented;
}
