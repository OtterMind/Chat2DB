import assert from 'node:assert/strict';
import {
  areWorkspacePaneContentBoundsEqual,
  resolveActiveWorkspaceTabPaneIds,
  resolveWorkspacePaneContentBounds,
} from './workspaceTabContentLayout';

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
