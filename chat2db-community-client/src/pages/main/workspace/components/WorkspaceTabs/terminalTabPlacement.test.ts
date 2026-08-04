import assert from 'node:assert/strict';
import { WorkspaceTabType } from '@/constants/workspace';
import type { IWorkspaceTab, IWorkspaceTabSplitLayout } from '@/typings';
import { applyTerminalTabOpenPositions } from './terminalTabPlacement';

function withoutNodeIds<T>(value: T): T {
  return JSON.parse(JSON.stringify(value, (key, item) => (key === 'nodeId' ? undefined : item)));
}

const editorTab: IWorkspaceTab = {
  id: 'editor-1',
  type: WorkspaceTabType.CONSOLE,
  title: 'Console',
};
const rightTerminal: IWorkspaceTab = {
  id: 'terminal-1',
  type: WorkspaceTabType.Terminal,
  title: 'Terminal',
  uniqueData: { terminalOpenPosition: 'right' },
};

const rightLayout = applyTerminalTabOpenPositions(null, [editorTab, rightTerminal], rightTerminal.id);
assert.match(rightLayout?.root?.type === 'split' ? rightLayout.root.nodeId || '' : '', /^split_/);
assert.deepEqual(withoutNodeIds(rightLayout?.root), {
  type: 'split',
  direction: 'vertical',
  size: '70%',
  first: { type: 'pane', id: 'main' },
  second: { type: 'pane', id: 'terminal-panel:right' },
});
assert.deepEqual(rightLayout?.paneTabIds, {
  main: ['editor-1'],
  'terminal-panel:right': ['terminal-1'],
});
assert.equal(rightLayout?.activePane, 'terminal-panel:right');

const bottomTerminal: IWorkspaceTab = {
  ...rightTerminal,
  id: 'terminal-2',
  uniqueData: { terminalOpenPosition: 'bottom' },
};
const secondBottomTerminal: IWorkspaceTab = {
  ...rightTerminal,
  id: 'terminal-3',
  uniqueData: { terminalOpenPosition: 'bottom' },
};
const bottomLayout = applyTerminalTabOpenPositions(
  rightLayout,
  [editorTab, rightTerminal, bottomTerminal, secondBottomTerminal],
  secondBottomTerminal.id,
);
assert.deepEqual(bottomLayout?.paneTabIds['terminal-panel:bottom'], ['terminal-2', 'terminal-3']);
assert.equal(bottomLayout?.activeTabIds['terminal-panel:bottom'], 'terminal-3');
assert.equal(bottomLayout?.activePane, 'terminal-panel:bottom');

const tabTerminal: IWorkspaceTab = {
  ...rightTerminal,
  uniqueData: { terminalOpenPosition: 'tab' },
};
assert.equal(applyTerminalTabOpenPositions(null, [editorTab, tabTerminal], tabTerminal.id), null);

const manuallyPlacedLayout: IWorkspaceTabSplitLayout = {
  direction: 'vertical',
  activePane: 'main',
  root: { type: 'pane', id: 'main' },
  paneTabIds: { main: ['editor-1', 'terminal-1'] },
  activeTabIds: { main: 'terminal-1' },
};
assert.deepEqual(
  applyTerminalTabOpenPositions(manuallyPlacedLayout, [editorTab, rightTerminal], rightTerminal.id),
  manuallyPlacedLayout,
);

console.log('Terminal tab placement tests passed');
