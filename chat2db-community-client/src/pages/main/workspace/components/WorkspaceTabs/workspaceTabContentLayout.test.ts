import assert from 'node:assert/strict';
import {
  areWorkspacePaneContentBoundsEqual,
  resolveActiveWorkspaceTabPaneIds,
  resolveWorkspaceTabContentActivation,
  resolveWorkspacePaneContentBounds,
} from './workspaceTabContentLayout';

assert.deepEqual(
  resolveWorkspaceTabContentActivation({
    paneId: 'pane-right',
    tabId: 'tab-b',
    activePaneId: 'main',
    activeConsoleId: 'tab-a',
  }),
  { paneId: 'pane-right', tabId: 'tab-b' },
  'pressing a sibling pane body should activate its visible tab before shortcuts run',
);
assert.equal(
  resolveWorkspaceTabContentActivation({
    paneId: 'main',
    tabId: 'tab-a',
    activePaneId: 'main',
    activeConsoleId: 'tab-a',
  }),
  null,
  'pressing the already active pane body should not repeat the selection update',
);
assert.equal(
  resolveWorkspaceTabContentActivation({
    paneId: undefined,
    tabId: 'tab-hidden',
    activePaneId: 'main',
    activeConsoleId: 'tab-a',
  }),
  null,
  'a hidden tab body cannot change the active pane',
);

assert.deepEqual(
  Array.from(
    resolveActiveWorkspaceTabPaneIds({
      activeConsoleId: 'tab-a',
      mainPaneId: 'main',
    }),
  ),
  [['tab-a', 'main']],
);
assert.deepEqual(
  Array.from(
    resolveActiveWorkspaceTabPaneIds({
      activeConsoleId: 'tab-a',
      paneActiveTabIds: { main: 'tab-b', 'pane-right': 'tab-a' },
      mainPaneId: 'main',
    }),
  ),
  [
    ['tab-b', 'main'],
    ['tab-a', 'pane-right'],
  ],
  'moving a tab changes only its pane placement while retaining the tab identity',
);

const mainBounds = resolveWorkspacePaneContentBounds(
  { left: 100, top: 40, width: 1200, height: 800 },
  { left: 120, top: 76, width: 580, height: 744 },
);
assert.deepEqual(mainBounds, { left: 20, top: 36, width: 580, height: 744 });

assert.equal(
  areWorkspacePaneContentBoundsEqual({ main: mainBounds }, { main: { ...mainBounds } }),
  true,
  'equivalent measurements must not trigger another layout render',
);
assert.equal(
  areWorkspacePaneContentBoundsEqual(
    { main: mainBounds },
    { main: { ...mainBounds, width: mainBounds.width + 1 } },
  ),
  false,
  'a resized pane must update the persistent content layer',
);

console.log('Workspace tab content layout tests passed');
