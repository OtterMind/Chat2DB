import assert from 'node:assert/strict';
import { WorkspaceTabType, initUserConfigTree } from '@/constants/workspace';
import type { IWorkspaceTab } from '@/typings';
import {
  getActiveTabLocateTargetForPanel,
  getDirectActiveTabLocateTargets,
  resolveWorkspaceLeftPanel,
} from './activeTabTarget';

function consoleTab(uniqueData: IWorkspaceTab['uniqueData']): IWorkspaceTab {
  return {
    id: 101,
    type: WorkspaceTabType.CONSOLE,
    title: 'Query',
    uniqueData,
  };
}

const connectedConsole = consoleTab({
  dataSourceId: 7,
  databaseName: 'app',
  schemaName: 'public',
});
const disconnectedConsole = consoleTab(undefined);
const localFile: IWorkspaceTab = {
  id: 'local-file',
  type: WorkspaceTabType.LocalSQLFile,
  title: 'local.sql',
  uniqueData: { filePath: '/tmp/local.sql' },
};
const databaseObject: IWorkspaceTab = {
  id: 'table-editor',
  type: WorkspaceTabType.EditTable,
  title: 'users',
  uniqueData: {
    dataSourceId: 7,
    databaseName: 'app',
    schemaName: 'public',
    tableName: 'users',
  },
};

assert.deepEqual(getDirectActiveTabLocateTargets(connectedConsole), {
  explorer: { surface: 'explorerSession', sessionId: 101 },
  database: { surface: 'databaseTree' },
});
assert.deepEqual(getDirectActiveTabLocateTargets(disconnectedConsole), {
  explorer: { surface: 'explorerSession', sessionId: 101 },
  database: undefined,
});
assert.deepEqual(getDirectActiveTabLocateTargets(localFile), {
  explorer: { surface: 'localFile', filePath: '/tmp/local.sql' },
});

const consoleTargets = getDirectActiveTabLocateTargets(connectedConsole)!;
assert.equal(getActiveTabLocateTargetForPanel(consoleTargets, 'explorer')?.surface, 'explorerSession');
assert.equal(getActiveTabLocateTargetForPanel(consoleTargets, 'database')?.surface, 'databaseTree');

const localFileTargets = getDirectActiveTabLocateTargets(localFile)!;
assert.equal(getActiveTabLocateTargetForPanel(localFileTargets, 'explorer')?.surface, 'localFile');
assert.equal(getActiveTabLocateTargetForPanel(localFileTargets, 'database'), undefined);

assert.equal(getDirectActiveTabLocateTargets(databaseObject), undefined);
const databaseObjectTargets = {
  database: { surface: 'databaseTree' as const },
};
assert.equal(getActiveTabLocateTargetForPanel(databaseObjectTargets, 'explorer'), undefined);
assert.equal(getActiveTabLocateTargetForPanel(databaseObjectTargets, 'database')?.surface, 'databaseTree');

assert.deepEqual(getDirectActiveTabLocateTargets(null), {});
assert.equal(initUserConfigTree.workspaceLeftPanel, 'database');
assert.equal(resolveWorkspaceLeftPanel(undefined), 'database');
assert.equal(resolveWorkspaceLeftPanel('explorer'), 'explorer');

console.log('Active workspace tab locator tests passed');
