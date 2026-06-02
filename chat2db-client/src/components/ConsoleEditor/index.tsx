import Popularize from '@/components/Popularize';
import i18n from '@/i18n';
import { IBoundInfo } from '@/typings';
import { requestAiSqlCompletion, IAiSqlCompletionTask } from '@/service/aiSqlCompletion';
import { Modal, Spin, message } from 'antd';
import React, {
  createContext,
  ForwardedRef,
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from 'react';
import { v4 as uuidv4 } from 'uuid';
import MonacoEditor, {
  IEditorIns,
  IEditorOptions,
  IExportRefFunction,
  IRangeType,
  ISelectedContentInfo,
  registerVinPasteTransform,
} from '../MonacoEditor';
import OperationLine from './components/OperationLine';
import styles from './index.less';

// ----- hooks -----
import { useSaveEditorData } from './hooks/useSaveEditorData';

// ----- store -----
import { setCurrentWorkspaceExtend, setPendingAiChat, IAiChatPromptType } from '@/pages/main/workspace/store/common';

// ----- function -----
import { handelCreateConsole } from '@/pages/main/workspace/functions/shortcutKeyCreateConsole';

export type IAppendValue = {
  text: any;
  range?: IRangeType;
};

interface IProps {
  /** 调用来源 */
  source?: 'workspace';
  isActive: boolean;
  /** 添加或修改的内容 */
  appendValue?: IAppendValue;
  defaultValue?: string;
  /** 是否可以开启SQL转到自然语言的相关ai操作 */
  hasAi2Lang?: boolean;
  /** 是否有 */
  hasSaveBtn?: boolean;
  value?: string;
  boundInfo: IBoundInfo;
  setBoundInfo: (params: IBoundInfo) => void;
  editorOptions?: IEditorOptions;
  onExecuteSQL: (sql: string, selectedInfo?: ISelectedContentInfo | null) => void;
}

export interface IConsoleRef {
  editorRef: IExportRefFunction | undefined;
}

interface IIntelligentEditorContext {
  isActive: boolean;
  tableNameList: string[];
  setTableNameList: (tables: string[]) => void;
}

export const IntelligentEditorContext = createContext<IIntelligentEditorContext>({} as any);

interface IAiCompletionContext {
  message: string;
  ext?: string;
  replaceRange: any;
  originalText: string;
}

interface IStatementBoundary {
  start: number;
  end: number;
}

const isAiCompletionShortcut = (event: KeyboardEvent) =>
  event.altKey &&
  !event.ctrlKey &&
  !event.metaKey &&
  (event.key === '\\' || event.code === 'Backslash' || event.code === 'IntlBackslash');

const getTrimmedBoundary = (sql: string, boundary: IStatementBoundary): IStatementBoundary => {
  let start = boundary.start;
  let end = boundary.end;

  while (start < end && /\s/.test(sql[start])) {
    start++;
  }
  while (end > start && /\s/.test(sql[end - 1])) {
    end--;
  }

  return { start, end };
};

const findStatementBoundary = (sql: string, cursorOffset: number): IStatementBoundary => {
  const boundaries: IStatementBoundary[] = [];
  let start = 0;
  let quote: "'" | '"' | '`' | '[' | null = null;
  let inLineComment = false;
  let inBlockComment = false;

  for (let i = 0; i < sql.length; i++) {
    const char = sql[i];
    const next = sql[i + 1];

    if (inLineComment) {
      if (char === '\n' || char === '\r') {
        inLineComment = false;
      }
      continue;
    }

    if (inBlockComment) {
      if (char === '*' && next === '/') {
        inBlockComment = false;
        i++;
      }
      continue;
    }

    if (quote) {
      if (quote === "'" && char === "'" && next === "'") {
        i++;
        continue;
      }
      if (quote === '"' && char === '"' && next === '"') {
        i++;
        continue;
      }
      if (quote === '`' && char === '`' && next === '`') {
        i++;
        continue;
      }
      if (
        (quote === "'" && char === "'") ||
        (quote === '"' && char === '"') ||
        (quote === '`' && char === '`') ||
        (quote === '[' && char === ']')
      ) {
        quote = null;
      }
      continue;
    }

    if (char === '-' && next === '-') {
      inLineComment = true;
      i++;
      continue;
    }

    if (char === '/' && next === '*') {
      inBlockComment = true;
      i++;
      continue;
    }

    if (char === "'" || char === '"' || char === '`' || char === '[') {
      quote = char as typeof quote;
      continue;
    }

    if (char === ';') {
      boundaries.push({ start, end: i });
      start = i + 1;
    }
  }

  boundaries.push({ start, end: sql.length });

  const containingBoundary = boundaries.find(
    (boundary) => cursorOffset >= boundary.start && cursorOffset <= boundary.end,
  );
  const preferredBoundary = containingBoundary || boundaries[boundaries.length - 1];
  const preferredIndex = boundaries.indexOf(preferredBoundary);
  const searchOrder = [
    preferredBoundary,
    ...boundaries.slice(preferredIndex + 1),
    ...boundaries.slice(0, preferredIndex).reverse(),
  ];

  for (const boundary of searchOrder) {
    const trimmedBoundary = getTrimmedBoundary(sql, boundary);
    if (trimmedBoundary.start < trimmedBoundary.end) {
      return trimmedBoundary;
    }
  }

  return { start: cursorOffset, end: cursorOffset };
};

function ConsoleEditor(props: IProps, ref: ForwardedRef<IConsoleRef>) {
  const { boundInfo, setBoundInfo, appendValue, hasSaveBtn = true, source, defaultValue, isActive } = props;
  const uid = useMemo(() => uuidv4(), []);
  const editorRef = useRef<IExportRefFunction>();
  const disposeVinPasteTransformRef = useRef<(() => void) | undefined>();
  const shortcutRegisteredRef = useRef(false);
  const aiCompletionTaskRef = useRef<IAiSqlCompletionTask | null>(null);
  const triggerAiCompletionRef = useRef<(editor: IEditorIns, monaco: any) => void>();
  const aiCompletionVisibleKeyRef = useRef<any>(null);
  const aiCompletionStateRef = useRef<{
    id: string;
    text: string;
    range: any;
    replaceRange: any;
    originalText: string;
  } | null>(null);
  const [tableNameList, setTableNameList] = useState<string[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [popularizeModal, setPopularizeModal] = useState(false);
  const [modalProps] = useState({});
  const [aiCompletion, setAiCompletion] = useState<{
    id: string;
    text: string;
    range: any;
    replaceRange: any;
    originalText: string;
  } | null>(null);
  const aiFetchIntervalRef = useRef<any>();

  // ---------------- new-code ----------------
  const { saveConsole } = useSaveEditorData({
    editorRef,
    isActive,
    boundInfo: props.boundInfo,
    source,
    defaultValue,
  });
  // ---------------- new-code ----------------

  useEffect(() => {
    if (appendValue) {
      editorRef?.current?.setValue(appendValue.text, appendValue.range);
    }
  }, [appendValue]);

  useEffect(() => {
    return () => {
      aiCompletionTaskRef.current?.cancel();
      disposeVinPasteTransformRef.current?.();
    };
  }, []);

  useImperativeHandle(
    ref,
    () => ({
      editorRef: editorRef?.current,
    }),
    [editorRef?.current],
  );

  const executeSQL = (sql?: string) => {
    const selectedInfo = editorRef?.current?.getCurrentSelectInfo();
    const sqlContent = sql || selectedInfo?.text || editorRef?.current?.getAllContent();

    if (!sqlContent) {
      return;
    }
    props.onExecuteSQL && props.onExecuteSQL(sqlContent, selectedInfo);
  };

  const handleEditorDidMount = useCallback((editor: IEditorIns) => {
    // SQL consoles support pasting Excel VIN rows directly inside IN (...).
    disposeVinPasteTransformRef.current?.();
    disposeVinPasteTransformRef.current = registerVinPasteTransform(editor);
  }, []);

  // 打开 AI 聊天面板并发送选中内容
  const openAiChatWithMessage = (selectedText: string, promptType: IAiChatPromptType) => {
    // 打开 AI 扩展面板
    setCurrentWorkspaceExtend('ai');
    // 设置待发送的 AI 聊天消息
    setPendingAiChat({
      dataSourceId: boundInfo.dataSourceId,
      message: selectedText,
      promptType,
    });
  };

  const addAction = [
    {
      id: 'explainSQL',
      label: i18n('common.text.explainSQL'),
      action: (selectedText: string) => {
        openAiChatWithMessage(selectedText, 'SQL_EXPLAIN');
      },
    },
    {
      id: 'optimizeSQL',
      label: i18n('common.text.optimizeSQL'),
      action: (selectedText: string) => {
        openAiChatWithMessage(selectedText, 'SQL_OPTIMIZER');
      },
    },
    {
      id: 'changeSQL',
      label: i18n('common.text.conversionSQL'),
      action: (selectedText: string) => {
        openAiChatWithMessage(selectedText, 'SQL_2_SQL');
      },
    },
  ];

  const buildAiCompletionContext = (editor: IEditorIns, monaco): IAiCompletionContext | null => {
    const model = editor.getModel();
    const position = editor.getPosition();
    const selection = editor.getSelection();
    if (!model || !position) {
      return null;
    }

    const currentSql = model?.getValue() || '';
    const selectedText = selection && !selection.isEmpty() ? model?.getValueInRange(selection) || '' : '';
    const cursorOffset = model.getOffsetAt(position);
    const boundary = selectedText
      ? {
          start: model.getOffsetAt(selection!.getStartPosition()),
          end: model.getOffsetAt(selection!.getEndPosition()),
        }
      : findStatementBoundary(currentSql, cursorOffset);
    const statementSql = selectedText || currentSql.slice(boundary.start, boundary.end);
    const startPosition = model.getPositionAt(boundary.start);
    const endPosition = model.getPositionAt(boundary.end);
    const replaceRange = new monaco.Range(
      startPosition.lineNumber,
      startPosition.column,
      endPosition.lineNumber,
      endPosition.column,
    );

    return {
      message: [`${selectedText ? '选中 SQL' : '当前语句'}:`, statementSql || '(空)'].join('\n'),
      replaceRange,
      originalText: statementSql,
    };
  };

  const getAiCompletionRange = (editor: IEditorIns, monaco) => {
    const position = editor.getPosition();
    if (position) {
      return new monaco.Range(position.lineNumber, position.column, position.lineNumber, position.column);
    }
    return new monaco.Range(1, 1, 1, 1);
  };

  const triggerAiCompletion = useCallback(
    async (editor: IEditorIns, monaco) => {
      if (!boundInfo?.dataSourceId) {
        message.warning('请先选择数据库连接');
        return;
      }

      const model = editor.getModel();
      if (!model) {
        return;
      }

      aiCompletionTaskRef.current?.cancel();
      setAiCompletion(null);
      setIsLoading(true);

      const versionId = model.getVersionId();
      const completionContext = buildAiCompletionContext(editor, monaco);
      if (!completionContext) {
        setIsLoading(false);
        return;
      }
      const task = requestAiSqlCompletion({
        boundInfo,
        message: completionContext.message,
        ext: completionContext.ext,
      });
      aiCompletionTaskRef.current = task;

      try {
        const sql = await task.promise;
        if (aiCompletionTaskRef.current !== task) {
          return;
        }
        aiCompletionTaskRef.current = null;

        if (!sql) {
          message.warning('AI 未返回可用的 SQL 建议');
          return;
        }

        if (model.getVersionId() !== versionId) {
          message.warning('编辑器内容已变化，请重新触发 AI 补全');
          return;
        }

        setAiCompletion({
          id: uuidv4(),
          text: sql,
          range: getAiCompletionRange(editor, monaco),
          replaceRange: completionContext.replaceRange,
          originalText: completionContext.originalText,
        });
      } catch (error) {
        if (aiCompletionTaskRef.current === task) {
          aiCompletionTaskRef.current = null;
          message.error('AI SQL 补全失败');
        }
      } finally {
        if (aiCompletionTaskRef.current === task || aiCompletionTaskRef.current === null) {
          setIsLoading(false);
        }
      }
    },
    [boundInfo],
  );

  const clearAiCompletion = useCallback(() => {
    setAiCompletion(null);
  }, []);

  const acceptAiCompletion = useCallback(
    (editor: IEditorIns) => {
      const completion = aiCompletionStateRef.current;
      if (!completion) {
        return false;
      }

      editor.executeEdits('aiSqlCompletion', [
        {
          range: completion.replaceRange,
          text: completion.text,
        },
      ]);

      const completionLines = completion.text.split(/\r\n|\r|\n/);
      const endLineNumber = completion.replaceRange.startLineNumber + completionLines.length - 1;
      const endColumn =
        completionLines.length === 1
          ? completion.replaceRange.startColumn + completionLines[0].length
          : completionLines[completionLines.length - 1].length + 1;
      editor.setPosition({ lineNumber: endLineNumber, column: endColumn });
      editor.focus();
      clearAiCompletion();
      return true;
    },
    [clearAiCompletion],
  );

  useEffect(() => {
    triggerAiCompletionRef.current = triggerAiCompletion;
  }, [triggerAiCompletion]);

  useEffect(() => {
    aiCompletionStateRef.current = aiCompletion;
    aiCompletionVisibleKeyRef.current?.set(Boolean(aiCompletion));
  }, [aiCompletion]);

  // 注册快捷键
  const registerShortcutKey = (editor, monaco) => {
    if (shortcutRegisteredRef.current) {
      return;
    }
    shortcutRegisteredRef.current = true;
    aiCompletionVisibleKeyRef.current = editor.createContextKey('aiSqlCompletionVisible', false);

    // 保存
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => {
      const value = editor?.getValue();
      saveConsole(value || '');
    });

    // 执行
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyR, () => {
      const value = editorRef.current?.getCurrentSelectContent();
      executeSQL(value);
    });

    // 执行
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.KeyL, () => {
      handelCreateConsole();
    });

    const aiCompletionKeyCodes = [
      monaco.KeyCode.Backslash,
      monaco.KeyCode.IntlBackslash,
      monaco.KeyCode.US_BACKSLASH,
    ].filter(Boolean);
    [...new Set(aiCompletionKeyCodes)].forEach((keyCode) => {
      editor.addCommand(monaco.KeyMod.Alt | keyCode, () => {
        triggerAiCompletionRef.current?.(editor, monaco);
      });
    });

    editor.addCommand(
      monaco.KeyCode.Tab,
      () => {
        acceptAiCompletion(editor);
      },
      'aiSqlCompletionVisible',
    );

    editor.getDomNode()?.addEventListener(
      'keydown',
      (event) => {
        if (isAiCompletionShortcut(event)) {
          event.preventDefault();
          event.stopPropagation();
          triggerAiCompletionRef.current?.(editor, monaco);
          return;
        }

        if (event.key !== 'Tab') {
          return;
        }

        if (acceptAiCompletion(editor)) {
          event.preventDefault();
          event.stopPropagation();
        }
      },
      true,
    );

    editor.onKeyDown((event) => {
      if (event.browserEvent.key === 'Escape' && aiCompletionStateRef.current) {
        event.preventDefault();
        event.stopPropagation();
        clearAiCompletion();
        return;
      }
    });
  };

  return (
    <IntelligentEditorContext.Provider
      value={{
        isActive,
        tableNameList,
        setTableNameList,
      }}
    >
      <div className={styles.console} ref={ref as any}>
        <OperationLine
          boundInfo={boundInfo}
          saveConsole={saveConsole}
          executeSQL={executeSQL}
          editorRef={editorRef}
          setBoundInfo={setBoundInfo}
          hasSaveBtn={hasSaveBtn}
        />
        <Spin spinning={isLoading} style={{ height: '100%' }}>
          <MonacoEditor
            id={uid}
            defaultValue={defaultValue}
            ref={editorRef as any}
            className={styles.consoleEditor}
            addAction={addAction}
            options={props.editorOptions}
            didMount={handleEditorDidMount}
            shortcutKey={registerShortcutKey}
            boundInfo={boundInfo}
            aiCompletion={aiCompletion}
            onContentChange={clearAiCompletion}
          />
        </Spin>
        <Modal
          open={popularizeModal}
          footer={false}
          onCancel={() => {
            aiFetchIntervalRef.current && clearInterval(aiFetchIntervalRef.current);
            setPopularizeModal(false);
          }}
        >
          <Popularize {...modalProps} />
        </Modal>
      </div>
    </IntelligentEditorContext.Provider>
  );
}

export default forwardRef(ConsoleEditor);
