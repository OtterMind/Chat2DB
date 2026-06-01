import { OperationColumn, TreeNodeType } from '@/constants';
import connectionService from '@/service/connection';
import { IConnectionDetails, ITreeNode } from '@/typings';
import { v4 as uuid } from 'uuid';

import mysqlServer from '@/service/sql';

export type ITreeConfig = Partial<{ [key in TreeNodeType]: ITreeConfigItem }>;

export const switchIcon: Partial<{ [key in TreeNodeType]: { icon: string; unfoldIcon?: string } }> = {
  [TreeNodeType.DATABASE]: {
    icon: '\ue669',
  },
  [TreeNodeType.SCHEMAS]: {
    icon: '\ue696',
  },
  [TreeNodeType.TABLE]: {
    icon: '\ue63e',
  },
  [TreeNodeType.TABLES]: {
    icon: '\ueabe',
    unfoldIcon: '\ueabf',
  },
  [TreeNodeType.DEPRECATED_TABLES]: {
    icon: '\ue73c',
    unfoldIcon: '\ueabf',
  },
  [TreeNodeType.DEPRECATED_TABLE]: {
    icon: '\ue63e',
  },
  [TreeNodeType.COLUMNS]: {
    icon: '\ueabe',
    unfoldIcon: '\ueabf',
  },
  [TreeNodeType.COLUMN]: {
    icon: '\ue611',
  },
  [TreeNodeType.KEYS]: {
    icon: '\ueabe',
    unfoldIcon: '\ueabf',
  },
  [TreeNodeType.V_KEYS]: {
    icon: '\ueabe',
    unfoldIcon: '\ueabf',
  },
  [TreeNodeType.KEY]: {
    icon: '\ue775',
  },
  [TreeNodeType.V_KEY]: {
    icon: '\ue775',
  },
  [TreeNodeType.INDEXES]: {
    icon: '\ueabe',
    unfoldIcon: '\ueabf',
  },
  [TreeNodeType.INDEX]: {
    icon: '\ue65b',
  },
  [TreeNodeType.VIEW]: {
    icon: '\ue70c',
  },
  [TreeNodeType.FUNCTION]: {
    icon: '\ue76a',
  },
  [TreeNodeType.PROCEDURE]: {
    icon: '\ue73c',
  },
  [TreeNodeType.TRIGGER]: {
    icon: '\ue64a',
  },
  [TreeNodeType.VIEWCOLUMNS]: {
    icon: '\ueabe',
    unfoldIcon: '\ueabf',
  },
  [TreeNodeType.VIEWCOLUMN]: {
    icon: '\ue647',
  },
  [TreeNodeType.FUNCTIONS]: {
    icon: '\ueabe',
    unfoldIcon: '\ueabf',
  },
  [TreeNodeType.PROCEDURES]: {
    icon: '\ueabe',
    unfoldIcon: '\ueabf',
  },
  [TreeNodeType.TRIGGERS]: {
    icon: '\ueabe',
    unfoldIcon: '\ueabf',
  },
  [TreeNodeType.VIEWS]: {
    icon: '\ueabe',
    unfoldIcon: '\ueabf',
  },
};

export interface ITreeConfigItem {
  icon?: string;
  getChildren?: (params: any, options?: any) => Promise<ITreeNode[]>;
  next?: TreeNodeType;
  operationColumn?: OperationColumn[];
}

export const treeConfig: { [key in TreeNodeType]: ITreeConfigItem } = {
  [TreeNodeType.DATA_SOURCES]: {
    getChildren: (_params, options) => {
      return new Promise((r: (value: ITreeNode[]) => void, j) => {
        const p = {
          pageNo: 1,
          pageSize: 1000,
        };
        connectionService
          .getList(p, options)
          .then((res) => {
            const data: ITreeNode[] = res.data.map((t: IConnectionDetails) => {
              return {
                uuid: uuid(),
                key: t.id,
                name: t.alias,
                treeNodeType: TreeNodeType.DATA_SOURCE,
                extraParams: {
                  databaseType: t.type,
                  dataSourceId: t.id,
                  dataSourceName: t.alias,
                },
              };
            });
            r(data);
          })
          .catch((error) => {
            j(error);
          });
      });
    },
  },

  [TreeNodeType.DATA_SOURCE]: {
    getChildren: (params: { dataSourceId: number; dataSourceName: string; extraParams: any }, options) => {
      return new Promise((r, j) => {
        const _extraParams = params.extraParams;
        delete params.extraParams;
        connectionService
          .getDatabaseList(params, options)
          .then((res) => {
            const data: ITreeNode[] = res.map((t: any) => {
              return {
                uuid: uuid(),
                key: t.name,
                name: t.name,
                treeNodeType: TreeNodeType.DATABASE,
                extraParams: {
                  ..._extraParams,
                  databaseName: t.name,
                },
              };
            });
            r(data);
          })
          .catch((error) => {
            j(error);
          });
      });
    },
    operationColumn: [
      OperationColumn.EditSource,
      OperationColumn.SchemaDiff,
      OperationColumn.Refresh,
      OperationColumn.ShiftOut,
    ],
    next: TreeNodeType.DATABASE,
  },

  [TreeNodeType.DATABASE]: {
    icon: '\ue62c',
    getChildren: (params, options) => {
      const _extraParams = params.extraParams;
      delete params.extraParams;
      return new Promise((r: (value: ITreeNode[], b?: any) => void, j) => {
        connectionService
          .getSchemaList(params, options)
          .then((res) => {
            const data: ITreeNode[] = res.map((t: any) => {
              return {
                uuid: uuid(),
                key: t.name,
                name: t.name,
                treeNodeType: t.treeNodeType === 'tables' ? TreeNodeType.TABLES : TreeNodeType.SCHEMAS,
                schemaName: t.name,
                extraParams: {
                  ..._extraParams,
                  schemaName: t.name,
                },
              };
            });
            r(data);
          })
          .catch((error) => {
            j(error);
          });
      });
    },
    operationColumn: [
      OperationColumn.CreateConsole,
      OperationColumn.ExecuteSqlStatement,
      OperationColumn.ExportSchemaDoc,
      OperationColumn.CreateSchema,
      // OperationColumn.CreateTable,
      OperationColumn.CopyName,
      OperationColumn.Refresh,
      OperationColumn.DeleteDatabase,
    ],
    next: TreeNodeType.SCHEMAS,
  },

  [TreeNodeType.SCHEMAS]: {
    icon: '\ue696',
    getChildren: (parentData: ITreeNode) => {
      const { dataSourceId, databaseName, schemaName } = parentData.extraParams!;
      const preCode = [dataSourceId, databaseName, schemaName].join('-');
      return new Promise((r: (value: ITreeNode[]) => void) => {
        const data = [
          {
            uuid: uuid(),
            key: `${preCode}-tables`,
            name: 'tables',
            treeNodeType: TreeNodeType.TABLES,
            extraParams: parentData.extraParams,
          },
          {
            uuid: uuid(),
            key: `${preCode}-deprecated-tables`,
            name: 'recycleBin',
            treeNodeType: TreeNodeType.DEPRECATED_TABLES,
            extraParams: parentData.extraParams,
          },
          {
            uuid: uuid(),
            key: `${preCode}-views`,
            name: 'view',
            treeNodeType: TreeNodeType.VIEWS,
            extraParams: parentData.extraParams,
          },
          {
            uuid: uuid(),
            key: `${preCode}-functions`,
            name: 'functions',
            treeNodeType: TreeNodeType.FUNCTIONS,
            extraParams: parentData.extraParams,
          },
          {
            uuid: uuid(),
            key: `${preCode}-procedures`,
            name: 'procedures',
            treeNodeType: TreeNodeType.PROCEDURES,
            extraParams: parentData.extraParams,
          },
          {
            uuid: uuid(),
            key: `${preCode}-triggers`,
            name: 'triggers',
            treeNodeType: TreeNodeType.TRIGGERS,
            extraParams: parentData.extraParams,
          },
        ];
        r(data);
      });
    },
    operationColumn: [OperationColumn.CreateConsole, OperationColumn.Refresh],
  },

  [TreeNodeType.TABLES]: {
    icon: '\ueac5',
    getChildren: (params, options) => {
      const _extraParams = params.extraParams;
      delete params.extraParams;
      params.pageSize = 1000;
      return new Promise((r, j) => {
        mysqlServer
          .getTableList(params, options)
          .then((res) => {
            const tableList: ITreeNode[] = res.data?.map((t: any) => {
              return {
                uuid: uuid(),
                name: t.name,
                treeNodeType: TreeNodeType.TABLE,
                key: t.name,
                pinned: t.pinned,
                comment: t.comment,
                extraParams: {
                  ..._extraParams,
                  tableName: t.name,
                },
              };
            });
            r({
              data: tableList,
              pageNo: res.pageNo,
              pageSize: res.pageSize,
              total: res.total,
              hasNextPage: res.hasNextPage,
            } as any);
          })
          .catch((error) => {
            j(error);
          });
      });
    },
    operationColumn: [
      OperationColumn.CreateConsole,
      OperationColumn.ViewAllTable,
      OperationColumn.ViewERDiagram,
      OperationColumn.ViewTableRelation,
      OperationColumn.CreateTable,
      OperationColumn.Refresh,
    ],
  },

  [TreeNodeType.DEPRECATED_TABLES]: {
    icon: '\ue73c',
    getChildren: (params, options) => {
      const _extraParams = params.extraParams;
      delete params.extraParams;
      params.pageSize = 1000;
      return new Promise((r, j) => {
        mysqlServer
          .getDeprecatedTableList(params, options)
          .then((res) => {
            const deprecatedTables = Array.isArray(res) ? res : res?.data || [];
            const tableList: ITreeNode[] = deprecatedTables.map((t: any) => {
              return {
                uuid: uuid(),
                name: t.name,
                treeNodeType: TreeNodeType.DEPRECATED_TABLE,
                key: t.name,
                comment: t.comment,
                extraParams: {
                  ..._extraParams,
                  tableName: t.name,
                },
              };
            });
            r({
              data: tableList,
              pageNo: 1,
              pageSize: tableList.length,
              total: tableList.length,
              hasNextPage: false,
            } as any);
          })
          .catch((error) => {
            j(error);
          });
      });
    },
    operationColumn: [OperationColumn.CreateConsole, OperationColumn.Refresh],
  },

  [TreeNodeType.DEPRECATED_TABLE]: {
    icon: '\ue63e',
    getChildren: (params) => {
      return new Promise((r: (value: ITreeNode[]) => void) => {
        const { dataSourceId, databaseName, schemaName, tableName } = params.extraParams!;
        const preCode = [dataSourceId, databaseName, schemaName, tableName].join('-');
        const list = [
          {
            uuid: uuid(),
            key: `${preCode}-columns`,
            name: 'columns',
            treeNodeType: TreeNodeType.COLUMNS,
            extraParams: params.extraParams,
          },
        ];

        r(list);
      });
    },
    operationColumn: [
      OperationColumn.OpenTable,
      OperationColumn.CreateConsole,
      OperationColumn.ViewDDL,
      OperationColumn.CopyName,
      OperationColumn.Refresh,
      OperationColumn.RestoreTable,
    ],
  },

  [TreeNodeType.TABLE]: {
    icon: '\ue63e',
    getChildren: (params) => {
      return new Promise((r: (value: ITreeNode[]) => void) => {
        const { dataSourceId, databaseName, schemaName, tableName } = params.extraParams!;
        const preCode = [dataSourceId, databaseName, schemaName, tableName].join('-');
        const list = [
          {
            uuid: uuid(),
            key: `${preCode}-columns`,
            name: 'columns',
            treeNodeType: TreeNodeType.COLUMNS,
            extraParams: params.extraParams,
          },
          {
            uuid: uuid(),
            key: `${preCode}-keys`,
            name: 'keys',
            treeNodeType: TreeNodeType.KEYS,
            extraParams: params.extraParams,
          },
          {
            uuid: uuid(),
            key: `${preCode}-indexs`,
            name: 'indexs',
            treeNodeType: TreeNodeType.INDEXES,
            extraParams: params.extraParams,
          },
        ];

        r(list);
      });
    },
    operationColumn: [
      OperationColumn.OpenTable,
      OperationColumn.CreateConsole,
      OperationColumn.EditTable,
      OperationColumn.Pin,
      OperationColumn.ViewDDL,
      OperationColumn.CopyName,
      OperationColumn.ImportData,
      OperationColumn.ExportData,
      OperationColumn.DataTransfer,
      OperationColumn.GenerateData,
      OperationColumn.Refresh,
      OperationColumn.DeleteTable,
      OperationColumn.TruncateTable,
      OperationColumn.DeprecatedTable,
    ],
  },

  [TreeNodeType.VIEWS]: {
    icon: '\ue70c',
    getChildren: (params, options) => {
      const _extraParams = params.extraParams;
      delete params.extraParams;
      return new Promise((r: (value: ITreeNode[]) => void, j) => {
        mysqlServer
          .getViewList(params, options)
          .then((res) => {
            const viewList: ITreeNode[] = res.data?.map((t: any) => {
              return {
                uuid: uuid(),
                name: t.name,
                treeNodeType: TreeNodeType.VIEW,
                key: t.name,
                pinned: t.pinned,
                comment: t.comment,
                extraParams: {
                  ..._extraParams,
                  tableName: t.name,
                },
              };
            });
            r(viewList);
          })
          .catch((error) => {
            j(error);
          });
      });
    },
    operationColumn: [OperationColumn.CreateConsole, OperationColumn.Refresh],
  },

  [TreeNodeType.FUNCTIONS]: {
    icon: '\ue76a',
    getChildren: (params, options) => {
      const _extraParams = params.extraParams;
      delete params.extraParams;
      return new Promise((r: (value: ITreeNode[]) => void, j) => {
        mysqlServer
          .getFunctionList(params, options)
          .then((res) => {
            const list: ITreeNode[] = res.data?.map((t: any) => {
              return {
                uuid: uuid(),
                name: t.name,
                treeNodeType: TreeNodeType.FUNCTION,
                key: t.name,
                pinned: t.pinned,
                comment: t.comment,
                isLeaf: true,
                extraParams: {
                  ..._extraParams,
                  functionName: t.name,
                },
              };
            });
            r(list);
          })
          .catch((error) => {
            j(error);
          });
      });
    },
    operationColumn: [OperationColumn.CreateConsole, OperationColumn.Refresh],
  },

  [TreeNodeType.FUNCTION]: {
    icon: '\ue76a',
    operationColumn: [OperationColumn.CreateConsole, OperationColumn.OpenFunction, OperationColumn.CopyName],
  },

  [TreeNodeType.PROCEDURES]: {
    icon: '\ue73c',
    getChildren: (params, options) => {
      const _extraParams = params.extraParams;
      delete params.extraParams;
      return new Promise((r: (value: ITreeNode[]) => void, j) => {
        mysqlServer
          .getProcedureList(params, options)
          .then((res) => {
            const list: ITreeNode[] = res.data?.map((t: any) => {
              return {
                uuid: uuid(),
                name: t.name,
                treeNodeType: TreeNodeType.PROCEDURE,
                key: t.name,
                pinned: t.pinned,
                comment: t.comment,
                isLeaf: true,
                extraParams: {
                  ..._extraParams,
                  procedureName: t.name,
                },
              };
            });
            r(list);
          })
          .catch((error) => {
            j(error);
          });
      });
    },
    operationColumn: [OperationColumn.CreateConsole, OperationColumn.Refresh],
  },

  [TreeNodeType.PROCEDURE]: {
    icon: '\ue73c',
    operationColumn: [OperationColumn.CreateConsole, OperationColumn.OpenProcedure, OperationColumn.CopyName],
  },

  [TreeNodeType.TRIGGERS]: {
    icon: '\ue64a',
    getChildren: (params, options) => {
      const _extraParams = params.extraParams;
      delete params.extraParams;
      return new Promise((r: (value: ITreeNode[]) => void, j) => {
        mysqlServer
          .getTriggerList(params, options)
          .then((res) => {
            const list: ITreeNode[] = res.data?.map((t: any) => {
              return {
                uuid: uuid(),
                name: t.name,
                treeNodeType: TreeNodeType.TRIGGER,
                key: t.name,
                pinned: t.pinned,
                comment: t.comment,
                isLeaf: true,
                extraParams: {
                  ..._extraParams,
                  triggerName: t.name,
                },
              };
            });
            r(list);
          })
          .catch((error) => {
            j(error);
          });
      });
    },
    operationColumn: [OperationColumn.CreateConsole, OperationColumn.Refresh],
  },

  [TreeNodeType.TRIGGER]: {
    icon: '\ue64a',
    operationColumn: [OperationColumn.CreateConsole, OperationColumn.OpenTrigger, OperationColumn.CopyName],
  },

  [TreeNodeType.VIEW]: {
    icon: '\ue70c',
    getChildren: (params) => {
      return new Promise((r: (value: ITreeNode[]) => void) => {
        const list = [
          {
            uuid: uuid(),
            name: 'columns',
            treeNodeType: TreeNodeType.COLUMNS,
            key: 'columns',
            extraParams: params.extraParams,
          },
        ];
        r(list);
      });
    },
    operationColumn: [OperationColumn.CreateConsole, OperationColumn.OpenView, OperationColumn.CopyName],
  },

  [TreeNodeType.VIEWCOLUMNS]: {
    icon: '\ue647',
    getChildren: (params, options) => {
      const _extraParams = params.extraParams;
      delete params.extraParams;
      return new Promise((r: (value: ITreeNode[]) => void, j) => {
        mysqlServer
          .getViewColumnList(params, options)
          .then((res) => {
            const list: ITreeNode[] = res.data?.map((t: any) => {
              return {
                uuid: uuid(),
                name: t.name,
                treeNodeType: TreeNodeType.VIEWCOLUMN,
                key: t.name,
                pinned: t.pinned,
                comment: t.comment,
                isLeaf: true,
                extraParams: _extraParams,
              };
            });
            r(list);
          })
          .catch((error) => {
            j(error);
          });
      });
    },
    operationColumn: [OperationColumn.CreateConsole, OperationColumn.CopyName, OperationColumn.Refresh],
  },

  [TreeNodeType.VIEWCOLUMN]: {
    icon: '\ue647',
    operationColumn: [OperationColumn.CreateConsole, OperationColumn.CopyName],
  },

  [TreeNodeType.COLUMNS]: {
    icon: '\ueac5',
    getChildren: (params, options) => {
      const _extraParams = params.extraParams;
      delete params.extraParams;
      return new Promise((r: (value: ITreeNode[]) => void, j) => {
        mysqlServer
          .getColumnList(params, options)
          .then((res) => {
            const tableList: ITreeNode[] = res?.map((item) => {
              return {
                uuid: uuid(),
                name: item.name,
                treeNodeType: TreeNodeType.COLUMN,
                key: item.name,
                isLeaf: true,
                columnType: item.columnType,
                comment: item.comment,
                extraParams: _extraParams,
              };
            });
            r(tableList);
          })
          .catch((error) => {
            j(error);
          });
      });
    },
    operationColumn: [OperationColumn.CreateConsole, OperationColumn.Refresh],
  },
  [TreeNodeType.COLUMN]: {
    icon: '\ue611',
    operationColumn: [OperationColumn.CreateConsole, OperationColumn.CopyName],
  },
  [TreeNodeType.KEYS]: {
    icon: '\ueac5',
    getChildren: (params, options) => {
      const _extraParams = params.extraParams;
      delete params.extraParams;
      return new Promise((r: (value: ITreeNode[]) => void, j) => {
        mysqlServer
          .getKeyList(params, options)
          .then((res) => {
            const tableList: ITreeNode[] = res?.map((item) => {
              const isVirtual = item.sourceType === 'VIRTUAL' || item.editable;
              return {
                uuid: uuid(),
                name: item.name,
                treeNodeType: isVirtual ? TreeNodeType.V_KEY : TreeNodeType.KEY,
                key: item.name,
                isLeaf: true,
                extraParams: _extraParams,
              };
            });
            r(tableList);
          })
          .catch((error) => {
            j(error);
          });
      });
    },
    operationColumn: [OperationColumn.CreateConsole, OperationColumn.CopyName, OperationColumn.Refresh],
  },
  [TreeNodeType.KEY]: {
    icon: '\ue775',
    operationColumn: [OperationColumn.ViewTableRelation, OperationColumn.CreateConsole, OperationColumn.CopyName],
  },
  [TreeNodeType.INDEXES]: {
    icon: '\ueac5',
    getChildren: (params, options) => {
      const _extraParams = params.extraParams;
      delete params.extraParams;
      return new Promise((r: (value: ITreeNode[]) => void, j) => {
        mysqlServer
          .getIndexList(params, options)
          .then((res) => {
            const tableList: ITreeNode[] = res?.map((item) => {
              return {
                uuid: uuid(),
                name: item.name,
                treeNodeType: TreeNodeType.INDEX,
                key: item.name,
                isLeaf: true,
                extraParams: _extraParams,
              };
            });
            r(tableList);
          })
          .catch((error) => {
            j(error);
          });
      });
    },
    operationColumn: [OperationColumn.CreateConsole, OperationColumn.CopyName, OperationColumn.Refresh],
  },
  [TreeNodeType.INDEX]: {
    icon: '\ue65b',
    operationColumn: [OperationColumn.CreateConsole, OperationColumn.CopyName],
  },
  [TreeNodeType.V_KEY]: {
    icon: '\ue775',
    operationColumn: [
      OperationColumn.ViewTableRelation,
      OperationColumn.CreateConsole,
      OperationColumn.CopyName,
      OperationColumn.DeleteVirtualKey,
    ],
  },
};
