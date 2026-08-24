import i18n from '@/i18n';
import { Form } from 'antd';
import { SquarePen } from 'lucide-react';
import { type ReactNode, useRef } from 'react';
import { v4 as uuid } from 'uuid';

import { ConsoleOpenedStatus, OperationColumn, TreeNodeType, WorkspaceTabType, databaseTypeList } from '@/constants';
import { ImportExportType } from '@/constants/importExport';
import { ShortcutAction } from '@/constants/shortcut';
import { TreeNodeData } from '@/typings';
import { canImportExport } from '@/utils/env';

// ----- store -----
import { useGlobalStore } from '@/store/global';
import { useImportExportStore } from '@/store/importExport';
import { useTreeStore } from '@/store/tree';
import { useWorkspaceStore } from '@/store/workspace';

import aiService from '@/service/ai';
import connectionService from '@/service/connection';
import historyServer from '@/service/history';
import sqlService from '@/service/sql';

// ---- functions -----
import { copyToClipboard, getParentNode } from '@/utils';
import { staticMessage, staticModal } from '@chat2db/ui';
import { deleteTable } from '../functions/deleteTable';
import { generateJavaClass } from '../functions/generateJavaClass';
import { neatenMoveToGroup } from '../functions/moveToGroup';
import { editView, openFunction, openProcedure, openTrigger, openView } from '../functions/openAsyncSql';
import { handelPinTable } from '../functions/pinTable';
import { openSchemaSyncModal } from '../functions/schemaSync';
import { viewDDL } from '../functions/viewDDL';

// ----- utils -----
import { compatibleDataBaseName, getDatabaseSupport } from '@/utils/database';
import {
  canDeleteDatabase,
  canDeleteSchema,
  canExportData,
  canExportSqlFile,
  canGenerateJavaClass,
  canImportData,
  canRunSqlFile,
} from '@/utils/databaseJudgments';
import { dropMenuConfig } from '../menuConfig';

import { handleExportSqlFile } from '@/blocks/ImportAndExport/functions/exportSqlFile';
import { useOrgStore } from '@/store/workspaceContext';
import { ILoadDataOptions, treeConfig } from '../treeConfig';

import { clientRuntime } from '@client-runtime';
import { resolveDataSourceAuthorization } from '@/utils/dataSourceAuthorization';
import accountAdminService, { AccountActionType, formatAccountExecuteMessage } from '@/service/accountAdmin';
import CreateAccountContent, { CreateAccountValues } from '../components/CreateAccountContent';
import DeleteDatabaseSchemaConfirmContent from '../components/DeleteDatabaseSchemaConfirmContent';
import { emitSavedConsoleUpdated } from '@/utils/savedConsoleEvents';
import { buildWorkspaceObjectTabTitle } from '@/utils/workspaceObjectTabTitle';
import { allowsResourceOperations } from '@/client-extension/resourceOperationCapabilities';
import type { ResourceOperation, ResourceOperationCapabilities } from '@/client-extension/types';
import clientExtension from '@client-extension';
import { DataSourceIdentityColorRequestRegistry } from '../dataSourceIdentityColorRequest';
import DataSourceColorMenuItem from '../components/DataSourceColorMenuItem';
import { withDataSourceColorMenuOption } from '../dataSourceColorMenu';
import { isDangerousTreeOperation } from '../treeMenuDanger';

export interface MenuLabelRenderContext {
  closeMenu: () => void;
  setInteractionOpen: (open: boolean) => void;
  registerFocusTarget: (focus: () => void) => () => void;
}

// Some operations are not supported by the database and need to be excluded.
interface IOperationColumnConfigItem {
  text: string;
  icon?: string | ReactNode;
  shortcutAction?: ShortcutAction;
  doubleClickTrigger?: boolean;
  handle?: () => void;
  discard?: boolean;
  requiredOperations?: readonly ResourceOperation[];
  keepOpen?: boolean;
  danger?: boolean;
  renderLabel?: (context: MenuLabelRenderContext) => ReactNode;
  children?: IOperationColumnConfigItem[];
}

interface IRightClickMenu {
  key: number | string;
  onClick?: () => void;
  type: OperationColumn;
  shortcutAction?: ShortcutAction;
  doubleClickTrigger?: boolean;
  keepOpen?: boolean;
  danger?: boolean;
  labelProps: {
    icon?: string | ReactNode;
    label: string;
    renderLabel?: (context: MenuLabelRenderContext) => ReactNode;
  };
  children?: IRightClickMenu[];
}

type CreateRightClickMenu = (
  treeNodeData: TreeNodeData,
  handleLoadData: (node: TreeNodeData, options?: ILoadDataOptions) => void,
  operationCapabilities?: ResourceOperationCapabilities,
) => IRightClickMenu[];

/**
 * Generate right-click menu list
 */
function handleMenuOptions(treeNodeType, databaseType) {
  const databaseDropMenuConfig = dropMenuConfig[databaseType] || dropMenuConfig['DEFAULT'];
  const menuOptions = databaseDropMenuConfig[treeNodeType] || dropMenuConfig['DEFAULT'][treeNodeType] || [];
  return withDataSourceColorMenuOption(menuOptions, treeNodeType);
}

// Node that can be double-clicked
export const canBeDoubleClicked = [
  TreeNodeType.TABLE,
  TreeNodeType.TABLES,
  TreeNodeType.VIEW,
  TreeNodeType.PROCEDURE,
  TreeNodeType.FUNCTION,
  TreeNodeType.TRIGGER,
  TreeNodeType.ALL_DATA,
  TreeNodeType.DATABASE_ACCOUNT,
  TreeNodeType.SAVE_CONSOLE,
];

export const useCreateRightClickMenu = () => {
  const [createAccountForm] = Form.useForm<CreateAccountValues>();
  const identityColorRequestRegistryRef = useRef(new DataSourceIdentityColorRequestRegistry());
  // Read only store actions here; dynamic data must be fetched again for each operation.
  const {
    setEditingTreeNode,
    createGroup,
    moveToGroup,
    deleteGroup,
    setIsModalVisible,
    setConnectionDetail,
    deleteDataSource,
    updateDataSourceIdentity,
    closeConnection,
  } = useTreeStore((state) => {
    return {
      setEditingTreeNode: state.setEditingTreeNode,
      createGroup: state.createGroup,
      moveToGroup: state.moveToGroup,
      deleteGroup: state.deleteGroup,
      setIsModalVisible: state.setIsModalVisible,
      setConnectionDetail: state.setConnectionDetail,
      deleteDataSource: state.deleteDataSource,
      updateDataSourceIdentity: state.updateDataSourceIdentity,
      closeConnection: state.closeConnection,
    };
  });

  const { openCreateDatabaseModal, addWorkspaceTab, createConsole, removeSavedConsole } = useWorkspaceStore((state) => {
    return {
      openCreateDatabaseModal: state.openCreateDatabaseModal,
      addWorkspaceTab: state.addWorkspaceTab,
      createConsole: state.createConsole,
      removeSavedConsole: state.removeSavedConsole,
    };
  });

  const { setImportExportDataBoundInfo, setRunSqlBoundInfo, getTaskList, openLogModal } = useImportExportStore(
    (state) => {
      return {
        setImportExportDataBoundInfo: state.setImportExportDataBoundInfo,
        setRunSqlBoundInfo: state.setRunSqlBoundInfo,
        getTaskList: state.getTaskList,
        openLogModal: state.openLogModal,
      };
    },
  );

  const { openUnifiedConfirmationModal } = useGlobalStore((state) => {
    return {
      openUnifiedConfirmationModal: state.openUnifiedConfirmationModal,
    };
  });

  const { isAdmin } = useOrgStore((state) => ({
    isAdmin: state.isAdmin,
  }));

  const createRightClickMenu: CreateRightClickMenu = (treeNodeData, handleLoadData, operationCapabilities) => {
    const treeData = useTreeStore.getState().treeData;

    if (!treeNodeData) return [];
    const { treeNodeType, extraParams, decorativeParams } = treeNodeData;
    const {
      databaseType,
      dataSourceId,
      dataSourceName,
      databaseName,
      schemaName,
      tableName,
      environmentId,
      environment,
      identityColor,
    } = extraParams;
    const { hasPermission, isAdmin: isDataSourceAdmin } = resolveDataSourceAuthorization(
      extraParams,
      clientRuntime.usesFixedIdentity,
    );

    const persistIdentityColor = (nextIdentityColor: string | null) => {
      const targetDataSourceId = dataSourceId!;
      const requestRegistry = identityColorRequestRegistryRef.current;
      const requestToken = requestRegistry.begin(targetDataSourceId, identityColor ?? null);
      updateDataSourceIdentity({ id: targetDataSourceId, identityColor: nextIdentityColor });
      return requestRegistry
        .enqueue(targetDataSourceId, () =>
          connectionService.updateIdentityColor({ id: targetDataSourceId, identityColor: nextIdentityColor }),
        )
        .then((response) => {
          requestRegistry.confirm(targetDataSourceId, response.identityColor);
          if (!requestRegistry.isLatest(requestToken)) {
            return response;
          }
          updateDataSourceIdentity(response);
          staticMessage.success(i18n('workspace.identityColor.saveSuccess'));
          return response;
        })
        .catch((error) => {
          if (!requestRegistry.isLatest(requestToken)) {
            return Promise.reject(error);
          }
          updateDataSourceIdentity({
            id: targetDataSourceId,
            identityColor: requestRegistry.getConfirmedColor(targetDataSourceId),
          });
          staticMessage.error(i18n('workspace.identityColor.saveFailed'));
          return Promise.reject(error);
        });
    };

    const { supportSchema, supportDatabase } = getDatabaseSupport(databaseType);
    const handelOpenCreateDatabaseModal = (type: 'database' | 'schema') => {
      const relyOnParams = {
        databaseType: treeNodeData.extraParams.databaseType!,
        dataSourceId: treeNodeData.extraParams.dataSourceId!,
        databaseName: type === 'schema' ? treeNodeData.originalTitle : undefined,
      };

      openCreateDatabaseModal?.({
        type,
        relyOnParams,
        executedCallback: () => {
          handleLoadData(treeNodeData, {
            refresh: true,
          });
        },
      });
    };

    const refreshAfterDelete = () => {
      const parentNode = getParentNode(treeNodeData.key, treeData);
      handleLoadData(parentNode || treeNodeData, {
        refresh: true,
      });
    };

    const refreshCurrentNode = () => {
      handleLoadData(treeNodeData, {
        refresh: true,
      });
    };

    const renderDeleteInputConfirmLabel = (labelKey: string, confirmName: string) => {
      return (
        <>
          {i18n(labelKey as any)}
          <span className="chat2db-delete-confirm-target-name">{confirmName}</span>
          {i18n('workspace.deleteDatabaseSchema.inputConfirmSuffix')}
        </>
      );
    };

    const openDeleteDatabaseModal = () => {
      sqlService
        .prepareDeleteDatabase({
          dataSourceId: dataSourceId!,
          databaseName: databaseName!,
        })
        .then((prepared) => {
          openUnifiedConfirmationModal({
            title: i18n('workspace.menu.deleteDatabase'),
            width: 560,
            content: <DeleteDatabaseSchemaConfirmContent sqlPreview={prepared.sqlPreview} objectType="database" />,
            needInputConfirmText: prepared.confirmName,
            inputConfirmLabel: renderDeleteInputConfirmLabel(
              'workspace.deleteDatabaseSchema.inputDatabaseName',
              prepared.confirmName,
            ),
            inputConfirmPlaceholder: prepared.confirmName,
            inputConfirmMismatchTip: i18n('workspace.deleteDatabaseSchema.confirmNameMismatch'),
            onOk: (confirmName) => {
              return sqlService
                .executeDeleteDatabase({
                  dataSourceId: dataSourceId!,
                  databaseName: databaseName!,
                  confirmName: confirmName || '',
                })
                .then(() => {
                  staticMessage.success(i18n('common.text.successfullyDelete'));
                  refreshAfterDelete();
                });
            },
          });
        });
    };

    const openDeleteSchemaModal = () => {
      sqlService
        .prepareDeleteSchema({
          dataSourceId: dataSourceId!,
          databaseName: databaseName!,
          schemaName: schemaName!,
        })
        .then((prepared) => {
          openUnifiedConfirmationModal({
            title: i18n('workspace.menu.deleteSchema'),
            width: 560,
            content: <DeleteDatabaseSchemaConfirmContent sqlPreview={prepared.sqlPreview} objectType="schema" />,
            needInputConfirmText: prepared.confirmName,
            inputConfirmLabel: renderDeleteInputConfirmLabel(
              'workspace.deleteDatabaseSchema.inputSchemaName',
              prepared.confirmName,
            ),
            inputConfirmPlaceholder: prepared.confirmName,
            inputConfirmMismatchTip: i18n('workspace.deleteDatabaseSchema.confirmNameMismatch'),
            onOk: (confirmName) => {
              return sqlService
                .executeDeleteSchema({
                  dataSourceId: dataSourceId!,
                  databaseName: databaseName!,
                  schemaName: schemaName!,
                  confirmName: confirmName || '',
                })
                .then(() => {
                  staticMessage.success(i18n('common.text.successfullyDelete'));
                  refreshAfterDelete();
                });
            },
          });
        });
    };

    const operationColumnConfig: { [key in string]: IOperationColumnConfigItem } = {
      // copyName
      [OperationColumn.CopyName]: {
        text: i18n('common.button.copyName'),
        icon: <span aria-hidden="true" style={{ display: 'inline-block', width: 20, height: 20 }} />,
        handle: () => {
          copyToClipboard(treeNodeData.originalTitle);
        },
      },

      // applies for permission
      [OperationColumn.ApplyPermission]: {
        text: i18n('common.button.confirm'),
        icon: 'icon-key1',
        handle: () => {
          const props = {
            applyType: 'data' as const,
            dataSourceId: dataSourceId!,
            databaseName,
            dataSourceName,
            schemaName,
          };
          clientExtension.openPermissionApplication?.(props);
        },
        discard: hasPermission || !clientExtension.openPermissionApplication,
      },

      [OperationColumn.CloseConnection]: {
        text: i18n('workspace.menu.closeConnection'),
        icon: 'icon-close-connection',
        handle: () => {
          closeConnection(treeNodeData.extraParams.dataSourceId!);
        },
      },

      [OperationColumn.OpenAccountPrivileges]: {
        text: i18n('workspace.databaseAccount.open'),
        icon: 'icon-users',
        doubleClickTrigger: true,
        handle: () => {
          const user = extraParams.user || '';
          const host = extraParams.host || '';
          const title = `${user}@${host}`;
          const id = ['mysql-user', dataSourceId, encodeURIComponent(user), encodeURIComponent(host)].join('-');
          addWorkspaceTab({
            id,
            type: WorkspaceTabType.AccountPrivileges,
            title,
            uniqueData: {
              ...extraParams,
            },
          });
        },
      },

      [OperationColumn.CreateAccount]: {
        text: i18n('workspace.databaseAccount.createUser'),
        icon: 'icon-users',
        handle: () => {
          createAccountForm.resetFields();
          staticModal.confirm({
            title: i18n('workspace.databaseAccount.createUser'),
            content: <CreateAccountContent form={createAccountForm} />,
            onOk: () => {
              return createAccountForm.validateFields().then((values) => {
                const command = {
                  dataSourceId: dataSourceId!,
                  user: values.user,
                  host: values.host,
                  password: values.password,
                  actionType: AccountActionType.CREATE_USER,
                };
                return accountAdminService.preview(command).then((preview) => {
                  return accountAdminService
                    .execute({
                      ...command,
                      previewToken: preview.previewToken,
                    })
                    .then((result) => {
                      if (!result.success) {
                        const errorMessage = formatAccountExecuteMessage(result);
                        staticMessage.error(errorMessage);
                        return Promise.reject(new Error(errorMessage));
                      }
                      staticMessage.success(formatAccountExecuteMessage(result));
                      refreshCurrentNode();
                      return result;
                    });
                });
              });
            },
          });
        },
      },

      // Create a data source.
      [OperationColumn.CreateDataSource]: {
        text: i18n('workspace.menu.newDataSource'),
        icon: 'icon-newdatabase',
        children: databaseTypeList.map((t) => {
          return {
            key: t.code,
            text: t.name,
            icon: t.icon,
            handle: () => {
              setConnectionDetail({
                type: t.code,
                spaceId: treeNodeData.id,
              } as any);
              setTimeout(() => {
                setIsModalVisible(true);
              }, 0);
            },
          };
        }),
        discard: !isAdmin,
      },

      // Create a group.
      [OperationColumn.CreateGroup]: {
        text: i18n('workspace.menu.newGroup'),
        icon: 'icon-folder',
        handle: () => {
          createGroup(extraParams.groupId);
        },
      },

      // Move to the selected group.
      [OperationColumn.MoveToGroup]: {
        text: i18n('workspace.menu.moveToGroup'),
        icon: 'icon-file-exchange',
        children: neatenMoveToGroup({
          treeData,
          moveToGroup,
          treeNodeData,
        }),
        discard: treeNodeType === TreeNodeType.DATA_SOURCE && !isDataSourceAdmin,
      },

      // Delete the group.
      [OperationColumn.RemoveGroup]: {
        text: i18n('workspace.menu.deleteGroup'),
        icon: 'icon-trash',
        handle: () => {
          openUnifiedConfirmationModal({
            title: i18n('common.text.deleteConfirmTitle'),
            content: i18n('common.text.deleteConfirmTip', treeNodeData.originalTitle),
            onOk: () => deleteGroup(treeNodeData),
          });
        },
        discard: !isAdmin,
      },

      [OperationColumn.SchemaSync]: {
        text: i18n('workspace.syncStructure.title'),
        icon: 'icon-schema-sync',
        handle: () => {
          openSchemaSyncModal({
            dataSourceId: dataSourceId!,
            databaseName,
            databaseType,
            schemaName,
            supportSchema,
            supportDatabase,
          });
        },
        discard: treeNodeType === TreeNodeType.DATABASE && supportSchema,
      },

      // Rename.
      [OperationColumn.Rename]: {
        text: i18n('workspace.menu.renameGroup'),
        icon: 'icon-edit',
        handle: () => {
          setEditingTreeNode(treeNodeData);
        },
        discard: !isAdmin,
      },

      // Remove the data source.
      [OperationColumn.RemoveDataSource]: {
        text: i18n('workspace.menu.removeDataSource'),
        icon: 'icon-trash',
        handle: () => {
          openUnifiedConfirmationModal({
            title: i18n('common.text.deleteConfirmTitle'),
            content: i18n('common.text.deleteConfirmTip', dataSourceName),
            onOk: () => deleteDataSource(treeNodeData),
          });
        },
        discard: !isDataSourceAdmin,
      },

      [OperationColumn.EditSource]: {
        text: i18n('workspace.menu.editSource'),
        icon: <SquarePen size={20} />,
        handle: () => {
          connectionService.getDetails({ id: dataSourceId! }).then((res) => {
            if (res) {
              setConnectionDetail(res);
              setIsModalVisible(true);
            }
          });
        },
        discard: !isDataSourceAdmin,
      },

      [OperationColumn.SetDataSourceColor]: {
        text: i18n('workspace.identityColor.label'),
        discard: !hasPermission,
        keepOpen: true,
        renderLabel: ({ closeMenu, setInteractionOpen, registerFocusTarget }) => {
          const finishSelection = (nextIdentityColor: string | null) => {
            setInteractionOpen(false);
            void persistIdentityColor(nextIdentityColor).catch(() => undefined);
            closeMenu();
          };
          return (
            <DataSourceColorMenuItem
              identityColor={identityColor}
              onSelect={finishSelection}
              onEscape={closeMenu}
              registerFocusTarget={registerFocusTarget}
              onPickerOpenChange={(open) => {
                setInteractionOpen(open);
                if (!open) {
                  closeMenu();
                }
              }}
            />
          );
        },
      },

      // Copy the data source.
      [OperationColumn.CopyDataSource]: {
        text: i18n('workspace.menu.copyDataSource'),
        icon: 'icon-copy',
        handle: () => {
          connectionService.getDetails({ id: dataSourceId! }).then((res) => {
            if (res) {
              // Copy data source details without the ID or sensitive fields.
              const copyData = {
                ...res,
                id: undefined,
                alias: `${res.alias}_copy`,
                identityColor: null,
                password: '', // Clear the password.
                ConsoleOpenedStatus: 'n' as const,
              };
              setConnectionDetail(copyData as any);
              setTimeout(() => {
                setIsModalVisible(true);
              }, 0);
            }
          });
        },
        discard: !isDataSourceAdmin,
      },

      // Refresh.
      [OperationColumn.Refresh]: {
        text: i18n('common.button.refresh'),
        icon: 'icon-refresh',
        shortcutAction: ShortcutAction.DatabaseTreeRefresh,
        handle: () => {
          handleLoadData(treeNodeData, {
            refresh: true,
          });
        },
        discard: treeNodeType === TreeNodeType.DATABASE && !supportSchema,
      },

      // Create a console.
      [OperationColumn.CreateConsole]: {
        text: i18n('workspace.menu.queryConsole'),
        icon: 'icon-terminal',
        handle: () => {
          createConsole({
            dataSourceId: dataSourceId!,
            dataSourceName: dataSourceName!,
            environmentId,
            environment,
            databaseType: databaseType!,
            databaseName,
            schemaName,
          });
        },
        discard: !hasPermission,
      },

      // View all tables.
      [OperationColumn.ViewAllTable]: {
        text: i18n('workspace.menu.viewAllTable'),
        icon: 'icon-table-all',
        doubleClickTrigger: true,
        handle: () => {
          const title = [dataSourceName, 'tables'].filter(Boolean).join('-');
          addWorkspaceTab({
            id: uuid(),
            type: WorkspaceTabType.ViewAllTable,
            title,
            uniqueData: {
              ...extraParams,
              objectType: 'TABLE',
            },
          });
        },
      },

      // View all views.
      [OperationColumn.ViewAllView]: {
        text: i18n('workspace.menu.viewAllView'),
        icon: 'icon-table-all',
        handle: () => {
          const title = [dataSourceName, 'views'].filter(Boolean).join('-');
          addWorkspaceTab({
            id: uuid(),
            type: WorkspaceTabType.ViewAllView,
            title,
            uniqueData: {
              ...extraParams,
              objectType: 'VIEW',
            },
          });
        },
      },

      // View the ER diagram.
      [OperationColumn.ViewERModal]: {
        text: i18n('workspace.menu.viewERModal'),
        icon: 'icon-er-modal',
        handle: () => {
          const title = [dataSourceName, 'er'].filter(Boolean).join('-');
          addWorkspaceTab({
            id: uuid(),
            type: WorkspaceTabType.ViewERModal,
            title,
            uniqueData: {
              ...extraParams,
            },
          });
        },
      },

      // Create a table.
      [OperationColumn.CreateTable]: {
        text: i18n('editTable.button.createTable'),
        icon: 'icon-table-add',
        shortcutAction: ShortcutAction.DatabaseTreeCreateTable,
        handle: () => {
          addWorkspaceTab({
            id: uuid(),
            title: i18n('editTable.button.createTable'),
            type: WorkspaceTabType.CreateTable,
            uniqueData: {
              ...extraParams,
              submitCallback: () => {
                handleLoadData(treeNodeData, {
                  refresh: true,
                });
              },
            },
          });
        },
        discard: treeNodeType === TreeNodeType.DATABASE && supportSchema,
        requiredOperations: ['CREATE'],
      },

      // Delete the table.
      [OperationColumn.DeleteTable]: {
        text: i18n('workspace.menu.deleteTable'),
        icon: 'icon-trash',
        handle: () => {
          deleteTable(treeNodeData, () => {
            const parentNode = getParentNode(treeNodeData.key, treeData);
            if (parentNode) {
              handleLoadData(parentNode, {
                refresh: true,
              });
            }
          });
        },
        requiredOperations: ['DROP'],
      },

      [OperationColumn.DeleteDatabase]: {
        text: i18n('workspace.menu.deleteDatabase'),
        icon: 'icon-trash',
        handle: openDeleteDatabaseModal,
        discard:
          treeNodeType !== TreeNodeType.DATABASE ||
          !hasPermission ||
          !supportDatabase ||
          !canDeleteDatabase(databaseType),
        requiredOperations: ['DROP'],
      },

      [OperationColumn.DeleteSchema]: {
        text: i18n('workspace.menu.deleteSchema'),
        icon: 'icon-trash',
        handle: openDeleteSchemaModal,
        discard:
          treeNodeType !== TreeNodeType.SCHEMA || !hasPermission || !supportSchema || !canDeleteSchema(databaseType),
        requiredOperations: ['DROP'],
      },

      // View the DDL.
      [OperationColumn.ViewDDL]: {
        text: i18n('workspace.menu.ViewDDL'),
        icon: 'icon-document-search',
        handle: () => {
          viewDDL(treeNodeData);
        },
      },

      // Generate CRUD statements.
      [OperationColumn.GenerateCRUD]: {
        text: i18n('workspace.menu.GenerateCRUD'),
        icon: 'icon-sparkles',
        handle: () => {},
      },

      [OperationColumn.ChangeAiTableInfoNodataCollection]: {
        text: i18n('workspace.menu.GenerateCRUD'),
        discard: true,
      },

      // Pin to the top.
      [OperationColumn.Pin]: {
        text: decorativeParams?.pinned ? i18n('workspace.menu.unPin') : i18n('workspace.menu.pin'),
        icon: decorativeParams?.pinned ? 'icon-no-ding' : 'icon-ding',
        handle: () => {
          handelPinTable({
            treeNodeData,
          }).then(() => {
            const parentNode = getParentNode(treeNodeData.key, treeData);
            if (parentNode) {
              handleLoadData(parentNode, {
                refresh: true,
              });
            }
          });
        },
      },

      // Edit the table.
      [OperationColumn.EditTable]: {
        text: i18n('workspace.menu.editTable'),
        icon: 'icon-table-edit',
        shortcutAction: ShortcutAction.DatabaseTreeEditTable,
        handle: () => {
          const title = buildWorkspaceObjectTabTitle({
            dataSourceName,
            databaseName,
            schemaName,
            objectName: tableName!,
          });

          const id =
            treeConfig?.[TreeNodeType.TABLE]?.createTreeNodeKey?.({
              dataSourceId,
              databaseName,
              schemaName,
              tableName,
            }) || tableName;
          addWorkspaceTab({
            id: `${OperationColumn.EditTable}-${id}`,
            title,
            type: WorkspaceTabType.EditTable,
            uniqueData: {
              ...extraParams,
              submitCallback: () => {
                const parentNode = getParentNode(treeNodeData.key, treeData);
                if (parentNode) {
                  handleLoadData(parentNode, {
                    refresh: true,
                  });
                }
              },
              popoverContent: title,
            },
          });
        },
        requiredOperations: ['ALTER'],
      },

      // Open all data.
      [OperationColumn.OpenAllData]: {
        text: i18n('workspace.menu.openAllData'),
        icon: 'icon-table-view',
        doubleClickTrigger: true,
        handle: () => {
          addWorkspaceTab({
            id: uuid(),
            title: `${extraParams.databaseName}-all_data(${extraParams.dataSourceName})`,
            type: WorkspaceTabType.RedisAllData,
            uniqueData: {
              ...extraParams,
            },
          });
        },
      },

      // Open the table.
      [OperationColumn.OpenTable]: {
        text: i18n('workspace.menu.openTable'),
        icon: 'icon-table',
        shortcutAction: ShortcutAction.DatabaseTreeOpenTable,
        doubleClickTrigger: true,
        handle: () => {
          const _tableName = compatibleDataBaseName(tableName!, databaseType!);
          const title = buildWorkspaceObjectTabTitle({
            dataSourceName,
            databaseName,
            schemaName,
            objectName: tableName!,
          });

          const id =
            treeConfig?.[TreeNodeType.TABLE]?.createTreeNodeKey?.({
              dataSourceId,
              databaseName,
              schemaName,
              tableName,
            }) || tableName;
          addWorkspaceTab({
            id: `${OperationColumn.OpenTable}-${id}`,
            title,
            type: WorkspaceTabType.EditTableData,
            uniqueData: {
              ...extraParams,
              sql: 'select * from ' + _tableName,
              popoverContent: title,
            },
          });
        },
      },

      // Open the view.
      [OperationColumn.OpenView]: {
        text: i18n('workspace.menu.openView'),
        icon: 'icon-table-view',
        doubleClickTrigger: true,
        handle: () => {
          openView({ treeNodeData, addWorkspaceTab });
        },
      },

      [OperationColumn.EditView]: {
        text: i18n('workspace.menu.editView'),
        icon: 'icon-edit',
        handle: () => {
          editView({ treeNodeData, addWorkspaceTab });
        },
      },

      // Open the function.
      [OperationColumn.OpenFunction]: {
        text: i18n('workspace.menu.view'),
        icon: 'icon-document-search',
        doubleClickTrigger: true,
        handle: () => {
          openFunction({ treeNodeData, addWorkspaceTab });
        },
      },

      // Open the stored procedure.
      [OperationColumn.OpenProcedure]: {
        text: i18n('workspace.menu.view'),
        icon: 'icon-document-search',
        doubleClickTrigger: true,
        handle: () => {
          openProcedure({ treeNodeData, addWorkspaceTab });
        },
      },

      // Open the trigger.
      [OperationColumn.OpenTrigger]: {
        text: i18n('workspace.menu.view'),
        icon: 'icon-document-search',
        doubleClickTrigger: true,
        handle: () => {
          openTrigger({ treeNodeData, addWorkspaceTab });
        },
      },

      // Create a database.
      [OperationColumn.CreateDatabase]: {
        text: i18n('workspace.menu.createDatabase'),
        icon: 'icon-newdatabase',
        handle: () => {
          handelOpenCreateDatabaseModal('database');
        },
        discard: !supportDatabase || !hasPermission,
        requiredOperations: ['CREATE'],
      },

      // Create a schema.
      [OperationColumn.CreateSchema]: {
        text: i18n('workspace.menu.createSchema'),
        icon: 'icon-newdatabase',
        handle: () => {
          handelOpenCreateDatabaseModal('schema');
        },
        discard: (treeNodeType === TreeNodeType.DATA_SOURCE && supportDatabase) || !supportSchema,
        requiredOperations: ['CREATE'],
      },

      // Open a console.
      [OperationColumn.OpenConsole]: {
        text: i18n('workspace.menu.openConsole'),
        icon: 'icon-terminal',
        doubleClickTrigger: true,
        handle: () => {
          // TODO: Call the detail API here.
          const params: any = {
            id: treeNodeData?.id,
            tabOpened: ConsoleOpenedStatus.IS_OPEN,
          };
          historyServer.updateSavedConsole(params).then(() => {
            addWorkspaceTab({
              id: treeNodeData.id,
              type: WorkspaceTabType.CONSOLE,
              title: treeNodeData.originalTitle,
              uniqueData: {
                ...extraParams,
              },
            });
          });
        },
      },

      // Delete the console.
      [OperationColumn.RemoveConsole]: {
        text: i18n('workspace.menu.removeConsole'),
        icon: 'icon-trash',
        handle: () => {
          removeSavedConsole(treeNodeData.id!).then(() => {
            emitSavedConsoleUpdated(extraParams);
          });
        },
      },

      // Run the SQL file.
      [OperationColumn.RunSqlFile]: {
        text: i18n('workspace.menu.runSqlFile'),
        icon: 'icon-run-sql',
        handle: () => {
          setRunSqlBoundInfo({
            dataSourceName: dataSourceName,
            dataSourceId: dataSourceId!,
            databaseName,
            schemaName,
          });
        },
        discard: !canImportExport || !canRunSqlFile(databaseType) || !hasPermission,
      },

      [OperationColumn.CopyMcpConfig]: {
        text: i18n('workspace.menu.copyMcpConfig'),
        icon: 'icon-mcp',
        handle: () => {
          aiService.getMcpConfig().then((res) => {
            copyToClipboard(res);
            staticMessage.success(i18n('common.button.copySuccessfully'));
          });
        },
        discard: treeNodeType === TreeNodeType.DATABASE && supportSchema,
      },

      [OperationColumn.CopyGlobalMcpConfig]: {
        text: i18n('workspace.menu.copyGlobalMcpConfig'),
        icon: 'icon-mcp',
        handle: () => {
          aiService.getMcpConfig().then((res) => {
            copyToClipboard(res);
            staticMessage.success(i18n('common.button.copySuccessfully'));
          });
        },
      },

      // Export the SQL file.
      [OperationColumn.ExportSqlFile]: {
        text: i18n('workspace.menu.exportSqlFile'),
        icon: 'icon-Vector',
        children: [
          {
            text: i18n('workspace.menu.exportStructure'),
            handle: () => {
              handleExportSqlFile({
                dataSourceId: dataSourceId!,
                databaseName,
                schemaName,
                tableNames: tableName ? [tableName] : undefined,
                scope: 'SCHEMA',
                getTaskList,
                openLogModal,
              });
            },
          },
          {
            text: i18n('workspace.menu.exportData'),
            handle: () => {
              handleExportSqlFile({
                dataSourceId: dataSourceId!,
                databaseName,
                schemaName,
                tableNames: tableName ? [tableName] : undefined,
                scope: 'TABLE',
                getTaskList,
                openLogModal,
              });
            },
          },
          {
            text: i18n('workspace.menu.exportStructureData'),
            handle: () => {
              handleExportSqlFile({
                dataSourceId: dataSourceId!,
                databaseName,
                schemaName,
                tableNames: tableName ? [tableName] : undefined,
                scope: 'ALL',
                getTaskList,
                openLogModal,
              });
            },
          },
        ],
        discard:
          (treeNodeType === TreeNodeType.DATABASE && supportSchema) ||
          !canImportExport ||
          !canExportSqlFile(databaseType),
      },

      // Export data.
      [OperationColumn.ExportData]: {
        text: i18n('workspace.menu.exportData'),
        icon: 'icon-download',
        handle: () => {
          setImportExportDataBoundInfo({
            dataSourceId: dataSourceId!,
            dataSourceName,
            databaseName,
            schemaName,
            tableName: tableName!,
            type: ImportExportType.EXPORT,
          });
        },
        discard:
          (treeNodeType === TreeNodeType.DATABASE && supportSchema) || !canImportExport || !canExportData(databaseType),
      },

      // Import data.
      [OperationColumn.ImportData]: {
        text: i18n('workspace.menu.importData'),
        icon: 'icon-upload',
        handle: () => {
          setImportExportDataBoundInfo({
            dataSourceId: dataSourceId!,
            dataSourceName,
            databaseName,
            schemaName,
            tableName: tableName!,
            type: ImportExportType.IMPORT,
          });
        },
        discard:
          (treeNodeType === TreeNodeType.DATABASE && supportSchema) || !canImportExport || !canImportData(databaseType),
        requiredOperations: ['INSERT'],
      },

      [OperationColumn.GenerateJavaClass]: {
        text: i18n('workspace.menu.generateJavaClass'),
        icon: 'icon-java',
        handle: () => {
          generateJavaClass({
            dataSourceId: dataSourceId!,
            dataSourceName,
            databaseName,
            schemaName,
            tableName: tableName!,
          });
        },
        discard: !canImportExport || !canGenerateJavaClass(databaseType),
      },

      // Truncate the table.
      [OperationColumn.TruncateTable]: {
        text: i18n('workspace.menu.truncateTable'),
        icon: 'icon-clear-table',
        handle: () => {
          openUnifiedConfirmationModal({
            title: i18n('common.text.clearConfirm'),
            headerIconCode: 'icon-clear-table',
            content: i18n('workspace.menu.truncateTable.tip', tableName!),
            needDoubleConfirmText: i18n('workspace.tree.clear.tip'),
            onOk: () => {
              return sqlService.truncateTable({
                dataSourceId: dataSourceId!,
                databaseName: databaseName!,
                schemaName,
                tableName: tableName!,
              });
            },
          });
        },
        requiredOperations: ['TRUNCATE'],
      },

      // Copy the table.
      [OperationColumn.CopyTable]: {
        text: i18n('workspace.menu.copyTable'),
        icon: 'icon-copy-table',
        children: [
          {
            text: i18n('workspace.menu.copyStructure'),
            requiredOperations: ['CREATE'],
            handle: () => {
              sqlService
                .copyTable({
                  dataSourceId: dataSourceId!,
                  databaseName: databaseName!,
                  schemaName,
                  tableName: tableName!,
                  copyData: false,
                })
                .then(() => {
                  const parentNode = getParentNode(treeNodeData.key, treeData);
                  if (parentNode) {
                    handleLoadData(parentNode, {
                      refresh: true,
                    });
                  }
                });
            },
          },
          {
            text: i18n('workspace.menu.copyStructureData'),
            requiredOperations: ['CREATE', 'SELECT', 'INSERT'],
            handle: () => {
              sqlService
                .copyTable({
                  dataSourceId: dataSourceId!,
                  databaseName: databaseName!,
                  schemaName,
                  tableName: tableName!,
                  copyData: true,
                })
                .then(() => {
                  const parentNode = getParentNode(treeNodeData.key, treeData);
                  if (parentNode) {
                    handleLoadData(parentNode, {
                      refresh: true,
                    });
                  }
                });
            },
          },
        ],
        requiredOperations: ['CREATE'],
      },
    };

    const generateChildren = (children: IOperationColumnConfigItem[], type, lastKey) => {
      if (!children.length) return undefined;
      const finalList: IRightClickMenu[] = [];
      children?.forEach((t, i) => {
        if (!t.discard && allowsResourceOperations(operationCapabilities, t.requiredOperations)) {
          finalList.push({
            key: `${lastKey}-${i}`,
            onClick: t.handle,
            type,
            shortcutAction: t.shortcutAction,
            keepOpen: t.keepOpen,
            danger: t.danger,
            labelProps: {
              icon: t.icon,
              label: t.text,
              renderLabel: t.renderLabel,
            },
            children: generateChildren(t.children || [], type, `${lastKey}-${i}`),
          });
        }
      });
      return finalList;
    };

    // Build the context menu from the configuration.
    const finalList: IRightClickMenu[] = [];
    const operationList = handleMenuOptions(treeNodeType, extraParams.databaseType);
    (operationList || []).forEach((t, i) => {
      // Add separators directly to the list.
      if (t === OperationColumn.Divider) {
        // Avoid a leading separator or consecutive separators.
        if (finalList.length > 0 && finalList[finalList.length - 1].type !== OperationColumn.Divider) {
          finalList.push({
            key: `divider-${i}`,
            type: OperationColumn.Divider,
            labelProps: { icon: '', label: '' },
          });
        }
        return;
      }

      const concrete = operationColumnConfig[t];

      if (
        concrete &&
        !concrete.discard &&
        allowsResourceOperations(operationCapabilities, concrete.requiredOperations)
      ) {
        finalList.push({
          key: i,
          onClick: concrete?.handle,
          type: t,
          shortcutAction: concrete.shortcutAction,
          doubleClickTrigger: concrete.doubleClickTrigger,
          keepOpen: concrete.keepOpen,
          danger: concrete.danger || isDangerousTreeOperation(t),
          labelProps: {
            icon: concrete?.icon,
            label: concrete?.text,
            renderLabel: concrete?.renderLabel,
          },
          children: generateChildren(concrete?.children || [], t, i),
        });
      }
    });

    // Remove the trailing separator.
    while (finalList.length > 0 && finalList[finalList.length - 1].type === OperationColumn.Divider) {
      finalList.pop();
    }

    return finalList;
  };

  return { createRightClickMenu };
};
