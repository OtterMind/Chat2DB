import {
  ShortcutAction,
  ShortcutScope,
  isShortcutEventMatch,
  type EffectiveShortcutConfig,
} from '@/constants/shortcut';
import { canHandleWebFrameZoom } from './jcefZoom';

const WEB_FRAME_ZOOM_ACTIONS = new Set<ShortcutAction>([
  ShortcutAction.ZoomIn,
  ShortcutAction.ZoomOut,
  ShortcutAction.ZoomReset,
]);

export type ShortcutDispatchResolution =
  | { kind: 'global'; action: ShortcutAction }
  | { kind: 'workspace-save' };

export function resolveShortcutDispatch(
  event: KeyboardEvent,
  shortcutConfig: Record<ShortcutAction, EffectiveShortcutConfig>,
  options: {
    activeScope?: ShortcutScope;
    editableTarget: boolean;
    workspaceSaveAllowed: boolean;
  },
): ShortcutDispatchResolution | undefined {
  const scopedShortcut = options.activeScope
    ? Object.values(shortcutConfig).find(
        (config) =>
          config.scope === options.activeScope &&
          !config.disabled &&
          isShortcutEventMatch(event, config.binding),
      )
    : undefined;
  if (scopedShortcut) {
    return undefined;
  }

  const globalShortcut = Object.values(shortcutConfig).find(
    (config) =>
      config.scope === ShortcutScope.Global &&
      !config.disabled &&
      isShortcutEventMatch(event, config.binding),
  );
  if (globalShortcut) {
    if (options.editableTarget && !globalShortcut.allowInEditable) {
      return undefined;
    }
    return { kind: 'global', action: globalShortcut.action };
  }

  const saveShortcut = shortcutConfig[ShortcutAction.SqlSave];
  if (
    !options.editableTarget &&
    options.workspaceSaveAllowed &&
    !saveShortcut.disabled &&
    isShortcutEventMatch(event, saveShortcut.binding)
  ) {
    return { kind: 'workspace-save' };
  }

  return undefined;
}

export function prepareGlobalShortcutHandling(
  event: Pick<KeyboardEvent, 'preventDefault'>,
  action: ShortcutAction,
): boolean {
  if (WEB_FRAME_ZOOM_ACTIONS.has(action) && !canHandleWebFrameZoom()) {
    return false;
  }

  event.preventDefault();
  return true;
}
