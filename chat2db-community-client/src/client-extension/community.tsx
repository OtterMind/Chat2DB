import type { INavItem } from '@/typings/main';
import type { ClientExtension } from './types';

const useCommunityNavigationItems = (items: readonly INavItem[]) => items;

export const clientExtension: ClientExtension = {
  mainPage: {
    useNavigationItems: useCommunityNavigationItems,
  },
  navigationItems: [],
  requestPolicy: {
    permissionDeniedInteraction: 'prompt-application',
  },
};

export default clientExtension;
