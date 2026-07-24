import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useStyles } from './style';
import ResultSetToolbar, { ResultSetToolbarRef, ToolbarOperationType } from '../ResultSetToolbar';
import ScreeningResult, { IScreeningResultRef } from '../ScreeningResult';
import FESearch, { FESearchRef } from '../FESearch';
import ResultSetTable, { IResultSetSelection, ResultSetTableRef } from '../ResultSetTable';
import useSqlExecutor from '@/hooks/useSqlExecutor';
import executeSql from '@/service/executeSql';
import SQLPreviewExecute, { SQLPreviewExecuteRef } from '../SQLPreviewExecute';
import ViewData, { ViewDataRef } from '../ViewData';
import RowDetail, { IChangeDataParams, RowDetailRef } from '../RowDetail';
import SelectionAggregates from '../SelectionAggregates';
import { IManageResultData } from '@/typings';
import { Button, Spin, Tabs, Tooltip } from 'antd';
import i18n from '@/i18n';
import { copyToClipboard } from '@/utils';
import StatusBar, { StatusBarRef } from '../StatusBar';
import { getBlankCreateCellValue, transformOperations } from '@/blocks/SearchResult/utils';
import MonacoEditorErrorTips from '@/components/SQLEditor/components/MonacoEditorErrorTips';
import { v4 as uuidv4 } from 'uuid';
import { ITableInstance } from '@/blocks/CanvasTable/typings';
import {
  ShortcutAction,
  ShortcutOverrides,
  getEffectiveShortcutConfigMap,
  isShortcutEventMatch,
} from '@/constants/shortcut';
import { useGlobalStore } from '@/store/global';
import { useAIStore } from '@/store/ai';
import { useWorkspaceStore } from '@/store/workspace';
import {
  getWorkspaceResultInspectorCode,
  shouldClearInactiveResultInspector,
  WORKSPACE_RESULT_INSPECTOR_PORTAL_ID,
} from '@/store/workspace/utils/resultInspector';
import { staticMessage } from '@chat2db/ui';
import { X } from 'lucide-react';

interface IProps {
  resultData: IManageResultData;
  active: boolean;
  viewTable?: boolean;
}

type InspectorTab = 'row' | 'value' | 'aggregates';

export default memo<IProps>(
  (props) => {
    const { viewTable } = props;
    const { styles, cx } = useStyles();
    const { executeSQL, stopExecuteSQL, executing } = useSqlExecutor();
    const [resultData, setResultData] = useState<IManageResultData>(props.resultData);
    const resultSetToolbarRef = useRef<ResultSetToolbarRef>(null);
    const screenResultRef = useRef<IScreeningResultRef>(null);
    const resultSetTableRef = useRef<ResultSetTableRef>(null);
    const [hasOperationRecord, setHasOperationRecord] = useState(false);
    const sqlPreviewExecuteRef = useRef<SQLPreviewExecuteRef>(null);
    const viewDataRef = useRef<ViewDataRef>(null);
    const rowDetailRef = useRef<RowDetailRef>(null);
    const statusBarRef = useRef<StatusBarRef>(null);
    const [executeErrorMessage, setExecuteErrorMessage] = useState<string | null>(null);
    const [tableInstance, setTableInstance] = useState<ITableInstance | null>(null);
    const [showFESearch, setShowFESearch] = useState(true);
    const [activeFilterCount, setActiveFilterCount] = useState(0);
    const resultSetRef = useRef<HTMLDivElement>(null);
    const searchAreaId = useMemo(() => uuidv4(), []);
    const feSearchRef = useRef<FESearchRef>(null);
    const [orderByText, setOrderByText] = useState<string>('');
    const [submitLoading, setSubmitLoading] = useState(false);
    const [inspectorTab, setInspectorTab] = useState<InspectorTab>('row');
    const [inspectorPortalTarget, setInspectorPortalTarget] = useState<HTMLElement | null>(null);
    const [selectedValues, setSelectedValues] = useState<unknown[]>([]);
    const [selectedRowCount, setSelectedRowCount] = useState(0);
    const [lastActiveCell, setLastActiveCell] = useState<IResultSetSelection['activeCell']>();
    const currentWorkspaceExtend = useWorkspaceStore((state) => state.currentWorkspaceExtend);
    const inspectorExtendCode = useMemo(() => getWorkspaceResultInspectorCode(searchAreaId), [searchAreaId]);
    const inspectorOpen = currentWorkspaceExtend === inspectorExtendCode;
    const shortcutOverrides = useGlobalStore((s) => s.shortcutOverrides);
    const shortcutConfig = useMemo(
      () => getEffectiveShortcutConfigMap(shortcutOverrides as ShortcutOverrides),
      [shortcutOverrides],
    );

    useEffect(() => {
      setResultData(props.resultData);
    }, [props.resultData]);

    useEffect(() => {
      setSelectedValues([]);
      setSelectedRowCount(0);
      setLastActiveCell(undefined);
      const workspaceStore = useWorkspaceStore.getState();
      if (workspaceStore.currentWorkspaceExtend === inspectorExtendCode) {
        workspaceStore.setCurrentWorkspaceExtend(null);
      }
    }, [inspectorExtendCode, resultData]);

    const closeInspector = useCallback(() => {
      const workspaceStore = useWorkspaceStore.getState();
      if (workspaceStore.currentWorkspaceExtend === inspectorExtendCode) {
        workspaceStore.setCurrentWorkspaceExtend(null);
      }
    }, [inspectorExtendCode]);

    useEffect(() => {
      const workspaceStore = useWorkspaceStore.getState();
      if (
        shouldClearInactiveResultInspector(
          workspaceStore.currentWorkspaceExtend,
          inspectorExtendCode,
          props.active,
        )
      ) {
        workspaceStore.setCurrentWorkspaceExtend(null);
      }
    }, [inspectorExtendCode, props.active]);

    const activateInspector = useCallback(
      (tab: InspectorTab) => {
        setInspectorTab(tab);
        useAIStore.getState().setShowPanel(false);
        const workspaceStore = useWorkspaceStore.getState();
        workspaceStore.setCurrentWorkspaceExtend(inspectorExtendCode);
        workspaceStore.togglePanelRight(true);
      },
      [inspectorExtendCode],
    );

    useEffect(() => closeInspector, [closeInspector]);

    useLayoutEffect(() => {
      if (!inspectorOpen) {
        setInspectorPortalTarget(null);
        return undefined;
      }

      const resolvePortalTarget = () => {
        setInspectorPortalTarget(document.getElementById(WORKSPACE_RESULT_INSPECTOR_PORTAL_ID));
      };
      resolvePortalTarget();
      const animationFrame = window.requestAnimationFrame(resolvePortalTarget);
      return () => window.cancelAnimationFrame(animationFrame);
    }, [inspectorOpen]);

    // Only resultData changes here. Database metadata is stable, and the toolbar controls pagination.
    const handleExecuteSQL = useCallback(
      ({ pageNo: _pageNo }: { pageNo?: number } = {}) => {
        // Clear operation records
        resultSetTableRef.current?.operationRecordUtils?.clearOperationRecord?.();
        // Do not execute before the result toolbar is mounted.
        if (!resultSetToolbarRef.current) return;
        // If there is no executeSqlParams, the execution information is not known, and no execution is performed.
        if (!resultData.executeSqlParams) return;
        // Get the current paging
        const { pageNo, pageSize } = resultSetToolbarRef.current.getPagingParams();
        const executeSqlParams = {
          ...resultData.executeSqlParams,
          pageSize,
          pageNo: _pageNo || pageNo,
        };
        // Filter conditions when viewing tables
        if (viewTable) {
          executeSqlParams.sql = screenResultRef.current?.getJointSQL() || '';
        }
        executeSQL(executeSqlParams).then((data) => {
          setExecuteErrorMessage(null);
          if (data.length) {
            const curResult = data.filter((item) => item.resultSetId === executeSqlParams.resultSetId)?.[0];
            if (curResult) {
              setResultData({
                ...curResult,
                executeSqlParams: {
                  ...resultData.executeSqlParams,
                  sql: curResult.originalSql,
                },
              });
            } else {
              setExecuteErrorMessage(data[0].message || '');
            }
          }
        });
      },
      [resultData],
    );

    const handleSearch = useCallback(() => {
      handleExecuteSQL({ pageNo: 1 });
    }, [handleExecuteSQL]);

    const completeActiveEditor = useCallback(async () => {
      await Promise.resolve(resultSetTableRef.current?.tableInstance?.completeEditCell?.());
      await new Promise((resolve) => setTimeout(resolve, 0));
    }, []);

    const handleUpdateSubmit = useCallback(() => {
      completeActiveEditor().then(() => {
        const operations = resultSetTableRef.current?.operationRecordUtils?.getOperationChangeDetail();
        sqlPreviewExecuteRef.current?.handleExecuteSql({
          operations: transformOperations(operations, resultData.headerList),
          resultData,
          callback: setSubmitLoading,
        });
      });
    }, [completeActiveEditor, resultData]);

    useEffect(() => {
      const handleKeyDown = (e: KeyboardEvent) => {
        if (e.code === 'KeyC' && e.shiftKey && (e.metaKey || e.ctrlKey)) {
          if (statusBarRef.current?.copyActiveMetric()) {
            e.preventDefault();
            e.stopPropagation();
          }
          return;
        }
        if (e.key === 'Escape') {
          feSearchRef.current?.close();
          return;
        }
        if (isShortcutEventMatch(e, shortcutConfig[ShortcutAction.ResultSearch].binding)) {
          setShowFESearch(true);
          e.preventDefault();
          setTimeout(() => {
            feSearchRef.current?.focus();
          });
        }
        if (isShortcutEventMatch(e, shortcutConfig[ShortcutAction.ResultSubmit].binding)) {
          e.preventDefault();
          if (hasOperationRecord) {
            handleUpdateSubmit();
          }
        }
        if (isShortcutEventMatch(e, shortcutConfig[ShortcutAction.ResultRefresh].binding)) {
          e.preventDefault();
          handleSearch();
        }
      };
      const resultSetContent = resultSetRef.current;
      resultSetContent?.addEventListener('keydown', handleKeyDown);
      return () => {
        resultSetContent?.removeEventListener('keydown', handleKeyDown);
      };
    }, [hasOperationRecord, handleSearch, handleUpdateSubmit, shortcutConfig]);

    // SQL execution successful
    const handleExecuteSuccess = useCallback(() => {
      setExecuteErrorMessage(null);
      handleExecuteSQL();
    }, [handleExecuteSQL]);

    // SQL execution failed
    const handleExecuteError = useCallback((errorMessage) => {
      setExecuteErrorMessage(errorMessage);
    }, []);

    // Close SQL execution failure prompt
    const handleCloseExecuteErrorMessage = useCallback(() => {
      setExecuteErrorMessage(null);
    }, []);

    const handleAddBlankRow = useCallback(() => {
      // creates blank rows of data
      const blankRow: any = {};
      const uuid = uuidv4();
      resultData.headerList.forEach((item, index) => {
        if (index === 0) {
          blankRow.CHAT2DB_ROW_NUMBER = uuid;
          return;
        }
        blankRow[index] = getBlankCreateCellValue(item);
      });
      resultSetTableRef.current?.operationRecordUtils?.handleAddBlankRow(blankRow, uuid);
    }, [resultData.headerList]);

    const handleDeleteRow = useCallback(() => {
      resultSetTableRef.current?.operationRecordUtils?.handleDeleteRow();
    }, []);

    const handleRevocation = useCallback(() => {
      resultSetTableRef.current?.operationRecordUtils?.handleRevocation();
    }, []);

    const handleOperationChange = useCallback((_hasOperationRecord) => {
      setHasOperationRecord(_hasOperationRecord);
    }, []);

    const handleViewSQl = () => {
      completeActiveEditor().then(() => {
        const operations = resultSetTableRef.current?.operationRecordUtils?.getOperationChangeDetail();
        sqlPreviewExecuteRef.current?.handleViewSQL({
          operations: transformOperations(operations, resultData.headerList),
          resultData,
        });
      });
    };

    const handleToolbarOperation = (type: ToolbarOperationType) => {
      switch (type) {
        // execute SQL
        case ToolbarOperationType.EXECUTE_SQL:
          handleExecuteSQL();
          break;
        // Add blank line
        case ToolbarOperationType.ADD_BLANK_ROW:
          handleAddBlankRow();
          break;
        // Delete row
        case ToolbarOperationType.DELETE_ROW:
          handleDeleteRow();
          break;
        // Cancel
        case ToolbarOperationType.REVOKE:
          handleRevocation();
          break;
        // View SQL
        case ToolbarOperationType.VIEW_SQL:
          handleViewSQl();
          break;
        // update submission
        case ToolbarOperationType.UPDATE_SUBMIT:
          handleUpdateSubmit();
          break;
        default:
          break;
      }
    };

    const openValueInspector = useCallback(
      (params) => {
        if (!params) {
          return;
        }
        const record = params.tableInstance.getRecordByCell(params.col, params.row);
        const nextParams = {
          ...params,
          rowId: params.rowId ?? record?.CHAT2DB_ROW_NUMBER,
          cellMeta: params.cellMeta ?? record?.__CHAT2DB_CELL_META__?.[params.col],
        };
        setLastActiveCell({
          tableInstance: params.tableInstance,
          col: params.col,
          row: params.row,
          rowId: nextParams.rowId,
        });
        activateInspector('value');
        setTimeout(() => {
          viewDataRef.current?.openPanel({
            ...nextParams,
            canEdit: !!resultData?.canEdit,
            operationRecordUtils: resultSetTableRef.current?.operationRecordUtils,
          });
        }, 0);
      },
      [activateInspector, resultData?.canEdit],
    );

    const openRowInspector = useCallback((params) => {
      if (!params) {
        return;
      }
      const record = params.tableInstance.getRecordByCell(params.col, params.row);
      setLastActiveCell({
        tableInstance: params.tableInstance,
        col: params.col,
        row: params.row,
        rowId: params.rowId ?? record?.CHAT2DB_ROW_NUMBER,
      });
      activateInspector('row');
      setTimeout(() => rowDetailRef.current?.openPanel(params), 0);
    }, [activateInspector]);

    const onTableOperationUtils = useMemo(() => {
      return {
        // Copy as insert or update or where statement
        copyGenerateSQL: (operations: any) => {
          executeSql
            .getCopyUpdateDataSql({
              ...(resultData.executeSqlParams || {}),
              tableName: resultData.tableName,
              headerList: resultData.headerList,
              operations: transformOperations(operations, resultData.headerList),
            })
            .then((sql) => {
              copyToClipboard(sql);
            });
        },
        copyGenerateInValues: (operations: any) => {
          executeSql
            .getCopyInValuesSql({
              ...(resultData.executeSqlParams || {}),
              headerList: resultData.headerList,
              sourceType: 'RESULT_SET',
              operations: transformOperations(operations, resultData.headerList),
            })
            .then((sql) => {
              if (copyToClipboard(sql)) {
                staticMessage.success(i18n('common.button.copySuccessfully'));
              } else {
                staticMessage.warning(i18n('common.sqlInValues.copyFailed'));
              }
            });
        },
        handleViewUpdateData: (params) => {
          openValueInspector(params);
        },
        handleViewRowDetail: (params) => {
          openRowInspector(params);
        },
      };
    }, [openRowInspector, openValueInspector, resultData]);

    const handleCloseFESearch = useCallback(() => {
      setShowFESearch(false);
    }, []);

    const handleClearAllFilters = useCallback(() => {
      resultSetTableRef.current?.clearAllFilters?.();
    }, []);

    const handleRowDetailChangeData = useCallback((params: IChangeDataParams) => {
      const { tableInstance: targetTableInstance, col, row, field, value } = params;
      const originData = targetTableInstance.getRecordByCell(col, row);
      if (
        params.rowId !== undefined &&
        String(originData?.CHAT2DB_ROW_NUMBER) !== String(params.rowId)
      ) {
        return;
      }
      const currentValue = targetTableInstance.getCellOriginValue(col, row);
      if (!originData || originData.__CHAT2DB_CELL_META__?.[col]?.largeValue || currentValue === value) {
        return;
      }

      originData[col] = value;
      targetTableInstance.changeCellValue(col, row, value);
      resultSetTableRef.current?.operationRecordUtils?.handleCellValueChange({
        field: String(targetTableInstance.getHeaderField(col, row) || field),
        rowId: originData.CHAT2DB_ROW_NUMBER,
        rawValue: currentValue,
        currentValue,
        changedValue: value,
      });
    }, []);

    const handleSelectionChange = useCallback(
      (selection: IResultSetSelection) => {
        setSelectedValues(selection.values);
        setSelectedRowCount(selection.rowCount);
        if (!selection.activeCell) {
          setLastActiveCell(undefined);
          closeInspector();
          return;
        }
        setLastActiveCell(selection.activeCell);
        if (inspectorOpen) {
          if (inspectorTab === 'row') {
            rowDetailRef.current?.openPanel(selection.activeCell);
          } else if (inspectorTab === 'value') {
            openValueInspector(selection.activeCell);
          }
        }
      },
      [closeInspector, inspectorOpen, inspectorTab, openValueInspector],
    );

    const handleInspectorTabChange = useCallback(
      (key: string) => {
        const nextTab = key as InspectorTab;
        setInspectorTab(nextTab);
        if (nextTab === 'aggregates' || !lastActiveCell) {
          return;
        }
        setTimeout(() => {
          const record = lastActiveCell.tableInstance.getRecordByCell(lastActiveCell.col, lastActiveCell.row);
          if (
            lastActiveCell.rowId !== undefined &&
            String(record?.CHAT2DB_ROW_NUMBER) !== String(lastActiveCell.rowId)
          ) {
            setLastActiveCell(undefined);
            closeInspector();
            return;
          }
          if (nextTab === 'row') {
            rowDetailRef.current?.openPanel(lastActiveCell);
            return;
          }
          viewDataRef.current?.openPanel({
            ...lastActiveCell,
            cellMeta: record?.__CHAT2DB_CELL_META__?.[lastActiveCell.col],
            canEdit: !!resultData?.canEdit,
            operationRecordUtils: resultSetTableRef.current?.operationRecordUtils,
          });
        }, 0);
      },
      [closeInspector, lastActiveCell, resultData?.canEdit],
    );

    const showAllAggregates = useCallback(() => {
      activateInspector('aggregates');
    }, [activateInspector]);

    useEffect(() => {
      if (!tableInstance) {
        return;
      }

      const resizeTimer = window.setTimeout(() => {
        if (resultSetTableRef.current?.tableInstance === tableInstance) {
          tableInstance.resize?.();
        }
      }, 0);

      return () => window.clearTimeout(resizeTimer);
    }, [inspectorOpen, tableInstance]);

    return (
      <>
        <div tabIndex={0} className={cx(styles.container)} ref={resultSetRef} id={searchAreaId}>
          {(executing || submitLoading) && (
            <div className={styles.tableLoading}>
              <Spin />
              {executing && (
                <div className={styles.stopExecuteSql} onClick={stopExecuteSQL}>
                  {i18n('common.button.cancelRequest')}
                </div>
              )}
            </div>
          )}
          <>
            <ResultSetToolbar
              ref={resultSetToolbarRef}
              handleToolbarOperation={handleToolbarOperation}
              hasOperationRecord={hasOperationRecord}
              resultData={resultData}
              activeFilterCount={activeFilterCount}
              onClearAllFilters={handleClearAllFilters}
            />
            {viewTable && (
              <ScreeningResult
                ref={screenResultRef}
                onSearch={handleSearch}
                originalSql={props.resultData.originalSql}
                promptWord={resultData.headerList}
                orderByText={orderByText}
                databaseType={resultData.executeSqlParams?.databaseType}
              />
            )}
            {showFESearch && (
              <FESearch
                ref={feSearchRef}
                searchAreaId={searchAreaId}
                onClose={handleCloseFESearch}
                tableInstance={tableInstance}
              />
            )}
            <div className={styles.resultSetContent}>
              <div className={styles.resultSetTableContainer}>
                <ResultSetTable
                  tableInstance={tableInstance}
                  setTableInstance={setTableInstance}
                  ref={resultSetTableRef}
                  resultData={resultData}
                  setOrderByText={setOrderByText}
                  onOperationChange={handleOperationChange}
                  onTableOperationUtils={onTableOperationUtils}
                  onFilterCountChange={setActiveFilterCount}
                  onSelectionChange={handleSelectionChange}
                />
              </div>
              {inspectorOpen && inspectorPortalTarget &&
                createPortal(
                  <aside className={styles.inspector}>
                    <Tabs
                      className={styles.inspectorTabs}
                      size="small"
                      activeKey={inspectorTab}
                      onChange={handleInspectorTabChange}
                      tabBarExtraContent={
                        <Tooltip title={i18n('common.button.close')}>
                          <Button
                            type="text"
                            size="small"
                            className={styles.inspectorClose}
                            aria-label={i18n('common.button.close')}
                            icon={<X size={15} strokeWidth={1.75} />}
                            onClick={closeInspector}
                          />
                        </Tooltip>
                      }
                      items={[
                        {
                          key: 'row',
                          label: i18n('common.resultInspector.record'),
                          children: (
                            <RowDetail
                              ref={rowDetailRef}
                              resultData={resultData}
                              onChangeData={handleRowDetailChangeData}
                              onViewData={openValueInspector}
                            />
                          ),
                        },
                        {
                          key: 'value',
                          label: i18n('common.resultInspector.value'),
                          children: <ViewData ref={viewDataRef} />,
                        },
                        {
                          key: 'aggregates',
                          label: i18n('common.resultInspector.aggregates'),
                          children: (
                            <SelectionAggregates
                              selectedValues={selectedValues}
                              selectedRowCount={selectedRowCount}
                            />
                          ),
                        },
                      ]}
                    />
                  </aside>,
                  inspectorPortalTarget,
                )}
            </div>
            <StatusBar
              ref={statusBarRef}
              resultData={resultData}
              selectedValues={selectedValues}
              selectedRowCount={selectedRowCount}
              onShowAllAggregates={showAllAggregates}
            />
          </>
          <MonacoEditorErrorTips errorMessage={executeErrorMessage} handleClose={handleCloseExecuteErrorMessage} />
        </div>
        <SQLPreviewExecute
          onExecuteError={handleExecuteError}
          onExecuteSuccess={handleExecuteSuccess}
          ref={sqlPreviewExecuteRef}
        />
      </>
    );
  },
  (prevProps, nextProps) =>
    prevProps.active === nextProps.active &&
    prevProps.resultData === nextProps.resultData &&
    prevProps.viewTable === nextProps.viewTable,
);
