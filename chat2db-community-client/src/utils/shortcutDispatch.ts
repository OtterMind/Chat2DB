import { ShortcutAction } from '@/constants/shortcut';
import { canHandleWebFrameZoom } from './jcefZoom';

const WEB_FRAME_ZOOM_ACTIONS = new Set<ShortcutAction>([
  ShortcutAction.ZoomIn,
  ShortcutAction.ZoomOut,
  ShortcutAction.ZoomReset,
]);

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
