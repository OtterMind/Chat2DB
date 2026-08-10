import { TreeNodeType, DatabaseTypeCode } from '@/constants';
import { DataCollectionElementType } from '@/constants/aiDataCollection';
import { TreeDataNode as AntdTreeDataNode } from 'antd';

export interface IExtraParams {
  groupId?: number;
  dataSourceId?: number;
  databaseType?: DatabaseTypeCode;
  dataSourceName?: string;
  supportDatabase?: boolean;
  supportSchema?: boolean;
  databaseName?: string;
  schemaName?: string;
  tableName?: string;
  viewName?: string;
  functionName?: string;
  procedureName?: string;
  triggerName?: string;
  user?: string;
  host?: string;
  aiDataCollectionId?: number;
  storageType?: string;
  hasPermission?: boolean;
  isAdmin?: boolean;
  dataCollectionElementType?: DataCollectionElementType;
}

export interface DecorativeParams {
  pinned?: boolean; // Whether to pin it to the top
  columnType?: string; // Column type
  comment?: string; // Comments on table columns
}

export interface TreeNodeData extends AntdTreeDataNode {
  key: string;
  // Backend ID used for updates; the frontend node identity remains AntdTreeDataNode.key.
  id?: number;
  position?: number; // location
  // Whether it is the last node, relative to the same level node
  isLastInSiblings?: boolean;
  originalTitle: string;
  describe?: string;
  treeNodeType: TreeNodeType;
  extraParams: IExtraParams;
  decorativeParams?: DecorativeParams;
  columnType?: string;
  childCount?: number;

  children?: TreeNodeData[];
}

export interface ITreeNode {
  key: string;
  name: string;
  children?: ITreeNode[] | null;

  treeNodeType: TreeNodeType; // Type of node: table, column, file, etc.
  pretendNodeType?: TreeNodeType; // Disguised node type, needed when the tree is discontinuous
  isLeaf?: boolean; // Whether it is a leaf node
  extraParams?: IExtraParams;
  columnType?: string; // Column type
  pinned?: boolean; // Whether to pin it to the top
  comment?: string; // Comments on table columns
  loadData?: (params: { refresh: boolean }) => void; // How to load data
  // parent element
  parentNode?: ITreeNode;
  level?: number; // Hierarchy
  // Whether to expand
  expanded?: boolean;
  parentId?: string;
  // Pagination
  page?: number;
  pageSize?: number;
  total?: number;
}

// View function trigger procedure general return result
export interface IRoutines {
  name: string; // Name
  comment: string; // Description
  pinned: boolean; // Whether to pin it to the top
}

export interface ITable {
  /**
   *Table description
   */
  comment?: string;
  /**
   *Table name
   */
  name: string;
  tableType: 'TABLE' | 'VIEW';
  /**
   * Whether it has been fixed
   */
  pinned?: boolean;
}

export interface GetTreeNodeKeyParams {
  dataSourceId: number;
  databaseType: DatabaseTypeCode;
  databaseName?: string;
  schemaName?: string;
  tableName?: string;
  treeNodeType: TreeNodeType;
}

// Update the location of a data source or group
export type UpdatePositionInTree = {
  dragNode: {
    id: number;
    type: 'NAMESPACE' | 'DATA_SOURCE';
  };
  dropToNode: {
    id: number;
    type: 'NAMESPACE' | 'DATA_SOURCE';
  };
  // -1: The dragged node is placed above the target node.
  // 0: The dragged node is placed inside the target node as a child node.
  // 1: The dragged node is placed below the target node.
  dropPosition: 0 | 1 | -1;
};
