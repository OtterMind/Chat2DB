import assert from 'node:assert/strict';
import {
  getResultInspectorPanelSize,
  getWorkspaceResultInspectorCode,
  isWorkspaceResultInspectorCode,
  RESULT_INSPECTOR_MAX_PANEL_RATIO,
  shouldClearInactiveResultInspector,
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

console.log('Workspace result inspector tests passed');
