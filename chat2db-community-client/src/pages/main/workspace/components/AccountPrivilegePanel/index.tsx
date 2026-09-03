import React, { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Checkbox, ConfigProvider, Empty, Form, Input, Modal, Select, Space, Spin, Tag, Tooltip, theme } from 'antd';
import { DeleteOutlined, KeyOutlined, LockOutlined, UnlockOutlined } from '@ant-design/icons';
import { staticMessage } from '@chat2db/ui';
import i18n from '@/i18n';
import SQLPreview from '@/components/SQLPreview';
import accountAdminService, {
  AccountActionType,
  AccountPrivilege,
  AccountPrivilegeScope,
  formatAccountExecuteMessage,
  type AccountCapability,
  type AccountCommand,
  type AccountExecute,
} from '@/service/accountAdmin';
import connectionService from '@/service/connection';
import sqlService from '@/service/sql';
import { DatabaseTypeCode, TreeNodeType } from '@/constants';
import { useTreeStore } from '@/store/tree';
import { IBoundInfo } from '@/typings';
import styles from './index.less';
import {
  canRevokeDirectColumnGrant,
  directColumnsFor,
  inheritedSourcesFor,
  parseAccountGrantState,
  type AccountGrantState,
} from './accountGrantState';

interface IProps {
  uniqueData?: IBoundInfo;
}

interface PreviewState {
  sql: string;
  command: AccountCommand;
  previewToken: string;
}

const defaultPrivileges = Object.values(AccountPrivilege);
const emptyGrantState: AccountGrantState = { directColumnGrants: [], inheritedPrivilegeGrants: [] };

function createPrivilegeOptions(privileges: AccountPrivilege[] = defaultPrivileges) {
  return privileges.map((privilege) => ({
    label: privilege.replace(/_/g, ' '),
    value: privilege,
  }));
}

const AccountPrivilegePanel = memo((props: IProps) => {
  const { uniqueData } = props;
  const dataSourceId = uniqueData?.dataSourceId;
  const [capability, setCapability] = useState<AccountCapability | null>(null);
  const [previewState, setPreviewState] = useState<PreviewState | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [confirmPreviewState, setConfirmPreviewState] = useState<PreviewState | null>(null);
  const [executeModalOpen, setExecuteModalOpen] = useState(false);
  const [accountModalOpen, setAccountModalOpen] = useState(false);
  const [accountActionType, setAccountActionType] = useState<AccountActionType | null>(null);
  const [executeLoading, setExecuteLoading] = useState(false);
  const [databaseOptions, setDatabaseOptions] = useState<Array<{ label: string; value: string }>>([]);
  const [tableOptions, setTableOptions] = useState<Array<{ label: string; value: string }>>([]);
  const [databaseLoading, setDatabaseLoading] = useState(false);
  const [tableLoading, setTableLoading] = useState(false);
  const [columnOptions, setColumnOptions] = useState<Array<{ label: string; value: string }>>([]);
  const [columnLoading, setColumnLoading] = useState(false);
  const [grantState, setGrantState] = useState<AccountGrantState>(emptyGrantState);
  const [grantStateLoading, setGrantStateLoading] = useState(false);
  const [form] = Form.useForm();
  const [accountForm] = Form.useForm();
  const previewRequestRef = useRef(0);
  const updateTreeNodeDataByDetail = useTreeStore((state) => state.updateTreeNodeDataByDetail);
  const accountLockSupported = capability?.accountLockSupported !== false;
  const { token } = theme.useToken();
  const submitButtonStyle = useMemo(
    () =>
      ({
        '--account-submit-bg': token.colorPrimary,
        '--account-submit-color': token.colorTextLightSolid,
      } as React.CSSProperties),
    [token.colorPrimary, token.colorTextLightSolid],
  );
  const privilegeOptions = useMemo(
    () => createPrivilegeOptions(capability?.editablePrivileges?.length ? capability.editablePrivileges : undefined),
    [capability?.editablePrivileges],
  );

  const watchedScope = Form.useWatch('scope', form);
  const watchedDatabaseName = Form.useWatch('databaseName', form);
  const watchedTableName = Form.useWatch('tableName', form);
  const watchedColumnList = Form.useWatch('columnList', form);
  const watchedPrivileges = Form.useWatch('privileges', form);
  const watchedGrantOption = Form.useWatch('grantOption', form);
  const watchedActionType = Form.useWatch('actionType', form);

  const selectedAccount =
    uniqueData?.user && uniqueData?.host
      ? {
          user: uniqueData.user,
          host: uniqueData.host,
        }
      : null;
  const singlePrivilege = watchedPrivileges?.length === 1 ? watchedPrivileges[0] : undefined;
  const directColumns = useMemo(
    () => directColumnsFor(grantState, watchedDatabaseName, watchedTableName, singlePrivilege),
    [grantState, singlePrivilege, watchedDatabaseName, watchedTableName],
  );
  const inheritedSources = useMemo(
    () =>
      Array.from(
        new Set(
          (watchedPrivileges || []).flatMap((privilege) =>
            inheritedSourcesFor(grantState, watchedDatabaseName, watchedTableName, privilege),
          ),
        ),
      ),
    [grantState, watchedDatabaseName, watchedPrivileges, watchedTableName],
  );

  const resetPreview = () => {
    setPreviewState(null);
  };

  const resetConfirmState = () => {
    setConfirmPreviewState(null);
  };

  const refreshUserTree = () => {
    if (!dataSourceId) {
      return;
    }
    updateTreeNodeDataByDetail({
      treeNodeType: TreeNodeType.DATABASE_ACCOUNTS,
      dataSourceId,
      databaseType: uniqueData?.databaseType || DatabaseTypeCode.MYSQL,
    });
  };

  const loadDatabases = () => {
    if (!dataSourceId) {
      setDatabaseOptions([]);
      return;
    }
    setDatabaseLoading(true);
    connectionService
      .getDatabaseList({ dataSourceId })
      .then((list) => {
        setDatabaseOptions(
          (list || []).map((item) => ({
            label: item.name,
            value: item.name,
          })),
        );
      })
      .catch(() => {
        setDatabaseOptions([]);
      })
      .finally(() => {
        setDatabaseLoading(false);
      });
  };

  const loadTables = (databaseName?: string) => {
    setTableOptions([]);
    if (!dataSourceId || !databaseName) {
      return;
    }
    setTableLoading(true);
    sqlService
      .getTableList({
        dataSourceId,
        databaseName,
        pageNo: 1,
        pageSize: 1000,
      })
      .then((res) => {
        setTableOptions(
          (res?.data || []).map((item) => ({
            label: item.name,
            value: item.name,
          })),
        );
      })
      .catch(() => {
        setTableOptions([]);
      })
      .finally(() => {
        setTableLoading(false);
      });
  };

  const loadColumns = (databaseName?: string, tableName?: string) => {
    setColumnOptions([]);
    if (!dataSourceId || !databaseName || !tableName) {
      return;
    }
    setColumnLoading(true);
    sqlService
      .getColumnList({ dataSourceId, databaseName, tableName, pageNo: 1, pageSize: 1000 } as never)
      .then((res) => {
        setColumnOptions(
          (res?.data || []).map((item: any) => ({
            label: item.name,
            value: item.name,
          })),
        );
      })
      .catch(() => {
        setColumnOptions([]);
      })
      .finally(() => {
        setColumnLoading(false);
      });
  };

  const loadCapability = () => {
    if (!dataSourceId) {
      return Promise.resolve(null);
    }
    return accountAdminService.capability({ dataSourceId }).then((nextCapability) => {
      setCapability(nextCapability);
      return nextCapability;
    });
  };

  const loadGrantState = useCallback(() => {
    if (!dataSourceId || !selectedAccount) {
      setGrantState(emptyGrantState);
      return Promise.resolve(emptyGrantState);
    }
    setGrantStateLoading(true);
    return accountAdminService
      .grants({ dataSourceId, user: selectedAccount.user, host: selectedAccount.host })
      .then((lines) => {
        const nextState = parseAccountGrantState(lines || []);
        setGrantState(nextState);
        return nextState;
      })
      .catch(() => {
        setGrantState(emptyGrantState);
        return emptyGrantState;
      })
      .finally(() => setGrantStateLoading(false));
  }, [dataSourceId, selectedAccount?.host, selectedAccount?.user]);

  useEffect(() => {
    if (!dataSourceId) {
      resetPreview();
      return;
    }
    loadCapability();
    loadDatabases();
    form.setFieldsValue({
      actionType: AccountActionType.GRANT_PRIVILEGE,
      scope: AccountPrivilegeScope.DATABASE,
      databaseName: uniqueData?.databaseName,
      tableName: undefined,
      privileges: [AccountPrivilege.SELECT],
      grantOption: false,
    });
    resetPreview();
  }, [dataSourceId, uniqueData?.databaseName]);

  useEffect(() => {
    resetPreview();
    resetConfirmState();
    setExecuteModalOpen(false);
    setAccountModalOpen(false);
    setAccountActionType(null);
    void loadGrantState();
  }, [dataSourceId, loadGrantState, uniqueData?.host, uniqueData?.user]);

  useEffect(() => {
    if (!dataSourceId) {
      return;
    }
    if (watchedScope === AccountPrivilegeScope.GLOBAL) {
      form.setFieldsValue({ databaseName: undefined, tableName: undefined });
      return;
    }
    if (!watchedDatabaseName && databaseOptions.length) {
      form.setFieldsValue({ databaseName: uniqueData?.databaseName || databaseOptions[0].value });
    }
  }, [watchedScope, watchedDatabaseName, databaseOptions, uniqueData?.databaseName]);

  useEffect(() => {
    if (!dataSourceId) {
      return;
    }
    if (watchedScope !== AccountPrivilegeScope.TABLE && watchedScope !== AccountPrivilegeScope.COLUMN) {
      form.setFieldsValue({ tableName: undefined });
      setTableOptions([]);
      return;
    }
    form.setFieldsValue({ tableName: undefined });
    loadTables(watchedDatabaseName);
  }, [watchedScope, watchedDatabaseName]);

  useEffect(() => {
    if (!dataSourceId) {
      return;
    }
    if (
      watchedScope === AccountPrivilegeScope.TABLE &&
      tableOptions.length &&
      (!watchedTableName || !tableOptions.some((option) => option.value === watchedTableName))
    ) {
      form.setFieldsValue({ tableName: tableOptions[0].value });
    }
  }, [watchedScope, watchedTableName, tableOptions]);

  useEffect(() => {
    if (watchedScope === AccountPrivilegeScope.COLUMN && watchedTableName) {
      form.setFieldsValue({ columnList: undefined });
      loadColumns(watchedDatabaseName, watchedTableName);
    }
  }, [watchedScope, watchedTableName]);

  useEffect(() => {
    if (!dataSourceId) {
      return;
    }
    if (watchedActionType === AccountActionType.REVOKE_PRIVILEGE) {
      form.setFieldsValue({ grantOption: false });
    }
  }, [watchedActionType]);

  useEffect(() => {
    if (
      watchedScope === AccountPrivilegeScope.COLUMN &&
      watchedActionType === AccountActionType.REVOKE_PRIVILEGE &&
      singlePrivilege &&
      directColumns.length
    ) {
      form.setFieldsValue({ columnList: directColumns });
    }
  }, [directColumns, singlePrivilege, watchedActionType, watchedScope]);

  const buildPrivilegeCommand = (): AccountCommand | null => {
    if (!dataSourceId || !selectedAccount) {
      return null;
    }
    const values = form.getFieldsValue();
    return {
      dataSourceId,
      user: selectedAccount.user,
      host: selectedAccount.host,
      actionType: values.actionType,
      scope: values.scope,
      databaseName: values.databaseName,
      tableName: values.tableName,
      privileges: values.privileges,
      columnList: values.columnList,
      grantOption: values.actionType === AccountActionType.GRANT_PRIVILEGE ? values.grantOption : false,
    };
  };

  useEffect(() => {
    const command = buildPrivilegeCommand();
    const revokeDirectAllowed =
      command?.actionType !== AccountActionType.REVOKE_PRIVILEGE ||
      command.scope !== AccountPrivilegeScope.COLUMN ||
      canRevokeDirectColumnGrant(
        grantState,
        command.databaseName,
        command.tableName,
        command.privileges,
        command.columnList,
      );
    if (!isPreviewReady(command) || !revokeDirectAllowed) {
      previewRequestRef.current += 1;
      resetPreview();
      setPreviewLoading(false);
      return;
    }

    const readyCommand = command;
    const requestId = previewRequestRef.current + 1;
    previewRequestRef.current = requestId;
    const timer = window.setTimeout(() => {
      if (previewRequestRef.current === requestId) {
        setPreviewLoading(true);
      }
      accountAdminService
        .preview(readyCommand)
        .then((preview) => {
          if (previewRequestRef.current !== requestId) {
            return;
          }
          setPreviewState({
            sql: preview.sql,
            command: readyCommand,
            previewToken: preview.previewToken,
          });
        })
        .catch(() => {
          if (previewRequestRef.current === requestId) {
            resetPreview();
          }
        })
        .finally(() => {
          if (previewRequestRef.current === requestId) {
            setPreviewLoading(false);
          }
        });
    }, 250);

    return () => {
      window.clearTimeout(timer);
    };
  }, [
    dataSourceId,
    selectedAccount?.user,
    selectedAccount?.host,
    watchedActionType,
    watchedScope,
    watchedDatabaseName,
    watchedTableName,
    watchedColumnList,
    watchedPrivileges,
    watchedGrantOption,
    grantState,
  ]);

  const previewAndConfirm = (command: AccountCommand) => {
    return accountAdminService.preview(command).then((preview) => {
      setConfirmPreviewState({
        sql: preview.sql,
        command,
        previewToken: preview.previewToken,
      });
      setExecuteModalOpen(true);
    });
  };

  const executePreview = (state: PreviewState | null) => {
    if (!state) {
      return Promise.resolve();
    }
    setExecuteLoading(true);
    return accountAdminService
      .execute({
        ...state.command,
        previewToken: state.previewToken,
      })
      .then((result) => {
        showExecutionMessage(result);
        if (result.success && dataSourceId) {
          refreshUserTree();
          void loadGrantState();
        }
        return result;
      })
      .finally(() => {
        setExecuteLoading(false);
      });
  };

  const executeConfirmCommand = () => {
    return executePreview(confirmPreviewState).then((result) => {
      if (result?.success) {
        setExecuteModalOpen(false);
      }
    });
  };

  const submitPrivilegeCommand = () => {
    form.validateFields().then(() => {
      executePreview(previewState);
    });
  };

  const openAccountModal = (actionType: AccountActionType) => {
    setAccountActionType(actionType);
    setAccountModalOpen(true);
  };

  useEffect(() => {
    if (!accountModalOpen) {
      return;
    }
    accountForm.setFieldsValue({
      user: selectedAccount?.user,
      host: selectedAccount?.host,
      password: '',
    });
  }, [accountModalOpen, selectedAccount?.user, selectedAccount?.host]);

  const submitAccountCommand = () => {
    if (!accountActionType) {
      return Promise.resolve();
    }
    return accountForm.validateFields().then((values) => {
      resetConfirmState();
      return previewAndConfirm({
        dataSourceId: dataSourceId!,
        user: values.user,
        host: values.host,
        password: values.password,
        actionType: accountActionType,
      }).then(() => {
        setAccountModalOpen(false);
      });
    });
  };

  const handleSelectedAccountCommand = (actionType: AccountActionType) => {
    if (!dataSourceId || !selectedAccount) {
      return;
    }
    resetConfirmState();
    previewAndConfirm({
      dataSourceId,
      user: selectedAccount.user,
      host: selectedAccount.host,
      actionType,
    });
  };

  if (!dataSourceId) {
    return (
      <>
        <Form form={form} style={{ display: 'none' }} />
        <Empty description={i18n('workspace.text.pleaseSelectDataSource')} />
      </>
    );
  }

  return (
    <div className={styles.container}>
      {capability && !capability.accountListReadable && (
        <Alert
          className={styles.alert}
          type="warning"
          showIcon
          message={i18n('workspace.databaseAccount.accountListUnreadable')}
        />
      )}
      <div className={styles.body}>
        {!selectedAccount ? (
          <>
            <Form form={form} style={{ display: 'none' }} />
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={i18n('workspace.databaseAccount.selectUserFromTree')}
            />
          </>
        ) : (
          <section className={styles.editorPane}>
            <Space className={styles.accountActions}>
              <Button
                disabled={!selectedAccount}
                onClick={() => openAccountModal(AccountActionType.ALTER_PASSWORD)}
              >
                {i18n('workspace.databaseAccount.changePassword')}
              </Button>
              <Tooltip
                title={
                  accountLockSupported
                    ? i18n('workspace.databaseAccount.lockAccount')
                    : i18n('workspace.databaseAccount.lockUnsupported')
                }
              >
                <Button
                  disabled={!selectedAccount || !accountLockSupported}
                  icon={<LockOutlined />}
                  onClick={() => handleSelectedAccountCommand(AccountActionType.LOCK_ACCOUNT)}
                />
              </Tooltip>
              <Tooltip
                title={
                  accountLockSupported
                    ? i18n('workspace.databaseAccount.unlockAccount')
                    : i18n('workspace.databaseAccount.lockUnsupported')
                }
              >
                <Button
                  disabled={!selectedAccount || !accountLockSupported}
                  icon={<UnlockOutlined />}
                  onClick={() => handleSelectedAccountCommand(AccountActionType.UNLOCK_ACCOUNT)}
                />
              </Tooltip>
              <Tooltip title={i18n('workspace.databaseAccount.deleteUser')}>
                <Button
                  danger
                  disabled={!selectedAccount}
                  icon={<DeleteOutlined />}
                  onClick={() => handleSelectedAccountCommand(AccountActionType.DROP_USER)}
                />
              </Tooltip>
            </Space>
            <Form form={form} layout="vertical" className={styles.privilegeForm}>
              <Form.Item name="actionType" label={i18n('workspace.databaseAccount.operation')} rules={[{ required: true }]}>
                <Select
                  options={[
                    {
                      label: i18n('workspace.databaseAccount.grantPrivilege'),
                      value: AccountActionType.GRANT_PRIVILEGE,
                    },
                    {
                      label: i18n('workspace.databaseAccount.revokePrivilege'),
                      value: AccountActionType.REVOKE_PRIVILEGE,
                    },
                  ]}
                />
              </Form.Item>
              <Form.Item name="scope" label={i18n('workspace.databaseAccount.scope')} rules={[{ required: true }]}>
                <Select
                  options={[
                    { label: i18n('workspace.databaseAccount.scopeGlobal'), value: AccountPrivilegeScope.GLOBAL },
                    { label: i18n('workspace.databaseAccount.scopeDatabase'), value: AccountPrivilegeScope.DATABASE },
                    { label: i18n('workspace.databaseAccount.scopeTable'), value: AccountPrivilegeScope.TABLE },
                    { label: i18n('workspace.databaseAccount.scopeColumn'), value: AccountPrivilegeScope.COLUMN },
                  ]}
                />
              </Form.Item>
              {watchedScope !== AccountPrivilegeScope.GLOBAL && (
                <Form.Item
                  name="databaseName"
                  label={i18n('workspace.databaseAccount.database')}
                  rules={[{ required: true }]}
                >
                  <Select
                    showSearch
                    loading={databaseLoading}
                    options={databaseOptions}
                    optionFilterProp="label"
                    placeholder={i18n('workspace.databaseAccount.selectDatabase')}
                  />
                </Form.Item>
              )}
              {(watchedScope === AccountPrivilegeScope.TABLE || watchedScope === AccountPrivilegeScope.COLUMN) && (
                <Form.Item name="tableName" label={i18n('workspace.databaseAccount.table')} rules={[{ required: true }]}>
                  <Select
                    showSearch
                    loading={tableLoading}
                    options={tableOptions}
                    optionFilterProp="label"
                    placeholder={i18n('workspace.databaseAccount.selectTable')}
                  />
                </Form.Item>
              )}
              {watchedScope === AccountPrivilegeScope.COLUMN && (
                <>
                  <Form.Item
                    name="columnList"
                    label={i18n('workspace.databaseAccount.columns')}
                    rules={[{ required: true, message: i18n('workspace.databaseAccount.columnsRequired') }]}
                  >
                    <Select
                      mode="multiple"
                      showSearch
                      loading={columnLoading || grantStateLoading}
                      options={columnOptions}
                      optionFilterProp="label"
                      placeholder={i18n('workspace.databaseAccount.selectColumns')}
                    />
                  </Form.Item>
                  {singlePrivilege && directColumns.length > 0 && (
                    <Alert
                      className={styles.alert}
                      type="info"
                      showIcon
                      message={i18n('workspace.databaseAccount.directColumnGrant')}
                      description={
                        <Space wrap>
                          <Tag>{singlePrivilege}</Tag>
                          {directColumns.map((column) => <Tag key={column}>{column}</Tag>)}
                        </Space>
                      }
                    />
                  )}
                  {inheritedSources.length > 0 && (
                    <Alert
                      className={styles.alert}
                      type="warning"
                      showIcon
                      message={i18n('workspace.databaseAccount.inheritedPrivilege')}
                      description={
                        <Space direction="vertical" size={4}>
                          <Space wrap>
                            {inheritedSources.map((source) => (
                              <Tag key={source}>{i18n(`workspace.databaseAccount.inheritedFrom${source}` as any)}</Tag>
                            ))}
                          </Space>
                          <span>{i18n('workspace.databaseAccount.noColumnDeny')}</span>
                        </Space>
                      }
                    />
                  )}
                  {watchedActionType === AccountActionType.REVOKE_PRIVILEGE &&
                    watchedPrivileges?.length > 0 &&
                    watchedColumnList?.length > 0 &&
                    !canRevokeDirectColumnGrant(
                      grantState,
                      watchedDatabaseName,
                      watchedTableName,
                      watchedPrivileges,
                      watchedColumnList,
                    ) && (
                      <Alert
                        className={styles.alert}
                        type="error"
                        showIcon
                        message={i18n('workspace.databaseAccount.inheritedOnlyRevokeBlocked')}
                      />
                    )}
                </>
              )}
              <Form.Item name="privileges" label={i18n('workspace.databaseAccount.privileges')} rules={[{ required: true }]}>
                <Select
                  mode="multiple"
                  allowClear
                  showSearch
                  className={styles.privilegeSelect}
                  options={privilegeOptions}
                  optionFilterProp="label"
                  placeholder={i18n('workspace.databaseAccount.selectPrivileges')}
                />
              </Form.Item>
              {watchedActionType !== AccountActionType.REVOKE_PRIVILEGE && (
                <Form.Item name="grantOption" valuePropName="checked">
                  <Checkbox>{i18n('workspace.databaseAccount.grantOption')}</Checkbox>
                </Form.Item>
              )}
            </Form>
            <section className={styles.previewSection}>
              <div className={styles.previewHeader}>
                <span>{i18n('workspace.databaseAccount.previewSql')}</span>
                {previewLoading && <Spin size="small" />}
              </div>
              {previewState?.sql ? (
                <SqlPreview sql={previewState.sql} />
              ) : (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={i18n('workspace.databaseAccount.previewEmpty')} />
              )}
            </section>
            <ConfigProvider wave={{ disabled: true }}>
              <Button
                block
                type="primary"
                className={styles.submitButton}
                style={submitButtonStyle}
                loading={executeLoading}
                disabled={!selectedAccount || !previewState}
                onClick={submitPrivilegeCommand}
              >
                {i18n('workspace.databaseAccount.confirmChange')}
              </Button>
            </ConfigProvider>
          </section>
        )}
      </div>
      <Modal
        title={
          <Space>
            <KeyOutlined />
            <span>
              {accountActionType ? accountActionTitle(accountActionType) : i18n('workspace.databaseAccount.userOperation')}
            </span>
          </Space>
        }
        open={accountModalOpen}
        destroyOnClose
        onOk={submitAccountCommand}
        onCancel={() => setAccountModalOpen(false)}
      >
        <Form form={accountForm} layout="vertical" className={styles.accountForm}>
          <Form.Item name="user" label={i18n('workspace.databaseAccount.user')} rules={[{ required: true }]}>
            <Input disabled />
          </Form.Item>
          <Form.Item name="host" label={i18n('workspace.databaseAccount.host')} rules={[{ required: true }]}>
            <Input disabled />
          </Form.Item>
          {accountActionType === AccountActionType.ALTER_PASSWORD && (
            <Form.Item name="password" label={i18n('workspace.databaseAccount.password')} rules={[{ required: true }]}>
              <Input.Password />
            </Form.Item>
          )}
        </Form>
      </Modal>
      <Modal
        title={i18n('workspace.databaseAccount.executeSql')}
        width={720}
        open={executeModalOpen}
        confirmLoading={executeLoading}
        maskClosable={false}
        onOk={executeConfirmCommand}
        onCancel={() => setExecuteModalOpen(false)}
      >
        <SqlPreview sql={confirmPreviewState?.sql || ''} />
      </Modal>
    </div>
  );
});

function SqlPreview({ sql }: { sql: string }) {
  return (
    <SQLPreview className={styles.sqlPreview} sql={sql} source="account-privilege-panel" foldable={false} />
  );
}

function showExecutionMessage(result: AccountExecute) {
  const content = formatAccountExecuteMessage(result);
  if (!result.success) {
    staticMessage.error(content);
    return;
  }
  staticMessage.success(content);
}

function isPreviewReady(command: AccountCommand | null): command is AccountCommand {
  if (!command?.actionType || !command.user || !command.host || !command.scope || !command.privileges?.length) {
    return false;
  }
  if (command.scope !== AccountPrivilegeScope.GLOBAL && !command.databaseName) {
    return false;
  }
  if (
    (command.scope === AccountPrivilegeScope.TABLE || command.scope === AccountPrivilegeScope.COLUMN) &&
    !command.tableName
  ) {
    return false;
  }
  if (command.scope === AccountPrivilegeScope.COLUMN && !command.columnList?.length) {
    return false;
  }
  return true;
}

function accountActionTitle(actionType: AccountActionType) {
  switch (actionType) {
    case AccountActionType.ALTER_PASSWORD:
      return i18n('workspace.databaseAccount.changePassword');
    case AccountActionType.LOCK_ACCOUNT:
      return i18n('workspace.databaseAccount.lockAccount');
    case AccountActionType.UNLOCK_ACCOUNT:
      return i18n('workspace.databaseAccount.unlockAccount');
    case AccountActionType.DROP_USER:
      return i18n('workspace.databaseAccount.deleteUser');
    default:
      return i18n('workspace.databaseAccount.userOperation');
  }
}

export default AccountPrivilegePanel;
