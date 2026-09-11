// Runtime environment constants
export const RUNTIME_ENV = {
  DESKTOP: 'desktop',
  OFFLINE: 'offline',
  COMMUNITY: 'community',
  WEB: 'web',
};

// Is it a desktop version?
export const isDesktopEnv = __RUNTIME_ENV__ === RUNTIME_ENV.DESKTOP;
// Is it an offline version?
export const isOfflineEnv = __RUNTIME_ENV__ === RUNTIME_ENV.OFFLINE;
// Is it community edition?
export const isCommunityEnv = __RUNTIME_ENV__ === RUNTIME_ENV.COMMUNITY;
// Is it the web version?
export const isWebEnv = __RUNTIME_ENV__ === RUNTIME_ENV.WEB;

// Whether to use hash routing
export const isHashHistoryEnv = isDesktopEnv || isOfflineEnv || isCommunityEnv;

export const isMac = /Mac|iPod|iPhone|iPad/.test(navigator.userAgent);

/** Whether it is desktop version */
export const isDesktop = window.javaQuery !== undefined;

// Is it a development environment?
export const isDevelopment = __ENV__ === 'development';

// Whether to use local persistence capabilities
export const isLocalStorageEnv = isOfflineEnv || isCommunityEnv;

// The local test environment can be imported and exported to facilitate testing
export const canImportExport = isDesktopEnv || isLocalStorageEnv || isDevelopment;
