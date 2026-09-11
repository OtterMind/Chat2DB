import assert from 'node:assert/strict';
import type { IWorkspaceTabSplitLayout } from '@/typings';
import { appendWorkspaceTabToPane } from './workspaceTabLayout';

const layout: IWorkspaceTabSplitLayout = {
  direction: 'horizontal',
  activePane: 'split',
  paneTabIds: {
    main: ['main-tab'],
    split: ['monitor-tab'],
  },
  activeTabIds: {
    main: 'main-tab',
    split: 'monitor-tab',
  },
};

const appended = appendWorkspaceTabToPane(layout, 596);
assert.deepEqual(appended.paneTabIds.split, ['monitor-tab', 596]);
assert.equal(appended.activeTabIds.split, 596);
assert.equal(appended.activePane, 'split');
assert.deepEqual(appended.paneTabIds.main, ['main-tab']);
assert.deepEqual(appendWorkspaceTabToPane(appended, 596).paneTabIds.split, ['monitor-tab', 596]);

console.log('Workspace tab layout tests passed');
