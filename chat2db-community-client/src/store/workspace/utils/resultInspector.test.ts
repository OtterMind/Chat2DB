import assert from 'node:assert/strict';
import {
  getWorkspaceResultInspectorCode,
  isWorkspaceResultInspectorCode,
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
assert.equal(WORKSPACE_RESULT_INSPECTOR_PORTAL_ID, 'workspace-result-inspector-portal');

console.log('Workspace result inspector tests passed');
