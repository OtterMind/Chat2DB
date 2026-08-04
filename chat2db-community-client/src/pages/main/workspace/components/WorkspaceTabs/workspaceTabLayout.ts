import type {
  IWorkspaceTabPaneNode,
  IWorkspaceTabSplitLayout,
  WorkspaceTabPaneId,
  WorkspaceTabSplitDirection,
} from '@/typings';

let splitNodeSequence = 0;

function createSplitNodeId() {
  splitNodeSequence += 1;
  return `split_${Date.now()}_${splitNodeSequence}`;
}

export function createWorkspaceTabSplitNode(
  direction: WorkspaceTabSplitDirection,
  first: IWorkspaceTabPaneNode,
  second: IWorkspaceTabPaneNode,
  size?: number | string,
): IWorkspaceTabPaneNode {
  return {
    type: 'split',
    nodeId: createSplitNodeId(),
    direction,
    ...(size === undefined ? {} : { size }),
    first,
    second,
  };
}

export function ensureWorkspaceTabSplitNodeIds(node: IWorkspaceTabPaneNode): IWorkspaceTabPaneNode {
  if (node.type === 'pane') {
    return node;
  }
  const first = ensureWorkspaceTabSplitNodeIds(node.first);
  const second = ensureWorkspaceTabSplitNodeIds(node.second);
  if (node.nodeId && first === node.first && second === node.second) {
    return node;
  }
  return {
    ...node,
    nodeId: node.nodeId || createSplitNodeId(),
    first,
    second,
  };
}

export function collectWorkspaceTabPaneIds(node?: IWorkspaceTabPaneNode | null): WorkspaceTabPaneId[] {
  if (!node) {
    return [];
  }
  if (node.type === 'pane') {
    return [node.id];
  }
  return [...collectWorkspaceTabPaneIds(node.first), ...collectWorkspaceTabPaneIds(node.second)];
}

export function replaceWorkspaceTabPaneNode(
  node: IWorkspaceTabPaneNode,
  paneId: WorkspaceTabPaneId,
  replacement: IWorkspaceTabPaneNode,
): IWorkspaceTabPaneNode {
  if (node.type === 'pane') {
    return node.id === paneId ? replacement : node;
  }
  const first = replaceWorkspaceTabPaneNode(node.first, paneId, replacement);
  const second = replaceWorkspaceTabPaneNode(node.second, paneId, replacement);
  return first === node.first && second === node.second ? node : { ...node, first, second };
}

export function updateWorkspaceTabSplitNodeSize(
  node: IWorkspaceTabPaneNode,
  nodeId: string,
  size: number | string,
): IWorkspaceTabPaneNode {
  if (node.type === 'pane') {
    return node;
  }
  if (node.nodeId === nodeId) {
    return node.size === size ? node : { ...node, size };
  }
  const first = updateWorkspaceTabSplitNodeSize(node.first, nodeId, size);
  const second = updateWorkspaceTabSplitNodeSize(node.second, nodeId, size);
  return first === node.first && second === node.second ? node : { ...node, first, second };
}

export function pruneWorkspaceTabPaneNode(
  node: IWorkspaceTabPaneNode,
  validPaneIds: Set<WorkspaceTabPaneId>,
): IWorkspaceTabPaneNode | null {
  if (node.type === 'pane') {
    return validPaneIds.has(node.id) ? node : null;
  }
  const first = pruneWorkspaceTabPaneNode(node.first, validPaneIds);
  const second = pruneWorkspaceTabPaneNode(node.second, validPaneIds);
  if (first && second) {
    return first === node.first && second === node.second ? node : { ...node, first, second };
  }
  return first || second;
}

function paneNodesEqual(left?: IWorkspaceTabPaneNode, right?: IWorkspaceTabPaneNode): boolean {
  if (left === right) {
    return true;
  }
  if (!left || !right || left.type !== right.type) {
    return false;
  }
  if (left.type === 'pane' && right.type === 'pane') {
    return left.id === right.id;
  }
  if (left.type !== 'split' || right.type !== 'split') {
    return false;
  }
  return (
    left.nodeId === right.nodeId &&
    left.direction === right.direction &&
    left.size === right.size &&
    paneNodesEqual(left.first, right.first) &&
    paneNodesEqual(left.second, right.second)
  );
}

function recordOfIdsEqual(
  left: Record<string, Array<string | number>>,
  right: Record<string, Array<string | number>>,
) {
  const leftKeys = Object.keys(left);
  const rightKeys = Object.keys(right);
  return (
    leftKeys.length === rightKeys.length &&
    leftKeys.every((key) => {
      if (!Object.prototype.hasOwnProperty.call(right, key)) {
        return false;
      }
      const leftIds = left[key] || [];
      const rightIds = right[key] || [];
      return leftIds.length === rightIds.length && leftIds.every((id, index) => id === rightIds[index]);
    })
  );
}

export function areWorkspaceTabSplitLayoutsEqual(
  left: IWorkspaceTabSplitLayout | null | undefined,
  right: IWorkspaceTabSplitLayout | null | undefined,
) {
  if (left === right) {
    return true;
  }
  if (!left || !right) {
    return !left && !right;
  }
  const leftActiveKeys = Object.keys(left.activeTabIds);
  const rightActiveKeys = Object.keys(right.activeTabIds);
  return (
    left.direction === right.direction &&
    left.activePane === right.activePane &&
    paneNodesEqual(left.root, right.root) &&
    recordOfIdsEqual(left.paneTabIds, right.paneTabIds) &&
    leftActiveKeys.length === rightActiveKeys.length &&
    leftActiveKeys.every(
      (key) =>
        Object.prototype.hasOwnProperty.call(right.activeTabIds, key) &&
        left.activeTabIds[key] === right.activeTabIds[key],
    )
  );
}
