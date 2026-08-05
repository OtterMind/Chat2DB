export const DEFAULT_MAIN_PAGE_ACTIVE_TAB = 'workspace';

export const resolveInitialMainPage = (routePage?: string, persistedPage?: string) =>
  routePage || persistedPage || DEFAULT_MAIN_PAGE_ACTIVE_TAB;

export const createMainRootRoute = (preserveLastSelection: boolean, component: string) =>
  preserveLastSelection
    ? { path: '/', component }
    : { path: '/', redirect: '/stream' };
