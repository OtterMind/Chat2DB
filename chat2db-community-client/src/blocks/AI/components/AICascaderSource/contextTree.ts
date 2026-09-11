import { TreeNodeType } from '@/constants/tree';
import type { TreeNodeData } from '@/typings';

const AI_CONTEXT_NODE_TYPES = new Set([
  TreeNodeType.GROUP,
  TreeNodeType.DATA_SOURCE,
  TreeNodeType.DATABASE,
  TreeNodeType.SCHEMA,
]);

export const buildAIContextTree = (data: readonly TreeNodeData[]): TreeNodeData[] =>
  data.flatMap((item) => {
    if (!AI_CONTEXT_NODE_TYPES.has(item.treeNodeType)) {
      return [];
    }

    const children = buildAIContextTree(item.children || []);
    if (item.treeNodeType === TreeNodeType.GROUP && !children.length) {
      return [];
    }

    const isLeaf =
      item.treeNodeType === TreeNodeType.SCHEMA ||
      (item.treeNodeType === TreeNodeType.DATABASE && !item.extraParams?.supportSchema) ||
      (item.treeNodeType === TreeNodeType.DATA_SOURCE &&
        item.extraParams?.supportDatabase === false &&
        item.extraParams?.supportSchema === false);

    return [
      {
        ...item,
        isLeaf,
        children: isLeaf || !children.length ? undefined : children,
      },
    ];
  });
