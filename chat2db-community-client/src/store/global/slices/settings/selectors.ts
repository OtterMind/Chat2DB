import { deepMerge } from '@/utils/merge';
import { GlobalStore } from '../../store';
import { DEFAULT_BASE_SETTINGS, DEFAULT_APP_CONFIG } from '@/constants/settings';
import { primaryColorsScales } from '@chat2db/ui';

const currentBaseSetting = (state: GlobalStore) => {
  return deepMerge(DEFAULT_BASE_SETTINGS, {
    ...state.baseSetting,
    // The new version has removed some theme colors, here is a compatible version
    primaryColor: primaryColorsScales[state.baseSetting.primaryColor?.label || '']
      ? state.baseSetting.primaryColor
      : undefined,
  });
};
const currentAppConfig = (state: GlobalStore) => deepMerge(DEFAULT_APP_CONFIG, state.appConfig);

export const settingSelectors = {
  currentBaseSetting,
  currentAppConfig,
};
