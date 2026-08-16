import assert from 'node:assert/strict';
import { WorkspaceTabType } from '@/constants/workspace';
import type { IWorkspaceTab, IWorkspaceTabSplitLayout } from '@/typings';
import { getNextActiveWorkspaceTabIdAfterClose } from './workspaceTabSelection';

const editorOne: IWorkspaceTab = { id: 'editor-1', type: WorkspaceTabType.CONSOLE, title: 'Editor 1' };
const editorTwo: IWorkspaceTab = { id: 'editor-2', type: WorkspaceTabType.CONSOLE, title: 'Editor 2' };
const terminal: IWorkspaceTab = { id: 'terminal-1', type: WorkspaceTabType.Terminal, title: 'Terminal' };
const splitLayout: IWorkspaceTabSplitLayout = {
  direction: 'horizontal',
  activePane: 'terminal-panel:bottom',
  root: {
    type: 'split',
    direction: 'horizontal',
    first: { type: 'pane', id: 'main' },
    second: { type: 'pane', id: 'terminal-panel:bottom' },
  },
  paneTabIds: {
    main: [editorOne.id, editorTwo.id],
    'terminal-panel:bottom': [terminal.id],
  },
  activeTabIds: {
    main: editorOne.id,
    'terminal-panel:bottom': terminal.id,
  },
};

assert.equal(
  getNextActiveWorkspaceTabIdAfterClose({
    activeConsoleId: terminal.id,
    closeTabIds: new Set([terminal.id]),
    layout: splitLayout,
    orderedNextWorkspaceTabList: [editorOne, editorTwo],
  }),
  editorOne.id,
  'closing a docked terminal should restore the editor that was active in the main pane',
);

assert.equal(
  getNextActiveWorkspaceTabIdAfterClose({
    activeConsoleId: editorOne.id,
    closeTabIds: new Set([terminal.id]),
    layout: splitLayout,
    orderedNextWorkspaceTabList: [editorOne, editorTwo],
  }),
  editorOne.id,
  'closing a background terminal must not change the active editor',
);

assert.equal(
  getNextActiveWorkspaceTabIdAfterClose({
    activeConsoleId: editorTwo.id,
    closeTabIds: new Set([editorTwo.id]),
    layout: splitLayout,
    orderedNextWorkspaceTabList: [editorOne, terminal],
  }),
  editorOne.id,
  'closing an editor should keep the existing same-pane neighbor behavior',
);

const multiEditorPaneLayout: IWorkspaceTabSplitLayout = {
  ...splitLayout,
  lastNonTerminalActiveTabId: editorTwo.id,
  root: {
    type: 'split',
    direction: 'horizontal',
    first: {
      type: 'split',
      direction: 'vertical',
      first: { type: 'pane', id: 'main' },
      second: { type: 'pane', id: 'editor-split' },
    },
    second: { type: 'pane', id: 'terminal-panel:bottom' },
  },
  paneTabIds: {
    main: [editorOne.id],
    'editor-split': [editorTwo.id],
    'terminal-panel:bottom': [terminal.id],
  },
  activeTabIds: {
    main: editorOne.id,
    'editor-split': editorTwo.id,
    'terminal-panel:bottom': terminal.id,
  },
};

assert.equal(
  getNextActiveWorkspaceTabIdAfterClose({
    activeConsoleId: terminal.id,
    closeTabIds: new Set([terminal.id]),
    layout: multiEditorPaneLayout,
    orderedNextWorkspaceTabList: [editorOne, editorTwo],
  }),
  editorTwo.id,
  'closing a docked terminal should restore the last active editor across multiple panes',
);

console.log('Workspace tab selection tests passed');
