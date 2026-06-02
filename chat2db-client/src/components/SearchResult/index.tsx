import React, {
  useCallback,
  useEffect,
  useMemo,
  useState,
  useRef,
  forwardRef,
  ForwardedRef,
  useImperativeHandle,
  Fragment,
  createContext,
} from 'react';
import classnames from 'classnames';
import Tabs, { ITabItem } from '@/components/Tabs';
import Iconfont from '@/components/Iconfont';
// import Output from '@/components/Output';
import { IManageResultData, IResultConfig } from '@/typings';
import TableBox from './components/TableBox';
import StatusBar from './components/StatusBar';
import styles from './index.less';
import EmptyImg from '@/assets/img/empty.svg';
import i18n from '@/i18n';
import sqlServer, { IExecuteSqlParams, ICreateVirtualFKParams } from '@/service/sql';
import { v4 as uuidV4 } from 'uuid';
import { Spin, Modal, message, Button } from 'antd';
import { setPendingAiChat, setCurrentWorkspaceExtend } from '@/pages/main/workspace/store/common';

interface IProps {
  className?: string;
  sql?: string;
  executeSqlParams: any;
  concealTabHeader?: boolean;
  viewTable?: boolean;
  isActive?: boolean;
  onLocateStatement?: (result: IManageResultData) => void;
}

const defaultResultConfig: IResultConfig = {
  pageNo: 1,
  pageSize: 200,
  total: 0,
  hasNextPage: true,
};

export interface ISearchResultRef {
  handleExecuteSQL: (sql: string, options?: { scriptStartLine?: number }) => void;
}

interface IContext {
  // 这里不用ref的话，会导致切换时闪动
  activeTabId: string;
  notChangedSql: string;
}

export const Context = createContext<IContext>({} as any);

export default forwardRef((props: IProps, ref: ForwardedRef<ISearchResultRef>) => {
  const { className, sql, executeSqlParams, concealTabHeader, viewTable, isActive } = props;
  const [resultDataList, setResultDataList] = useState<IManageResultData[]>();
  const [tableLoading, setTableLoading] = useState(false);
  const controllerRef = useRef<AbortController>();
  const [activeTabId, setActiveTabId] = useState<string>('');
  const [notChangedSql, setNotChangedSql] = useState<string>('');
  const executeSqlParamsRef = useRef(executeSqlParams);

  useEffect(() => {
    executeSqlParamsRef.current = executeSqlParams;
  }, [executeSqlParams]);

  const handleAiFix = useCallback((originalSql: string, errorMessage: string) => {
    const params = executeSqlParamsRef.current;
    if (!params) {
      message.warning(i18n('common.message.noConnection'));
      return;
    }

    setPendingAiChat({
      dataSourceId: params.dataSourceId,
      databaseName: params.databaseName,
      schemaName: params.schemaName,
      tableNames: params.tableName ? [params.tableName] : null,
      message: `请修复以下SQL错误：\n\n错误信息：${errorMessage}\n\n原始SQL：\n${originalSql}`,
      promptType: 'SQL_FIX',
      ext: JSON.stringify({
        originalSql,
        errorMessage,
      }),
      onSqlFixed: (fixedSql) => {
        console.log('[SearchResult] SQL fixed:', fixedSql);
      },
    });
    setCurrentWorkspaceExtend('ai');
  }, []);

  useEffect(() => {
    if (sql) {
      handleExecuteSQL(sql);
    }
  }, [sql]);

  useImperativeHandle(ref, () => ({
    handleExecuteSQL,
  }));

  /**
   * 执行SQL
   * @param sql
   */
  const handleExecuteSQL = (_sql: string, options?: { scriptStartLine?: number }) => {
    setTableLoading(true);
    const api = viewTable ? sqlServer.viewTable : sqlServer.executeSql;

    const executeSQLParams: IExecuteSqlParams = {
      sql: _sql,
      scriptStartLine: options?.scriptStartLine || 1,
      tableName: executeSqlParams?.tableName,
      ...defaultResultConfig,
      ...executeSqlParams,
      type: executeSqlParams.databaseType, // 兼容写法，希望后端可以统一把type改成databaseType
    };

    controllerRef.current = new AbortController();
    // 获取当前SQL的查询结果
    api(executeSQLParams, {
      signal: controllerRef.current.signal,
    })
      .then((res) => {
        const sqlResult = res.map((_res) => ({
          ..._res,
          uuid: uuidV4(),
        }));

        setResultDataList(sqlResult);
        if(!notChangedSql){
          setNotChangedSql(_sql);
        }

        // 检查是否有虚拟外键建议
        const allSuggestions: IVirtualFkSuggestion[] = [];
        res.forEach((item) => {
          if (item.vkSuggestions && item.vkSuggestions.length > 0) {
            allSuggestions.push(...item.vkSuggestions);
          }
        });

        if (allSuggestions.length > 0) {
          const suggestionText = allSuggestions
            .map((s) => `${s.sourceTable}.${s.sourceColumn} → ${s.targetTable}.${s.targetColumn}`)
            .join('\n');

          Modal.confirm({
            title: i18n('workspace.erDiagram.suggestionHint'),
            content: (
              <pre style={{ maxHeight: '300px', overflow: 'auto', fontSize: '12px' }}>
                {suggestionText}
              </pre>
            ),
            width: 600,
            okText: i18n('common.button.confirm'),
            cancelText: i18n('common.button.cancel'),
            onOk: async () => {
              try {
                for (const s of allSuggestions) {
                  const params: ICreateVirtualFKParams = {
                    dataSourceId: executeSqlParams.dataSourceId,
                    databaseName: executeSqlParams.databaseName,
                    schemaName: executeSqlParams.schemaName,
                    tableName: s.sourceTable,
                    columnName: s.sourceColumn,
                    referencedTable: s.targetTable,
                    referencedColumnName: s.targetColumn,
                  };
                  await sqlServer.createVirtualForeignKey(params);
                }
                message.success(i18n('workspace.erDiagram.inferVirtualFkSuccess', allSuggestions.length));
              } catch (error) {
                message.error(i18n('workspace.erDiagram.inferVirtualFkError'));
              }
            },
          });
        }
      })
      .finally(() => {
        setTableLoading(false);
      });
  };

  const onChange = useCallback((uuid) => {
    // activeTabIdRef.current = uuid;
    setActiveTabId(uuid);
    const currentResult = resultDataList?.find((item) => item.uuid === uuid);
    if (currentResult) {
      props.onLocateStatement?.(currentResult);
    }
  }, [resultDataList, props.onLocateStatement]);

  const renderResult = (queryResultData) => {
    function renderSuccessResult() {
      const needTable = queryResultData?.headerList?.length > 1;
      return (
        <div className={styles.successResult}>
          <div className={styles.successResultContent}>
            {needTable ? (
              <TableBox
                isActive={isActive}
                tableBoxId={queryResultData.uuid}
                key={queryResultData.uuid}
                outerQueryResultData={queryResultData}
                executeSqlParams={props.executeSqlParams}
                concealTabHeader={concealTabHeader}
              />
            ) : (
              <div className={styles.updateCountBox}>
                <div className={styles.updateCount}>
                  {i18n('common.text.affectedRows', queryResultData.updateCount)}
                </div>
                <StatusBar
                  dataLength={queryResultData?.dataList?.length}
                  duration={queryResultData.duration}
                  description={queryResultData.description}
                />
              </div>
            )}
          </div>
        </div>
      );
    }
    return (
      <Fragment key={queryResultData.uuid}>
        {queryResultData.success ? (
          renderSuccessResult()
        ) : (
          <div className={styles.errorContainer}>
            <div className={styles.errorContent}>
              <div className={styles.errorMessage}>
                <Iconfont code="&#xe755;" className={styles.errorIcon} />
                <div className={styles.errorText}>{queryResultData.message}</div>
              </div>
              <Button
                type="primary"
                className={styles.aiFixButton}
                onClick={() => handleAiFix(queryResultData.originalSql || queryResultData.sql, queryResultData.message)}
              >
                <Iconfont code="&#xe6ae;" />
                {i18n('common.button.aiFix')}
              </Button>
              <Button
                style={{ marginLeft: 8 }}
                onClick={() => props.onLocateStatement?.(queryResultData)}
              >
                {`定位 SQL 行 ${queryResultData.errorLine || queryResultData.statementStartLine || '-'}`}
              </Button>
            </div>
          </div>
        )}
      </Fragment>
    );
  };

  const tabsList = useMemo(() => {
    return resultDataList?.map((queryResultData, index) => {
      return {
        prefixIcon: (
          <Iconfont
            key={index}
            className={classnames(styles[queryResultData.success ? 'successIcon' : 'failIcon'], styles.statusIcon)}
            code={queryResultData.success ? '\ue605' : '\ue87c'}
          />
        ),
        popover: queryResultData.originalSql,
        label: i18n('common.text.executionResult', index + 1),
        key: queryResultData.uuid!,
        children: renderResult(queryResultData),
      };
    });
  }, [resultDataList, isActive]);

  const onEdit = useCallback(
    (type: 'add' | 'remove', data: ITabItem[]) => {
      if (type === 'remove') {
        const newResultDataList = resultDataList?.filter((d) => {
          return data.findIndex((item) => item.key === d.uuid) === -1;
        });
        setResultDataList(newResultDataList);
      }
    },
    [resultDataList],
  );

  const stopExecuteSql = () => {
    controllerRef.current && controllerRef.current.abort();
    setResultDataList([]);
    setTableLoading(false);
  };

  return (
    <Context.Provider
      value={{
        activeTabId: activeTabId,
        notChangedSql: notChangedSql,
      }}
    >
      <div className={classnames(className, styles.searchResult)}>
        {tableLoading ? (
          <div className={styles.tableLoading}>
            <Spin />
            <div className={styles.stopExecuteSql} onClick={stopExecuteSql}>
              {i18n('common.button.cancelRequest')}
            </div>
          </div>
        ) : (
          <>
            {tabsList?.length ? (
              <Tabs
                hideAdd
                className={styles.tabs}
                onChange={onChange as any}
                onEdit={onEdit as any}
                items={tabsList}
                concealTabHeader={concealTabHeader}
                destroyInactiveTabPane={true}
              />
            ) : (
              <div className={styles.noData}>
                <img src={EmptyImg} />
                <p>{i18n('common.text.noData')}</p>
              </div>
            )}
          </>
        )}
      </div>
    </Context.Provider>
  );
});
