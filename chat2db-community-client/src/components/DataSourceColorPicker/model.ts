import { normalizeIdentityColor } from '@/utils/dataSourceIdentity';

export const DATA_SOURCE_COLOR_PRESETS = [
  '#F5222D',
  '#FA8C16',
  '#FADB14',
  '#52C41A',
  '#13C2C2',
  '#1677FF',
  '#722ED1',
  '#EB2F96',
] as const;

export const DATA_SOURCE_COLOR_CONTROL_COUNT = DATA_SOURCE_COLOR_PRESETS.length + 2;

export type DataSourceColorNavigationKey = 'ArrowLeft' | 'ArrowRight' | 'Home' | 'End';

export type DataSourceColorSelection =
  | { type: 'clear'; color: null }
  | { type: 'preset'; color: string }
  | { type: 'custom'; color: string };

export function resolveDataSourceColorSelection(identityColor?: string | null): DataSourceColorSelection {
  const color = normalizeIdentityColor(identityColor);
  if (!color) {
    return { type: 'clear', color: null };
  }
  if ((DATA_SOURCE_COLOR_PRESETS as readonly string[]).includes(color)) {
    return { type: 'preset', color };
  }
  return { type: 'custom', color };
}

export function getDataSourceColorSelectionIndex(identityColor?: string | null) {
  const selection = resolveDataSourceColorSelection(identityColor);
  if (selection.type === 'clear') {
    return 0;
  }
  if (selection.type === 'custom') {
    return DATA_SOURCE_COLOR_CONTROL_COUNT - 1;
  }
  return DATA_SOURCE_COLOR_PRESETS.indexOf(selection.color as (typeof DATA_SOURCE_COLOR_PRESETS)[number]) + 1;
}

export function getNextDataSourceColorControlIndex(
  currentIndex: number,
  key: DataSourceColorNavigationKey,
  controlCount = DATA_SOURCE_COLOR_CONTROL_COUNT,
) {
  if (key === 'Home') {
    return 0;
  }
  if (key === 'End') {
    return controlCount - 1;
  }
  const offset = key === 'ArrowRight' ? 1 : -1;
  return (currentIndex + offset + controlCount) % controlCount;
}
