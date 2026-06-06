import { DatabaseTypeCode, OperationColumn, TreeNodeType, WorkspaceTabType } from '@/constants';
import i18n from '@/i18n';
import sqlServer from '@/service/sql';
import { ITreeNode } from '@/typings';
import { message } from 'antd';
import { v4 as uuid } from 'uuid';

// ----- components -----
import { dataSourceFormConfigs } from '@/components/ConnectionEdit/config/dataSource';
import { IConnectionConfig } from '@/components/ConnectionEdit/config/types';

// ----- config -----
import { useMemo } from 'react';
import { ITreeConfigItem, treeConfig } from '../treeConfig';

// ----- store -----
import { useWorkspaceStore } from '@/pages/main/workspace/store';
import { addWorkspaceTab, createConsole } from '@/pages/main/workspace/store/console';

// ---- functions -----
import { deleteTable } from '../functions/deleteTable';
import { deleteDatabase } from '../functions/deleteDatabase';
import { truncateTable } from '../functions/truncateTable';
import { openFunction, openProcedure, openTrigger, openView } from '../functions/openAsyncSql';
import { handelPinTable } from '../functions/pinTable';
import { deprecatedTable, restoreDeprecatedTable } from '../functions/deprecatedTable';
import { viewDDL } from '../functions/viewDDL';
import { openTableRelationModal } from '../components/TableRelationModal';

// ----- utils -----
import { compatibleDataBaseName } from '@/utils/database';

interface IProps {
  treeNodeData: ITreeNode;
  loadData: any;
  refreshRootData?: (refresh?: boolean) => void;
}

interface IOperationColumnConfigItem {
  text: string;
  icon: string;
  doubleClickTrigger?: boolean;
  handle: (treeNodeData: ITreeNode) => void;
  discard?: boolean;
}

interface IRightClickMenu {
  key: number | string;
  onClick: (treeNodeData: ITreeNode) => void;
  type: OperationColumn | string;
  doubleClickTrigger?: boolean;
  labelProps: {
    icon: string;
    label: string;
  };
  children?: IRightClickMenu[];
}

// 将"导入数据""导出数据""生成数据"合并到二级菜单"数据操作"
const DATA_OPS: (OperationColumn | string)[] = [
  OperationColumn.ImportData,
  OperationColumn.ExportData,
  OperationColumn.DataTransfer,
  OperationColumn.GenerateData,
];

const REDIS_EXCLUDED_OPERATIONS = new Set<OperationColumn>([
  OperationColumn.SchemaDiff,
  OperationColumn.CreateSchema,
  OperationColumn.CreateTable,
  OperationColumn.ViewERDiagram,
  OperationColumn.ViewTableRelation,
  OperationColumn.ViewDDL,
  OperationColumn.ViewAllTable,
  OperationColumn.ExportSchemaDoc,
  OperationColumn.ExecuteSqlStatement,
  OperationColumn.DeleteDatabase,
  OperationColumn.DeleteTable,
  OperationColumn.EditTable,
  OperationColumn.EditTableData,
  OperationColumn.TruncateTable,
  OperationColumn.ImportData,
  OperationColumn.ExportData,
  OperationColumn.DataTransfer,
  OperationColumn.DeprecatedTable,
  OperationColumn.RestoreTable,
  OperationColumn.GenerateData,
]);

const groupDataOperations = (list: IRightClickMenu[]): IRightClickMenu[] => {
  const dataOps = list.filter((m) => DATA_OPS.includes(m.type));
  if (dataOps.length === 0) return list;
  const result: IRightClickMenu[] = [];
  let inserted = false;
  list.forEach((m) => {
    if (DATA_OPS.includes(m.type)) {
      if (!inserted) {
        result.push({
          key: 'dataOperation',
          type: 'dataOperation',
          onClick: () => {},
          labelProps: {
            icon: '\ue653',
            label: i18n('workspace.menu.dataOperation'),
          },
          children: dataOps,
        });
        inserted = true;
      }
    } else {
      result.push(m);
    }
  });
  return result;
};

export const useGetRightClickMenu = (props: IProps) => {
  const { treeNodeData, loadData, refreshRootData } = props;

  const { openCreateDatabaseModal, currentConnectionDetails } = useWorkspaceStore((state) => {
    return {
      openCreateDatabaseModal: state.openCreateDatabaseModal,
      currentConnectionDetails: state.currentConnectionDetails,
    };
  });

  const handelOpenCreateDatabaseModal = (type: 'database' | 'schema') => {
    const relyOnParams = {
      databaseType: treeNodeData.extraParams!.databaseType,
      dataSourceId: treeNodeData.extraParams!.dataSourceId!,
      databaseName: treeNodeData.name,
    };

    openCreateDatabaseModal?.({
      type,
      relyOnParams,
      executedCallback: () => {
        loadData({
          refresh: true,
        });
      },
    });
  };

  const rightClickMenu = useMemo(() => {
    // 拿出当前节点的配置
    const treeNodeConfig: ITreeConfigItem = treeConfig[treeNodeData.treeNodeType];
    const { operationColumn } = treeNodeConfig;

    const dataSourceFormConfig = dataSourceFormConfigs.find((t: IConnectionConfig) => {
      return t.type === treeNodeData.extraParams?.databaseType;
    })!;

    // 有些数据库不支持的操作，需要排除掉
    function excludeSomeOperation() {
      const excludes = dataSourceFormConfig.baseInfo.excludes;
      const newOperationColumn: OperationColumn[] = [];
      const isRedis = treeNodeData.extraParams?.databaseType === DatabaseTypeCode.REDIS;
      operationColumn?.map((item: OperationColumn) => {
        let flag = false;
        excludes?.map((t) => {
          if (item === t) {
            flag = true;
          }
        });
        if (isRedis && REDIS_EXCLUDED_OPERATIONS.has(item)) {
          flag = true;
        }
        if (!flag) {
          newOperationColumn.push(item);
        }
      });
      return newOperationColumn;
    }

    const operationColumnConfig: { [key in string]: IOperationColumnConfigItem } = {
      // 刷新
      [OperationColumn.Refresh]: {
        text: i18n('common.button.refresh'),
        icon: '\uec08',
        handle: () => {
          loadData?.({
            refresh: true,
          });
        },
      },

      // 创建console
      [OperationColumn.CreateConsole]: {
        text: i18n('workspace.menu.queryConsole'),
        icon: '\ue619',
        doubleClickTrigger: treeNodeData.treeNodeType === TreeNodeType.REDIS_QUERY,
        handle: () => {
          const isRedis = treeNodeData.extraParams?.databaseType === DatabaseTypeCode.REDIS;
          const tableName = isRedis
            ? ''
            : compatibleDataBaseName(
                treeNodeData.name!,
                treeNodeData.extraParams!.databaseType,
                treeNodeData.extraParams?.schemaName,
              );
          createConsole({
            dataSourceId: treeNodeData.extraParams!.dataSourceId!,
            dataSourceName: treeNodeData.extraParams!.dataSourceName!,
            databaseType: treeNodeData.extraParams!.databaseType!,
            databaseName: treeNodeData.extraParams?.databaseName,
            schemaName: treeNodeData.extraParams?.schemaName,
            ddl: isRedis ? '' : `select * from ${tableName}`,
          });
        },
      },

      [OperationColumn.OpenRedisData]: {
        text: '打开数据',
        icon: '\ue618',
        doubleClickTrigger: true,
        handle: () => {
          addWorkspaceTab({
            id: `${OperationColumn.OpenRedisData}-${treeNodeData.uuid}`,
            title: `${treeNodeData.extraParams?.databaseName || '0'}-数据`,
            type: WorkspaceTabType.RedisData,
            uniqueData: {
              dataSourceId: treeNodeData.extraParams!.dataSourceId!,
              dataSourceName: treeNodeData.extraParams!.dataSourceName!,
              databaseType: treeNodeData.extraParams!.databaseType!,
              databaseName: treeNodeData.extraParams?.databaseName,
            },
          });
        },
      },

      [OperationColumn.OpenRedisMonitor]: {
        text: '打开监控',
        icon: '\ue611',
        doubleClickTrigger: true,
        handle: () => {
          addWorkspaceTab({
            id: `${OperationColumn.OpenRedisMonitor}-${treeNodeData.uuid}`,
            title: `${treeNodeData.extraParams?.databaseName || '0'}-监控`,
            type: WorkspaceTabType.RedisMonitor,
            uniqueData: {
              dataSourceId: treeNodeData.extraParams!.dataSourceId!,
              dataSourceName: treeNodeData.extraParams!.dataSourceName!,
              databaseType: treeNodeData.extraParams!.databaseType!,
              databaseName: treeNodeData.extraParams?.databaseName,
            },
          });
        },
      },

      // 查看所有表
      [OperationColumn.ViewAllTable]: {
        text: i18n('workspace.menu.viewAllTable'),
        icon: '\ue611',
        handle: () => {
          addWorkspaceTab({
            id: uuid(),
            type: WorkspaceTabType.ViewAllTable,
            title: `${treeNodeData.extraParams!.databaseName!}-tables`,
            uniqueData: {
              dataSourceId: treeNodeData.extraParams!.dataSourceId!,
              dataSourceName: treeNodeData.extraParams!.dataSourceName!,
              databaseType: treeNodeData.extraParams!.databaseType!,
              databaseName: treeNodeData.extraParams?.databaseName,
              schemaName: treeNodeData.extraParams?.schemaName,
            },
          });
        },
      },
      // 添加查看 ER 图
      [OperationColumn.ViewERDiagram]: {
        text: i18n('workspace.menu.viewERDiagram'),
        icon: '\ue611',
        handle: () => {
          addWorkspaceTab({
            id: uuid(),
            type: WorkspaceTabType.ViewERDiagram,
            title: `${treeNodeData.extraParams!.databaseName!}-ER`,
            uniqueData: {
              dataSourceId: treeNodeData.extraParams!.dataSourceId!,
              dataSourceName: treeNodeData.extraParams!.dataSourceName!,
              databaseType: treeNodeData.extraParams!.databaseType!,
              databaseName: treeNodeData.extraParams?.databaseName,
              schemaName: treeNodeData.extraParams?.schemaName,
            },
          });
        },
      },

      // 结构对比
      [OperationColumn.SchemaDiff]: {
        text: i18n('schemaDiff.title'),
        icon: '\ue6f3',
        handle: () => {
          addWorkspaceTab({
            id: uuid(),
            type: WorkspaceTabType.SchemaDiff,
            title: i18n('schemaDiff.title'),
            uniqueData: {
              dataSourceId: treeNodeData.extraParams!.dataSourceId!,
              databaseType: treeNodeData.extraParams!.databaseType!,
            },
          });
        },
      },

      // 创建表
      [OperationColumn.CreateTable]: {
        text: i18n('editTable.button.createTable'),
        icon: '\ue792',
        handle: () => {
          addWorkspaceTab({
            id: uuid(),
            title: i18n('editTable.button.createTable'),
            type: WorkspaceTabType.CreateTable,
            uniqueData: {
              dataSourceId: treeNodeData.extraParams!.dataSourceId!,
              databaseType: treeNodeData.extraParams!.databaseType!,
              databaseName: treeNodeData.extraParams?.databaseName,
              schemaName: treeNodeData.extraParams?.schemaName,
              submitCallback: () => {
                loadData?.({ refresh: true });
              },
            },
          });
        },
        discard: treeNodeData.treeNodeType === TreeNodeType.DATABASE && currentConnectionDetails?.supportSchema,
      },

      // 删除表
      [OperationColumn.DeleteTable]: {
        text: i18n('workspace.menu.deleteTable'),
        icon: '\ue6a7',
        handle: () => {
          deleteTable(treeNodeData, loadData);
        },
      },
      [OperationColumn.TruncateTable]: {
        text: i18n('workspace.menu.truncateTable'), // 假设i18n函数已定义好对应的语言资源
        icon: '\ue60c', // 选择一个合适的图标
        handle: () => {
          truncateTable(treeNodeData, loadData);
        },
      },
      // 查看ddl
      [OperationColumn.ViewDDL]: {
        text: i18n('workspace.menu.ViewDDL'),
        icon: '\ue665',
        handle: () => {
          viewDDL(treeNodeData);
        },
      },

      // 置顶
      [OperationColumn.Pin]: {
        text: treeNodeData.pinned ? i18n('workspace.menu.unPin') : i18n('workspace.menu.pin'),
        icon: treeNodeData.pinned ? '\ue61d' : '\ue627',
        handle: () => {
          handelPinTable({
            treeNodeData,
            loadData: () => {
              loadData({ treeNodeData: treeNodeData.parentNode });
            },
          });
        },
      },

      // 编辑表
      [OperationColumn.EditTable]: {
        text: i18n('workspace.menu.editTable'),
        icon: '\ue602',
        handle: () => {
          addWorkspaceTab({
            id: `${OperationColumn.EditTable}-${treeNodeData.uuid}`,
            title: treeNodeData?.name,
            type: WorkspaceTabType.EditTable,
            uniqueData: {
              dataSourceId: treeNodeData.extraParams!.dataSourceId!,
              databaseType: treeNodeData.extraParams!.databaseType!,
              databaseName: treeNodeData.extraParams?.databaseName,
              schemaName: treeNodeData.extraParams?.schemaName,
              tableName: treeNodeData?.name,
              submitCallback: () => {
                loadData({
                  treeNodeData: treeNodeData.parentNode,
                  refresh: true,
                });
              },
            },
          });
        },
      },

      // 复制名称
      [OperationColumn.CopyName]: {
        text: i18n('common.button.copyName'),
        icon: '\uec7a',
        handle: () => {
          navigator.clipboard.writeText(treeNodeData.name);
        },
      },

      // 打开表
      [OperationColumn.OpenTable]: {
        text: i18n('workspace.menu.openTable'),
        icon: '\ue618',
        doubleClickTrigger: true,
        handle: () => {
          const databaseName = compatibleDataBaseName(
            treeNodeData.name!,
            treeNodeData.extraParams!.databaseType,
            treeNodeData.extraParams?.schemaName,
          );
          addWorkspaceTab({
            id: `${OperationColumn.OpenTable}-${treeNodeData.uuid}`,
            title: treeNodeData.name,
            type: WorkspaceTabType.EditTableData,
            uniqueData: {
              dataSourceId: treeNodeData.extraParams!.dataSourceId!,
              databaseType: treeNodeData.extraParams!.databaseType!,
              databaseName: treeNodeData.extraParams?.databaseName,
              schemaName: treeNodeData.extraParams?.schemaName,
              tableName: treeNodeData.name,
              sql: 'select * from ' + databaseName,
            },
          });
        },
      },

      // 打开视图
      [OperationColumn.OpenView]: {
        text: i18n('workspace.menu.view'),
        icon: '\ue651',
        doubleClickTrigger: true,
        handle: () => {
          openView({
            addWorkspaceTab,
            treeNodeData,
          });
        },
      },

      // 打开函数
      [OperationColumn.OpenFunction]: {
        text: i18n('workspace.menu.view'),
        icon: '\ue651',
        doubleClickTrigger: true,
        handle: () => {
          openFunction({
            addWorkspaceTab,
            treeNodeData,
          });
        },
      },

      // 打开存储过程
      [OperationColumn.OpenProcedure]: {
        text: i18n('workspace.menu.view'),
        icon: '\ue651',
        doubleClickTrigger: true,
        handle: () => {
          openProcedure({
            addWorkspaceTab,
            treeNodeData,
          });
        },
      },

      // 打开触发器
      [OperationColumn.OpenTrigger]: {
        text: i18n('workspace.menu.view'),
        icon: '\ue651',
        doubleClickTrigger: true,
        handle: () => {
          openTrigger({
            addWorkspaceTab,
            treeNodeData,
          });
        },
      },

      // 创建数据库
      [OperationColumn.CreateDatabase]: {
        text: i18n('workspace.menu.createDatabase'),
        icon: '\ue816',
        handle: () => {
          handelOpenCreateDatabaseModal('database');
        },
      },

      // 删除数据库
      [OperationColumn.DeleteDatabase]: {
        text: i18n('workspace.menu.deleteDatabase'),
        icon: '\ue6a7',
        handle: () => {
          deleteDatabase(treeNodeData, loadData, refreshRootData);
        },
      },

      [OperationColumn.ViewTableRelation]: {
        text:
          treeNodeData.treeNodeType === TreeNodeType.KEY || treeNodeData.treeNodeType === TreeNodeType.V_KEY
            ? i18n('workspace.menu.view')
            : i18n('workspace.menu.viewTableRelation'),
        icon: '\ue611',
        handle: () => {
          openTableRelationModal(treeNodeData);
        },
      },

      // 创建schema
      [OperationColumn.CreateSchema]: {
        text: i18n('workspace.menu.createSchema'),
        icon: '\ue696',
        handle: () => {
          handelOpenCreateDatabaseModal('schema');
        },
        discard: !currentConnectionDetails?.supportSchema,
      },

      // 删除虚拟外键
      [OperationColumn.DeleteVirtualKey]: {
        text: i18n('workspace.menu.deleteVirtualKey'),
        icon: '\ue6a7',
        handle: async () => {
          await deleteVirtualForeignKey(treeNodeData, loadData);
        },
      },

      // 导入数据
      [OperationColumn.ImportData]: {
        text: i18n('workspace.menu.importData'),
        icon: '\ue653',
        handle: () => {
          const { openImportDataModal } = useWorkspaceStore.getState();
          openImportDataModal?.({
            tableName: treeNodeData.name,
            dataSourceId: treeNodeData.extraParams!.dataSourceId!,
            databaseName: treeNodeData.extraParams?.databaseName,
            schemaName: treeNodeData.extraParams?.schemaName,
            executedCallback: () => {
              loadData?.({
                refresh: true,
              });
            },
          });
        },
      },

      // 导出数据
      [OperationColumn.ExportData]: {
        text: i18n('workspace.menu.exportData'),
        icon: '\ue613',
        handle: () => {
          const { openExportDataModal } = useWorkspaceStore.getState();
          openExportDataModal?.({
            tableName: treeNodeData.name,
            dataSourceId: treeNodeData.extraParams!.dataSourceId!,
            databaseName: treeNodeData.extraParams?.databaseName,
            schemaName: treeNodeData.extraParams?.schemaName,
          });
        },
      },

      // 数据传输
      [OperationColumn.DataTransfer]: {
        text: '数据传输',
        icon: '\ue60e',
        handle: () => {
          const { openDataTransferModal } = useWorkspaceStore.getState();
          openDataTransferModal?.({
            dataSourceId: treeNodeData.extraParams!.dataSourceId!,
            databaseName: treeNodeData.extraParams?.databaseName,
            schemaName: treeNodeData.extraParams?.schemaName,
            tableNames: [treeNodeData.name],
            executedCallback: () => {
              loadData?.({
                refresh: true,
              });
            },
          });
        },
      },

      // 导出数据结构
      [OperationColumn.ExportSchemaDoc]: {
        text: i18n('workspace.menu.exportSchemaDoc'),
        icon: '\ue613',
        handle: () => {
          const { openExportSchemaDocModal } = useWorkspaceStore.getState();
          openExportSchemaDocModal?.({
            dataSourceId: treeNodeData.extraParams!.dataSourceId!,
            databaseName: treeNodeData.extraParams?.databaseName,
            schemaName: treeNodeData.extraParams?.schemaName,
          });
        },
      },

      [OperationColumn.ExecuteSqlStatement]: {
        text: i18n('workspace.menu.executeSqlStatement'),
        icon: '\ue619',
        handle: () => {
          const { openExecuteSqlStatementModal } = useWorkspaceStore.getState();
          openExecuteSqlStatementModal?.({
            dataSourceId: treeNodeData.extraParams!.dataSourceId!,
            databaseName: treeNodeData.extraParams?.databaseName,
            schemaName: treeNodeData.extraParams?.schemaName,
            executedCallback: () => {
              loadData?.({
                refresh: true,
              });
            },
          });
        },
      },

      // 废弃表
      [OperationColumn.DeprecatedTable]: {
        text: i18n('workspace.menu.deprecatedTable'),
        icon: '\ue73c',
        handle: () => {
          deprecatedTable({
            treeNodeData,
            loadData: () => {
              loadData({ treeNodeData: treeNodeData.parentNode });
            },
          });
        },
      },

      // 恢复废弃表
      [OperationColumn.RestoreTable]: {
        text: i18n('workspace.menu.restoreTable'),
        icon: '\ue63e',
        handle: () => {
          restoreDeprecatedTable({
            treeNodeData,
            loadData: () => {
              loadData({ treeNodeData: treeNodeData.parentNode });
            },
          });
        },
      },

      // 生成数据
      [OperationColumn.GenerateData]: {
        text: i18n('workspace.menu.generateData'),
        icon: '\ue816',
        handle: () => {
          const { openDataGenerationModal } = useWorkspaceStore.getState();
          openDataGenerationModal?.({
            dataSourceId: treeNodeData.extraParams!.dataSourceId!,
            databaseName: treeNodeData.extraParams?.databaseName,
            schemaName: treeNodeData.extraParams?.schemaName,
            tableName: treeNodeData.name!,
          });
        },
      },
    };

    // 根据配置生成右键菜单
    const finalList: IRightClickMenu[] = [];
    excludeSomeOperation().forEach((t, i) => {
      const concrete = operationColumnConfig[t];
      if (!!concrete && !concrete.discard) {
        finalList.push({
          key: i,
          onClick: concrete?.handle,
          type: t,
          doubleClickTrigger: concrete.doubleClickTrigger,
          labelProps: {
            icon: concrete?.icon,
            label: concrete?.text,
          },
        });
      }
    });
    return groupDataOperations(finalList);
  }, [treeNodeData]);

  return rightClickMenu;
};

export const getRightClickMenu = (props: IProps) => {
  const { treeNodeData, loadData } = props;

  const openCreateDatabaseModal = useWorkspaceStore.getState().openCreateDatabaseModal;
  const currentConnectionDetails = useWorkspaceStore.getState().currentConnectionDetails;

  const handelOpenCreateDatabaseModal = (type: 'database' | 'schema') => {
    const relyOnParams = {
      databaseType: treeNodeData.extraParams!.databaseType,
      dataSourceId: treeNodeData.extraParams!.dataSourceId!,
      databaseName: treeNodeData.name,
    };

    openCreateDatabaseModal?.({
      type,
      relyOnParams,
      executedCallback: () => {
        loadData({
          refresh: true,
        });
      },
    });
  };

  // 拿出当前节点的配置
  const treeNodeConfig: ITreeConfigItem = treeConfig[treeNodeData.treeNodeType];
  const { operationColumn } = treeNodeConfig;

  const dataSourceFormConfig = dataSourceFormConfigs.find((t: IConnectionConfig) => {
    return t.type === treeNodeData.extraParams?.databaseType;
  })!;

  // 有些数据库不支持的操作，需要排除掉
  function excludeSomeOperation() {
    const excludes = dataSourceFormConfig.baseInfo.excludes;
    const newOperationColumn: OperationColumn[] = [];
    operationColumn?.map((item: OperationColumn) => {
      let flag = false;
      excludes?.map((t) => {
        if (item === t) {
          flag = true;
        }
      });
      if (!flag) {
        newOperationColumn.push(item);
      }
    });
    return newOperationColumn;
  }

  const operationColumnConfig: { [key in string]: IOperationColumnConfigItem } = {
    // 刷新
    [OperationColumn.Refresh]: {
      text: i18n('common.button.refresh'),
      icon: '\uec08',
      handle: () => {
        loadData?.({
          refresh: true,
        });
      },
    },

    // 创建console
    [OperationColumn.CreateConsole]: {
      text: i18n('workspace.menu.queryConsole'),
      icon: '\ue619',
      doubleClickTrigger: treeNodeData.treeNodeType === TreeNodeType.REDIS_QUERY,
      handle: () => {
        const isRedis = treeNodeData.extraParams?.databaseType === DatabaseTypeCode.REDIS;
        const tableName = isRedis
          ? ''
          : compatibleDataBaseName(
              treeNodeData.name!,
              treeNodeData.extraParams!.databaseType,
              treeNodeData.extraParams?.schemaName,
            );
        createConsole({
          dataSourceId: treeNodeData.extraParams!.dataSourceId!,
          dataSourceName: treeNodeData.extraParams!.dataSourceName!,
          databaseType: treeNodeData.extraParams!.databaseType!,
          databaseName: treeNodeData.extraParams?.databaseName,
          schemaName: treeNodeData.extraParams?.schemaName,
          ddl: isRedis ? '' : `select * from ${tableName}`,
        });
      },
    },

    [OperationColumn.OpenRedisData]: {
      text: '打开数据',
      icon: '\ue618',
      doubleClickTrigger: true,
      handle: () => {
        addWorkspaceTab({
          id: `${OperationColumn.OpenRedisData}-${treeNodeData.uuid}`,
          title: `${treeNodeData.extraParams?.databaseName || '0'}-数据`,
          type: WorkspaceTabType.RedisData,
          uniqueData: {
            dataSourceId: treeNodeData.extraParams!.dataSourceId!,
            dataSourceName: treeNodeData.extraParams!.dataSourceName!,
            databaseType: treeNodeData.extraParams!.databaseType!,
            databaseName: treeNodeData.extraParams?.databaseName,
          },
        });
      },
    },

    [OperationColumn.OpenRedisMonitor]: {
      text: '打开监控',
      icon: '\ue611',
      doubleClickTrigger: true,
      handle: () => {
        addWorkspaceTab({
          id: `${OperationColumn.OpenRedisMonitor}-${treeNodeData.uuid}`,
          title: `${treeNodeData.extraParams?.databaseName || '0'}-监控`,
          type: WorkspaceTabType.RedisMonitor,
          uniqueData: {
            dataSourceId: treeNodeData.extraParams!.dataSourceId!,
            dataSourceName: treeNodeData.extraParams!.dataSourceName!,
            databaseType: treeNodeData.extraParams!.databaseType!,
            databaseName: treeNodeData.extraParams?.databaseName,
          },
        });
      },
    },

    // 查看所有表
    [OperationColumn.ViewAllTable]: {
      text: i18n('workspace.menu.viewAllTable'),
      icon: '\ue611',
      handle: () => {
        addWorkspaceTab({
          id: uuid(),
          type: WorkspaceTabType.ViewAllTable,
          title: `${treeNodeData.extraParams!.databaseName!}-tables`,
          uniqueData: {
            dataSourceId: treeNodeData.extraParams!.dataSourceId!,
            dataSourceName: treeNodeData.extraParams!.dataSourceName!,
            databaseType: treeNodeData.extraParams!.databaseType!,
            databaseName: treeNodeData.extraParams?.databaseName,
            schemaName: treeNodeData.extraParams?.schemaName,
          },
        });
      },
    },
    // 添加查看 ER 图
    [OperationColumn.ViewERDiagram]: {
      text: i18n('workspace.menu.viewERDiagram'),
      icon: '\ue611',
      handle: () => {
        addWorkspaceTab({
          id: uuid(),
          type: WorkspaceTabType.ViewERDiagram,
          title: `${treeNodeData.extraParams!.databaseName!}-ER`,
          uniqueData: {
            dataSourceId: treeNodeData.extraParams!.dataSourceId!,
            dataSourceName: treeNodeData.extraParams!.dataSourceName!,
            databaseType: treeNodeData.extraParams!.databaseType!,
            databaseName: treeNodeData.extraParams?.databaseName,
            schemaName: treeNodeData.extraParams?.schemaName,
          },
        });
      },
    },

    // 创建表
    [OperationColumn.CreateTable]: {
      text: i18n('editTable.button.createTable'),
      icon: '\ue792',
      handle: () => {
        addWorkspaceTab({
          id: uuid(),
          title: i18n('editTable.button.createTable'),
          type: WorkspaceTabType.CreateTable,
          uniqueData: {
            dataSourceId: treeNodeData.extraParams!.dataSourceId!,
            databaseType: treeNodeData.extraParams!.databaseType!,
            databaseName: treeNodeData.extraParams?.databaseName,
            schemaName: treeNodeData.extraParams?.schemaName,
            submitCallback: () => {
              treeNodeData.loadData?.({ refresh: true });
            },
          },
        });
      },
      discard: treeNodeData.treeNodeType === TreeNodeType.DATABASE && currentConnectionDetails?.supportSchema,
    },

    // 删除表
    [OperationColumn.DeleteTable]: {
      text: i18n('workspace.menu.deleteTable'),
      icon: '\ue6a7',
      handle: () => {
        deleteTable(treeNodeData, loadData);
      },
    },

    // 查看ddl
    [OperationColumn.ViewDDL]: {
      text: i18n('workspace.menu.ViewDDL'),
      icon: '\ue665',
      handle: () => {
        viewDDL(treeNodeData);
      },
    },

    // 置顶
    [OperationColumn.Pin]: {
      text: treeNodeData.pinned ? i18n('workspace.menu.unPin') : i18n('workspace.menu.pin'),
      icon: treeNodeData.pinned ? '\ue61d' : '\ue627',
      handle: () => {
        handelPinTable({ treeNodeData, loadData: treeNodeData.parentNode!.loadData! });
      },
    },

    // 编辑表
    [OperationColumn.EditTable]: {
      text: i18n('workspace.menu.editTable'),
      icon: '\ue602',
      handle: () => {
        addWorkspaceTab({
          id: `${OperationColumn.EditTable}-${treeNodeData.uuid}`,
          title: treeNodeData?.name,
          type: WorkspaceTabType.EditTable,
          uniqueData: {
            dataSourceId: treeNodeData.extraParams!.dataSourceId!,
            databaseType: treeNodeData.extraParams!.databaseType!,
            databaseName: treeNodeData.extraParams?.databaseName,
            schemaName: treeNodeData.extraParams?.schemaName,
            tableName: treeNodeData?.name,
            submitCallback: () => {
              treeNodeData.parentNode?.loadData?.({ refresh: true });
            },
          },
        });
      },
    },

    // 复制名称
    [OperationColumn.CopyName]: {
      text: i18n('common.button.copyName'),
      icon: '\uec7a',
      handle: () => {
        navigator.clipboard.writeText(treeNodeData.name);
      },
    },

    // 打开表
    [OperationColumn.OpenTable]: {
      text: i18n('workspace.menu.openTable'),
      icon: '\ue618',
      doubleClickTrigger: true,
      handle: () => {
        const databaseName = compatibleDataBaseName(
          treeNodeData.name!,
          treeNodeData.extraParams!.databaseType,
          treeNodeData.extraParams?.schemaName,
        );
        addWorkspaceTab({
          id: `${OperationColumn.OpenTable}-${treeNodeData.uuid}`,
          title: treeNodeData.name,
          type: WorkspaceTabType.EditTableData,
          uniqueData: {
            dataSourceId: treeNodeData.extraParams!.dataSourceId!,
            databaseType: treeNodeData.extraParams!.databaseType!,
            databaseName: treeNodeData.extraParams?.databaseName,
            schemaName: treeNodeData.extraParams?.schemaName,
            tableName: treeNodeData.name,
            sql: 'select * from ' + databaseName,
          },
        });
      },
    },

    // 打开视图
    [OperationColumn.OpenView]: {
      text: i18n('workspace.menu.view'),
      icon: '\ue651',
      doubleClickTrigger: true,
      handle: () => {
        openView({
          addWorkspaceTab,
          treeNodeData,
        });
      },
    },

    // 打开函数
    [OperationColumn.OpenFunction]: {
      text: i18n('workspace.menu.view'),
      icon: '\ue651',
      doubleClickTrigger: true,
      handle: () => {
        openFunction({
          addWorkspaceTab,
          treeNodeData,
        });
      },
    },

    // 打开存储过程
    [OperationColumn.OpenProcedure]: {
      text: i18n('workspace.menu.view'),
      icon: '\ue651',
      doubleClickTrigger: true,
      handle: () => {
        openProcedure({
          addWorkspaceTab,
          treeNodeData,
        });
      },
    },

    // 打开触发器
    [OperationColumn.OpenTrigger]: {
      text: i18n('workspace.menu.view'),
      icon: '\ue651',
      doubleClickTrigger: true,
      handle: () => {
        openTrigger({
          addWorkspaceTab,
          treeNodeData,
        });
      },
    },

    // 创建数据库
    [OperationColumn.CreateDatabase]: {
      text: i18n('workspace.menu.createDatabase'),
      icon: '\ue816',
      handle: () => {
        handelOpenCreateDatabaseModal('database');
      },
    },

    // 创建schema
    [OperationColumn.CreateSchema]: {
      text: i18n('workspace.menu.createSchema'),
      icon: '\ue696',
      handle: () => {
        handelOpenCreateDatabaseModal('schema');
      },
      discard: !currentConnectionDetails?.supportSchema,
    },

    // 删除虚拟外键
    [OperationColumn.DeleteVirtualKey]: {
      text: i18n('workspace.menu.deleteVirtualKey'),
      icon: '\ue6a7',
      handle: async () => {
        await deleteVirtualForeignKey(treeNodeData, loadData);
      },
    },

    // 导入数据
    [OperationColumn.ImportData]: {
      text: i18n('workspace.menu.importData'),
      icon: '\ue653',
      handle: () => {
        const { openImportDataModal } = useWorkspaceStore.getState();
        openImportDataModal?.({
          tableName: treeNodeData.name,
          dataSourceId: treeNodeData.extraParams!.dataSourceId!,
          databaseName: treeNodeData.extraParams?.databaseName,
          schemaName: treeNodeData.extraParams?.schemaName,
          executedCallback: () => {
            loadData?.({
              refresh: true,
            });
          },
        });
      },
    },

    // 导出数据
    [OperationColumn.ExportData]: {
      text: i18n('workspace.menu.exportData'),
      icon: '\ue613',
      handle: () => {
        const { openExportDataModal } = useWorkspaceStore.getState();
        openExportDataModal?.({
          tableName: treeNodeData.name,
          dataSourceId: treeNodeData.extraParams!.dataSourceId!,
          databaseName: treeNodeData.extraParams?.databaseName,
          schemaName: treeNodeData.extraParams?.schemaName,
        });
      },
    },

    [OperationColumn.ViewTableRelation]: {
      text:
        treeNodeData.treeNodeType === TreeNodeType.KEY || treeNodeData.treeNodeType === TreeNodeType.V_KEY
          ? i18n('workspace.menu.view')
          : i18n('workspace.menu.viewTableRelation'),
      icon: '\ue611',
      handle: () => {
        openTableRelationModal(treeNodeData);
      },
    },

    // 数据传输
    [OperationColumn.DataTransfer]: {
      text: '数据传输',
      icon: '\ue60e',
      handle: () => {
        const { openDataTransferModal } = useWorkspaceStore.getState();
        openDataTransferModal?.({
          dataSourceId: treeNodeData.extraParams!.dataSourceId!,
          databaseName: treeNodeData.extraParams?.databaseName,
          schemaName: treeNodeData.extraParams?.schemaName,
          tableNames: [treeNodeData.name],
          executedCallback: () => {
            loadData?.({
              refresh: true,
            });
          },
        });
      },
    },

    // 导出数据结构
    [OperationColumn.ExportSchemaDoc]: {
      text: i18n('workspace.menu.exportSchemaDoc'),
      icon: '\ue613',
      handle: () => {
        const { openExportSchemaDocModal } = useWorkspaceStore.getState();
        openExportSchemaDocModal?.({
          dataSourceId: treeNodeData.extraParams!.dataSourceId!,
          databaseName: treeNodeData.extraParams?.databaseName,
          schemaName: treeNodeData.extraParams?.schemaName,
        });
      },
    },

    [OperationColumn.ExecuteSqlStatement]: {
      text: i18n('workspace.menu.executeSqlStatement'),
      icon: '\ue619',
      handle: () => {
        const { openExecuteSqlStatementModal } = useWorkspaceStore.getState();
        openExecuteSqlStatementModal?.({
          dataSourceId: treeNodeData.extraParams!.dataSourceId!,
          databaseName: treeNodeData.extraParams?.databaseName,
          schemaName: treeNodeData.extraParams?.schemaName,
          executedCallback: () => {
            loadData?.({
              refresh: true,
            });
          },
        });
      },
    },

    // 废弃表
    [OperationColumn.DeprecatedTable]: {
      text: i18n('workspace.menu.deprecatedTable'),
      icon: '\ue73c',
      handle: () => {
        deprecatedTable({
          treeNodeData,
          loadData: () => {
            // 如果有 parentNode，刷新父节点；否则刷新当前节点
            if (treeNodeData.parentNode?.loadData) {
              loadData({ treeNodeData: treeNodeData.parentNode });
            } else {
              loadData({ refresh: true });
            }
          },
        });
      },
    },

    // 恢复废弃表
    [OperationColumn.RestoreTable]: {
      text: i18n('workspace.menu.restoreTable'),
      icon: '\ue63e',
      handle: () => {
        restoreDeprecatedTable({
          treeNodeData,
          loadData: () => {
            // 如果有 parentNode，刷新父节点；否则刷新当前节点
            if (treeNodeData.parentNode?.loadData) {
              loadData({ treeNodeData: treeNodeData.parentNode });
            } else {
              loadData({ refresh: true });
            }
          },
        });
      },
    },

    // 生成数据
    [OperationColumn.GenerateData]: {
      text: i18n('workspace.menu.generateData'),
      icon: '\ue816',
      handle: () => {
        const { openDataGenerationModal } = useWorkspaceStore.getState();
        openDataGenerationModal?.({
          dataSourceId: treeNodeData.extraParams!.dataSourceId!,
          databaseName: treeNodeData.extraParams?.databaseName,
          schemaName: treeNodeData.extraParams?.schemaName,
          tableName: treeNodeData.name!,
        });
      },
    },
  };

  // 根据配置生成右键菜单
  const finalList: IRightClickMenu[] = [];
  excludeSomeOperation().forEach((t, i) => {
    const concrete = operationColumnConfig[t];
    if (!!concrete && !concrete.discard) {
      finalList.push({
        key: i,
        onClick: concrete?.handle,
        type: t,
        doubleClickTrigger: concrete.doubleClickTrigger,
        labelProps: {
          icon: concrete?.icon,
          label: concrete?.text,
        },
      });
    }
  });
  return groupDataOperations(finalList);
};

const deleteVirtualForeignKey = async (treeNode: ITreeNode, loadData: () => void) => {
  const { dataSourceId, databaseName, schemaName, tableName } = treeNode.extraParams!;
  if (!databaseName) {
    message.error('数据库名称不能为空');
    return;
  }
  if (!tableName) {
    message.error('表名不能为空');
    return;
  }
  try {
    await sqlServer.deleteVirtualForeignKey({
      dataSourceId,
      databaseName,
      schemaName,
      tableName,
      keyName: treeNode.name,
    });

    message.success('删除虚拟外键成功');

    // 刷新父节点（KEYS节点）
    loadData({
      refresh: true,
      treeNodeData: treeNode.parentNode,
    });
  } catch (error) {
    message.error('删除虚拟外键失败');
    console.error('删除虚拟外键失败:', error);
  }
};
