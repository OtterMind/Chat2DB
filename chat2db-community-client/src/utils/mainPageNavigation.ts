export const DEFAULT_MAIN_PAGE_ACTIVE_TAB = 'workspace';

const RESTORABLE_MAIN_PAGE_TABS = new Set(['stream', 'workspace', 'dashboard']);

const normalizeMainPagePath = (routePath?: string) => {
  const hashlessPath = (routePath || '').replace(/^#/, '');
  if (!hashlessPath) {
    return '/';
  }
  return hashlessPath.startsWith('/') ? hashlessPath : `/${hashlessPath}`;
};

export const resolveInitialMainPage = (routePage?: string, persistedPage?: string) =>
  routePage || persistedPage || DEFAULT_MAIN_PAGE_ACTIVE_TAB;

export const readPersistedMainPageActiveTab = (serializedStore?: string | null) => {
  if (!serializedStore) {
    return undefined;
  }

  try {
    const page = JSON.parse(serializedStore)?.state?.mainPageActiveTab;
    return typeof page === 'string' && RESTORABLE_MAIN_PAGE_TABS.has(page) ? page : undefined;
  } catch {
    return undefined;
  }
};

export const resolveDesktopInitialMainPage = (routePath?: string, persistedPage?: string) => {
  const normalizedPath = normalizeMainPagePath(routePath);
  const routeSegments = normalizedPath.split('/').filter(Boolean);
  const routePage = routeSegments[0] || '';
  const restoresLastSelection =
    routeSegments.length === 0 || (routeSegments.length === 1 && RESTORABLE_MAIN_PAGE_TABS.has(routePage));
  const page = restoresLastSelection
    ? persistedPage || routePage || DEFAULT_MAIN_PAGE_ACTIVE_TAB
    : routePage || persistedPage || DEFAULT_MAIN_PAGE_ACTIVE_TAB;

  return {
    page,
    pathName: restoresLastSelection ? `/${page}` : normalizedPath,
  };
};

export const createMainRootRoute = (preserveLastSelection: boolean, component: string) =>
  preserveLastSelection ? { path: '/', component } : { path: '/', redirect: '/stream' };
