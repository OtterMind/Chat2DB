import { forwardRef, useCallback, useEffect, useImperativeHandle, useRef, useState } from 'react';
import SQLParserService from '@/service/sqlParser';
import { IBoundInfo } from '@/typings';
import { useGlobalStore } from '@/store/global';
import { debounce } from 'lodash';
import MonacoEditor, { MonacoEditorRef } from '../MonacoEditor';
import CompletionProviderManager from '../../core/completionProviderManager';
import {
  isBackendCompletionDatabaseType,
  isBackendEditorHintsDatabaseType,
  setBackendCompletionModel,
} from '../../core/sqlCompletionModelMode';
import {
  SqlCompletionHintScope,
  createSqlCompletionHintStore,
  sqlCompletionHintScopeFromRange,
} from '../../core/sqlCompletionHintStore';
import {
  setExecuteButtonDecorations,
  setInsertValueHighlightDecorations,
  setSqlValueTypeHintDecorations,
  setTableIdentifierDecorations,
} from '../../core/setDecorations';
import * as monaco from 'monaco-editor';
import { ISqlEditorHintVO, MarkMessage, SqlStatement, StatementValidTypeEnum } from '@/typings/sqlParser';
import { setModelMarkers } from '../../core/setModelMarkers';
import { onHoverEditor } from '../../core/registerHoverProvider';
import { EditorSetValueType, EditorSettings, SQLOptType } from '../../type';
import { findNearestSQL, findSqlStatement } from '../../helper/utils';
import HoverHelp, { HoverHelperInfo } from '../../components/HoverHelp';
import { IContextMenuInfo } from '../SQLEditorWithOperation';
import {
  getInsertValueHintContext,
  getInsertValueHighlightRanges,
  getInsertValueMismatchMarkersWithMessage,
} from '../../helper/insertValueHighlight';
import {
  ParameterHintContext,
  parameterHintContextFromInsertValue,
  parameterHintContextFromEditorHints,
} from '../../helper/parameterHint';
import {
  EditorTableIdentifier,
  findTableIdentifierAtPosition,
  getTableIdentifierUnderlineRanges,
} from '../../helper/tableIdentifier';
import i18n from '@/i18n';
import ParameterHintWidget from '../../components/ParameterHintWidget';
import {
  getInsertValueAutoFill,
  materializeInsertValueAutoFillHints,
  mergeInsertValueHints,
  rematerializeInsertValueHints,
} from '../../helper/sqlInsertValueDefaults';
import {
  ShortcutAction,
  ShortcutOverrides,
  getEffectiveShortcutConfigMap,
  isShortcutEventMatch,
  shortcutBindingToMonacoKeybinding,
} from '@/constants/shortcut';
import { useStyles } from './style';

const INSERT_VALUE_HINT_ACTION_ID = 'chat2db-insert-value-hints';
const EDITOR_ESCAPE_KEY_CODE = 'Escape';

export interface SQLEditorProps {
  /** Editor ID. */
  id: string;
  /** Default editor value. */
  defaultValue?: string;
  /** Editor mount callback. */
  onMount?: (editor: monaco.editor.IStandaloneCodeEditor) => void;
  onReady?: () => void;
  onContextMenu?: (e: monaco.editor.IEditorMouseEvent) => void;
  /** Editor value change callback. */
  onChange?: (value: string) => void;

  dbInfo: IBoundInfo;
  active?: boolean;
  className?: string;
  readOnly?: boolean;
  options?: monaco.editor.IStandaloneEditorConstructionOptions;

  action: (type: SQLOptType, params?: any) => void;
  contextMenuInfo?: IContextMenuInfo;
  enableContentDiffHints?: boolean;
  onTableIdentifierContextChange?: (tableIdentifier: EditorTableIdentifier | null) => void;
}

export interface SQLEditorRef extends MonacoEditorRef {
  /** Get the SQL statement at the current cursor position. */
  getCursorSQL: () => string;
  /** Get the nearest SQL statement to the current cursor line. */
  getCursorCurLineNearestSQL: () => string;
  /** Parse an SQL statement. */
  handleSQLParser: (sql: string, dbInfo: IBoundInfo) => void;
  /** Execute SQL through the quick action. */
  handleQuickSQLParser: (sql: string, dbInfo: IBoundInfo) => void;
  /** Get the table name at the specified position. */
  getTableIdentifierAtPosition: (position: monaco.IPosition | null | undefined) => EditorTableIdentifier | null;
}

const hoverHelpDefaultConfig: HoverHelperInfo = {
  open: false,
  hoverInfo: null,
  position: {
    left: 0,
    top: 0,
  },
  editor: null,
  mouse: null,
};

const getTableDDLTriggerMode = (editorSettings?: EditorSettings) => editorSettings?.tableDDLTriggerMode || 'hover';

const toArray = <T,>(value: T[] | null | undefined): T[] => (Array.isArray(value) ? value : []);
type EditorHintsListener = (editorHints: ISqlEditorHintVO[]) => void;

type BackendEditorHintsSource = 'completion' | 'content' | 'parser';

interface ApplyBackendEditorHintsOptions {
  source: BackendEditorHintsSource;
  scope?: SqlCompletionHintScope | null;
}

const SQLEditor = forwardRef<SQLEditorRef, SQLEditorProps>(
  (
    {
      id,
      defaultValue,
      dbInfo,
      active,
      onChange,
      onContextMenu,
      className,
      readOnly,
      options,
      action,
      contextMenuInfo,
      enableContentDiffHints,
      onMount,
      onReady,
      onTableIdentifierContextChange,
      ...rest
    },
    ref,
  ) => {
    const { styles } = useStyles();
    const [sqlTemp, setSqlTemp] = useState<string>(defaultValue ?? '');
    const [hoverHelperInfo, setHoverHelpInfo] = useState<HoverHelperInfo>(hoverHelpDefaultConfig);

    const completionProvider = useRef<CompletionProviderManager>();
    const editorRef = useRef<MonacoEditorRef | null>(null);
    const decorationCollectionRef = useRef<monaco.editor.IEditorDecorationsCollection | null>(null);
    const sqlValueTypeHintCollectionRef = useRef<monaco.editor.IEditorDecorationsCollection | null>(null);
    const sqlValueTypeHintsRef = useRef<ISqlEditorHintVO[]>([]);
    const sqlValueTypeHintSnapshotRef = useRef<string | null>(null);
    const parameterHintWidgetRef = useRef<ParameterHintWidget | null>(null);
    const insertValueHintActionRef = useRef<monaco.IDisposable | null>(null);
    const insertValueHintKeydownRef = useRef<monaco.IDisposable | null>(null);
    const insertValueHintBlurRef = useRef<monaco.IDisposable | null>(null);
    const parameterHintFocusRef = useRef<monaco.IDisposable | null>(null);
    const snippetPlaceholderCompletionRef = useRef<monaco.IDisposable | null>(null);
    const parameterHintVisibleRef = useRef(false);
    const parameterHintAutoSuppressedRef = useRef(false);
    const parameterHintFocusedRef = useRef(false);
    const backendEditorHintStoreRef = useRef(createSqlCompletionHintStore());
    const backendEditorHintsRef = useRef<ISqlEditorHintVO[]>([]);
    const backendEditorHintsListenerRef = useRef<EditorHintsListener | null>(null);
    const backendEditorHintsRequestRef = useRef(0);
    const backendEditorHintsEpochRef = useRef(0);
    const autoFillEditInProgressRef = useRef(false);
    const cursorPositionRef = useRef<HTMLDivElement | null>(null);

    const sqlStatementListRef = useRef<SqlStatement[]>([]);
    const markMessageListRef = useRef<MarkMessage[]>([]);
    const tableDDLTriggerMode = useGlobalStore((s) => getTableDDLTriggerMode(s.editorSettings));
    const shortcutOverrides = useGlobalStore((s) => s.shortcutOverrides);
    const [isObjectClickModifierPressed, setIsObjectClickModifierPressed] = useState(false);
    const shortcutConfig = getEffectiveShortcutConfigMap(shortcutOverrides as ShortcutOverrides);

    useImperativeHandle(ref, () => ({
      getId: () => id,
      getInstance,
      getValue,
      setValue,
      getContentDiffBaseline: () => editorRef.current?.getContentDiffBaseline() ?? '',
      resetContentDiffBaseline: (value?: string) => {
        editorRef.current?.resetContentDiffBaseline(value);
      },
      getSelectedContent: () => {
        return editorRef.current?.getSelectedContent() ?? '';
      },
      getCursorCurLineNearestSQL,
      // Get the SQL statement at the current cursor position.
      getCursorSQL,
      handleSQLParser: handleSQLParserRightNow,
      handleQuickSQLParser: (sql, _dbInfo) => handleSQLParserRightNow(sql, _dbInfo, true),
      getTableIdentifierAtPosition,
    }));

    const getInstance = useCallback(() => {
      return editorRef?.current?.getInstance() ?? null;
    }, []);

    const getValue = useCallback(() => {
      return editorRef.current?.getValue() ?? '';
    }, []);

    const syncBackendCompletionModel = useCallback(
      (editor: monaco.editor.IStandaloneCodeEditor | null) => {
        const model = editor?.getModel();
        if (!model) {
          return;
        }
        const backendCompletionMode = isBackendCompletionDatabaseType(dbInfo.databaseType);
        setBackendCompletionModel(model, backendCompletionMode);
        completionProvider.current?.bindModelDBInfo(model, dbInfo);
        if (!backendCompletionMode) {
          backendEditorHintsRequestRef.current += 1;
          backendEditorHintsEpochRef.current += 1;
          backendEditorHintsRef.current = backendEditorHintStoreRef.current.clear();
        }
      },
      [dbInfo],
    );

    const setValue = useCallback((value: string, type?: EditorSetValueType) => {
      editorRef.current?.setValue(value, type);
    }, []);

    const bindSnippetPlaceholderCompletion = useCallback((editor: monaco.editor.IStandaloneCodeEditor | null) => {
      safelyDisposeEditorResource(() => snippetPlaceholderCompletionRef.current?.dispose());
      snippetPlaceholderCompletionRef.current =
        completionProvider.current?.bindSnippetPlaceholderCompletion(editor) ?? null;
    }, []);

    function getTableIdentifierAtPosition(position: monaco.IPosition | null | undefined) {
      return findTableIdentifierAtPosition(position, sqlStatementListRef.current, dbInfo);
    }

    useEffect(() => {
      // Close hover help when contextMenuInfo changes.
      setHoverHelpInfo(hoverHelpDefaultConfig);
    }, [contextMenuInfo]);

    useEffect(() => {
      return () => {
        safelyDisposeEditorResource(() => decorationCollectionRef.current?.clear());
        decorationCollectionRef.current = null;
        safelyDisposeEditorResource(() => sqlValueTypeHintCollectionRef.current?.clear());
        sqlValueTypeHintCollectionRef.current = null;
        safelyDisposeEditorResource(() => insertValueHintActionRef.current?.dispose());
        safelyDisposeEditorResource(() => insertValueHintKeydownRef.current?.dispose());
        safelyDisposeEditorResource(() => insertValueHintBlurRef.current?.dispose());
        safelyDisposeEditorResource(() => parameterHintFocusRef.current?.dispose());
        safelyDisposeEditorResource(() => snippetPlaceholderCompletionRef.current?.dispose());
        safelyDisposeEditorResource(() => parameterHintWidgetRef.current?.dispose());
        if (backendEditorHintsListenerRef.current) {
          completionProvider.current?.clearEditorHintsListener(
            backendEditorHintsListenerRef.current,
            getInstance()?.getModel(),
          );
        }
        backendEditorHintsRequestRef.current += 1;
        backendEditorHintsEpochRef.current += 1;
        backendEditorHintsRef.current = backendEditorHintStoreRef.current.clear();
        refreshBackendParameterHints.cancel();
        handleSQLParser.cancel();
        completionProvider.current?.clearModelDBInfo(getInstance()?.getModel());
        setBackendCompletionModel(getInstance()?.getModel(), false);
        insertValueHintActionRef.current = null;
        insertValueHintKeydownRef.current = null;
        insertValueHintBlurRef.current = null;
        parameterHintFocusRef.current = null;
        snippetPlaceholderCompletionRef.current = null;
        parameterHintWidgetRef.current = null;
        backendEditorHintsListenerRef.current = null;
      };
    }, [getInstance]);

    useEffect(() => {
      syncBackendCompletionModel(getInstance());
    }, [getInstance, syncBackendCompletionModel]);

    useEffect(() => {
      if (active && dbInfo) {
        if (!completionProvider.current) {
          completionProvider.current = CompletionProviderManager.getInstance(dbInfo);
        } else {
          completionProvider.current?.changeDBInfo(dbInfo);
        }
        const provider = completionProvider.current;
        const editor = getInstance();
        const model = editor?.getModel();
        syncBackendCompletionModel(editor);
        bindSnippetPlaceholderCompletion(editor);
        const editorHintsListener: EditorHintsListener = (editorHints) => {
          backendEditorHintsRequestRef.current += 1;
          applyBackendEditorHints(withoutSqlValueTypeHints(editorHints), { source: 'completion' });
        };
        backendEditorHintsListenerRef.current = editorHintsListener;
        completionProvider.current.setEditorHintsListener(editorHintsListener, model);
        return () => {
          safelyDisposeEditorResource(() => snippetPlaceholderCompletionRef.current?.dispose());
          snippetPlaceholderCompletionRef.current = null;
          if (completionProvider.current === provider) {
            completionProvider.current?.clearEditorHintsListener(editorHintsListener, model);
          }
          if (backendEditorHintsListenerRef.current === editorHintsListener) {
            backendEditorHintsListenerRef.current = null;
          }
        };
      }
      return undefined;
    }, [active, bindSnippetPlaceholderCompletion, dbInfo, getInstance, syncBackendCompletionModel]);

    useEffect(() => {
      const { dataSourceId } = dbInfo || {};
      if (dataSourceId && active) {
        const sql = getValue();
        handleSQLParser(sql);
      }
    }, [active]);

    useEffect(() => {
      if (sqlTemp) {
        handleSQLParser(sqlTemp);
      }
    }, [sqlTemp, dbInfo]);

    const handleSQLParserRightNow = async (sql: string, _dbInfo: IBoundInfo, isQuick: boolean = false) => {
      const { dataSourceId, databaseName, schemaName } = _dbInfo;
      if (!dataSourceId) return;
      if (!sql) {
        sqlStatementListRef.current = [];
        markMessageListRef.current = [];
        clearBackendEditorHints();
        hideParameterHint();
        return;
      }

      // Skip parsing SQL statements longer than 50,000 characters.
      if (sql.length >= 50000) {
        return;
      }

      const queryParser = isQuick ? SQLParserService.queryQuickSQLParser : SQLParserService.querySQLParser;

      const parser = await queryParser({
        consoleId: _dbInfo.consoleId!,
        sql,
        dataSourceId,
        databaseName,
        schemaName,
      });
      const { sqlStatementList, markMessageList } = parser || {};
      const nextSqlStatementList = sqlStatementList || [];
      const nextMarkMessageList = markMessageList || [];
      sqlStatementListRef.current = nextSqlStatementList;
      markMessageListRef.current = nextMarkMessageList;

      if (completionProvider.current) {
        const editor = getInstance();
        if (!editor) return '';
        const position = editor.getPosition();
        if (!position) return '';
        const curStatement = findSqlStatement(position, nextSqlStatementList);

        completionProvider.current.onParserChange(nextSqlStatementList, curStatement);
      }
      updateMarkMessage();
      updateDecoration(getInstance(), decorationCollectionRef.current);
      updateParameterHint(getInstance(), localInsertValueParameterHint(getInstance()));
      refreshBackendParameterHints(getInstance(), 'parser');
    };

    const handleSQLParser = useCallback(
      debounce((sql) => {
        handleSQLParserRightNow(sql, dbInfo);
      }, 500),
      [dbInfo],
    );

    useEffect(() => {
      return () => {
        handleSQLParser.cancel();
      };
    }, [handleSQLParser]);

    const closeHoverHelp = useCallback(() => {
      setHoverHelpInfo(hoverHelpDefaultConfig);
    }, []);

    const handleValueChange = useCallback(
      (
        sql: string,
        editor: monaco.editor.IStandaloneCodeEditor,
        changePosition?: monaco.Position | null,
        changeRange?: monaco.IRange | null,
      ) => {
        if (!editor) return;

        setSqlTemp(sql);
        onChange && onChange?.(sql);

        const isAutoFillChange = autoFillEditInProgressRef.current;
        autoFillEditInProgressRef.current = false;
        const preservedValueTypeHints = sqlValueTypeHintSnapshotRef.current === sql
          ? sqlValueTypeHintsRef.current
          : rematerializeInsertValueHints(sql, sqlValueTypeHintsRef.current, sqlValueTypeHintSnapshotRef.current);
        if (!preservedValueTypeHints.length) {
          clearSqlValueTypeHints();
        }
        clearBackendEditorHints();
        decorationCollectionRef.current?.clear();
        if (!sql.trim()) {
          backendEditorHintsRequestRef.current += 1;
          backendEditorHintsEpochRef.current += 1;
          clearBackendEditorHints();
          hideParameterHint();
          return;
        }
        if (isAutoFillChange || preservedValueTypeHints.length) {
          if (preservedValueTypeHints.length) {
            window.requestAnimationFrame(() => {
              if (editor.getModel()?.getValue() === sql) {
                showSqlValueTypeHints(editor, preservedValueTypeHints);
              }
            });
          }
          setHoverHelpInfo(hoverHelpDefaultConfig);
          if (isAutoFillChange) {
            return;
          }
        }
        refreshBackendParameterHints(
          editor,
          'content',
          changePosition,
          getEditorHintScope('content', changePosition, changeRange),
        );

        // Close hover help when editor content changes
        setHoverHelpInfo(hoverHelpDefaultConfig);
      },
      [dbInfo],
    );

    const handleCursorChange = useCallback((editor: monaco.editor.IStandaloneCodeEditor) => {
      if (!editor) return;

      const position = editor.getPosition();
      if (position && cursorPositionRef.current) {
        cursorPositionRef.current.textContent = `Ln ${position.lineNumber}, Col ${position.column}`;
      }

      if (!decorationCollectionRef.current) {
        decorationCollectionRef.current = editor.createDecorationsCollection();
      }

      decorationCollectionRef.current.clear();
      updateDecoration(editor, decorationCollectionRef.current);
      updateParameterHint(editor, localInsertValueParameterHint(editor));
    }, []);

    const handleMouseClick = (editor: monaco.editor.IStandaloneCodeEditor, e: monaco.editor.IEditorMouseEvent) => {
      if (!editor) return;
      if (e.target.type === monaco.editor.MouseTargetType.GUTTER_GLYPH_MARGIN) {
        const target = e.target.element;
        if (target instanceof HTMLDivElement && target.classList.contains('execute-button-glyph')) {
          handleClickExecuteButton();
        }
        return;
      }

      if (
        getTableDDLTriggerMode(useGlobalStore.getState().editorSettings) === 'click' &&
        isCtrlOrMetaClick(e) &&
        e.target.type === monaco.editor.MouseTargetType.CONTENT_TEXT
      ) {
        const tableIdentifier = getTableIdentifierAtPosition(e.target.position);
        if (tableIdentifier) {
          e.event.preventDefault();
          action(SQLOptType.VIEW_TABLE_DDL, tableIdentifier);
        }
      }
    };

    // Update decorations.
    const updateDecoration = (
      editor: monaco.editor.IStandaloneCodeEditor | null,
      decorationCollection: monaco.editor.IEditorDecorationsCollection | null,
    ) => {
      if (!editor || !decorationCollection) return;
      decorationCollection.clear();

      const position = editor.getPosition();
      const model = editor.getModel();
      const nextDecorations: monaco.editor.IModelDeltaDecoration[] =
        getTableDDLTriggerMode(useGlobalStore.getState().editorSettings) === 'click' && isObjectClickModifierPressed
          ? toArray(setTableIdentifierDecorations(getTableIdentifierUnderlineRanges(sqlStatementListRef.current)))
          : [];
      if (position && model) {
        const currentStatement = findSqlStatement(position, sqlStatementListRef.current);
        const insertValueHint = getInsertValueHintContext(currentStatement, position, (range) =>
          model.getValueInRange(range),
        );
        const insertValueHighlight = insertValueHint?.highlightRanges?.length
          ? insertValueHint.highlightRanges
          : getInsertValueHighlightRanges(currentStatement, editor.getSelection());
        if (insertValueHighlight.length) {
          nextDecorations.push(...toArray(setInsertValueHighlightDecorations(insertValueHighlight)));
        }

        const sqlStartLine = currentStatement ? currentStatement.sqlStartRowNum : null;
        if (!readOnly && sqlStartLine && currentStatement?.statementType === StatementValidTypeEnum.VALID) {
          const executeButton = setExecuteButtonDecorations([sqlStartLine]);

          nextDecorations.push(...toArray(executeButton));
        }
      }

      decorationCollection.set(nextDecorations);
    };

    function getParameterHintWidget(editor: monaco.editor.IStandaloneCodeEditor) {
      if (!parameterHintWidgetRef.current) {
        parameterHintWidgetRef.current = new ParameterHintWidget(editor);
      }
      return parameterHintWidgetRef.current;
    }

    function hideParameterHint(hideOptions?: { suppressAuto?: boolean }) {
      parameterHintVisibleRef.current = false;
      if (hideOptions?.suppressAuto) {
        parameterHintAutoSuppressedRef.current = true;
      }
      parameterHintWidgetRef.current?.hide();
    }

    function clearBackendEditorHints() {
      backendEditorHintsRef.current = backendEditorHintStoreRef.current.clear();
    }

    function clearSqlValueTypeHints() {
      sqlValueTypeHintCollectionRef.current?.clear();
      sqlValueTypeHintsRef.current = [];
      sqlValueTypeHintSnapshotRef.current = null;
    }

    function showSqlValueTypeHints(
      editor: monaco.editor.IStandaloneCodeEditor,
      editorHints: ISqlEditorHintVO[],
    ) {
      if (!sqlValueTypeHintCollectionRef.current) {
        sqlValueTypeHintCollectionRef.current = editor.createDecorationsCollection();
      }
      sqlValueTypeHintsRef.current = editorHints;
      sqlValueTypeHintSnapshotRef.current = editor.getModel()?.getValue() || null;
      sqlValueTypeHintCollectionRef.current.set(setSqlValueTypeHintDecorations(editorHints));
    }

    function withoutSqlValueTypeHints(editorHints: ISqlEditorHintVO[] | null | undefined) {
      return (editorHints || []).filter((hint) => hint.type !== 'INSERT_VALUE');
    }

    function applyBackendEditorHints(editorHints: ISqlEditorHintVO[], hintOptions?: ApplyBackendEditorHintsOptions) {
      const editor = getInstance();
      if (hintOptions?.scope) {
        backendEditorHintsRef.current = backendEditorHintStoreRef.current.commitScoped(hintOptions.scope, editorHints);
      } else if (editorHints.length > 0) {
        backendEditorHintsRef.current = backendEditorHintStoreRef.current.commitHints(editorHints);
      } else {
        backendEditorHintsRef.current = backendEditorHintStoreRef.current.clear();
      }
      updateDecoration(editor, decorationCollectionRef.current);
      updateParameterHint(editor, localInsertValueParameterHint(editor));
    }

    function getEditorHintScope(
      source: BackendEditorHintsSource,
      position?: monaco.Position | null,
      changeRange?: monaco.IRange | null,
    ): SqlCompletionHintScope | null {
      if (source !== 'content') {
        return null;
      }
      if (changeRange) {
        return sqlCompletionHintScopeFromRange(source, {
          startLineNumber: changeRange.startLineNumber,
          startColumn: changeRange.startColumn,
          endLineNumber: changeRange.endLineNumber,
          endColumn: changeRange.endColumn,
        });
      }
      const currentPosition = position || getInstance()?.getPosition();
      if (!currentPosition) {
        return null;
      }
      return sqlCompletionHintScopeFromRange(source, {
        startLineNumber: currentPosition.lineNumber,
        startColumn: currentPosition.column,
        endLineNumber: currentPosition.lineNumber,
        endColumn: currentPosition.column,
      });
    }

    function localInsertValueParameterHint(
      editor: monaco.editor.IStandaloneCodeEditor | null,
    ): ParameterHintContext | null {
      if (!editor) {
        return null;
      }

      const position = editor.getPosition();
      const model = editor.getModel();
      if (!position || !model) {
        return null;
      }

      const currentStatement = findSqlStatement(position, sqlStatementListRef.current);
      const context = getInsertValueHintContext(currentStatement, position, (range) =>
        model.getValueInRange(range),
      );
      return parameterHintContextFromInsertValue(context);
    }

    function updateParameterHint(
      editor: monaco.editor.IStandaloneCodeEditor | null,
      fallbackContext?: ParameterHintContext | null,
      forceOpen = false,
    ) {
      if (!editor) {
        hideParameterHint();
        return null;
      }
      const position = editor.getPosition();
      const context =
        parameterHintContextFromEditorHints(backendEditorHintsRef.current, position) || fallbackContext || null;
      if (!context) {
        hideParameterHint();
        return null;
      }
      if (context.source === 'INSERT_VALUE') {
        hideParameterHint();
        return context;
      }
      if (!forceOpen && !parameterHintFocusedRef.current) {
        return context;
      }
      if (forceOpen) {
        parameterHintAutoSuppressedRef.current = false;
      } else if (parameterHintAutoSuppressedRef.current) {
        return context;
      }
      if (forceOpen || !parameterHintVisibleRef.current) {
        parameterHintVisibleRef.current = true;
      }
      if (parameterHintVisibleRef.current) {
        getParameterHintWidget(editor).show(context);
      }
      return context;
    }

    const refreshBackendParameterHints = useCallback(
      debounce(async (
        editor: monaco.editor.IStandaloneCodeEditor | null,
        source: BackendEditorHintsSource,
        positionSnapshot?: monaco.Position | null,
        scope?: SqlCompletionHintScope | null,
      ) => {
        if (!isBackendEditorHintsDatabaseType(dbInfo.databaseType) || !completionProvider.current || !editor) {
          return;
        }
        const model = editor.getModel();
        const position = positionSnapshot || editor.getPosition();
        if (!model || !position) {
          return;
        }
        const modelVersionId = model.getVersionId();
        const requestEpoch = backendEditorHintsEpochRef.current;
        const requestId = backendEditorHintsRequestRef.current + 1;
        backendEditorHintsRequestRef.current = requestId;
        const editorHints = await completionProvider.current.refreshEditorHints({
          model,
          sql: model.getValue(),
          cursor: model.getOffsetAt(position),
        });
        if (
          requestId !== backendEditorHintsRequestRef.current ||
          requestEpoch !== backendEditorHintsEpochRef.current ||
          editorHints === null ||
          model.getVersionId() !== modelVersionId
        ) {
          return;
        }
        const autoFill = source === 'content' && !readOnly
          ? getInsertValueAutoFill(model.getValue(), model.getOffsetAt(position), editorHints)
          : null;
        if (autoFill) {
          editor.pushUndoStop();
          autoFillEditInProgressRef.current = true;
          const applied = editor.executeEdits('chat2db-insert-value-defaults', [{
            range: new monaco.Range(position.lineNumber, position.column, position.lineNumber, position.column),
            text: autoFill.text,
            forceMoveMarkers: true,
          }]);
          if (applied) {
            refreshBackendParameterHints.cancel();
            const insertionOffset = model.getOffsetAt(position);
            const filledSql = model.getValue();
            const materializedHints = materializeInsertValueAutoFillHints(
              filledSql,
              insertionOffset,
              editorHints,
            );
            const accumulatedHints = mergeInsertValueHints(
              filledSql,
              sqlValueTypeHintsRef.current,
              materializedHints,
            );
            editor.setSelection(new monaco.Selection(
              position.lineNumber,
              position.column,
              position.lineNumber,
              position.column + autoFill.firstValueLength,
            ));
            editor.pushUndoStop();
            window.requestAnimationFrame(() => {
              if (getInstance()?.getModel() === model && model.getValue() === filledSql) {
                showSqlValueTypeHints(editor, accumulatedHints);
              }
            });
            return;
          }
          autoFillEditInProgressRef.current = false;
        }
        applyBackendEditorHints(withoutSqlValueTypeHints(editorHints), { source, scope });
      }, 150),
      [dbInfo.databaseType, readOnly],
    );

    useEffect(() => {
      return () => {
        refreshBackendParameterHints.cancel();
      };
    }, [refreshBackendParameterHints]);

    useEffect(() => {
      backendEditorHintsRequestRef.current += 1;
      backendEditorHintsEpochRef.current += 1;
      refreshBackendParameterHints.cancel();
      clearBackendEditorHints();
      hideParameterHint();
    }, [
      dbInfo.consoleId,
      dbInfo.dataSourceId,
      dbInfo.databaseName,
      dbInfo.schemaName,
      dbInfo.databaseType,
      getInstance,
      refreshBackendParameterHints,
    ]);

    useEffect(() => {
      updateDecoration(getInstance(), decorationCollectionRef.current);
    }, [tableDDLTriggerMode, isObjectClickModifierPressed]);

    useEffect(() => {
      const handleModifierKeyDown = (event: KeyboardEvent) => {
        if (event.ctrlKey || event.metaKey) {
          setIsObjectClickModifierPressed(true);
        }
      };
      const handleModifierKeyUp = (event: KeyboardEvent) => {
        if (!event.ctrlKey && !event.metaKey) {
          setIsObjectClickModifierPressed(false);
        }
      };
      const handleBlur = () => {
        setIsObjectClickModifierPressed(false);
      };

      window.addEventListener('keydown', handleModifierKeyDown);
      window.addEventListener('keyup', handleModifierKeyUp);
      window.addEventListener('blur', handleBlur);
      return () => {
        window.removeEventListener('keydown', handleModifierKeyDown);
        window.removeEventListener('keyup', handleModifierKeyUp);
        window.removeEventListener('blur', handleBlur);
      };
    }, []);

    useEffect(() => {
      if (tableDDLTriggerMode !== 'hover') {
        setHoverHelpInfo(hoverHelpDefaultConfig);
      }
    }, [tableDDLTriggerMode]);

    useEffect(() => {
      const editor = getInstance();
      if (!editor) {
        return;
      }

      safelyDisposeEditorResource(() => insertValueHintActionRef.current?.dispose());
      safelyDisposeEditorResource(() => insertValueHintKeydownRef.current?.dispose());
      insertValueHintActionRef.current = editor.addAction({
        id: INSERT_VALUE_HINT_ACTION_ID,
        label: i18n('sqlEditor.hint.insertValueParameter'),
        keybindings: (() => {
          const keybinding = shortcutBindingToMonacoKeybinding(
            shortcutConfig[ShortcutAction.SqlInsertValueHint].binding,
            monaco,
          );
          return keybinding ? [keybinding] : [];
        })(),
        run: () => {
          updateParameterHint(editor, localInsertValueParameterHint(editor), true);
        },
      });
      insertValueHintKeydownRef.current = editor.onKeyDown((event) => {
        if (event.code === EDITOR_ESCAPE_KEY_CODE && parameterHintVisibleRef.current) {
          hideParameterHint({ suppressAuto: true });
          event.preventDefault();
          event.stopPropagation();
          return;
        }
        if (isShortcutEventMatch(event.browserEvent, shortcutConfig[ShortcutAction.SqlInsertValueHint].binding)) {
          const context = updateParameterHint(editor, localInsertValueParameterHint(editor), true);
          if (context) {
            event.preventDefault();
            event.stopPropagation();
          }
        }
      });
    }, [getInstance, shortcutConfig]);

    const handleContextMenu = useCallback(
      (e: monaco.editor.IEditorMouseEvent) => {
        onTableIdentifierContextChange?.(getTableIdentifierAtPosition(e.target.position));
        onContextMenu?.(e);
      },
      [onContextMenu, onTableIdentifierContextChange, dbInfo],
    );

    /**
     * Update error markers
     */
    const updateMarkMessage = useCallback(
      debounce(() => {
        const editor = getInstance();
        if (!editor) return;

        const model = editor.getModel();
        if (!model) return;

        const syntaxMarkers = toArray(markMessageListRef.current);
        const insertValueMismatchMarkers = toArray(
          getInsertValueMismatchMarkersWithMessage(sqlStatementListRef.current || [], (expectedCount, actualCount) =>
            expectedCount !== undefined && actualCount !== undefined
              ? i18n('sqlEditor.warning.insertValueCountMismatch', expectedCount, actualCount)
              : i18n('sqlEditor.warning.insertValueMismatch'),
          ),
        );
        setModelMarkers(model, editor.getId(), [...syntaxMarkers, ...insertValueMismatchMarkers]);
      }, 300),
      [],
    );

    const handleClickExecuteButton = async () => {
      if (readOnly) {
        return;
      }
      const editor = getInstance();
      if (!editor) return;

      const position = editor.getPosition();
      if (!position) return;

      await handleSQLParserRightNow(editor.getValue(), dbInfo, true);

      // Find the current SQL statement.
      const currentStatement = (sqlStatementListRef.current || []).find(
        (stmt) => position?.lineNumber >= stmt.sqlStartRowNum && position?.lineNumber <= stmt.sqlEndRowNum,
      );
      if (!currentStatement) return;

      // Execute the SQL statement.
      action(SQLOptType.EXECUTE_SINGLE_SQL, currentStatement?.sql ?? '');
    };

    const handleHover = useCallback(
      async (editor: monaco.editor.IStandaloneCodeEditor, mouse: monaco.editor.IEditorMouseEvent) => {
        if (getTableDDLTriggerMode(useGlobalStore.getState().editorSettings) !== 'hover') {
          setHoverHelpInfo(hoverHelpDefaultConfig);
          return;
        }

        const hoverInfoArr = await onHoverEditor(editor, mouse, {
          dbInfo,
          sqlStatementListRef,
        });

        if (!hoverInfoArr?.length || contextMenuInfo?.open) {
          setHoverHelpInfo(hoverHelpDefaultConfig);
          return;
        }

        const { posx: left, posy: top } = mouse.event;

        setHoverHelpInfo({
          open: true,
          hoverInfo: hoverInfoArr[0],
          position: { left: `${left}px`, top: `${top}px` },
          editor,
          mouse,
        });
      },
      [dbInfo],
    );

    const handleEditorMount = useCallback(
      (editor: monaco.editor.IStandaloneCodeEditor) => {
        safelyDisposeEditorResource(() => decorationCollectionRef.current?.clear());
        decorationCollectionRef.current = editor.createDecorationsCollection();

        safelyDisposeEditorResource(() => parameterHintWidgetRef.current?.dispose());
        parameterHintWidgetRef.current = new ParameterHintWidget(editor);
        safelyDisposeEditorResource(() => insertValueHintActionRef.current?.dispose());
        safelyDisposeEditorResource(() => insertValueHintKeydownRef.current?.dispose());
        safelyDisposeEditorResource(() => insertValueHintBlurRef.current?.dispose());
        safelyDisposeEditorResource(() => parameterHintFocusRef.current?.dispose());
        insertValueHintBlurRef.current = editor.onDidBlurEditorText(() => {
          parameterHintFocusedRef.current = false;
          hideParameterHint();
        });
        parameterHintFocusRef.current = editor.onDidFocusEditorText(() => {
          parameterHintFocusedRef.current = true;
          updateParameterHint(editor, localInsertValueParameterHint(editor));
        });
        parameterHintFocusedRef.current = editor.hasTextFocus();
        syncBackendCompletionModel(editor);
        bindSnippetPlaceholderCompletion(editor);
        onReady?.();
        onMount && onMount(editor);
      },
      [bindSnippetPlaceholderCompletion, handleHover, onMount, onReady, syncBackendCompletionModel],
    );

    /**
     * Get the SQL statement at the current cursor position
     */
    const getCursorSQL = useCallback(() => {
      const editor = getInstance();
      if (!editor) return '';
      const position = editor.getPosition();
      if (!position) return '';
      const curStatement = findSqlStatement(position, sqlStatementListRef.current);
      return curStatement?.sql ?? '';
    }, []);

    /**
     * Get the nearest SQL statement to the current cursor line
     */
    const getCursorCurLineNearestSQL = useCallback(() => {
      const editor = getInstance();
      if (!editor) return '';
      const position = editor.getPosition();
      if (!position) return '';
      const curStatement = findNearestSQL(position, sqlStatementListRef.current);
      return curStatement?.sql ?? '';
    }, []);

    return (
      <div className={styles.editor}>
        <div className={styles.editorBody}>
          <MonacoEditor
            {...rest}
            options={{
              ...(options || {}),
              ...(readOnly === undefined ? {} : { readOnly }),
            }}
            id={id}
            ref={editorRef}
            className={className}
            defaultValue={defaultValue}
            onMount={handleEditorMount}
            onChange={handleValueChange}
            onCursorChange={handleCursorChange}
            onMouseClick={handleMouseClick}
            onContextMenu={handleContextMenu}
            onHover={handleHover}
            enableContentDiffHints={enableContentDiffHints}
          />
          <HoverHelp
            hoverHelperInfo={hoverHelperInfo}
            onClose={closeHoverHelp}
            canShow={() => !contextMenuInfo?.open}
          />
        </div>
        <div ref={cursorPositionRef} className={styles.cursorStatus}>
          Ln 1, Col 1
        </div>
      </div>
    );
  },
);
export default SQLEditor;

function isCtrlOrMetaClick(e: monaco.editor.IEditorMouseEvent) {
  return !!(e.event.ctrlKey || e.event.metaKey);
}

function safelyDisposeEditorResource(dispose: () => void) {
  try {
    dispose();
  } catch {
    // Monaco can cancel async work while the editor is being disposed.
  }
}
