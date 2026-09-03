import { ConsoleStatus, DatabaseCapability, OperationColumn, TreeNodeType, WorkspaceTabType } from '@/constants';
import i18n from '@/i18n';
import accountAdminService from '@/service/accountAdmin';
import connectionService from '@/service/connection';
import historyService from '@/service/history';
import mysqlServer from '@/service/sql';
import { useTreeStore } from '@/store/tree';
import { IConnectionDetails, TreeNodeData } from '@/typings';
import { getDatabaseSupport } from '@/utils/database';
import { isDatabaseCapabilitySupported } from '@/utils/databaseJudgments';
import { v4 as uuid } from 'uuid';
import { createSavedConsoleTreeNodeKey } from '@/store/tree/backgroundRefresh';
import { createEventTreeNodeDescription, createEventTreeNodeKey, supportsEventTree } from './eventTreeIdentity';
import {
  createActiveTransactionsTreeNodeKey,
  createMonitorTreeNodeKey,
  MONITOR_TREE_ITEMS,
} from './monitorTree';

const fileIcon = 'icon-colourful-folder-close';
const unfoldFileIcon = 'icon-colourful-folder-open';

export interface ILoadDataOptions {
  refresh?: boolean;
  // Turn off the expansion of tree nodes
  closeExpandTreeNode?: boolean;
  // Refresh children without changing the current selection or collapsing descendants.
  preserveInteraction?: boolean;
}

export interface ILoadDataResult {
  children: TreeNodeData[];
  committed: boolean;
}

export const switchIcon: Partial<{
  [key in TreeNodeType]: { icon: string; unfoldIcon?: string; iconExistDark?: boolean };
}> = {
  [TreeNodeType.GROUP]: {
    icon: fileIcon,
    iconExistDark: true,
    unfoldIcon: unfoldFileIcon,
  },
  [TreeNodeType.DATABASE]: {
    icon: 'icon-database',
  },
  [TreeNodeType.DATABASE_ACCOUNTS]: {
    icon: 'icon-users',
  },
  [TreeNodeType.DATABASE_ACCOUNT]: {
    icon: 'icon-users',
  },
  [TreeNodeType.SCHEMAS]: {
    icon: fileIcon,
    iconExistDark: true,
    unfoldIcon: unfoldFileIcon,
  },
  [TreeNodeType.SCHEMA]: {
    icon: 'icon-schema',
  },
  [TreeNodeType.TABLE]: {
    icon: 'icon-colourful-table',
    iconExistDark: true,
  },
  [TreeNodeType.TABLES]: {
    icon: fileIcon,
    iconExistDark: true,
    unfoldIcon: unfoldFileIcon,
  },
  [TreeNodeType.COLUMNS]: {
    icon: fileIcon,
    iconExistDark: true,
    unfoldIcon: unfoldFileIcon,
  },
  [TreeNodeType.COLUMN]: {
    icon: 'icon-column',
  },
  [TreeNodeType.KEYS]: {
    icon: fileIcon,
    iconExistDark: true,
    unfoldIcon: unfoldFileIcon,
  },
  [TreeNodeType.KEY]: {
    icon: 'icon-key',
  },
  [TreeNodeType.INDEXES]: {
    icon: fileIcon,
    iconExistDark: true,
    unfoldIcon: unfoldFileIcon,
  },
  [TreeNodeType.INDEX]: {
    icon: 'icon-extend-nav-info',
  },
  [TreeNodeType.VIEW]: {
    icon: 'icon-colourful-table-view',
    iconExistDark: true,
  },
  [TreeNodeType.FUNCTION]: {
    icon: 'icon-function',
  },
  [TreeNodeType.PROCEDURE]: {
    icon: 'icon-procedure',
  },
  [TreeNodeType.TRIGGER]: {
    icon: 'icon-trigger',
  },
  [TreeNodeType.VIEWCOLUMNS]: {
    icon: fileIcon,
    iconExistDark: true,
    unfoldIcon: unfoldFileIcon,
  },
  [TreeNodeType.VIEWCOLUMN]: {
    icon: 'icon-column',
  },
  [TreeNodeType.FUNCTIONS]: {
    icon: fileIcon,
    iconExistDark: true,
    unfoldIcon: unfoldFileIcon,
  },
  [TreeNodeType.PROCEDURES]: {
    icon: fileIcon,
    iconExistDark: true,
    unfoldIcon: unfoldFileIcon,
  },
  [TreeNodeType.TRIGGERS]: {
    icon: fileIcon,
    iconExistDark: true,
    unfoldIcon: unfoldFileIcon,
  },
  [TreeNodeType.EVENTS]: {
    icon: fileIcon,
    iconExistDark: true,
    unfoldIcon: unfoldFileIcon,
  },
  [TreeNodeType.EVENT]: {
    icon: 'icon-event',
  },
  [TreeNodeType.VIEWS]: {
    icon: fileIcon,
    iconExistDark: true,
    unfoldIcon: unfoldFileIcon,
  },
  [TreeNodeType.ALL_DATA]: {
    icon: 'icon-colourful-table-view',
    iconExistDark: true,
  },
  [TreeNodeType.SAVE_CONSOLES]: {
    icon: fileIcon,
    iconExistDark: true,
    unfoldIcon: unfoldFileIcon,
  },
  [TreeNodeType.SAVE_CONSOLE]: {
    icon: 'icon-code-rimless',
  },
};

export interface OperationColumnObject {
  type: OperationColumn;
  title?: string;
}

export interface ITreeConfigItem {
  getChildren?: (extraParams: any, options?: ILoadDataOptions) => Promise<TreeNodeData[] | TreeNodeLoadResult>;
  createTreeNodeKey?: (data: any) => string;
  renameCallback?: any;
}

export interface TreeNodeLoadResult {
  children: TreeNodeData[];
  total?: number;
}

export const normalizeTreeNodeLoadResult = (result: TreeNodeData[] | TreeNodeLoadResult): TreeNodeLoadResult =>
  Array.isArray(result) ? { children: result } : result;

// receives an object. When the value in the object contains '' or null, it is converted to undefined.
export const formatObject = (obj: Record<string, any>): Record<string, string | any> => {
  return Object.keys(obj).reduce((acc: Record<string, string | any>, key: string) => {
    // Normalize null and empty values before using them to construct a key.
    acc[key] = obj[key] === '' || obj[key] === null ? undefined : obj[key];
    return acc;
  }, {});
};

function createSaveConsolesNode(extraParams: any): TreeNodeData {
  return {
    key: treeConfig[TreeNodeType.SAVE_CONSOLES].createTreeNodeKey!(extraParams),
    originalTitle: i18n('common.text.consoles'),
    title: null,
    treeNodeType: TreeNodeType.SAVE_CONSOLES,
    isLeaf: false,
    extraParams,
  };
}

function createMonitorNode(extraParams: any): TreeNodeData {
  return {
    key: createMonitorTreeNodeKey(extraParams.dataSourceId),
    originalTitle: i18n('workspace.ops.monitor'),
    title: null,
    treeNodeType: TreeNodeType.MONITOR,
    isLeaf: false,
    extraParams,
  };
}

export const treeConfig: { [key in TreeNodeType]: ITreeConfigItem } = {
  [TreeNodeType.GROUPS]: {
    getChildren: () => {
      return new Promise((r: (value: TreeNodeData[]) => void) => {
        r([]);
      });
    },
  },

  [TreeNodeType.GROUP]: {
    getChildren: () => {
      return new Promise((r: (value: TreeNodeData[]) => void) => {
        r([]);
      });
    },
    renameCallback: (text: string, nodeData: TreeNodeData) => {
      if (nodeData.id && text) {
        connectionService.updateNamespace({ id: nodeData.id, name: text }).then(() => {
          useTreeStore.getState().updateOriginalTitleByNodeId(nodeData.key as string, text);
        });
      }
    },
    createTreeNodeKey: (params) => {
      const { groupId } = formatObject(params);
      return `group_${groupId}`;
    },
  },

  [TreeNodeType.DATA_SOURCES]: {
    getChildren: () => {
      return new Promise((r, j) => {
        connectionService
          .getList({
            pageNo: 1,
            pageSize: 1000,
          })
          .then((res) => {
            const data: TreeNodeData[] = res?.data?.map((t: IConnectionDetails) => {
              const key = treeConfig[TreeNodeType.DATA_SOURCE].createTreeNodeKey!({
                dataSourceId: t.id,
              });
              return {
                key,
                originalTitle: t.alias,
                title: null, // Keep this null so the tree does not apply a title to the demo label.
                treeNodeType: TreeNodeType.DATA_SOURCE,
                isLeaf: false,
                extraParams: {
                  databaseType: t.type,
                  dataSourceId: t.id,
                  dataSourceName: t.alias,
                  environmentId: t.environmentId ?? t.environment?.id ?? null,
                  environment: t.environment ?? null,
                  identityColor: t.identityColor ?? null,
                  watermarkEnabled: t.watermarkEnabled ?? null,
                  watermarkContent: t.watermarkContent ?? null,
                },
              };
            });
            r(data);
          })
          .catch(() => {
            j();
          });
      });
    },
    createTreeNodeKey: () => {
      return `dataSources_chat2dbCatalogue`;
    },
  },

  [TreeNodeType.DATA_SOURCE]: {
    getChildren: (extraParams: any) => {
      return new Promise((r, j) => {
        const { dataSourceId, databaseType } = extraParams;
        const { supportDatabase, supportSchema } = getDatabaseSupport(databaseType);
        const accountNode: TreeNodeData | null = isDatabaseCapabilitySupported(
          databaseType,
          DatabaseCapability.ACCOUNT_MANAGEMENT,
        )
          ? {
              key: treeConfig[TreeNodeType.DATABASE_ACCOUNTS].createTreeNodeKey!({ dataSourceId }),
              originalTitle: i18n('workspace.databaseAccount.title'),
              title: null,
              treeNodeType: TreeNodeType.DATABASE_ACCOUNTS,
              isLeaf: false,
              extraParams,
            }
          : null;
        const monitorNode = MONITOR_TREE_ITEMS.some(({ capability }) =>
          isDatabaseCapabilitySupported(databaseType, capability),
        )
          ? createMonitorNode(extraParams)
          : null;
        const appendDataSourceNodes = (data: TreeNodeData[]) => {
          if (monitorNode) {
            data.push(monitorNode);
          }
          if (accountNode) {
            data.push(accountNode);
          }
          return data;
        };
        if (supportDatabase === false && supportSchema === false) {
          // No database or schema level at all (Firebird, IoTDB, ...): the
          // connection itself is the namespace, so show the object folders
          // directly instead of requesting an empty schema list.
          const params = { dataSourceId };
          const nodeExtraParams = { ...extraParams };
          const data: TreeNodeData[] = [
            {
              key: treeConfig[TreeNodeType.TABLES].createTreeNodeKey!(params),
              originalTitle: i18n('common.text.tables'),
              title: null,
              treeNodeType: TreeNodeType.TABLES,
              isLeaf: false,
              extraParams: nodeExtraParams,
            },
            {
              key: treeConfig[TreeNodeType.VIEWS].createTreeNodeKey!(params),
              originalTitle: i18n('common.text.views'),
              title: null,
              treeNodeType: TreeNodeType.VIEWS,
              isLeaf: false,
              extraParams: nodeExtraParams,
            },
            {
              key: treeConfig[TreeNodeType.FUNCTIONS].createTreeNodeKey!(params),
              originalTitle: i18n('common.text.functions'),
              title: null,
              treeNodeType: TreeNodeType.FUNCTIONS,
              isLeaf: false,
              extraParams: nodeExtraParams,
            },
            {
              key: treeConfig[TreeNodeType.PROCEDURES].createTreeNodeKey!(params),
              originalTitle: i18n('common.text.procedures'),
              title: null,
              treeNodeType: TreeNodeType.PROCEDURES,
              isLeaf: false,
              extraParams: nodeExtraParams,
            },
            {
              key: treeConfig[TreeNodeType.TRIGGERS].createTreeNodeKey!(params),
              originalTitle: i18n('common.text.triggers'),
              title: null,
              treeNodeType: TreeNodeType.TRIGGERS,
              isLeaf: false,
              extraParams: nodeExtraParams,
            },
            createSaveConsolesNode(nodeExtraParams),
          ];
          r(appendDataSourceNodes(data));
          return;
        }
        if (supportDatabase === false) {
          connectionService
            .getSchemaList(extraParams)
            .then((res) => {
              const data: TreeNodeData[] = res.map((t: any) => {
                const key = treeConfig[TreeNodeType.SCHEMA].createTreeNodeKey!({
                  dataSourceId,
                  schemaName: t.name,
                });
                return {
                  // key: `dataSource_${dataSourceId}-schema_${t.name}`,
                  key,
                  originalTitle: t.name,
                  title: null,
                  treeNodeType: TreeNodeType.SCHEMA,
                  isLeaf: false,
                  extraParams: {
                    ...extraParams,
                    schemaName: t.name,
                  },
                };
              });
              r(appendDataSourceNodes(data));
            })
            .catch(() => {
              j();
            });
        } else {
          connectionService
            .getDatabaseList({ dataSourceId: extraParams.dataSourceId, refresh: extraParams.refresh })
            .then((res) => {
              const data: TreeNodeData[] = res?.map((t: any) => {
                const key = treeConfig[TreeNodeType.DATABASE].createTreeNodeKey!({
                  dataSourceId,
                  databaseName: t.name,
                });
                return {
                  // key: `dataSource_${dataSourceId}-database_${t.name}`,
                  key,
                  originalTitle: t.name,
                  title: null,
                  treeNodeType: TreeNodeType.DATABASE,
                  isLeaf: false,
                  extraParams: {
                    ...extraParams,
                    databaseName: t.name,
                  },
                };
              });
              r(appendDataSourceNodes(data));
            })
            .catch(() => {
              j();
            });
        }
      });
    },
    createTreeNodeKey: (params) => {
      const { dataSourceId } = formatObject(params);
      return `dataSource_${dataSourceId}`;
    },
  },

  [TreeNodeType.MONITOR]: {
    getChildren: (extraParams: any) => {
      return Promise.resolve(
        MONITOR_TREE_ITEMS.filter(({ capability }) =>
          isDatabaseCapabilitySupported(extraParams.databaseType, capability),
        ).map(({ treeNodeType, titleKey }) => ({
          key: treeConfig[treeNodeType].createTreeNodeKey!(extraParams),
          originalTitle: i18n(titleKey),
          title: null,
          treeNodeType,
          isLeaf: true,
          extraParams,
        })),
      );
    },
    createTreeNodeKey: (params) => createMonitorTreeNodeKey(params.dataSourceId),
  },

  [TreeNodeType.ACTIVE_TRANSACTIONS]: {
    getChildren: () => Promise.resolve([]),
    createTreeNodeKey: (params) => createActiveTransactionsTreeNodeKey(params.dataSourceId),
  },

  [TreeNodeType.DATABASE_ACCOUNTS]: {
    getChildren: (extraParams: any) => {
      return accountAdminService.list({ dataSourceId: extraParams.dataSourceId }).then((accounts) => {
        return (accounts || []).map((account) => ({
          key: treeConfig[TreeNodeType.DATABASE_ACCOUNT].createTreeNodeKey!({
            dataSourceId: extraParams.dataSourceId,
            user: account.user,
            host: account.host,
          }),
          originalTitle: account.displayName,
          title: null,
          treeNodeType: TreeNodeType.DATABASE_ACCOUNT,
          isLeaf: true,
          extraParams: {
            ...extraParams,
            user: account.user,
            host: account.host,
            popoverContent: account.displayName,
          },
        }));
      });
    },
    createTreeNodeKey: (params) => {
      const { dataSourceId } = formatObject(params);
      return `dataSource_${dataSourceId}-databaseAccounts`;
    },
  },

  [TreeNodeType.DATABASE_ACCOUNT]: {
    getChildren: () => {
      return new Promise((r: (value: TreeNodeData[]) => void) => {
        r([]);
      });
    },
    createTreeNodeKey: (params) => {
      const { dataSourceId, user, host } = formatObject(params);
      return `dataSource_${dataSourceId}-databaseAccount_${encodeURIComponent(user || '')}_${encodeURIComponent(
        host || '',
      )}`;
    },
  },

  [TreeNodeType.SCHEMAS]: {
    getChildren: (extraParams: any) => {
      return new Promise((r, j) => {
        const { dataSourceId, databaseType } = extraParams;
        const { supportDatabase } = getDatabaseSupport(databaseType);
        if (supportDatabase === false) {
          connectionService
            .getSchemaList(extraParams)
            .then((res) => {
              const data: TreeNodeData[] = res.map((t: any) => {
                const key = treeConfig[TreeNodeType.SCHEMA].createTreeNodeKey!({
                  dataSourceId,
                  schemaName: t.name,
                });
                return {
                  // key: `dataSource_${extraParams.dataSourceId}-schema_${t.name}`,
                  key,
                  originalTitle: t.name,
                  title: null,
                  treeNodeType: TreeNodeType.SCHEMA,
                  isLeaf: false,
                  extraParams: {
                    ...extraParams,
                    schemaName: t.name,
                  },
                };
              });
              r(data);
            })
            .catch(() => {
              j();
            });
        } else {
          connectionService
            .getDatabaseList({ dataSourceId: extraParams.dataSourceId, refresh: extraParams.refresh })
            .then((res) => {
              const data: TreeNodeData[] = res?.map((t: any) => {
                const key = treeConfig[TreeNodeType.DATABASE].createTreeNodeKey!({
                  dataSourceId,
                  databaseName: t.name,
                });
                return {
                  // key: `dataSource_${dataSourceId}-database_${t.name}`,
                  key,
                  originalTitle: t.name,
                  title: null,
                  treeNodeType: TreeNodeType.DATABASE,
                  isLeaf: false,
                  extraParams: {
                    ...extraParams,
                    databaseName: t.name,
                  },
                };
              });
              r(data);
            })
            .catch(() => {
              j();
            });
        }
      });
    },
    createTreeNodeKey: (params) => {
      const { dataSourceId, schemaName } = formatObject(params);
      return `dataSource_${dataSourceId}-schema_${schemaName}`;
    },
  },

  [TreeNodeType.DATABASE]: {
    getChildren: (extraParams) => {
      return new Promise((r: (value: TreeNodeData[], b?: any) => void, j) => {
        const { dataSourceId, databaseName, databaseType } = extraParams;
        const { supportSchema } = getDatabaseSupport(databaseType);
        if (supportSchema === true) {
          connectionService
            .getSchemaList(extraParams)
            .then((res) => {
              const data: TreeNodeData[] = res.map((t: any) => {
                const key = treeConfig[TreeNodeType.SCHEMA].createTreeNodeKey!({
                  dataSourceId,
                  databaseName,
                  schemaName: t.name,
                });
                return {
                  key,
                  originalTitle: t.name,
                  title: null,
                  treeNodeType: TreeNodeType.SCHEMA,
                  isLeaf: false,
                  extraParams: {
                    ...extraParams,
                    schemaName: t.name,
                  },
                };
              });
              r(data);
            })
            .catch(() => {
              j();
            });
        } else {
          const params = {
            dataSourceId,
            databaseName,
          };
          const nodeExtraParams = {
            ...extraParams,
            ...params,
          };
          const tablesKey = treeConfig[TreeNodeType.TABLES].createTreeNodeKey!(params);
          const viewsKey = treeConfig[TreeNodeType.VIEWS].createTreeNodeKey!(params);
          const functionsKey = treeConfig[TreeNodeType.FUNCTIONS].createTreeNodeKey!(params);
          const proceduresKey = treeConfig[TreeNodeType.PROCEDURES].createTreeNodeKey!(params);
          const triggersKey = treeConfig[TreeNodeType.TRIGGERS].createTreeNodeKey!(params);
          const allDataKey = treeConfig[TreeNodeType.ALL_DATA].createTreeNodeKey!(params);
          const data = [
            {
              key: tablesKey,
              originalTitle: i18n('common.text.tables'),
              title: null,
              treeNodeType: TreeNodeType.TABLES,
              isLeaf: false,
              extraParams: nodeExtraParams,
            },
            {
              key: viewsKey,
              originalTitle: i18n('common.text.views'),
              title: null,
              treeNodeType: TreeNodeType.VIEWS,
              isLeaf: false,
              extraParams: nodeExtraParams,
            },
            {
              key: functionsKey,
              originalTitle: i18n('common.text.functions'),
              title: null,
              treeNodeType: TreeNodeType.FUNCTIONS,
              isLeaf: false,
              extraParams: nodeExtraParams,
            },
            {
              key: proceduresKey,
              originalTitle: i18n('common.text.procedures'),
              title: null,
              treeNodeType: TreeNodeType.PROCEDURES,
              isLeaf: false,
              extraParams: nodeExtraParams,
            },
            {
              key: triggersKey,
              originalTitle: i18n('common.text.triggers'),
              title: null,
              treeNodeType: TreeNodeType.TRIGGERS,
              isLeaf: false,
              extraParams: nodeExtraParams,
            },
            ...(supportsEventTree(databaseType)
              ? [
                  {
                    key: treeConfig[TreeNodeType.EVENTS].createTreeNodeKey!(params),
                    originalTitle: i18n('common.text.events'),
                    title: null,
                    treeNodeType: TreeNodeType.EVENTS,
                    isLeaf: false,
                    extraParams: nodeExtraParams,
                  },
                ]
              : []),
            createSaveConsolesNode(nodeExtraParams),
          ];

          let finalData = data;

          const redisData = [
            {
              key: allDataKey,
              originalTitle: i18n('common.text.allData'),
              title: null,
              isLeaf: true,
              treeNodeType: TreeNodeType.ALL_DATA,
              extraParams: nodeExtraParams,
            },
            createSaveConsolesNode(nodeExtraParams),
          ];
          if (isDatabaseCapabilitySupported(databaseType, DatabaseCapability.REDIS_TREE)) {
            finalData = redisData;
          }
          r(finalData);
        }
      });
    },
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName } = formatObject(params);
      return `dataSource_${dataSourceId}-database_${databaseName}`;
    },
  },

  [TreeNodeType.ALL_DATA]: {
    getChildren: () => {
      return new Promise((r: (value: TreeNodeData[]) => void) => {
        r([]);
      });
    },
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName } = formatObject(params);
      return `dataSource_${dataSourceId}-database_${databaseName}-allData_chat2dbCatalogue`;
    },
  },

  [TreeNodeType.SCHEMA]: {
    getChildren: (extraParams: any) => {
      const { dataSourceId, databaseName, schemaName, databaseType } = extraParams;
      const params = {
        dataSourceId,
        databaseName,
        schemaName,
      };
      const nodeExtraParams = {
        ...extraParams,
        ...params,
      };

      const tablesKey = treeConfig[TreeNodeType.TABLES].createTreeNodeKey!(params);
      const viewsKey = treeConfig[TreeNodeType.VIEWS].createTreeNodeKey!(params);
      const functionsKey = treeConfig[TreeNodeType.FUNCTIONS].createTreeNodeKey!(params);
      const proceduresKey = treeConfig[TreeNodeType.PROCEDURES].createTreeNodeKey!(params);
      const triggersKey = treeConfig[TreeNodeType.TRIGGERS].createTreeNodeKey!(params);

      return new Promise((r: (value: TreeNodeData[]) => void) => {
        const data = [
          {
            key: tablesKey,
            originalTitle: i18n('common.text.tables'),
            title: null,
            treeNodeType: TreeNodeType.TABLES,
            isLeaf: false,
            extraParams: nodeExtraParams,
          },
          {
            key: viewsKey,
            originalTitle: i18n('common.text.views'),
            title: null,
            treeNodeType: TreeNodeType.VIEWS,
            isLeaf: false,
            extraParams: nodeExtraParams,
          },
          {
            key: functionsKey,
            originalTitle: i18n('common.text.functions'),
            title: null,
            treeNodeType: TreeNodeType.FUNCTIONS,
            isLeaf: false,
            extraParams: nodeExtraParams,
          },
          {
            key: proceduresKey,
            originalTitle: i18n('common.text.procedures'),
            title: null,
            treeNodeType: TreeNodeType.PROCEDURES,
            isLeaf: false,
            extraParams: nodeExtraParams,
          },
          {
            key: triggersKey,
            originalTitle: i18n('common.text.triggers'),
            title: null,
            treeNodeType: TreeNodeType.TRIGGERS,
            isLeaf: false,
            extraParams: nodeExtraParams,
          },
          createSaveConsolesNode(nodeExtraParams),
        ];

        const mongodbData = [
          {
            key: tablesKey,
            originalTitle: i18n('common.text.collections'),
            title: null,
            treeNodeType: TreeNodeType.TABLES,
            isLeaf: false,
            extraParams: nodeExtraParams,
          },
          createSaveConsolesNode(nodeExtraParams),
        ];

        let finalData = data;
        if (isDatabaseCapabilitySupported(databaseType, DatabaseCapability.MONGODB_TREE)) {
          finalData = mongodbData;
        }
        r(finalData);
      });
    },
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName, schemaName } = formatObject(params);
      return [`dataSource_${dataSourceId}`, `database_${databaseName}`, `schema_${schemaName}`].join('-');
    },
  },

  [TreeNodeType.TABLES]: {
    getChildren: (extraParams) => {
      const { dataSourceId, databaseName, schemaName } = extraParams;

      return new Promise((r, j) => {
        mysqlServer
          .getTableList({
            ...extraParams,
            pageNo: 1,
            pageSize: 100000,
          })
          .then((res) => {
            // const worker = new Worker(new URL('@/workers/treeData.ts', import.meta.url));
            // worker.postMessage({
            //   type: 'handlingRes',
            //   data: {
            //     data: res.data,
            //     extraParams,
            //   },
            // });

            // worker.onmessage = (event: any) => {
            //   r(event.data.data);
            // };

            const pinnedList: string[] = [];
            const tableList: TreeNodeData[] = [];
            res.data?.forEach((t: any) => {
              if (!pinnedList.includes(t.name)) {
                const key = treeConfig[TreeNodeType.TABLE].createTreeNodeKey!({
                  dataSourceId,
                  databaseName,
                  schemaName,
                  tableName: t.name,
                });
                tableList.push({
                  key,
                  originalTitle: t.name,
                  title: null,
                  treeNodeType: TreeNodeType.TABLE,
                  isLeaf: false,
                  describe: t.comment,
                  extraParams: {
                    ...extraParams,
                    tableName: t.name,
                  },
                  decorativeParams: {
                    pinned: t.pinned,
                    comment: t.comment,
                  },
                });
              }
              if (t.pinned) {
                pinnedList.push(t.name);
              }
            });
            r({ children: tableList, total: res.total });
          })
          .catch((error) => {
            j(error);
          });
      });
    },
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName, schemaName } = formatObject(params);
      return [
        `dataSource_${dataSourceId}`,
        `database_${databaseName}`,
        `schema_${schemaName}`,
        'tables_chat2dbCatalogue',
      ].join('-');
    },
  },

  [TreeNodeType.TABLE]: {
    getChildren: (extraParams) => {
      return new Promise((r: (value: TreeNodeData[]) => void) => {
        const { dataSourceId, databaseName, schemaName, tableName } = extraParams;
        const params = {
          dataSourceId,
          databaseName,
          schemaName,
          tableName,
        };
        const columnsKey = treeConfig[TreeNodeType.COLUMNS].createTreeNodeKey!(params);
        const keysKey = treeConfig[TreeNodeType.KEYS].createTreeNodeKey!(params);
        const indexesKey = treeConfig[TreeNodeType.INDEXES].createTreeNodeKey!(params);

        const list = [
          {
            key: columnsKey,
            originalTitle: i18n('common.text.columns'),
            title: null,
            treeNodeType: TreeNodeType.COLUMNS,
            isLeaf: false,
            extraParams,
          },
          {
            key: keysKey,
            originalTitle: i18n('common.text.keys'),
            title: null,
            treeNodeType: TreeNodeType.KEYS,
            isLeaf: false,
            extraParams,
          },
          {
            key: indexesKey,
            originalTitle: i18n('common.text.indexs'),
            title: null,
            treeNodeType: TreeNodeType.INDEXES,
            isLeaf: false,
            extraParams,
          },
        ];

        r(list);
      });
    },
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName, schemaName, tableName } = formatObject(params);
      return [
        `dataSource_${dataSourceId}`,
        databaseName ? `database_${databaseName}` : '',
        schemaName ? `schema_${schemaName}` : '',
        `table_${tableName}`,
      ].join('-');
    },
  },

  [TreeNodeType.VIEWS]: {
    getChildren: (extraParams) => {
      return new Promise((r: (value: TreeNodeLoadResult) => void, j) => {
        const { dataSourceId, databaseName, schemaName } = extraParams;
        mysqlServer
          .getViewList(extraParams)
          .then((res) => {
            const viewList: TreeNodeData[] = res.data?.map((t: any) => {
              const key = treeConfig[TreeNodeType.VIEW].createTreeNodeKey!({
                dataSourceId,
                databaseName,
                schemaName,
                tableName: t.name,
              });
              return {
                key,
                originalTitle: t.name,
                title: null,
                treeNodeType: TreeNodeType.VIEW,
                isLeaf: false,
                extraParams: {
                  ...extraParams,
                  tableName: t.name,
                  viewName: t.name,
                },
              };
            });
            r({ children: viewList || [], total: res.total });
          })
          .catch((error) => {
            j(error);
          });
      });
    },
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName, schemaName } = formatObject(params);
      return [
        `dataSource_${dataSourceId}`,
        `database_${databaseName}`,
        `schema_${schemaName}`,
        'views_chat2dbCatalogue',
      ].join('-');
    },
  },

  [TreeNodeType.FUNCTIONS]: {
    getChildren: (extraParams) => {
      return new Promise((r: (value: TreeNodeLoadResult) => void, j) => {
        const { dataSourceId, databaseName, schemaName } = extraParams;
        mysqlServer
          .getFunctionList(extraParams)
          .then((res) => {
            const list: TreeNodeData[] = res.data?.map((t: any) => {
              const key = treeConfig[TreeNodeType.FUNCTION].createTreeNodeKey!({
                dataSourceId,
                databaseName,
                schemaName,
                functionName: t.functionName,
                specificName: t.specificName,
              });
              return {
                key,
                originalTitle: t.functionName,
                title: null,
                treeNodeType: TreeNodeType.FUNCTION,
                isLeaf: true,
                extraParams: {
                  ...extraParams,
                  functionName: t.functionName,
                },
              };
            });
            r({ children: list || [], total: res.total });
          })
          .catch((error) => {
            j(error);
          });
      });
    },
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName, schemaName } = formatObject(params);
      return [
        `dataSource_${dataSourceId}`,
        `database_${databaseName}`,
        `schema_${schemaName}`,
        'functions_chat2dbCatalogue',
      ].join('-');
    },
  },

  [TreeNodeType.PROCEDURES]: {
    getChildren: (extraParams) => {
      const { dataSourceId, databaseName, schemaName } = extraParams;
      return new Promise((r: (value: TreeNodeLoadResult) => void, j) => {
        mysqlServer
          .getProcedureList(extraParams)
          .then((res) => {
            const list: TreeNodeData[] = res.data?.map((t: any) => {
              const key = treeConfig[TreeNodeType.PROCEDURE].createTreeNodeKey!({
                dataSourceId,
                databaseName,
                schemaName,
                procedureName: t.procedureName,
              });
              return {
                key,
                originalTitle: t.procedureName,
                title: null,
                treeNodeType: TreeNodeType.PROCEDURE,
                isLeaf: true,
                extraParams: {
                  ...extraParams,
                  procedureName: t.procedureName,
                },
              };
            });
            r({ children: list || [], total: res.total });
          })
          .catch((error) => {
            j(error);
          });
      });
    },
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName, schemaName } = formatObject(params);
      return [
        `dataSource_${dataSourceId}`,
        `database_${databaseName}`,
        `schema_${schemaName}`,
        'procedures_chat2dbCatalogue',
      ].join('-');
    },
  },

  [TreeNodeType.TRIGGERS]: {
    getChildren: (extraParams) => {
      const { dataSourceId, databaseName, schemaName } = extraParams;
      return new Promise((r: (value: TreeNodeLoadResult) => void, j) => {
        mysqlServer
          .getTriggerList(extraParams)
          .then((res) => {
            const list: TreeNodeData[] = res.data?.map((t: any) => {
              const key = treeConfig[TreeNodeType.TRIGGER].createTreeNodeKey!({
                dataSourceId,
                databaseName,
                schemaName,
                triggerName: t.triggerName,
              });
              return {
                key,
                originalTitle: t.triggerName,
                title: null,
                treeNodeType: TreeNodeType.TRIGGER,
                isLeaf: true,
                extraParams: {
                  ...extraParams,
                  triggerName: t.triggerName,
                },
              };
            });
            r({ children: list || [], total: res.total });
          })
          .catch((error) => {
            j(error);
          });
      });
    },
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName, schemaName } = formatObject(params);
      return [
        `dataSource_${dataSourceId}`,
        `database_${databaseName}`,
        `schema_${schemaName}`,
        'triggers_chat2dbCatalogue',
      ].join('-');
    },
  },

  [TreeNodeType.VIEWCOLUMNS]: {
    getChildren: (extraParams) => {
      return new Promise((r: (value: TreeNodeLoadResult) => void, j) => {
        const { dataSourceId, databaseName, schemaName } = extraParams;
        mysqlServer
          .getViewColumnList(extraParams)
          .then((res) => {
            const list: TreeNodeData[] = res.data?.map((t: any) => {
              const key = treeConfig[TreeNodeType.VIEWCOLUMN].createTreeNodeKey!({
                dataSourceId,
                databaseName,
                schemaName,
                tableName: t.name,
                columnName: t.name,
              });
              return {
                key,
                originalTitle: t.name,
                title: null,
                treeNodeType: TreeNodeType.VIEWCOLUMN,
                isLeaf: true,
                extraParams,
              };
            });
            r({ children: list || [], total: res.total ?? list?.length ?? 0 });
          })
          .catch((error) => {
            j(error);
          });
      });
    },
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName, schemaName, tableName } = formatObject(params);
      return [
        `dataSource_${dataSourceId}`,
        `database_${databaseName}`,
        `schema_${schemaName}`,
        `view_${tableName}`,
        'columns_chat2dbCatalogue',
      ].join('-');
    },
  },

  [TreeNodeType.PROCEDURE]: {
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName, schemaName, procedureName } = formatObject(params);
      return [
        `dataSource_${dataSourceId}`,
        `database_${databaseName}`,
        `schema_${schemaName}`,
        `procedure_${procedureName}`,
        `uuid_${uuid()}`,
      ].join('-');
    },
  },

  [TreeNodeType.FUNCTION]: {
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName, schemaName, functionName, specificName } = formatObject(params);
      return [
        `dataSource_${dataSourceId}`,
        `database_${databaseName}`,
        `schema_${schemaName}`,
        `function_${specificName || functionName}`,
        `uuid_${uuid()}`,
      ].join('-');
    },
  },

  [TreeNodeType.EVENTS]: {
    getChildren: (extraParams) => {
      const { dataSourceId, databaseName } = extraParams;
      return new Promise((r: (value: TreeNodeLoadResult) => void, j) => {
        Promise.all([
          mysqlServer.getEventList({ dataSourceId, databaseName }),
          mysqlServer.getEventSchedulerStatus({ dataSourceId, databaseName }).catch(() => undefined),
        ])
          .then(([res, schedulerStatus]) => {
            const list: TreeNodeData[] = (res || []).map((t: any) => {
              const key = treeConfig[TreeNodeType.EVENT].createTreeNodeKey!({
                dataSourceId,
                databaseName,
                eventName: t.eventName,
              });
              return {
                key,
                originalTitle: t.eventName,
                title: null,
                describe: createEventTreeNodeDescription(
                  t.status,
                  schedulerStatus?.schedulerEnabled,
                  i18n('workspace.event.schedulerOff'),
                ),
                treeNodeType: TreeNodeType.EVENT,
                isLeaf: true,
                extraParams: {
                  ...extraParams,
                  eventName: t.eventName,
                },
              };
            });
            r({ children: list || [], total: list.length });
          })
          .catch((error) => {
            j(error);
          });
      });
    },
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName } = formatObject(params);
      return [`dataSource_${dataSourceId}`, `database_${databaseName}`, 'events_chat2dbCatalogue'].join('-');
    },
  },

  [TreeNodeType.EVENT]: {
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName, eventName } = formatObject(params);
      return createEventTreeNodeKey({ dataSourceId, databaseName, eventName });
    },
  },

  [TreeNodeType.TRIGGER]: {
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName, schemaName, triggerName } = formatObject(params);
      return [
        `dataSource_${dataSourceId}`,
        `database_${databaseName}`,
        `schema_${schemaName}`,
        `trigger_${triggerName}`,
        `uuid_${uuid()}`,
      ].join('-');
    },
  },

  [TreeNodeType.VIEW]: {
    getChildren: (extraParams) => {
      const { dataSourceId, databaseName, schemaName, tableName } = extraParams;
      return new Promise((r: (value: TreeNodeData[]) => void) => {
        const columnsKey = treeConfig[TreeNodeType.VIEWCOLUMNS].createTreeNodeKey!({
          dataSourceId,
          databaseName,
          schemaName,
          tableName,
        });
        const list = [
          {
            key: columnsKey,
            originalTitle: i18n('common.text.columns'),
            title: null,
            treeNodeType: TreeNodeType.COLUMNS,
            isLeaf: false,
            extraParams,
          },
        ];
        r(list);
      });
    },
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName, schemaName, tableName } = formatObject(params);
      return [
        `dataSource_${dataSourceId}`,
        `database_${databaseName}`,
        `schema_${schemaName}`,
        `view_${tableName}`,
      ].join('-');
    },
  },

  [TreeNodeType.VIEWCOLUMN]: {
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName, schemaName, tableName, columnName } = formatObject(params);
      return [
        `dataSource_${dataSourceId}`,
        `database_${databaseName}`,
        `schema_${schemaName}`,
        `view_${tableName}`,
        `column_${columnName}`,
        `uuid_${uuid()}`,
      ].join('-');
    },
  },

  [TreeNodeType.COLUMNS]: {
    getChildren: (extraParams) => {
      return new Promise((r: (value: TreeNodeLoadResult) => void, j) => {
        const { dataSourceId, databaseName, schemaName, tableName } = extraParams;
        mysqlServer
          .getColumnList(extraParams)
          .then((res) => {
            const tableList: TreeNodeData[] = res.data?.map((item) => {
              const key = treeConfig[TreeNodeType.COLUMN].createTreeNodeKey!({
                dataSourceId,
                databaseName,
                schemaName,
                tableName,
                columnName: item.name,
              });
              return {
                key,
                originalTitle: item.name,
                title: null,
                treeNodeType: TreeNodeType.COLUMN,
                isLeaf: true,
                columnType: item.columnType,
                describe: item.comment,
                comment: item.comment,
                extraParams,
              };
            });
            r({ children: tableList || [], total: res.total ?? tableList?.length ?? 0 });
          })
          .catch(() => {
            j();
          });
      });
    },
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName, schemaName, tableName } = formatObject(params);
      return [
        `dataSource_${dataSourceId}`,
        `database_${databaseName}`,
        `schema_${schemaName}`,
        `table_${tableName}`,
        'columns_chat2dbCatalogue',
      ].join('-');
    },
  },

  [TreeNodeType.COLUMN]: {
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName, schemaName, tableName, columnName } = formatObject(params);
      return [
        `dataSource_${dataSourceId}`,
        `database_${databaseName}`,
        `schema_${schemaName}`,
        `table_${tableName}`,
        `column_${columnName}`,
        `uuid_${uuid()}`,
      ].join('-');
    },
  },

  [TreeNodeType.KEYS]: {
    getChildren: (extraParams) => {
      return new Promise((r: (value: TreeNodeLoadResult) => void, j) => {
        const { dataSourceId, databaseName, schemaName, tableName } = extraParams;
        mysqlServer
          .getKeyList(extraParams)
          .then((res) => {
            const tableList: TreeNodeData[] = res.data?.map((item) => {
              const key = treeConfig[TreeNodeType.KEY].createTreeNodeKey!({
                dataSourceId,
                databaseName,
                schemaName,
                tableName,
                keyName: item.name,
              });
              return {
                key,
                originalTitle: item.name,
                title: null,
                treeNodeType: TreeNodeType.KEY,
                isLeaf: true,
                extraParams,
              };
            });
            r({ children: tableList || [], total: res.total ?? tableList?.length ?? 0 });
          })
          .catch(() => {
            j();
          });
      });
    },
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName, schemaName, tableName } = formatObject(params);
      return [
        `dataSource_${dataSourceId}`,
        `database_${databaseName}`,
        `schema_${schemaName}`,
        `table_${tableName}`,
        'keys_chat2dbCatalogue',
      ].join('-');
    },
  },

  [TreeNodeType.KEY]: {
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName, schemaName, tableName, keyName } = formatObject(params);
      return [
        `dataSource_${dataSourceId}`,
        `database_${databaseName}`,
        `schema_${schemaName}`,
        `table_${tableName}`,
        `key_${keyName}`,
        `uuid_${uuid()}`,
      ].join('-');
    },
  },

  [TreeNodeType.INDEXES]: {
    getChildren: (extraParams) => {
      return new Promise((r: (value: TreeNodeLoadResult) => void, j) => {
        const { dataSourceId, databaseName, schemaName, tableName } = extraParams;
        mysqlServer
          .getIndexList(extraParams)
          .then((res) => {
            const tableList: TreeNodeData[] = res.data?.map((item) => {
              const key = treeConfig[TreeNodeType.INDEX].createTreeNodeKey!({
                dataSourceId,
                databaseName,
                schemaName,
                tableName,
                indexName: item.name,
              });
              return {
                key,
                originalTitle: item.name,
                title: null,
                treeNodeType: TreeNodeType.INDEX,
                isLeaf: true,
                extraParams,
              };
            });
            r({ children: tableList || [], total: res.total ?? tableList?.length ?? 0 });
          })
          .catch(() => {
            j();
          });
      });
    },
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName, schemaName, tableName } = formatObject(params);
      return [
        `dataSource_${dataSourceId}`,
        `database_${databaseName}`,
        `schema_${schemaName}`,
        `table_${tableName}`,
        'indexes_chat2dbCatalogue',
      ].join('-');
    },
  },

  [TreeNodeType.INDEX]: {
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName, schemaName, tableName, indexName } = formatObject(params);
      return [
        `dataSource_${dataSourceId}`,
        `database_${databaseName}`,
        `schema_${schemaName}`,
        `table_${tableName}`,
        `index_${indexName}`,
        `uuid_${uuid()}`,
      ].join('-');
    },
  },

  [TreeNodeType.SAVE_CONSOLES]: {
    getChildren: (extraParams) => {
      return new Promise((r: (value: TreeNodeLoadResult) => void, j) => {
        const { dataSourceId, databaseName, schemaName } = extraParams;
        historyService
          .getConsoleList({
            ...extraParams,
            pageNo: 1,
            pageSize: 1000,
            status: ConsoleStatus.RELEASE,
            type: WorkspaceTabType.CONSOLE,
            orderByDesc: true,
          })
          .then((res) => {
            const tableList: TreeNodeData[] = res?.data.map((item) => {
              const key = treeConfig[TreeNodeType.SAVE_CONSOLE].createTreeNodeKey!({
                dataSourceId,
                databaseName,
                schemaName,
                consoleId: item.id,
              });
              return {
                id: item.id,
                key,
                originalTitle: item.name || '',
                title: null,
                treeNodeType: TreeNodeType.SAVE_CONSOLE,
                isLeaf: true,
                extraParams: {
                  ...extraParams,
                  status: item.status,
                  ddl: item.ddl,
                  connectable: item.connectable,
                },
              };
            });
            r({ children: tableList || [], total: res.total });
          })
          .catch(() => {
            j();
          });
      });
    },
    createTreeNodeKey: (params) => {
      const { dataSourceId, databaseName, schemaName } = formatObject(params);
      return [
        `dataSource_${dataSourceId}`,
        `database_${databaseName}`,
        `schema_${schemaName}`,
        'consoles_chat2dbCatalogue',
      ].join('-');
    },
  },

  [TreeNodeType.SAVE_CONSOLE]: {
    renameCallback: (text: string, nodeData: TreeNodeData) => {
      historyService.updateSavedConsole({
        id: nodeData.id!,
        name: text,
      });
    },
    createTreeNodeKey: (params) => {
      return createSavedConsoleTreeNodeKey(formatObject(params));
    },
  },
};
