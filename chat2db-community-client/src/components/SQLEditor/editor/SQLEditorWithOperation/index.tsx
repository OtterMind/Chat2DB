import React, { forwardRef, useImperativeHandle, useRef, useCallback, useState, useEffect, CSSProperties } from 'react';
import { useSize } from 'ahooks';
import { useStyles } from './style';
import { OperationLine, MonacoEditorErrorTips } from '../../components';
import SQLEditor, { SQLEditorRef } from '../SQLEditor';
import RoutineOperationModals from './RoutineOperationModals';
import InitialSaveNameModal from './InitialSaveNameModal';
import { EditorSetValueType, EditorType, SQLOptType } from '../../type';
import { staticMessage } from '@chat2db/ui';
import { Modal } from 'antd';
import { IConsoleReturnExecuteSql, IBoundInfo, TreeNodeData } from '@/typings';
import { saveFileToDesktop, updateFileContent } from '@/utils/file';
import i18n from '@/i18n';
import { useSaveEditorData } from '@/components/SQLEditor/hooks/useSaveEditorData';
import { formatSql } from '../../helper/utils';
import ContextMenu from '../../components/ContextMenu';
import * as monaco from 'monaco-editor';
import { useGlobalStore } from '@/store/global';
import { ChatSourceType, QuestionType } from '@/constants/chat';
import { useWorkspaceStore } from '@/store/workspace';
import { useAIStore } from '@/store/ai';
import sqlService, { type IRoutineMigrationParams } from '@/service/sql';
import { isRoutineOperationSupportedDatabaseType, OperationColumn, TreeNodeType, WorkspaceTabType } from '@/constants';
import { EditorTableIdentifier } from '../../helper/tableIdentifier';
import { useTreeStore } from '@/store/tree';
import { isTemporaryId } from '@/utils';
import { readClipboard } from '@/utils/clipboard';
import executeSql from '@/service/executeSql';
import { parseClipboardTextToSqlInTokens } from '@/utils/sqlInClipboard';
import {
  createDatabaseObjectTreeNodeKey,
  findDatabaseObjectTreeNode as findLoadedDatabaseObjectTreeNode,
  findTreeNodeByKey,
  getCandidateTreeNodeTypes,
  getDatabaseObjectTypeByTreeNodeType,
  getParentTreeNodeTypeByObjectType,
  getTreeNodeTypeByObjectType,
} from '../../helper/databaseObjectTreeNode';
import { treeConfig } from '@/blocks/NewTree/treeConfig';
import {
  ContentDiffDenyReason,
  getContentDiffEligibility,
  getContentDiffOpenBlockReason,
} from '../../helper/contentDiffGuard';
import { normalizeSavedConsoleName, resolveInitialSavedConsoleName } from '../../helper/savedConsoleName';
import { hasUnsavedLocalFileChanges } from '@/utils/localFileEncoding';
import type { EditorCloseGuardRef } from '@/utils/editorCloseGuard';
import { buildWorkspaceObjectTabTitle } from '@/utils/workspaceObjectTabTitle';
import { getDataSourceWatermarkContent, getDataSourceWatermarkLayout } from '@/utils/dataSourceWatermark';
import { withIdentityColorAlpha } from '@/utils/dataSourceIdentity';
import { useDataSourceIdentityColor } from '@/components/DataSourceIdentityMark';
import type { EditorDataSourceState } from '@/utils/editorDataSourceLifecycle';
import {
  createDataSourceExecutionBoundInfo,
  createDataSourceExecutionSnapshot,
  type DataSourceExecutionSnapshot,
} from '@/service/dataSourceExecutionSnapshot';

export interface SQLExecutionInvocation extends IConsoleReturnExecuteSql {
  executionTarget: DataSourceExecutionSnapshot;
  dataSourceState: EditorDataSourceState;
}

interface ISQLEditorWithOperationProps {
  id: string;
  /** Call source. */
  source?: 'workspace';
  type: EditorType;

  active: boolean;
  defaultSQL?: string;
  dbInfo: IBoundInfo;
  setDBInfo: (dbInfo: IBoundInfo) => void;

  sqlFileName?: string;
  workspaceTabsTitle?: string;

  isConsole?: boolean;

  sqlActionEnabled?: boolean;
  dataSourceState?: EditorDataSourceState;
  reloadSQL?: () => Promise<string>;

  onExecuteSQL: (props: SQLExecutionInvocation) => Promise<any>;
  onChange?: (value: string) => void;
}

export interface ISQLEditorWithOperationRef extends SQLEditorRef, EditorCloseGuardRef {
  executeSQL: () => void;
}

const contextMenuDefaultConfig = {
  open: false,
  context: '',
  position: {
    left: 0,
    top: 0,
  },
};

const BASIC_EDITOR_ACTIONS = new Set<SQLOptType>([
  SQLOptType.COPY,
  SQLOptType.PASTE,
  SQLOptType.CUT,
  SQLOptType.SAVE_FILE,
  SQLOptType.SAVE_FILE_TO_DESKTOP,
  SQLOptType.OPEN_CONTENT_DIFF,
]);

const READONLY_ALLOWED_ACTIONS = new Set<SQLOptType>([
  SQLOptType.EXECUTE_ROUTINE,
  SQLOptType.APPLY_ROUTINE_DDL,
  SQLOptType.REFRESH_ROUTINE_DDL,
  SQLOptType.REVERT_ROUTINE_DDL,
  SQLOptType.OPEN_CONTENT_DIFF,
]);

export interface IContextMenuInfo {
  open: boolean;
  context: string;
  position: CSSProperties;
}

const SQLEditorWithOperation = forwardRef<ISQLEditorWithOperationRef, ISQLEditorWithOperationProps>((props, ref) => {
  const {
    id,
    defaultSQL,
    dbInfo,
    setDBInfo,
    type,
    active,
    sqlFileName,
    workspaceTabsTitle,
    isConsole = true,
    sqlActionEnabled = true,
    dataSourceState = 'available',
    reloadSQL,
    onChange,
  } = props;
  const isReadOnly = !!dbInfo.readOnly;
  const isSupportedRoutineEditor =
    isRoutineOperationSupportedDatabaseType(dbInfo.databaseType) &&
    [WorkspaceTabType.FUNCTION, WorkspaceTabType.PROCEDURE].includes(type as WorkspaceTabType);
  const { styles } = useStyles();
  const [modal, modalContextHolder] = Modal.useModal();
  const [contextMenuInfo, setContextMenuInfo] = useState<IContextMenuInfo>(contextMenuDefaultConfig);
  const [contextTableIdentifier, setContextTableIdentifier] = useState<EditorTableIdentifier | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [hasEditorContent, setHasEditorContent] = useState<boolean>(!!defaultSQL?.trim());
  const [initialSaveNameModalOpen, setInitialSaveNameModalOpen] = useState(false);
  const [initialSaveName, setInitialSaveName] = useState('');
  const [initialSaveLoading, setInitialSaveLoading] = useState(false);
  const pendingCloseSaveResolveRef = useRef<((saved: boolean) => void) | null>(null);
  const handleEditorContentChange = useCallback((value: string) => {
    setHasEditorContent(!!value.trim());
  }, []);
  const [routineExecutionModal, setRoutineExecutionModal] = useState({
    open: false,
    title: '',
    sql: '',
  });
  const [routineMigrationModal, setRoutineMigrationModal] = useState<{
    open: boolean;
    title: string;
    sql: string;
    request: IRoutineMigrationParams | null;
    loading: boolean;
  }>({
    open: false,
    title: '',
    sql: '',
    request: null,
    loading: false,
  });
  const sqlEditorRef = useRef<SQLEditorRef>(null);
  const editorViewportRef = useRef<HTMLDivElement>(null);
  const editorViewportSize = useSize(editorViewportRef);
  const routineExecutionEditorRef = useRef<SQLEditorRef>(null);
  // Preserve the previous edit position.
  const lastPositionRef = useRef<{
    lineNumber: number;
    column: number;
  }>({
    lineNumber: 1,
    column: 1,
  });

  const { setSettingPageActiveTab } = useGlobalStore((s) => ({
    setSettingPageActiveTab: s.setSettingPageActiveTab,
  }));

  useImperativeHandle(ref, () => ({
    getId: () => id,
    getInstance,
    getValue,
    setValue,
    getContentDiffBaseline: () => sqlEditorRef.current?.getContentDiffBaseline() ?? '',
    resetContentDiffBaseline: (value?: string) => {
      sqlEditorRef.current?.resetContentDiffBaseline(value);
    },
    getSelectedContent: () => sqlEditorRef.current?.getSelectedContent() ?? '',
    getCursorSQL: () => sqlEditorRef.current?.getCursorSQL() ?? '',
    getCursorCurLineNearestSQL: () => sqlEditorRef.current?.getCursorCurLineNearestSQL() ?? '',
    handleSQLParser: (sql: string, _dbInfo: IBoundInfo) => sqlEditorRef.current?.handleSQLParser(sql, _dbInfo),
    handleQuickSQLParser: (sql: string, _dbInfo: IBoundInfo) =>
      sqlEditorRef.current?.handleQuickSQLParser(sql, _dbInfo),
    getTableIdentifierAtPosition: (position) => sqlEditorRef.current?.getTableIdentifierAtPosition(position) ?? null,
    executeSQL: handleExecuteSQL,
    hasUnsavedChangesBeforeClose,
    saveBeforeClose,
    persistBeforeApplicationExit: type === WorkspaceTabType.CONSOLE ? flushAutoSave : undefined,
  }));

  const getInstance = useCallback(() => {
    return sqlEditorRef?.current?.getInstance() ?? null;
  }, []);

  const getValue = useCallback(() => {
    return sqlEditorRef.current?.getValue() ?? '';
  }, []);

  const createExecutionInvocation = useCallback(
    (params: IConsoleReturnExecuteSql): SQLExecutionInvocation => ({
      ...params,
      executionTarget: createDataSourceExecutionSnapshot(dbInfo),
      dataSourceState,
    }),
    [dbInfo, dataSourceState],
  );

  const setValue = useCallback((value: string, _type?: EditorSetValueType) => {
    setHasEditorContent(!!value?.trim());
    sqlEditorRef.current?.setValue(value, _type);
  }, []);

  const confirmDiscardUnsavedChanges = useCallback(
    () =>
      new Promise<boolean>((resolve) => {
        modal.confirm({
          title: i18n('workspace.fileEncoding.unsavedTitle'),
          content: i18n('workspace.fileEncoding.unsavedContent'),
          okText: i18n('workspace.fileEncoding.reloadConfirm'),
          okButtonProps: { danger: true },
          cancelText: i18n('common.button.cancel'),
          onOk: () => resolve(true),
          onCancel: () => resolve(false),
        });
      }),
    [modal],
  );

  const handleFileEncodingChange = useCallback(
    async (charset?: string) => {
      if (!dbInfo.filePath) {
        return;
      }
      const persistedContent = sqlEditorRef.current ? sqlEditorRef.current.getContentDiffBaseline() : defaultSQL || '';
      if (hasUnsavedLocalFileChanges(getValue(), persistedContent) && !(await confirmDiscardUnsavedChanges())) {
        return;
      }
      const file = await useWorkspaceStore.getState().readFile(dbInfo.filePath, dbInfo.fileExtension, {
        rootToken: dbInfo.fileRootToken,
        relativePath: dbInfo.fileRelativePath,
        charset,
        workspaceTabId: dbInfo.workspaceTabId,
      });
      if (!file) {
        return;
      }
      setValue(file.content, 'reset');
      setDBInfo({
        ...dbInfo,
        ddl: file.content,
        fileCharset: file.charset,
        fileBom: file.bom,
      });
    },
    [confirmDiscardUnsavedChanges, dbInfo, defaultSQL, getValue, setDBInfo, setValue],
  );

  useEffect(() => {
    setHasEditorContent(!!defaultSQL?.trim());
  }, [defaultSQL]);

  useEffect(() => {
    if (!sqlEditorRef.current) return;
    const editor = sqlEditorRef.current.getInstance();

    if (active) {
      if (editor) {
        editor.focus();
        // Use setTimeout so Monaco Editor can finish layout and rendering.
        // Avoid focusing during DOM updates, which may fail.
        setTimeout(() => {
          try {
            // Check editor again because it may have been disposed during the delay.
            if (sqlEditorRef.current?.getInstance()) {
              const currentEditor = sqlEditorRef.current.getInstance();
              if (currentEditor && !currentEditor.hasTextFocus()) {
                currentEditor.focus();
                currentEditor.setPosition(lastPositionRef.current);
              }
            }
          } catch (error) {
            console.warn('Failed to focus editor:', error);
          }
        }, 0);
      }
    } else {
      lastPositionRef.current = editor?.getPosition() ?? { lineNumber: 1, column: 1 };
    }
  }, [active]);

  const { saveConsole, hasSavedSqlRecord, hasUnsavedChanges, flushAutoSave } = useSaveEditorData({
    editorRef: sqlEditorRef,
    isActive: active,
    boundInfo: dbInfo,
    source: 'workspace',
    // defaultValue:  getValue(),
    defaultValue: defaultSQL,
    name: workspaceTabsTitle,
    onBoundInfoChange: setDBInfo,
    type,
  });

  const { enabled: enableContentDiffHints, sourceId: contentDiffSourceId } = getContentDiffEligibility({
    editorType: type,
    dbInfo,
    savedSqlRecord: hasSavedSqlRecord,
  });

  const handleAction = (actionType: SQLOptType, params?: any) => {
    if (isReadOnly && !READONLY_ALLOWED_ACTIONS.has(actionType)) {
      return;
    }
    if (!sqlActionEnabled && !BASIC_EDITOR_ACTIONS.has(actionType)) {
      return;
    }

    switch (actionType) {
      case SQLOptType.NL_2_SQL:
      case SQLOptType.SQL_EXPLAIN:
      case SQLOptType.SQL_OPTIMIZER:
        handleAI(actionType);
        break;
      case SQLOptType.COPY:
        handleCopy();
        break;
      case SQLOptType.PASTE:
        handlePaste();
        break;
      case SQLOptType.PASTE_AS_SQL_IN_VALUES:
        handlePasteAsSqlInValues();
        break;
      case SQLOptType.CUT:
        handleCut();
        break;
      case SQLOptType.CASE_CONVERT:
        handleCaseConvert();
        break;
      case SQLOptType.EXECUTE_SQL:
        handleExecuteSQL();
        break;
      case SQLOptType.EXECUTE_ROUTINE:
        void handleExecuteRoutine();
        break;
      case SQLOptType.APPLY_ROUTINE_DDL:
        void handleApplyRoutineDDL();
        break;
      case SQLOptType.REFRESH_ROUTINE_DDL:
        void handleRefreshRoutineDDL();
        break;
      case SQLOptType.REVERT_ROUTINE_DDL:
        handleRevertRoutineDDL();
        break;
      case SQLOptType.EXECUTE_SINGLE_SQL:
        handleExecuteSingleSQL();
        break;
      case SQLOptType.EXECUTE_SHORTCUT_SQL:
        handleShortCutExecuteSQL();
        break;
      case SQLOptType.EXECUTE_TABLE:
        break;
      case SQLOptType.SAVE_SQL:
        handleSave();
        break;
      case SQLOptType.SAVE_FILE:
        handleSaveFile();
        break;
      case SQLOptType.SAVE_FILE_TO_DESKTOP:
        handleSaveFileToDesktop();
        break;
      case SQLOptType.OPEN_CONTENT_DIFF:
        handleOpenContentDiff();
        break;
      case SQLOptType.FORMAT_SQL:
        handleFormat();
        break;
      case SQLOptType.TOGGLE_LINE_COMMENT:
        sqlEditorRef.current?.getInstance()?.trigger('keyboard', 'editor.action.commentLine', null);
        break;
      case SQLOptType.TOGGLE_BLOCK_COMMENT:
        sqlEditorRef.current?.getInstance()?.trigger('keyboard', 'editor.action.blockComment', null);
        break;
      case SQLOptType.EXPLAIN_SQL:
        handleExecuteSQL({
          explain: true,
        });
        break;
      case SQLOptType.OPEN_SETTINGS:
        setSettingPageActiveTab('editSetting');
        break;
      case SQLOptType.VIEW_TABLE_DDL:
        void handleViewTableDDL(params);
        break;
      case SQLOptType.EDIT_TABLE:
        handleEditTable(contextTableIdentifier);
        break;
      default:
        break;
    }
    setContextMenuInfo(contextMenuDefaultConfig);
    setContextTableIdentifier(null);
  };

  const handleExecuteRoutine = async () => {
    const routineRequest = getRoutineOperationRequest(type, dbInfo);
    if (!routineRequest) {
      staticMessage.warning(i18n('workspace.routine.tips.unsupportedInvoke'));
      return;
    }

    try {
      const preview = await sqlService.previewRoutineInvocation(routineRequest);
      const previewSql = getRoutinePreviewSql(preview);
      if (!previewSql) {
        staticMessage.warning(i18n('workspace.routine.tips.unsupportedInvoke'));
        return;
      }

      setRoutineExecutionModal({
        open: true,
        title: getRoutineExecutionTitle(type),
        sql: previewSql,
      });
    } catch (error: any) {
      setErrorMessage(error?.errorMessage || error?.message || '');
    }
  };

  const handleApplyRoutineDDL = async () => {
    const baseRequest = getRoutineOperationRequest(type, dbInfo);
    const routineRequest = baseRequest ? { ...baseRequest, ddl: getValue() } : null;
    if (!routineRequest) {
      staticMessage.warning(i18n('workspace.routine.tips.onlyMysqlRoutine'));
      return;
    }

    try {
      const preview = await sqlService.previewRoutineMigration(routineRequest);
      const migrationSql = getRoutinePreviewSql(preview);
      if (!migrationSql) {
        staticMessage.warning(i18n('workspace.routine.tips.onlyMysqlRoutine'));
        return;
      }
      setRoutineMigrationModal({
        open: true,
        title: getRoutineMigrationTitle(type),
        sql: migrationSql,
        request: routineRequest,
        loading: false,
      });
    } catch (error: any) {
      setErrorMessage(error?.errorMessage || error?.message || '');
    }
  };

  const handleConfirmRoutineExecution = () => {
    const executionSql = routineExecutionEditorRef.current?.getValue() || routineExecutionModal.sql;
    setRoutineExecutionModal({
      open: false,
      title: '',
      sql: '',
    });
    if (!executionSql) {
      return;
    }

    props
      ?.onExecuteSQL(createExecutionInvocation({ sql: executionSql, single: false }))
      .then(() => {
        setErrorMessage(null);
      })
      .catch((error) => {
        setErrorMessage(error.errorMessage || '');
      });
  };

  const handleCancelRoutineExecution = () => {
    setRoutineExecutionModal({
      open: false,
      title: '',
      sql: '',
    });
  };

  const handleConfirmRoutineMigration = async () => {
    const routineRequest = routineMigrationModal.request;
    if (!routineRequest) {
      return;
    }

    setRoutineMigrationModal((prev) => ({ ...prev, loading: true }));
    try {
      const result = await sqlService.executeRoutineMigration(routineRequest);
      if (result && result.success === false) {
        const message = result.message || '';
        setRoutineMigrationModal({
          open: false,
          title: '',
          sql: '',
          request: null,
          loading: false,
        });
        setErrorMessage(message);
        return;
      }
      setErrorMessage(null);
      setRoutineMigrationModal({
        open: false,
        title: '',
        sql: '',
        request: null,
        loading: false,
      });
      await handleRefreshRoutineDDL({ silent: true });
      staticMessage.success(i18n('common.text.successfulExecution'));
    } catch (error: any) {
      const message = error?.errorMessage || error?.message || '';
      setRoutineMigrationModal({
        open: false,
        title: '',
        sql: '',
        request: null,
        loading: false,
      });
      setErrorMessage(message);
    }
  };

  const handleCancelRoutineMigration = () => {
    setRoutineMigrationModal({
      open: false,
      title: '',
      sql: '',
      request: null,
      loading: false,
    });
  };

  const handleRefreshRoutineDDL = async (options?: { silent?: boolean }) => {
    if (!reloadSQL) {
      return;
    }
    try {
      const sql = await reloadSQL();
      setValue(sql || '', 'reset');
      sqlEditorRef.current?.resetContentDiffBaseline(sql || '');
      if (!options?.silent) {
        staticMessage.success(i18n('workspace.routine.tips.refreshSuccess'));
      }
    } catch (error: any) {
      setErrorMessage(error?.errorMessage || error?.message || '');
    }
  };

  const handleRevertRoutineDDL = () => {
    const baseline = sqlEditorRef.current?.getContentDiffBaseline() ?? '';
    setValue(baseline, 'reset');
    sqlEditorRef.current?.resetContentDiffBaseline(baseline);
    staticMessage.success(i18n('workspace.routine.tips.revertSuccess'));
  };

  const handleViewTableDDL = async (tableIdentifier?: EditorTableIdentifier | null) => {
    const tableNode = createTableTreeNode(tableIdentifier);
    if (!tableNode) {
      return;
    }

    const selected = await selectTableTreeNode(tableNode);
    if (!selected) {
      return;
    }

    useGlobalStore.getState().setMainPageActiveTab({ page: 'workspace' });
    useAIStore.getState().setShowPanel(false);
    useWorkspaceStore.getState().setCurrentWorkspaceExtend('info');
    useWorkspaceStore.getState().togglePanelRight(true);
  };

  const handleEditTable = (tableIdentifier?: EditorTableIdentifier | null) => {
    if (!tableIdentifier?.dataSourceId || !tableIdentifier?.tableName) {
      return;
    }

    const title = buildWorkspaceObjectTabTitle({
      dataSourceName: tableIdentifier.dataSourceName,
      databaseName: tableIdentifier.databaseName,
      schemaName: tableIdentifier.schemaName,
      objectName: tableIdentifier.tableName,
    });
    const tabId =
      treeConfig?.[TreeNodeType.TABLE]?.createTreeNodeKey?.({
        dataSourceId: tableIdentifier.dataSourceId,
        databaseName: tableIdentifier.databaseName,
        schemaName: tableIdentifier.schemaName,
        tableName: tableIdentifier.tableName,
      }) || tableIdentifier.tableName;

    useWorkspaceStore.getState().addWorkspaceTab({
      id: `${OperationColumn.EditTable}-${tabId}`,
      title,
      type: WorkspaceTabType.EditTable,
      uniqueData: {
        dataSourceId: tableIdentifier.dataSourceId,
        dataSourceName: tableIdentifier.dataSourceName,
        databaseType: tableIdentifier.databaseType,
        databaseName: tableIdentifier.databaseName,
        schemaName: tableIdentifier.schemaName,
        tableName: tableIdentifier.tableName,
        popoverContent: title,
      },
    });
  };

  const handleAI = async (actionType: SQLOptType) => {
    const selectSQL = sqlEditorRef.current?.getSelectedContent();
    if (!selectSQL) {
      staticMessage.warning(i18n('common.placeholder.select', 'SQL'));
      return;
    }

    const { dataSourceId, databaseName, schemaName, databaseType, supportDatabase, supportSchema } = dbInfo;
    if (!dataSourceId || !databaseType) {
      staticMessage.warning(i18n('common.placeholder.select', i18n('common.dataSource.title')));
      return;
    }
    if (supportDatabase && !databaseName) {
      staticMessage.warning(i18n('common.placeholder.select', i18n('common.database.title')));
      return;
    }
    if (supportSchema && !schemaName) {
      staticMessage.warning(i18n('common.placeholder.select', i18n('common.schema.title')));
      return;
    }

    const scenario = {
      [SQLOptType.NL_2_SQL]: {
        questionType: QuestionType.NL_2_SQL,
        input: selectSQL,
      },
      [SQLOptType.SQL_EXPLAIN]: {
        questionType: QuestionType.SQL_EXPLAIN,
        input: `${i18n('ai.aiType.SQLExplain.preContent')}: \\\n ${selectSQL}`,
      },
      [SQLOptType.SQL_OPTIMIZER]: {
        questionType: QuestionType.SQL_OPTIMIZER,
        input: `${i18n('ai.aiType.SQLOptimizer.preContent')}: \\\n ${selectSQL}`,
      },
    }[actionType];
    if (!scenario) return;

    useAIStore.getState().setShowPanel(true);
    window.setTimeout(
      () =>
        window.dispatchEvent(
          new CustomEvent('stream:sendMessage', {
            detail: {
              ...scenario,
              source: ChatSourceType.DATASOURCE_CHAT,
              dataSourceId,
              databaseName,
              schemaName,
              databaseType,
              sql: selectSQL,
            },
          }),
        ),
      0,
    );
  };

  const handleCopy = useCallback(() => {
    const editor = sqlEditorRef.current?.getInstance();
    const selectedText = sqlEditorRef.current?.getSelectedContent() || '';

    if (selectedText) {
      navigator.clipboard.writeText(selectedText);
    }

    if (editor) {
      editor.trigger('keyboard', 'editor.action.clipboardCopyAction', null);
    }
  }, []);

  const insertTextToEditor = useCallback((text: string, source = 'insert') => {
    const editor = sqlEditorRef.current?.getInstance();
    if (!editor) {
      return;
    }

    const selection = editor.getSelection();
    if (selection) {
      editor.executeEdits(source, [
        {
          range: selection,
          text,
          forceMoveMarkers: true,
        },
      ]);
      return;
    }

    const position = editor.getPosition();
    if (position) {
      editor.executeEdits(source, [
        {
          range: new monaco.Range(position.lineNumber, position.column, position.lineNumber, position.column),
          text,
          forceMoveMarkers: true,
        },
      ]);
    }
  }, []);

  const handlePaste = useCallback(async () => {
    const activeElement = document.activeElement;

    if (
      activeElement &&
      !activeElement.className.includes('monaco') &&
      (activeElement.tagName === 'INPUT' || activeElement.tagName === 'TEXTAREA')
    ) {
      return;
    }

    try {
      const clipboardText = await readClipboard();
      insertTextToEditor(clipboardText, 'paste');
    } catch (error) {
      console.error('Failed to read clipboard:', error);
    }
  }, [insertTextToEditor]);

  const handlePasteAsSqlInValues = useCallback(async () => {
    if (!dbInfo.dataSourceId || !dbInfo.consoleId || isTemporaryId(dbInfo.consoleId)) {
      staticMessage.warning(i18n('common.sqlInValues.missingConnection'));
      return;
    }

    try {
      const clipboardText = await readClipboard();
      const externalValues = parseClipboardTextToSqlInTokens(clipboardText);
      if (!externalValues.length) {
        staticMessage.warning(i18n('common.sqlInValues.emptyInput'));
        return;
      }

      const sql = await executeSql.getCopyInValuesSql({
        dataSourceId: dbInfo.dataSourceId,
        dataSourceName: dbInfo.dataSourceName,
        databaseType: dbInfo.databaseType,
        databaseName: dbInfo.databaseName,
        schemaName: dbInfo.schemaName,
        consoleId: dbInfo.consoleId,
        sourceType: 'EXTERNAL_TEXT',
        externalValues,
      });
      insertTextToEditor(sql, 'pasteAsSqlInValues');
    } catch (error) {
      console.error('Failed to paste as SQL IN values:', error);
      staticMessage.warning(i18n('common.sqlInValues.copyFailed'));
    }
  }, [dbInfo, insertTextToEditor]);

  const handleCut = useCallback(() => {
    const editor = sqlEditorRef.current?.getInstance();
    const selectedText = sqlEditorRef.current?.getSelectedContent() || '';
    const selection = editor?.getSelection();

    if (selectedText && selection && editor) {
      // Copy to the clipboard first.
      navigator.clipboard.writeText(selectedText);

      // Then delete the selection.
      editor.executeEdits('cut', [
        {
          range: selection,
          text: '',
          forceMoveMarkers: true,
        },
      ]);
    } else {
      // Use the default cut behavior when nothing is selected.
      if (editor) {
        editor.trigger('keyboard', 'editor.action.clipboardCutAction', null);
      }
    }
  }, []);

  const handleCaseConvert = useCallback(() => {
    const content = sqlEditorRef.current?.getSelectedContent() ?? '';
    if (content) {
      const isFirstLetterLowerCase = content[0] === content[0].toLowerCase();
      const newValue = isFirstLetterLowerCase ? content.toUpperCase() : content.toLowerCase();
      sqlEditorRef.current?.setValue(newValue, 'replace');

      /**
       * Select the word at the cursor after changing its case.
       */
      const editor = sqlEditorRef.current?.getInstance();
      const cursorPosition = editor?.getPosition();
      if (cursorPosition) {
        const wordAtCursor = editor?.getModel()?.getWordAtPosition(cursorPosition);
        if (wordAtCursor) {
          editor?.setSelection({
            startLineNumber: cursorPosition.lineNumber,
            startColumn: wordAtCursor.startColumn,
            endLineNumber: cursorPosition.lineNumber,
            endColumn: wordAtCursor.endColumn,
          });
        }
      }
    }
  }, []);

  const handleExecuteSQL = (params?: { explain?: boolean }) => {
    const { explain = false } = params ?? {};
    const allSQL = getValue();
    const selectSQL = sqlEditorRef.current?.getSelectedContent();
    const sql = selectSQL || allSQL;
    if (!sql) {
      staticMessage.warning(i18n('common.placeholder.select', 'SQL'));
      return;
    }

    const executeSqlParams = {
      sql,
      explain,
      errorContinue: useGlobalStore.getState().editorSettings.errorContinue,
    };

    props
      ?.onExecuteSQL(createExecutionInvocation(executeSqlParams))
      .then(() => {
        setErrorMessage(null);
      })
      .catch((error) => {
        setErrorMessage(error.errorMessage || '');
      });
  };

  /**
   * Execute one SQL statement.
   */
  const handleExecuteSingleSQL = () => {
    const selectSQL = sqlEditorRef.current?.getSelectedContent() || '';
    const cursorSQL = sqlEditorRef.current?.getCursorCurLineNearestSQL() || '';
    const sql = selectSQL || cursorSQL;
    if (!sql) {
      staticMessage.warning(i18n('common.placeholder.select', 'SQL'));
      return;
    }

    const executeSqlParams = {
      sql,
      single: true,
    };

    props
      ?.onExecuteSQL(createExecutionInvocation(executeSqlParams))
      .then(() => {
        setErrorMessage(null);
      })
      .catch((error) => {
        setErrorMessage(error.errorMessage || '');
      });
  };

  /**
   * Execute SQL via shortcut.
   */
  const handleShortCutExecuteSQL = useCallback(async () => {
    const executionBoundInfo = createDataSourceExecutionBoundInfo(dbInfo);
    const executionTarget = createDataSourceExecutionSnapshot(executionBoundInfo);
    const executionDataSourceState = dataSourceState;
    await sqlEditorRef.current?.handleQuickSQLParser(sqlEditorRef.current?.getValue() || '', executionBoundInfo);
    // await sqlEditorRef.current?.handleSQLParser(sqlEditorRef.current?.getValue() || '', dbInfo);

    const selectSQL = sqlEditorRef.current?.getSelectedContent() || '';
    const cursorSQL = sqlEditorRef.current?.getCursorCurLineNearestSQL() || '';
    const isSingle = selectSQL ? false : true;
    const sql = selectSQL || cursorSQL;
    if (!sql) {
      staticMessage.warning(i18n('common.placeholder.select', 'SQL'));
      return;
    }

    props
      ?.onExecuteSQL({
        sql,
        single: isSingle,
        executionTarget,
        dataSourceState: executionDataSourceState,
      })
      .then(() => {
        setErrorMessage(null);
      })
      .catch((error) => {
        setErrorMessage(error.errorMessage || '');
      });
  }, [props?.onExecuteSQL, dbInfo, dataSourceState]);

  /** Save current editor data. */
  const handleSave = useCallback(() => {
    if (type !== WorkspaceTabType.CONSOLE || typeof dbInfo.consoleId !== 'number' || isTemporaryId(dbInfo.consoleId)) {
      return;
    }
    if (!hasSavedSqlRecord) {
      if (!initialSaveNameModalOpen) {
        setInitialSaveName(
          resolveInitialSavedConsoleName({
            workspaceTitle: workspaceTabsTitle,
            databaseName: dbInfo.databaseName,
            schemaName: dbInfo.schemaName,
            fallback: i18n('workspace.savedConsole.untitled'),
          }),
        );
        setInitialSaveNameModalOpen(true);
      }
      return;
    }
    void saveConsole(getValue(), { mode: 'manual' });
  }, [
    dbInfo.consoleId,
    dbInfo.databaseName,
    dbInfo.schemaName,
    hasSavedSqlRecord,
    initialSaveNameModalOpen,
    getValue,
    saveConsole,
    type,
    workspaceTabsTitle,
  ]);

  const handleConfirmInitialSave = useCallback(async () => {
    const name = normalizeSavedConsoleName(initialSaveName);
    if (!name || initialSaveLoading) {
      return;
    }

    setInitialSaveLoading(true);
    try {
      await saveConsole(getValue(), { mode: 'manual', initialName: name });
      setInitialSaveNameModalOpen(false);
      pendingCloseSaveResolveRef.current?.(true);
      pendingCloseSaveResolveRef.current = null;
    } catch {
      // The request layer presents the error. Keep the name dialog open for retry.
    } finally {
      setInitialSaveLoading(false);
    }
  }, [getValue, initialSaveLoading, initialSaveName, saveConsole]);

  const handleCancelInitialSave = useCallback(() => {
    if (initialSaveLoading) {
      return;
    }
    setInitialSaveNameModalOpen(false);
    pendingCloseSaveResolveRef.current?.(false);
    pendingCloseSaveResolveRef.current = null;
  }, [initialSaveLoading]);

  const handleSaveFile = async () => {
    const fileContent = sqlEditorRef.current?.getValue() ?? '';
    try {
      await updateFileContent({
        filePath: dbInfo.filePath!,
        fileContent,
        charset: dbInfo.fileCharset,
        bom: dbInfo.fileBom,
      });
    } catch (error) {
      console.error('update local file error', error);
      staticMessage.error(i18n('common.text.failure'));
      return false;
    }
    try {
      sqlEditorRef.current?.resetContentDiffBaseline(fileContent);
    } catch {
      // Content diff is only a hint and must not affect file saving.
    }
    staticMessage.success(i18n('workspace.text.changeFileSuccess'));
    return true;
  };

  function hasUnsavedChangesBeforeClose() {
    if (isReadOnly) {
      return false;
    }
    if (type === WorkspaceTabType.LocalSQLFile) {
      return hasUnsavedLocalFileChanges(getValue(), sqlEditorRef.current?.getContentDiffBaseline() ?? defaultSQL ?? '');
    }
    if (type === WorkspaceTabType.CONSOLE) {
      return hasUnsavedChanges(getValue());
    }
    return false;
  }

  async function saveBeforeClose() {
    if (type === WorkspaceTabType.LocalSQLFile) {
      return handleSaveFile();
    }
    if (type !== WorkspaceTabType.CONSOLE) {
      return false;
    }
    if (hasSavedSqlRecord) {
      try {
        await saveConsole(getValue(), { mode: 'manual' });
        return true;
      } catch {
        return false;
      }
    }
    if (pendingCloseSaveResolveRef.current) {
      return false;
    }

    return new Promise<boolean>((resolve) => {
      pendingCloseSaveResolveRef.current = resolve;
      if (!initialSaveNameModalOpen) {
        setInitialSaveName(
          resolveInitialSavedConsoleName({
            workspaceTitle: workspaceTabsTitle,
            databaseName: dbInfo.databaseName,
            schemaName: dbInfo.schemaName,
            fallback: i18n('workspace.savedConsole.untitled'),
          }),
        );
        setInitialSaveNameModalOpen(true);
      }
    });
  }

  useEffect(
    () => () => {
      pendingCloseSaveResolveRef.current?.(false);
      pendingCloseSaveResolveRef.current = null;
    },
    [],
  );

  const handleSaveFileToDesktop = () => {
    const { dataSourceName, databaseName, schemaName } = dbInfo;
    saveFileToDesktop({
      fileName: sqlFileName || [dataSourceName, databaseName, schemaName].filter(Boolean).join('_'),
      fileContent: getValue(),
      fileType: 'sql',
    });
  };

  const handleOpenContentDiff = useCallback(() => {
    try {
      if (!enableContentDiffHints) {
        return;
      }

      const originalText = sqlEditorRef.current?.getContentDiffBaseline() ?? '';
      const modifiedText = sqlEditorRef.current?.getValue() ?? '';
      if (getContentDiffOpenBlockReason(originalText, modifiedText) === ContentDiffDenyReason.TextTooLarge) {
        staticMessage.warning(i18n('monaco.text.diffContentTooLarge'));
        return;
      }

      const sourceId =
        contentDiffSourceId || String(id || dbInfo.consoleId || workspaceTabsTitle || sqlFileName || 'editor');
      const tabId = `${WorkspaceTabType.ContentDiff}:${sourceId}`;
      const title = `${getContentDiffSourceTitle({ workspaceTabsTitle, sqlFileName, dbInfo })}@`;
      const tab = {
        id: tabId,
        type: WorkspaceTabType.ContentDiff,
        title,
        uniqueData: {
          diffOriginalText: originalText,
          diffModifiedText: modifiedText,
          diffLanguage: 'sql',
          popoverContent: title,
        },
      };
      const workspaceStore = useWorkspaceStore.getState();
      const workspaceTabList = workspaceStore.workspaceTabList || [];

      if (workspaceTabList.some((item) => item.id === tabId)) {
        workspaceStore.setWorkspaceTabList(workspaceTabList.map((item) => (item.id === tabId ? tab : item)));
        workspaceStore.setActiveConsoleId(tabId);
        return;
      }

      workspaceStore.addWorkspaceTab(tab);
    } catch {
      // Content diff is only a hint and must not affect editor workflows.
    }
  }, [contentDiffSourceId, dbInfo, enableContentDiffHints, id, sqlFileName, workspaceTabsTitle]);

  const handleFormat = async () => {
    const sql = sqlEditorRef.current?.getValue() || '';
    const selectedContent = sqlEditorRef.current?.getSelectedContent();
    if (!dbInfo.databaseType) {
      staticMessage.warning(i18n('common.placeholder.select', i18n('common.database.title')));
      return;
    }

    const formatSQL = await formatSql(selectedContent || sql, dbInfo.databaseType);

    setValue(formatSQL, selectedContent ? 'replace' : 'cover');
  };

  const handleContextMenu = (e: monaco.editor.IEditorMouseEvent) => {
    e.event.preventDefault();

    const context = sqlEditorRef.current?.getSelectedContent() ?? '';

    const { posx: left, posy: top, target } = e.event;

    if (target.className.includes('ant-input')) {
      return;
    }

    setContextMenuInfo({
      open: true,
      context,
      position: {
        ...contextMenuInfo.position,
        left: `${left}px`,
        top: `${top}px`,
      },
    });
  };

  const identityColor = useDataSourceIdentityColor(dbInfo.dataSourceId);
  const watermarkContent =
    isConsole && type === WorkspaceTabType.CONSOLE && dbInfo.dataSourceId
      ? getDataSourceWatermarkContent(dbInfo, dataSourceState)
      : undefined;
  const watermarkColor = identityColor ? withIdentityColorAlpha(identityColor, 0.4) : undefined;
  const watermarkLayout = getDataSourceWatermarkLayout(editorViewportSize?.width, editorViewportSize?.height);

  return (
    <div className={styles.wrapper}>
      {modalContextHolder}
      {(!isReadOnly || isSupportedRoutineEditor) && sqlActionEnabled && (
        <OperationLine
          active={active}
          type={type}
          dbInfo={dbInfo}
          hasEditorContent={hasEditorContent}
          isConsole={isConsole}
          setDBInfo={setDBInfo}
          contentDiffEnabled={enableContentDiffHints}
          action={handleAction}
        />
      )}
      <div ref={editorViewportRef} style={{ position: 'relative', flex: 1, height: '0px', width: '100%' }}>
        {watermarkContent && watermarkColor && (
          <div
            className={styles.watermarkLayer}
            style={{
              color: watermarkColor,
              gridTemplateColumns: `repeat(${watermarkLayout.columns}, minmax(0, 1fr))`,
              gridTemplateRows: `repeat(${watermarkLayout.rows}, minmax(0, 1fr))`,
            }}
            aria-hidden="true"
          >
            {Array.from({ length: watermarkLayout.itemCount }, (_, index) => (
              <div className={styles.watermarkItem} key={index}>
                <div className={styles.watermarkTitle}>{watermarkContent.title}</div>
                {watermarkContent.subtitle && (
                  <div className={styles.watermarkSubtitle}>{watermarkContent.subtitle}</div>
                )}
                {watermarkContent.connectionUnavailable && (
                  <div className={styles.watermarkStatus}>
                    {i18n('workspace.dataSourceLifecycle.connectionUnavailable')}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
        <SQLEditor
          className={styles.sqlEditor}
          id={id}
          ref={sqlEditorRef}
          dbInfo={dbInfo}
          active={active}
          defaultValue={defaultSQL}
          readOnly={isReadOnly}
          action={handleAction}
          enableContentDiffHints={enableContentDiffHints}
          onContentChange={handleEditorContentChange}
          onChange={onChange}
          onFileEncodingChange={handleFileEncodingChange}
          contextMenuInfo={contextMenuInfo}
          onTableIdentifierContextChange={setContextTableIdentifier}
          onContextMenu={isReadOnly ? undefined : handleContextMenu}
        />
        {!isReadOnly && (
          <ContextMenu
            id={id}
            dbInfo={dbInfo}
            type={type}
            editorRef={sqlEditorRef}
            config={contextMenuInfo}
            canEditTable={!!contextTableIdentifier}
            sqlActionEnabled={sqlActionEnabled}
            contentDiffEnabled={enableContentDiffHints}
            onClick={handleAction}
            onCloseContextMenu={() => setContextMenuInfo(contextMenuDefaultConfig)}
          />
        )}
      </div>
      <MonacoEditorErrorTips errorMessage={errorMessage} handleClose={() => setErrorMessage(null)} />
      <RoutineOperationModals
        editorId={id}
        dbInfo={dbInfo}
        executionModal={routineExecutionModal}
        migrationModal={routineMigrationModal}
        executionEditorRef={routineExecutionEditorRef}
        onConfirmExecution={handleConfirmRoutineExecution}
        onCancelExecution={handleCancelRoutineExecution}
        onConfirmMigration={handleConfirmRoutineMigration}
        onCancelMigration={handleCancelRoutineMigration}
      />
      <InitialSaveNameModal
        open={initialSaveNameModalOpen}
        name={initialSaveName}
        loading={initialSaveLoading}
        onNameChange={setInitialSaveName}
        onConfirm={handleConfirmInitialSave}
        onCancel={handleCancelInitialSave}
      />
    </div>
  );
});

export default SQLEditorWithOperation;

const getContentDiffSourceTitle = (params: {
  workspaceTabsTitle?: string;
  sqlFileName?: string;
  dbInfo: IBoundInfo;
}) => {
  const { workspaceTabsTitle, sqlFileName, dbInfo } = params;
  const title = workspaceTabsTitle || sqlFileName || dbInfo.viewName || dbInfo.tableName || 'diff';
  return title.replace(/\[.*?\]/g, '').trim() || 'diff';
};

const createTableTreeNode = (tableIdentifier?: EditorTableIdentifier | null): TreeNodeData | null => {
  if (!tableIdentifier?.dataSourceId || !tableIdentifier?.tableName) {
    return null;
  }

  const treeNodeType = getTreeNodeTypeByObjectType(tableIdentifier.objectType);
  const key =
    createDatabaseObjectTreeNodeKey({
      treeNodeType,
      dataSourceId: tableIdentifier.dataSourceId,
      databaseType: tableIdentifier.databaseType,
      databaseName: tableIdentifier.databaseName,
      schemaName: tableIdentifier.schemaName,
      name: tableIdentifier.tableName,
    }) || tableIdentifier.tableName;

  return {
    key,
    originalTitle: tableIdentifier.tableName,
    title: null,
    treeNodeType,
    isLeaf: tableIdentifier.objectType === 'TABLE' || tableIdentifier.objectType === 'VIEW' ? false : true,
    extraParams: {
      dataSourceId: tableIdentifier.dataSourceId,
      dataSourceName: tableIdentifier.dataSourceName,
      databaseType: tableIdentifier.databaseType,
      databaseName: tableIdentifier.databaseName,
      schemaName: tableIdentifier.schemaName,
      tableName: tableIdentifier.tableName,
      viewName: tableIdentifier.objectType === 'VIEW' ? tableIdentifier.tableName : undefined,
      functionName: tableIdentifier.objectType === 'FUNCTION' ? tableIdentifier.tableName : undefined,
      procedureName: tableIdentifier.objectType === 'PROCEDURE' ? tableIdentifier.tableName : undefined,
    },
  };
};

const selectTableTreeNode = async (tableNode: TreeNodeData) => {
  const { dataSourceId, databaseType, databaseName, schemaName, tableName } = tableNode.extraParams;
  const candidateTreeNodeTypes = getCandidateTreeNodeTypes(tableNode.treeNodeType);
  const loadedPathKeys: React.Key[] = [];
  let loadedTableNode: TreeNodeData | null = null;

  for (const treeNodeType of candidateTreeNodeTypes) {
    const loadedCandidatePathKeys = await loadDatabaseObjectTreePath({
      treeNodeType,
      dataSourceId,
      databaseType,
      databaseName,
      schemaName,
    });
    loadedPathKeys.push(...loadedCandidatePathKeys);
    loadedTableNode = findLoadedDatabaseObjectTreeNode(useTreeStore.getState().treeData, {
      treeNodeType,
      dataSourceId,
      databaseType,
      databaseName,
      schemaName,
      name: tableName,
    });
    if (loadedTableNode) {
      break;
    }
  }

  if (!loadedTableNode) {
    return false;
  }

  const selectedNode = loadedTableNode;
  ensureWorkspaceLeftPanelVisible();
  const treeStore = useTreeStore.getState();
  const expandedKeys = Array.from(new Set([...treeStore.expandedKeys, ...loadedPathKeys]));

  treeStore.setExpandedKeys(expandedKeys);
  treeStore.setCurrentTreeNode(selectedNode);
  treeStore.setSelectedKeys([selectedNode.key]);
  treeStore.setScrollTargetKey(selectedNode.key);
  return true;
};

const loadDatabaseObjectTreePath = async (params: {
  treeNodeType: TreeNodeType;
  dataSourceId?: number;
  databaseType?: TreeNodeData['extraParams']['databaseType'];
  databaseName?: string;
  schemaName?: string;
}) => {
  const { treeNodeType, dataSourceId, databaseName, schemaName } = params;
  const objectType = getDatabaseObjectTypeByTreeNodeType(treeNodeType);
  const parentNodeType = getParentTreeNodeTypeByObjectType(objectType);
  const pathKeys = [
    treeConfig[TreeNodeType.DATA_SOURCE].createTreeNodeKey?.({ dataSourceId }),
    databaseName ? treeConfig[TreeNodeType.DATABASE].createTreeNodeKey?.({ dataSourceId, databaseName }) : null,
    schemaName ? treeConfig[TreeNodeType.SCHEMA].createTreeNodeKey?.({ dataSourceId, databaseName, schemaName }) : null,
    treeConfig[parentNodeType].createTreeNodeKey?.({ dataSourceId, databaseName, schemaName }),
  ].filter(Boolean) as string[];
  const loadedPathKeys: React.Key[] = [];

  for (const key of pathKeys) {
    const node = findTreeNodeByKey(key, useTreeStore.getState().treeData);
    if (!node) {
      continue;
    }

    const treeStore = useTreeStore.getState();
    try {
      const loadResult = await treeStore.handleLoadData(node, {
        closeExpandTreeNode: true,
        preserveInteraction: true,
      });
      if (!loadResult.committed) {
        break;
      }
      loadedPathKeys.push(key);
    } catch {
      break;
    }
  }

  return loadedPathKeys;
};

const ensureWorkspaceLeftPanelVisible = () => {
  const workspaceStore = useWorkspaceStore.getState();
  if (workspaceStore.layout.panelLeftWidth < 100) {
    workspaceStore.setPanelLeftWidth(240);
  }
};

const getRoutineObjectName = (type: EditorType, dbInfo: IBoundInfo) => {
  if (type === WorkspaceTabType.FUNCTION) {
    return dbInfo.functionName;
  }
  if (type === WorkspaceTabType.PROCEDURE) {
    return dbInfo.procedureName;
  }
  return '';
};

const getRoutineType = (type: EditorType): 'FUNCTION' | 'PROCEDURE' | '' => {
  if (type === WorkspaceTabType.FUNCTION) {
    return 'FUNCTION';
  }
  if (type === WorkspaceTabType.PROCEDURE) {
    return 'PROCEDURE';
  }
  return '';
};

const getRoutineExecutionTitle = (type: EditorType) => {
  if (type === WorkspaceTabType.FUNCTION) {
    return i18n('workspace.routine.invoke.functionTitle');
  }
  if (type === WorkspaceTabType.PROCEDURE) {
    return i18n('workspace.routine.invoke.procedureTitle');
  }
  return i18n('workspace.routine.invoke.title');
};

const getRoutineMigrationTitle = (type: EditorType) => {
  if (type === WorkspaceTabType.FUNCTION) {
    return i18n('workspace.routine.migration.functionTitle');
  }
  if (type === WorkspaceTabType.PROCEDURE) {
    return i18n('workspace.routine.migration.procedureTitle');
  }
  return i18n('workspace.routine.migration.title');
};

const getRoutineOperationRequest = (type: EditorType, dbInfo: IBoundInfo) => {
  const routineName = getRoutineObjectName(type, dbInfo);
  const routineType = getRoutineType(type);
  if (!dbInfo.dataSourceId || !routineName || !routineType) {
    return null;
  }

  return {
    dataSourceId: dbInfo.dataSourceId,
    databaseName: dbInfo.databaseName,
    schemaName: dbInfo.schemaName,
    routineType,
    routineName,
  };
};

const getRoutinePreviewSql = (preview: any) => {
  return preview?.sql || preview?.data?.sql || '';
};
