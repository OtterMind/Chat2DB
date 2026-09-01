import React, { memo, useRef, useState, createContext, useEffect, useMemo } from 'react';
import { Button, Modal, Segmented } from 'antd';
import i18n from '@/i18n';
import lodash from 'lodash';
import IndexList, { IIndexListRef } from './IndexList';
import ForeignKeyList, { IForeignKeyListRef } from './ForeignKeyList';
import ColumnList, { IColumnListRef } from './ColumnList';
import BaseInfo, { IBaseInfoRef } from './BaseInfo';
import sqlService, { IModifyTableSqlParams } from '@/service/sql';
import ExecuteSQL from '@/components/ExecuteSQL';
import { IEditTableInfo, IWorkspaceTab, IColumnTypes, IDatabaseBaseInfo } from '@/typings';
import { WorkspaceTabType } from '@/constants';
import LoadingContent from '@/components/Loading/LoadingContent';
import { useStyles } from './style';
import { staticMessage } from '@chat2db/ui';
import AIEntryButton from '@/components/AIEntryButton';
import { useAIStore } from '@/store/ai';
import { useWorkspaceStore } from '@/store/workspace';
import { shouldShowMysqlForeignKeyEditor } from '@/utils/databaseJudgments';
import { ForeignKeySubmitValidationError, hasForeignKeyRebuild } from './ForeignKeyList/foreignKeyEditor';

interface IProps {
  databaseBaseInfo: IDatabaseBaseInfo;
  changeTabDetails: (data: IWorkspaceTab) => void;
  tabDetails: IWorkspaceTab;
  submitCallback?: () => void;
}

interface IContext extends IProps {
  tableDetails: IEditTableInfo;
  baseInfoRef: React.RefObject<IBaseInfoRef>;
  columnListRef: React.RefObject<IColumnListRef>;
  indexListRef: React.RefObject<IIndexListRef>;
  foreignKeyListRef: React.RefObject<IForeignKeyListRef>;
  databaseSupportField: IDatabaseSupportField;
}

export const Context = createContext<IContext>({} as any);

interface IOption {
  label: string;
  value: string | number | null;
}

// column field type, data structure required by options of select component
interface IColumnTypesOption extends IColumnTypes {
  label: string;
  value: string | number | null;
}
export interface IDatabaseSupportField {
  columnTypes: IColumnTypesOption[];
  charsets: IOption[];
  collations: IOption[];
  indexTypes: IOption[];
  defaultValues: IOption[];
  engineTypes: IOption[];
}

export default memo((props: IProps) => {
  const { changeTabDetails, tabDetails, submitCallback, databaseBaseInfo } = props;
  const { dataSourceId, databaseName, schemaName, tableName } = databaseBaseInfo;
  const [tableDetails, setTableDetails] = useState<IEditTableInfo>({} as any);
  const [oldTableDetails, setOldTableDetails] = useState<IEditTableInfo>({} as any);
  const [viewSqlModal, setViewSqlModal] = useState<boolean>(false);
  const baseInfoRef = useRef<IBaseInfoRef>(null);
  const columnListRef = useRef<IColumnListRef>(null);
  const indexListRef = useRef<IIndexListRef>(null);
  const foreignKeyListRef = useRef<IForeignKeyListRef>(null);
  const [appendValue, setAppendValue] = useState<string>('');
  const aiEntryButtonRef = useRef<HTMLDivElement>(null);

  const contentList = useMemo(() => {
    const list = [
      {
        key: 'basic',
        label: i18n('editTable.tab.basicInfo'),
        content: <BaseInfo ref={baseInfoRef} />,
      },
      {
        key: 'column',
        label: i18n('editTable.tab.columnInfo'),
        content: <ColumnList ref={columnListRef} />,
      },
      {
        key: 'index',
        label: i18n('editTable.tab.indexInfo'),
        content: <IndexList ref={indexListRef} />,
      },
    ];
    if (shouldShowMysqlForeignKeyEditor(databaseBaseInfo.databaseType)) {
      list.push({
        key: 'foreignKey',
        label: i18n('editTable.tab.foreignKeyInfo'),
        content: <ForeignKeyList ref={foreignKeyListRef} />,
      });
    }
    return list;
  }, [databaseBaseInfo.databaseType, tableDetails]);

  const segmentedOptions = useMemo(() => {
    return contentList.map((item) => {
      return {
        label: item.label,
        value: item.key,
      };
    });
  }, [contentList]);

  const [currentTab, setCurrentTab] = useState<string>('column');

  const [databaseSupportField, setDatabaseSupportField] = useState<IDatabaseSupportField>({
    columnTypes: [],
    charsets: [],
    collations: [],
    indexTypes: [],
    defaultValues: [],
    engineTypes: [],
  });
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const { styles, cx } = useStyles();
  const { setShowPanel, setCascaderData } = useAIStore((state) => ({
    setShowPanel: state.setShowPanel,
    setCascaderData: state.setCascaderData,
  }));
  const setCurrentWorkspaceExtend = useWorkspaceStore((state) => state.setCurrentWorkspaceExtend);

  function changeTab(item) {
    setCurrentTab(item);
  }

  useEffect(() => {
    if (tableName) {
      getTableDetails();
    }
    getDatabaseFieldTypeList();
  }, []);

  // Get database field type list
  const getDatabaseFieldTypeList = () => {
    if (!dataSourceId || !databaseName) {
      return;
    }

    sqlService
      .getDatabaseFieldTypeList({
        dataSourceId,
        databaseName,
      })
      .then((res) => {
        const columnTypes =
          res?.columnTypes?.map((i) => {
            return {
              ...i,
              value: i.typeName,
              label: i.typeName,
            };
          }) || [];

        const charsets =
          res?.charsets?.map((i) => {
            return {
              value: i.charsetName,
              label: i.charsetName,
            };
          }) || [];

        const collations =
          res?.collations?.map((i) => {
            return {
              value: i.collationName,
              label: i.collationName,
            };
          }) || [];

        const indexTypes =
          res?.indexTypes?.map((i) => {
            return {
              value: i.typeName,
              label: i.typeName,
            };
          }) || [];

        const defaultValues =
          res?.defaultValues?.map((i) => {
            return {
              value: i.defaultValue,
              label: i.defaultValue,
            };
          }) || [];

        const engineTypes =
          res?.engineTypes?.map((i) => {
            return {
              value: i.name,
              label: i.name,
            };
          }) || [];

        setDatabaseSupportField({
          columnTypes,
          charsets,
          collations,
          indexTypes,
          defaultValues,
          engineTypes,
        });
      });
  };

  const getTableDetails = (myParams?: { tableNameProps?: string }) => {
    const { tableNameProps } = myParams || {};
    const myTableName = tableNameProps || tableName;
    if (dataSourceId && myTableName) {
      const params = {
        databaseName,
        dataSourceId,
        tableName: myTableName,
        schemaName,
        refresh: true,
      };
      setIsLoading(true);
      sqlService
        .getTableDetails(params)
        .then((res) => {
          const newTableDetails = lodash.cloneDeep(res);
          setTableDetails(newTableDetails || {});
          setOldTableDetails(res);
        })
        .finally(() => {
          setIsLoading(false);
        });
    }
  };

  function submit() {
    if (!dataSourceId || !databaseName) {
      return;
    }

    if (baseInfoRef.current && columnListRef.current && indexListRef.current) {
      let foreignKeyList = oldTableDetails.foreignKeyList || [];
      try {
        foreignKeyList = foreignKeyListRef.current?.getForeignKeyListInfo() || foreignKeyList;
      } catch (error) {
        if (error instanceof ForeignKeySubmitValidationError) {
          staticMessage.error(i18n(error.messageKey));
          return;
        }
        throw error;
      }

      const newTable = {
        ...oldTableDetails,
        ...baseInfoRef.current.getBaseInfo(),
        columnList: columnListRef.current.getColumnListInfo()!,
        indexList: indexListRef.current.getIndexListInfo()!,
        foreignKeyList,
      };

      const params: IModifyTableSqlParams = {
        databaseName,
        dataSourceId,
        schemaName,
        refresh: true,
        newTable,
      };

      if (tableName) {
        // params.tableName = tableName;
        params.oldTable = oldTableDetails;
      }
      sqlService.getModifyTableSql(params).then((res) => {
        setViewSqlModal(true);
        setAppendValue(res?.[0].sql);
      });
    }
  }

  const executeSuccessCallBack = (res) => {
    setViewSqlModal(false);
    staticMessage.success(i18n('common.text.successfulExecution'));
    const newTableName = res.tableName || baseInfoRef.current?.getBaseInfo().name;
    getTableDetails({ tableNameProps: newTableName });
    if (!tableName) {
      changeTabDetails({
        ...tabDetails,
        title: `${newTableName}`,
        type: WorkspaceTabType.EditTable,
        uniqueData: {
          ...(tabDetails.uniqueData || {}),
          tableName: newTableName,
        },
      });
    }
    // Refresh the tree after a successful save.
    submitCallback?.();
  };

  const contextValue = useMemo<IContext>(() => ({
    databaseBaseInfo,
    changeTabDetails,
    tabDetails,
    submitCallback,
    tableDetails,
    baseInfoRef,
    columnListRef,
    indexListRef,
    foreignKeyListRef,
    databaseSupportField,
  }), [databaseBaseInfo, changeTabDetails, tabDetails, submitCallback, tableDetails, databaseSupportField]);

  return (
    <Context.Provider value={contextValue}>
      <LoadingContent coverLoading isLoading={isLoading} className={styles.tableEditor}>
        <div className={styles.header}>
          <div className={styles.headerLeft}>
            <Segmented value={currentTab} onChange={changeTab} options={segmentedOptions} />
            <div
              ref={aiEntryButtonRef}
              onClick={() => {
                setCurrentWorkspaceExtend(null);
                setCascaderData('workspace', {
                  dataSourceId,
                  databaseName,
                  schemaName,
                });
                setShowPanel(true);
              }}
            >
              <AIEntryButton text="Table Copilot" />
            </div>
          </div>

          <Button type="primary" onClick={submit}>
            {i18n('common.button.save')}
          </Button>
        </div>
        <div className={styles.main}>
          {contentList.map((t, index) => {
            return (
              <div key={t.key} className={cx(styles.content, { [styles.hiddenContent]: currentTab !== t.key })}>
                {contentList[index].content}
              </div>
            );
          })}
        </div>
      </LoadingContent>
      <Modal
        title={i18n('editTable.title.sqlPreview')}
        open={!!viewSqlModal}
        onCancel={() => {
          setViewSqlModal(false);
        }}
        width="60vw"
        maskClosable={false}
        footer={false}
        destroyOnClose={true}
      >
        {hasForeignKeyRebuild(appendValue) && (
          <div style={{ marginBottom: 12 }}>
            <span>{i18n('editTable.foreignKey.rebuildWarning')}</span>
          </div>
        )}
        <ExecuteSQL
          initSql={appendValue}
          databaseBaseInfo={databaseBaseInfo}
          executeSuccessCallBack={executeSuccessCallBack}
        />
      </Modal>
    </Context.Provider>
  );
});
