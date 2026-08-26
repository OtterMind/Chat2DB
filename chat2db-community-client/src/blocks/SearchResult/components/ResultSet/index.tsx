import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useStyles } from './style';
import ResultSetToolbar, { ToolbarOperationType } from '../ResultSetToolbar';
import { buildResultPageExecuteParams, resolveResultPaging, ResultPaging } from './pagination';
import ScreeningResult, { IScreeningResultRef } from '../ScreeningResult';
import FESearch, { FESearchRef } from '../FESearch';
import ResultSetTable, { IResultSetSelection, ResultSetTableRef } from '../ResultSetTable';
import useSqlExecutor from '@/hooks/useSqlExecutor';
import executeSql from '@/service/executeSql';
import SQLPreviewExecute, { SQLPreviewExecuteRef } from '../SQLPreviewExecute';
import ViewData, { ViewDataRef } from '../ViewData';
import RowDetail, { IChangeDataParams, IViewDataParams, RowDetailRef } from '../RowDetail';
import SelectionAggregates from '../SelectionAggregates';
import { IExecuteSqlParams, IManageResultData } from '@/typings';
import { Button, Tabs, Tooltip } from 'antd';
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
  createResultInspectorModeStorageKey,
  getResultInspectorTabs,
  getResultInspectorPreferenceStorage,
  getWorkspaceResultInspectorCode,
  persistResultInspectorMode,
  readResultInspectorMode,
  ResultInspectorMode,
  ResultInspectorTab,
  shouldClearInactiveResultInspector,
  subscribeResultInspectorMode,
  toggleResultInspectorMode,
  WORKSPACE_RESULT_INSPECTOR_PORTAL_ID,
} from '@/store/workspace/utils/resultInspector';
import { Modal, staticMessage } from '@chat2db/ui';
import { PanelRight, SquareSquare, X } from 'lucide-react';
import {
  applyResultSearchVisibilityAction,
  getResultSearchVisibility,
  RESULT_SEARCH_VISIBLE_BY_DEFAULT,
} from './resultSearchVisibility';
import {
  getResultCellMetaAtTableColumn,
  getResultFieldAtTableColumn,
} from '../ResultSetTable/columnState';
import { resolveResultInspectorActiveCell } from '../ResultSetTable/selectionState';
import { areResultCellValuesEquivalent } from './inspectorState';
import SqlExecutionLoading from '@/components/SqlExecutionLoading';

interface IProps {
  resultData: IManageResultData;
  active: boolean;
  viewTable?: boolean;
  onResultPagingChange?: (resultData: IManageResultData, params: IExecuteSqlParams) => Promise<unknown> | void;
}

const RESULT_INSPECTOR_MODE_STORAGE_KEY = createResultInspectorModeStorageKey(
  'community',
  __RUNTIME_ENV__,
);

export default memo<IProps>(
  (props) => {
    const { viewTable } = props;
    const { styles, cx } = useStyles();
    const { executeSQL, stopExecuteSQL, executing, canExecuteSQL } = useSqlExecutor();
    const [resultData, setResultData] = useState<IManageResultData>(props.resultData);
    const executeRequestSequenceRef = useRef(0);
    const baseQuerySqlRef = useRef(
      props.resultData.originalSql || props.resultData.sql || props.resultData.executeSqlParams?.sql || '',
    );
    const screenResultRef = useRef<IScreeningResultRef>(null);
    const resultSetTableRef = useRef<ResultSetTableRef>(null);
    const [hasOperationRecord, setHasOperationRecord] = useState(false);
    const sqlPreviewExecuteRef = useRef<SQLPreviewExecuteRef>(null);
    const sidebarViewDataRef = useRef<ViewDataRef>(null);
    const modalViewDataRef = useRef<ViewDataRef>(null);
    const sidebarRowDetailRef = useRef<RowDetailRef>(null);
    const modalRowDetailRef = useRef<RowDetailRef>(null);
    const statusBarRef = useRef<StatusBarRef>(null);
    const [executeErrorMessage, setExecuteErrorMessage] = useState<string | null>(null);
    const [tableInstance, setTableInstance] = useState<ITableInstance | null>(null);
    const [showFESearch, setShowFESearch] = useState(RESULT_SEARCH_VISIBLE_BY_DEFAULT);
    const [activeFilterCount, setActiveFilterCount] = useState(0);
    const resultSetRef = useRef<HTMLDivElement>(null);
    const searchAreaId = useMemo(() => uuidv4(), []);
    const feSearchRef = useRef<FESearchRef>(null);
    const [orderByText, setOrderByText] = useState<string>('');
    const [submitLoading, setSubmitLoading] = useState(false);
    const [inspectorMode, setInspectorMode] = useState<ResultInspectorMode>(() =>
      readResultInspectorMode(
        getResultInspectorPreferenceStorage(),
        RESULT_INSPECTOR_MODE_STORAGE_KEY,
      ),
    );
    const [inspectorTab, setInspectorTab] = useState<ResultInspectorTab>('row');
    const [inspectorModalOpen, setInspectorModalOpen] = useState(false);
    const [inspectorPortalTarget, setInspectorPortalTarget] = useState<HTMLElement | null>(null);
    const [selectedValues, setSelectedValues] = useState<unknown[]>([]);
    const [selectedRowCount, setSelectedRowCount] = useState(0);
    const lastActiveCellRef = useRef<IResultSetSelection['activeCell']>();
    const inspectorActiveCellRef = useRef<IResultSetSelection['activeCell']>();
    const inspectorOpenInteractionRevisionRef = useRef(0);
    const currentWorkspaceExtend = useWorkspaceStore((state) => state.currentWorkspaceExtend);
    const inspectorExtendCode = useMemo(() => getWorkspaceResultInspectorCode(searchAreaId), [searchAreaId]);
    const inspectorSidebarOpen = currentWorkspaceExtend === inspectorExtendCode;
    const inspectorOpen = inspectorSidebarOpen || inspectorModalOpen;
    const shortcutOverrides = useGlobalStore((s) => s.shortcutOverrides);
    const shortcutConfig = useMemo(
      () => getEffectiveShortcutConfigMap(shortcutOverrides as ShortcutOverrides),
      [shortcutOverrides],
    );

    const setLastActiveCell = useCallback((activeCell: IResultSetSelection['activeCell']) => {
      lastActiveCellRef.current = activeCell;
    }, []);

    const setInspectorActiveCell = useCallback((activeCell: IResultSetSelection['activeCell']) => {
      inspectorActiveCellRef.current = activeCell;
      lastActiveCellRef.current = activeCell;
    }, []);

    const clearActiveCell = useCallback(() => {
      inspectorActiveCellRef.current = undefined;
      lastActiveCellRef.current = undefined;
      inspectorOpenInteractionRevisionRef.current = 0;
    }, []);

    useEffect(() => {
      setResultData(props.resultData);
    }, [props.resultData]);

    useEffect(() => {
      setSelectedValues([]);
      setSelectedRowCount(0);
      clearActiveCell();
      setInspectorModalOpen(false);
      const workspaceStore = useWorkspaceStore.getState();
      if (workspaceStore.currentWorkspaceExtend === inspectorExtendCode) {
        workspaceStore.setCurrentWorkspaceExtend(null);
      }
    }, [clearActiveCell, inspectorExtendCode, resultData]);

    const closeInspector = useCallback(() => {
      clearActiveCell();
      setInspectorModalOpen(false);
      const workspaceStore = useWorkspaceStore.getState();
      if (workspaceStore.currentWorkspaceExtend === inspectorExtendCode) {
        workspaceStore.setCurrentWorkspaceExtend(null);
      }
    }, [clearActiveCell, inspectorExtendCode]);

    useEffect(
      () =>
        subscribeResultInspectorMode((storageKey, mode) => {
          if (storageKey === RESULT_INSPECTOR_MODE_STORAGE_KEY) {
            setInspectorMode(mode);
          }
        }),
      [],
    );

    useEffect(() => {
      const workspaceStore = useWorkspaceStore.getState();
      if (!props.active) {
        setInspectorModalOpen(false);
      }
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
      (tab: ResultInspectorTab, requestedMode: ResultInspectorMode = inspectorMode) => {
        setInspectorMode(requestedMode);
        setInspectorTab(tab);
        const workspaceStore = useWorkspaceStore.getState();
        if (requestedMode === 'modal') {
          if (workspaceStore.currentWorkspaceExtend === inspectorExtendCode) {
            workspaceStore.setCurrentWorkspaceExtend(null);
          }
          setInspectorModalOpen(true);
          return;
        }
        setInspectorModalOpen(false);
        useAIStore.getState().setShowPanel(false);
        workspaceStore.setCurrentWorkspaceExtend(inspectorExtendCode);
        workspaceStore.togglePanelRight(true);
      },
      [inspectorExtendCode, inspectorMode],
    );

    useEffect(() => {
      return () => {
        const workspaceStore = useWorkspaceStore.getState();
        if (workspaceStore.currentWorkspaceExtend === inspectorExtendCode) {
          workspaceStore.setCurrentWorkspaceExtend(null);
        }
      };
    }, [inspectorExtendCode]);

    useLayoutEffect(() => {
      if (!inspectorSidebarOpen) {
        setInspectorPortalTarget(null);
        return undefined;
      }

      const resolvePortalTarget = () => {
        setInspectorPortalTarget(document.getElementById(WORKSPACE_RESULT_INSPECTOR_PORTAL_ID));
      };
      resolvePortalTarget();
      const animationFrame = window.requestAnimationFrame(resolvePortalTarget);
      return () => window.cancelAnimationFrame(animationFrame);
    }, [inspectorSidebarOpen]);

    // Only resultData changes here. Database metadata is stable, and the toolbar controls pagination.
    const handleExecuteSQL = useCallback(
      (pagingOverride?: Partial<ResultPaging>) => {
        if (!canExecuteSQL()) return;
        // Clear operation records
        resultSetTableRef.current?.operationRecordUtils?.clearOperationRecord?.();
        // If there is no executeSqlParams, the execution information is not known, and no execution is performed.
        if (!resultData.executeSqlParams) return;
        const paging = resolveResultPaging(resultData.executeSqlParams, pagingOverride);
        const executeSqlParams = buildResultPageExecuteParams(
          resultData.executeSqlParams,
          paging,
          viewTable ? screenResultRef.current?.getJointSQL() || '' : undefined,
        );
        if (props.onResultPagingChange) {
          props.onResultPagingChange(resultData, executeSqlParams);
          return;
        }
        const requestSequence = ++executeRequestSequenceRef.current;
        executeSQL(executeSqlParams).then((data) => {
          if (requestSequence !== executeRequestSequenceRef.current) return;
          setExecuteErrorMessage(null);
          if (data.length) {
            const curResult = data.filter((item) => item.resultSetId === executeSqlParams.resultSetId)?.[0];
            if (curResult) {
              setResultData({
                ...curResult,
                executeSqlParams: {
                  ...executeSqlParams,
                  sql: curResult.originalSql,
                },
              });
            } else {
              setExecuteErrorMessage(data[0].message || '');
            }
          }
        });
      },
      [canExecuteSQL, executeSQL, props.onResultPagingChange, resultData, viewTable],
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
        const resultSearchAction =
          e.key === 'Escape'
            ? 'close'
            : isShortcutEventMatch(e, shortcutConfig[ShortcutAction.ResultSearch].binding)
            ? 'open'
            : null;
        if (resultSearchAction) {
          applyResultSearchVisibilityAction(resultSearchAction, {
            close: () => feSearchRef.current?.close(),
            defer: (callback) => setTimeout(callback),
            focus: () => feSearchRef.current?.focus(),
            open: () => setShowFESearch(getResultSearchVisibility('open')),
            preventDefault: () => e.preventDefault(),
          });
          return;
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

    const handleToolbarOperation = (type: ToolbarOperationType, paging?: ResultPaging) => {
      switch (type) {
        // execute SQL
        case ToolbarOperationType.EXECUTE_SQL:
          handleExecuteSQL(paging);
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

    const isResultFieldFrozen = useCallback(
      (field: string | number | undefined) =>
        field !== undefined && !!resultSetTableRef.current?.isFieldFrozen(field),
      [],
    );

    const openValueInspector = useCallback(
      (params) => {
        if (!params) {
          return;
        }
        const field = params.field || getResultFieldAtTableColumn(params.tableInstance, params.col, params.row);
        const fieldIsFrozen = isResultFieldFrozen(field);
        const tableCol = field ? params.tableInstance.getTableIndexByField(field) : params.col;
        const recordCol = tableCol >= 1 ? tableCol : 1;
        const record = params.tableInstance.getRecordByCell(recordCol, params.row);
        const nextParams = {
          ...params,
          col: tableCol,
          field,
          rowId: params.rowId ?? record?.CHAT2DB_ROW_NUMBER,
          cellMeta:
            params.cellMeta ??
            (field
              ? record?.__CHAT2DB_CELL_META__?.[Number(field)]
              : getResultCellMetaAtTableColumn(params.tableInstance, record, recordCol, params.row)),
        };
        inspectorOpenInteractionRevisionRef.current =
          resultSetTableRef.current?.getInteractionRevision() ?? 0;
        setInspectorActiveCell({
          tableInstance: params.tableInstance,
          col: tableCol,
          row: params.row,
          rowId: nextParams.rowId,
          field,
        });
        const targetMode = inspectorMode;
        activateInspector('value', targetMode);
        setTimeout(() => {
          const targetRef = targetMode === 'sidebar' ? sidebarViewDataRef : modalViewDataRef;
          targetRef.current?.openPanel({
            ...nextParams,
            canEdit: !!resultData?.canEdit && !fieldIsFrozen,
            operationRecordUtils: resultSetTableRef.current?.operationRecordUtils,
          });
        }, 0);
      },
      [activateInspector, inspectorMode, isResultFieldFrozen, resultData?.canEdit, setInspectorActiveCell],
    );

    const openRowInspector = useCallback((params) => {
      if (!params) {
        return;
      }
      const field = params.field || getResultFieldAtTableColumn(params.tableInstance, params.col, params.row);
      const tableCol = field ? params.tableInstance.getTableIndexByField(field) : params.col;
      const record = params.tableInstance.getRecordByCell(tableCol >= 1 ? tableCol : 1, params.row);
      inspectorOpenInteractionRevisionRef.current =
        resultSetTableRef.current?.getInteractionRevision() ?? 0;
      setInspectorActiveCell({
        tableInstance: params.tableInstance,
        col: tableCol,
        row: params.row,
        rowId: params.rowId ?? record?.CHAT2DB_ROW_NUMBER,
        field,
      });
      const targetMode = inspectorMode;
      activateInspector('row', targetMode);
      setTimeout(() => {
        const targetRef = targetMode === 'sidebar' ? sidebarRowDetailRef : modalRowDetailRef;
        targetRef.current?.openPanel(params);
      }, 0);
    }, [activateInspector, inspectorMode, setInspectorActiveCell]);

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
      setShowFESearch(getResultSearchVisibility('close'));
    }, []);

    const handleClearAllFilters = useCallback(() => {
      resultSetTableRef.current?.clearAllFilters?.();
    }, []);

    const handleManageColumns = useCallback(() => {
      resultSetTableRef.current?.openColumnVisibility();
    }, []);

    const handleRowDetailChangeData = useCallback((params: IChangeDataParams) => {
      const { tableInstance: targetTableInstance, row, field, value } = params;
      const sourceField = String(field);
      if (isResultFieldFrozen(sourceField)) {
        return;
      }
      const tableCol = targetTableInstance.getTableIndexByField(sourceField);
      const originData = targetTableInstance.getRecordByCell(tableCol >= 1 ? tableCol : 1, row);
      if (
        params.rowId !== undefined &&
        String(originData?.CHAT2DB_ROW_NUMBER) !== String(params.rowId)
      ) {
        return;
      }
      const currentValue = originData?.[sourceField];
      if (
        !originData ||
        originData.__CHAT2DB_CELL_META__?.[Number(sourceField)]?.largeValue ||
        areResultCellValuesEquivalent(currentValue, value)
      ) {
        return;
      }

      originData[sourceField] = value;
      if (tableCol >= 1) {
        targetTableInstance.changeCellValue(tableCol, row, value);
      } else {
        targetTableInstance.render();
      }
      resultSetTableRef.current?.operationRecordUtils?.handleCellValueChange({
        field: sourceField,
        rowId: originData.CHAT2DB_ROW_NUMBER,
        rawValue: currentValue,
        currentValue,
        changedValue: value,
      });
    }, [isResultFieldFrozen]);

    const handleRowDetailActiveFieldChange = useCallback((params: IViewDataParams) => {
      setInspectorActiveCell({
        tableInstance: params.tableInstance,
        col: params.col,
        row: params.row,
        rowId: params.rowId,
        field: params.field,
      });
    }, [setInspectorActiveCell]);

    const handleSelectionChange = useCallback(
      (selection: IResultSetSelection) => {
        setSelectedValues(selection.values);
        setSelectedRowCount(selection.rowCount);
        const preserveInspectorForTableSelection =
          selection.cause === 'table-selection' &&
          !!inspectorActiveCellRef.current &&
          selection.interactionRevision <= inspectorOpenInteractionRevisionRef.current;
        const activeCell = resolveResultInspectorActiveCell(
          inspectorActiveCellRef.current,
          selection.activeCell,
          selection.cause,
          preserveInspectorForTableSelection,
        );
        if (selection.cause === 'table-selection' && !preserveInspectorForTableSelection) {
          inspectorActiveCellRef.current = undefined;
        }
        if (!activeCell) {
          clearActiveCell();
          closeInspector();
          return;
        }
        setLastActiveCell(activeCell);
        if (
          inspectorActiveCellRef.current &&
          (selection.cause === 'value-change' || preserveInspectorForTableSelection)
        ) {
          return;
        }
        if (inspectorOpen) {
          if (inspectorTab === 'row') {
            const targetRef = inspectorMode === 'sidebar' ? sidebarRowDetailRef : modalRowDetailRef;
            targetRef.current?.openPanel(activeCell);
          } else if (inspectorTab === 'value') {
            openValueInspector(activeCell);
          }
        }
      },
      [
        clearActiveCell,
        closeInspector,
        inspectorMode,
        inspectorOpen,
        inspectorTab,
        openValueInspector,
        setLastActiveCell,
      ],
    );

    const handleInspectorTabChange = useCallback(
      (key: string, targetMode: ResultInspectorMode = inspectorMode) => {
        const nextTab = key as ResultInspectorTab;
        setInspectorTab(nextTab);
        if (nextTab === 'aggregates') {
          return;
        }
        setTimeout(() => {
          const lastActiveCell = lastActiveCellRef.current;
          if (!lastActiveCell) {
            return;
          }
          const field =
            lastActiveCell.field ||
            getResultFieldAtTableColumn(lastActiveCell.tableInstance, lastActiveCell.col, lastActiveCell.row);
          const tableCol = field
            ? lastActiveCell.tableInstance.getTableIndexByField(field)
            : lastActiveCell.col;
          const record = lastActiveCell.tableInstance.getRecordByCell(
            tableCol >= 1 ? tableCol : 1,
            lastActiveCell.row,
          );
          if (
            lastActiveCell.rowId !== undefined &&
            String(record?.CHAT2DB_ROW_NUMBER) !== String(lastActiveCell.rowId)
          ) {
            clearActiveCell();
            closeInspector();
            return;
          }
          if (nextTab === 'row') {
            const targetRef = targetMode === 'sidebar' ? sidebarRowDetailRef : modalRowDetailRef;
            targetRef.current?.openPanel({ ...lastActiveCell, col: tableCol, field });
            return;
          }
          const targetRef = targetMode === 'sidebar' ? sidebarViewDataRef : modalViewDataRef;
          targetRef.current?.openPanel({
            ...lastActiveCell,
            col: tableCol,
            field,
            cellMeta: field ? record?.__CHAT2DB_CELL_META__?.[Number(field)] : undefined,
            canEdit: !!resultData?.canEdit && !isResultFieldFrozen(field),
            operationRecordUtils: resultSetTableRef.current?.operationRecordUtils,
          });
        }, 0);
      },
      [clearActiveCell, closeInspector, inspectorMode, isResultFieldFrozen, resultData?.canEdit],
    );

    const handleInspectorModeSwitch = useCallback(() => {
      const nextMode = toggleResultInspectorMode(inspectorMode);
      persistResultInspectorMode(
        getResultInspectorPreferenceStorage(),
        RESULT_INSPECTOR_MODE_STORAGE_KEY,
        nextMode,
      );
      activateInspector(inspectorTab, nextMode);
      handleInspectorTabChange(inspectorTab, nextMode);
    }, [activateInspector, handleInspectorTabChange, inspectorMode, inspectorTab]);

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
    }, [inspectorSidebarOpen, tableInstance]);

    const renderInspectorTabs = (mode: ResultInspectorMode) => {
      const availableTabs = getResultInspectorTabs(mode);
      const switchLabel = i18n(
        mode === 'sidebar'
          ? 'common.resultInspector.switchToModal'
          : 'common.resultInspector.switchToSidebar',
      );
      const items = [
        {
          key: 'row',
          label: i18n('common.resultInspector.record'),
          children: (
            <RowDetail
              ref={mode === 'sidebar' ? sidebarRowDetailRef : modalRowDetailRef}
              resultData={resultData}
              isFieldReadOnly={isResultFieldFrozen}
              onActiveFieldChange={handleRowDetailActiveFieldChange}
              onChangeData={handleRowDetailChangeData}
              onViewData={openValueInspector}
            />
          ),
        },
        {
          key: 'value',
          label: i18n('common.resultInspector.value'),
          children: <ViewData ref={mode === 'sidebar' ? sidebarViewDataRef : modalViewDataRef} />,
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
      ].filter((item) => availableTabs.includes(item.key as ResultInspectorTab));

      return (
        <Tabs
          className={styles.inspectorTabs}
          size="small"
          activeKey={inspectorTab}
          onChange={handleInspectorTabChange}
          tabBarExtraContent={
            <div className={styles.inspectorActions}>
              <Tooltip title={switchLabel}>
                <Button
                  type="text"
                  size="small"
                  className={styles.inspectorActionButton}
                  aria-label={switchLabel}
                  icon={
                    mode === 'sidebar'
                      ? <SquareSquare size={16} strokeWidth={1.75} />
                      : <PanelRight size={16} strokeWidth={1.75} />
                  }
                  onClick={handleInspectorModeSwitch}
                />
              </Tooltip>
              <Tooltip title={i18n('common.button.close')}>
                <Button
                  type="text"
                  size="small"
                  className={styles.inspectorActionButton}
                  aria-label={i18n('common.button.close')}
                  icon={<X size={16} strokeWidth={1.75} />}
                  onClick={closeInspector}
                />
              </Tooltip>
            </div>
          }
          items={items}
        />
      );
    };

    return (
      <>
        <div tabIndex={0} className={cx(styles.container)} ref={resultSetRef} id={searchAreaId}>
          {(executing || submitLoading) && (
            <SqlExecutionLoading onCancel={executing ? stopExecuteSQL : undefined} />
          )}
          <>
            <ResultSetToolbar
              handleToolbarOperation={handleToolbarOperation}
              hasOperationRecord={hasOperationRecord}
              resultData={resultData}
              activeFilterCount={activeFilterCount}
              onClearAllFilters={handleClearAllFilters}
              onManageColumns={handleManageColumns}
            />
            {viewTable && (
              <ScreeningResult
                ref={screenResultRef}
                onSearch={handleSearch}
                originalSql={baseQuerySqlRef.current}
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
              {inspectorSidebarOpen && inspectorPortalTarget &&
                createPortal(
                  <aside className={styles.inspector}>
                    {renderInspectorTabs('sidebar')}
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
        <Modal
          open={inspectorModalOpen}
          onCancel={closeInspector}
          width="60vw"
          maskClosable={false}
          forceRender={true}
          footer={null}
          closable={false}
        >
          <div className={styles.inspectorModalBody}>
            {renderInspectorTabs('modal')}
          </div>
        </Modal>
      </>
    );
  },
  (prevProps, nextProps) =>
    prevProps.active === nextProps.active &&
    prevProps.resultData === nextProps.resultData &&
    prevProps.viewTable === nextProps.viewTable,
);
