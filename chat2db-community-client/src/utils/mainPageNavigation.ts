export const DEFAULT_MAIN_PAGE_ACTIVE_TAB = 'workspace';

const RESTORABLE_MAIN_PAGE_TABS = ['stream', 'workspace', 'sessions', 'dashboard'] as const;
const RESTORABLE_MAIN_PAGE_TAB_SET = new Set<string>(RESTORABLE_MAIN_PAGE_TABS);

const normalizeMainPagePath = (routePath?: string) => {
  const hashlessPath = (routePath || '').replace(/^#/, '');
  if (!hashlessPath) {
    return '/';
  }
  return hashlessPath.startsWith('/') ? hashlessPath : `/${hashlessPath}`;
};

export const resolveInitialMainPage = (routePage?: string, persistedPage?: string) =>
  routePage || persistedPage || DEFAULT_MAIN_PAGE_ACTIVE_TAB;

export const readPersistedMainPageActiveTab = (
  serializedStore?: string | null,
  availablePages: readonly string[] = RESTORABLE_MAIN_PAGE_TABS,
) => {
  if (!serializedStore) {
    return undefined;
  }

  try {
    const page = JSON.parse(serializedStore)?.state?.mainPageActiveTab;
    return typeof page === 'string' && new Set(availablePages).has(page) ? page : undefined;
  } catch {
    return undefined;
  }
};

export const resolveDesktopInitialMainPage = (
  routePath?: string,
  persistedPage?: string,
  availablePages: readonly string[] = RESTORABLE_MAIN_PAGE_TABS,
) => {
  const normalizedPath = normalizeMainPagePath(routePath);
  const routeSegments = normalizedPath.split('/').filter(Boolean);
  const routePage = routeSegments[0] || '';
  const availablePageSet = new Set(availablePages);
  const restoresLastSelection =
    routeSegments.length === 0 ||
    (routeSegments.length === 1 && (RESTORABLE_MAIN_PAGE_TAB_SET.has(routePage) || availablePageSet.has(routePage)));
  const restoredPage = [persistedPage, routePage, DEFAULT_MAIN_PAGE_ACTIVE_TAB, ...availablePages].find(
    (candidate) => candidate && availablePageSet.has(candidate),
  );
  const page = restoresLastSelection
    ? restoredPage || DEFAULT_MAIN_PAGE_ACTIVE_TAB
    : routePage || persistedPage || DEFAULT_MAIN_PAGE_ACTIVE_TAB;

  return {
    page,
    pathName: restoresLastSelection ? `/${page}` : normalizedPath,
  };
};

export const createMainRootRoute = (preserveLastSelection: boolean, component: string) =>
  preserveLastSelection ? { path: '/', component } : { path: '/', redirect: '/stream' };
