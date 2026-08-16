export const WORKSPACE_RESULT_INSPECTOR_PORTAL_ID = 'workspace-result-inspector-portal';

export const RESULT_INSPECTOR_MAX_PANEL_RATIO = 0.5;

export type ResultInspectorMode = 'sidebar' | 'modal';
export type ResultInspectorTab = 'row' | 'value' | 'aggregates';

export interface ResultInspectorPreferenceStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

type ResultInspectorModeListener = (storageKey: string, mode: ResultInspectorMode) => void;

export const DEFAULT_RESULT_INSPECTOR_MODE: ResultInspectorMode = 'sidebar';

const RESULT_INSPECTOR_TABS: ResultInspectorTab[] = ['row', 'value', 'aggregates'];

const WORKSPACE_RESULT_INSPECTOR_PREFIX = 'resultInspector:';

const modeListeners = new Set<ResultInspectorModeListener>();

export function createResultInspectorModeStorageKey(clientEdition: string, runtimeEnv: string) {
  return `chat2db.${clientEdition}.${runtimeEnv}.result-inspector.mode.v1`;
}

export function getResultInspectorPreferenceStorage(): ResultInspectorPreferenceStorage | undefined {
  try {
    return typeof window === 'undefined' ? undefined : window.localStorage;
  } catch {
    return undefined;
  }
}

export function readResultInspectorMode(
  storage: ResultInspectorPreferenceStorage | undefined,
  storageKey: string,
): ResultInspectorMode {
  try {
    const storedValue = storage?.getItem(storageKey);
    return storedValue === 'sidebar' || storedValue === 'modal'
      ? storedValue
      : DEFAULT_RESULT_INSPECTOR_MODE;
  } catch {
    return DEFAULT_RESULT_INSPECTOR_MODE;
  }
}

export function persistResultInspectorMode(
  storage: ResultInspectorPreferenceStorage | undefined,
  storageKey: string,
  mode: ResultInspectorMode,
) {
  try {
    storage?.setItem(storageKey, mode);
  } catch {
    // Storage can be unavailable in restricted browser contexts.
  }
  modeListeners.forEach((listener) => listener(storageKey, mode));
}

export function subscribeResultInspectorMode(listener: ResultInspectorModeListener) {
  modeListeners.add(listener);
  return () => {
    modeListeners.delete(listener);
  };
}

export function getWorkspaceResultInspectorCode(ownerId: string) {
  return `${WORKSPACE_RESULT_INSPECTOR_PREFIX}${ownerId}`;
}

export function isWorkspaceResultInspectorCode(code?: string | null) {
  return !!code?.startsWith(WORKSPACE_RESULT_INSPECTOR_PREFIX);
}

export function shouldClearInactiveResultInspector(
  currentWorkspaceExtend: string | null | undefined,
  inspectorExtendCode: string,
  active: boolean,
) {
  return !active && currentWorkspaceExtend === inspectorExtendCode;
}

export function getResultInspectorPanelSize(preferredSize: number, workspaceWidth: number) {
  const maxSize = workspaceWidth * RESULT_INSPECTOR_MAX_PANEL_RATIO;
  return maxSize > 0 ? Math.min(preferredSize, maxSize) : preferredSize;
}

export function toggleResultInspectorMode(mode: ResultInspectorMode): ResultInspectorMode {
  return mode === 'sidebar' ? 'modal' : 'sidebar';
}

export function getResultInspectorTabs(_mode: ResultInspectorMode): ResultInspectorTab[] {
  return [...RESULT_INSPECTOR_TABS];
}
