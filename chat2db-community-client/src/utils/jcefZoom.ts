import jcefApi from '@/jcef';
import { isJcefApiAvailable } from '@/jcef/base';

export type WebFrameZoomType = 'in' | 'out' | 'reset';

const ZOOM_ACTION_MAP: Record<WebFrameZoomType, 'zoomIn' | 'zoomOut' | 'zoomReset'> = {
  in: 'zoomIn',
  out: 'zoomOut',
  reset: 'zoomReset',
};

export function canHandleWebFrameZoom(): boolean {
  return isJcefApiAvailable();
}

export async function handleWebFrameZoom(type: WebFrameZoomType): Promise<void> {
  if (!canHandleWebFrameZoom()) {
    return;
  }

  try {
    await jcefApi.webFrameSetZoom({ action: ZOOM_ACTION_MAP[type] });
  } catch (error) {
    console.warn('Failed to set web frame zoom:', error);
  }
}
