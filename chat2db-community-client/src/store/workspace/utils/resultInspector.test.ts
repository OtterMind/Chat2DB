import assert from 'node:assert/strict';
import {
  DEFAULT_RESULT_INSPECTOR_MODE,
  createResultInspectorModeStorageKey,
  getResultInspectorPanelSize,
  getResultInspectorPreferenceStorage,
  getResultInspectorTabs,
  getWorkspaceResultInspectorCode,
  isWorkspaceResultInspectorCode,
  RESULT_INSPECTOR_MAX_PANEL_RATIO,
  persistResultInspectorMode,
  readResultInspectorMode,
  shouldClearInactiveResultInspector,
  subscribeResultInspectorMode,
  toggleResultInspectorMode,
  WORKSPACE_RESULT_INSPECTOR_PORTAL_ID,
} from './resultInspector';

const ownerCode = getWorkspaceResultInspectorCode('result-set-1');

assert.equal(ownerCode, 'resultInspector:result-set-1');
assert.equal(isWorkspaceResultInspectorCode(ownerCode), true);
assert.equal(isWorkspaceResultInspectorCode('info'), false);
assert.equal(isWorkspaceResultInspectorCode(null), false);
assert.equal(shouldClearInactiveResultInspector(ownerCode, ownerCode, false), true);
assert.equal(shouldClearInactiveResultInspector(ownerCode, ownerCode, true), false);
assert.equal(shouldClearInactiveResultInspector('resultInspector:other', ownerCode, false), false);
assert.equal(getResultInspectorPanelSize(320, 1200), 320);
assert.equal(getResultInspectorPanelSize(900, 1200), 600);
assert.equal(getResultInspectorPanelSize(900, 0), 900);
assert.equal(RESULT_INSPECTOR_MAX_PANEL_RATIO, 0.5);
assert.equal(WORKSPACE_RESULT_INSPECTOR_PORTAL_ID, 'workspace-result-inspector-portal');
assert.equal(DEFAULT_RESULT_INSPECTOR_MODE, 'sidebar');
assert.equal(toggleResultInspectorMode('sidebar'), 'modal');
assert.equal(toggleResultInspectorMode('modal'), 'sidebar');
assert.deepEqual(getResultInspectorTabs('sidebar'), ['row', 'value', 'aggregates']);
assert.deepEqual(getResultInspectorTabs('modal'), ['row', 'value', 'aggregates']);

const modeStorageKey = createResultInspectorModeStorageKey('community', 'desktop');
assert.equal(modeStorageKey, 'chat2db.community.desktop.result-inspector.mode.v1');

const values = new Map<string, string>();
const storage = {
  getItem: (key: string) => values.get(key) ?? null,
  setItem: (key: string, value: string) => {
    values.set(key, value);
  },
};
assert.equal(readResultInspectorMode(storage, modeStorageKey), DEFAULT_RESULT_INSPECTOR_MODE);
values.set(modeStorageKey, 'modal');
assert.equal(readResultInspectorMode(storage, modeStorageKey), 'modal');
values.set(modeStorageKey, 'invalid');
assert.equal(readResultInspectorMode(storage, modeStorageKey), DEFAULT_RESULT_INSPECTOR_MODE);

let notifiedMode: string | undefined;
const unsubscribe = subscribeResultInspectorMode((storageKey, mode) => {
  if (storageKey === modeStorageKey) {
    notifiedMode = mode;
  }
});
persistResultInspectorMode(storage, modeStorageKey, 'sidebar');
unsubscribe();
assert.equal(values.get(modeStorageKey), 'sidebar');
assert.equal(notifiedMode, 'sidebar');

const throwingStorage = {
  getItem: () => {
    throw new Error('unavailable');
  },
  setItem: () => {
    throw new Error('unavailable');
  },
};
assert.equal(readResultInspectorMode(throwingStorage, modeStorageKey), DEFAULT_RESULT_INSPECTOR_MODE);
assert.doesNotThrow(() => persistResultInspectorMode(throwingStorage, modeStorageKey, 'modal'));
assert.equal(getResultInspectorPreferenceStorage(), undefined);

console.log('Workspace result inspector tests passed');
