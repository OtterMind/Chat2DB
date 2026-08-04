import assert from 'node:assert/strict';
import {
  createWorkspaceTabEdgeSplitLayout,
  getWorkspaceTabDropPlacement,
  getWorkspaceTabEdgeDropId,
  getWorkspaceTabEdgeDropTarget,
} from './workspaceTabDrop';

function withoutNodeIds<T>(value: T): T {
  return JSON.parse(JSON.stringify(value, (key, item) => (key === 'nodeId' ? undefined : item)));
}

assert.deepEqual(getWorkspaceTabEdgeDropTarget(getWorkspaceTabEdgeDropId('main', 'right')), {
  paneId: 'main',
  position: 'right',
});
assert.deepEqual(getWorkspaceTabEdgeDropTarget(getWorkspaceTabEdgeDropId('pane:with/slashes', 'top')), {
  paneId: 'pane:with/slashes',
  position: 'top',
});
assert.equal(getWorkspaceTabEdgeDropTarget('workspace-tab-edge:main:center'), undefined);
assert.equal(getWorkspaceTabEdgeDropTarget('workspace-tab-pane:main'), undefined);

assert.deepEqual(getWorkspaceTabDropPlacement('left'), {
  direction: 'vertical',
  newPanePlacement: 'first',
});
assert.deepEqual(getWorkspaceTabDropPlacement('right'), {
  direction: 'vertical',
  newPanePlacement: 'second',
});
assert.deepEqual(getWorkspaceTabDropPlacement('top'), {
  direction: 'horizontal',
  newPanePlacement: 'first',
});
assert.deepEqual(getWorkspaceTabDropPlacement('bottom'), {
  direction: 'horizontal',
  newPanePlacement: 'second',
});

const unsplitLayout = createWorkspaceTabEdgeSplitLayout({
  currentLayout: {
    direction: 'vertical',
    activePane: 'main',
    root: { type: 'pane', id: 'main' },
    paneTabIds: { main: ['first', 'dragged'] },
    activeTabIds: { main: 'dragged' },
  },
  currentRoot: { type: 'pane', id: 'main' },
  sourcePaneId: 'main',
  sourceTabId: 'dragged',
  targetPaneId: 'main',
  newPaneId: 'right-pane',
  position: 'right',
});
assert.match(unsplitLayout?.root?.type === 'split' ? unsplitLayout.root.nodeId || '' : '', /^split_/);
assert.deepEqual(withoutNodeIds(unsplitLayout?.root), {
  type: 'split',
  direction: 'vertical',
  first: { type: 'pane', id: 'main' },
  second: { type: 'pane', id: 'right-pane' },
});
assert.deepEqual(unsplitLayout?.paneTabIds, {
  main: ['first'],
  'right-pane': ['dragged'],
});
assert.deepEqual(unsplitLayout?.activeTabIds, {
  main: 'first',
  'right-pane': 'dragged',
});

const existingRoot = {
  type: 'split' as const,
  direction: 'vertical' as const,
  first: { type: 'pane' as const, id: 'source' },
  second: { type: 'pane' as const, id: 'target' },
};
const crossPaneLayout = createWorkspaceTabEdgeSplitLayout({
  currentLayout: {
    direction: 'vertical',
    activePane: 'source',
    root: existingRoot,
    paneTabIds: { source: ['dragged'], target: ['existing'] },
    activeTabIds: { source: 'dragged', target: 'existing' },
  },
  currentRoot: existingRoot,
  sourcePaneId: 'source',
  sourceTabId: 'dragged',
  targetPaneId: 'target',
  newPaneId: 'top-pane',
  position: 'top',
});
assert.deepEqual(crossPaneLayout?.paneTabIds, {
  source: [],
  target: ['existing'],
  'top-pane': ['dragged'],
});
assert.deepEqual(withoutNodeIds(crossPaneLayout?.root), {
  type: 'split',
  direction: 'vertical',
  first: { type: 'pane', id: 'source' },
  second: {
    type: 'split',
    direction: 'horizontal',
    first: { type: 'pane', id: 'top-pane' },
    second: { type: 'pane', id: 'target' },
  },
});

assert.equal(
  createWorkspaceTabEdgeSplitLayout({
    currentLayout: {
      direction: 'vertical',
      activePane: 'main',
      root: { type: 'pane', id: 'main' },
      paneTabIds: { main: ['only-tab'] },
      activeTabIds: { main: 'only-tab' },
    },
    currentRoot: { type: 'pane', id: 'main' },
    sourcePaneId: 'main',
    sourceTabId: 'only-tab',
    targetPaneId: 'main',
    newPaneId: 'right-pane',
    position: 'right',
  }),
  undefined,
);

console.log('Workspace tab edge-drop tests passed');
