export type LocalSQLFileTreeNodeType = 'directory' | 'file';

export type LocalSQLFileTreeCreateType = 'directory' | 'file';

export interface LocalSQLFileTreeNode {
  key: string;
  rootToken: string;
  rootPath?: string;
  name: string;
  path: string;
  relativePath: string;
  type: LocalSQLFileTreeNodeType;
  disabled?: boolean;
  sqlFile?: boolean;
  textFile?: boolean;
  previewFile?: boolean;
  fileExtension?: string;
  hasChildren?: boolean;
  loaded?: boolean;
  loading?: boolean;
  children?: LocalSQLFileTreeNode[];
}
