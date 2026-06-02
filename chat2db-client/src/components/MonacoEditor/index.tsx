import React, { ForwardedRef, forwardRef, useEffect, useImperativeHandle, useRef } from 'react';
import cs from 'classnames';
import { useTheme } from '@/hooks';
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import { DatabaseTypeCode, EditorThemeType } from '@/constants';
import { editorDefaultOptions } from './monacoEditorConfig';
import { IQuickInputService } from 'monaco-editor/esm/vs/platform/quickinput/common/quickInput';
import { IBoundInfo } from '@/typings';
import { initSqlAutocomplete, ISqlAutocompleteDisposable } from './syntax-parser/plugin/monaco-plugin/sql-autocomplete';

import styles from './index.less';

export type IEditorIns = monaco.editor.IStandaloneCodeEditor;
export type IEditorOptions = monaco.editor.IStandaloneEditorConstructionOptions;
export type IEditorContentChangeEvent = monaco.editor.IModelContentChangedEvent;

export type IAppendValue = {
  text: any;
  range?: IRangeType;
};

export interface ISelectedContentInfo {
  text: string;
  startLine: number;
  endLine: number;
}

const databaseTypeList = Object.keys(DatabaseTypeCode).map((d) => ({
  type: d,
  id: d,
  label: d,
}));

const vinRegExp = /^[A-HJ-NPR-Z0-9]{17}$/i;
type SqlQuote = "'" | '"' | '`' | '[';

const getFirstExcelColumn = (line: string) => {
  const [firstColumn] = line.split('\t');
  return firstColumn.trim().replace(/^["']|["']$/g, '');
};

// Converts Excel-copied VIN rows into SQL string literals for IN clauses.
const buildVinSqlValueList = (clipboardText: string) => {
  const vinList = clipboardText
    .split(/\r\n|\r|\n/)
    .map(getFirstExcelColumn)
    .filter(Boolean);
  const invalidVinList = vinList.filter((vin) => !vinRegExp.test(vin));

  if (!vinList.length || invalidVinList.length) {
    return null;
  }

  return vinList.map((vin) => `'${vin.toUpperCase().replace(/'/g, "''")}'`).join(', ');
};

// Finds the innermost unclosed parenthesis before the cursor while ignoring SQL strings and comments.
const getOpenInParenOffset = (sql: string, cursorOffset: number) => {
  const parenStack: number[] = [];
  let quote: SqlQuote | null = null;
  let inLineComment = false;
  let inBlockComment = false;

  for (let index = 0; index < cursorOffset; index++) {
    const char = sql[index];
    const next = sql[index + 1];

    if (inLineComment) {
      if (char === '\n' || char === '\r') {
        inLineComment = false;
      }
      continue;
    }

    if (inBlockComment) {
      if (char === '*' && next === '/') {
        inBlockComment = false;
        index++;
      }
      continue;
    }

    if (quote) {
      if (quote === "'" && char === "'" && next === "'") {
        index++;
        continue;
      }
      if (quote === '"' && char === '"' && next === '"') {
        index++;
        continue;
      }
      if (quote === '`' && char === '`' && next === '`') {
        index++;
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
      index++;
      continue;
    }

    if (char === '/' && next === '*') {
      inBlockComment = true;
      index++;
      continue;
    }

    if (char === "'" || char === '"' || char === '`' || char === '[') {
      quote = char as SqlQuote;
      continue;
    }

    if (char === '(') {
      parenStack.push(index);
      continue;
    }

    if (char === ')') {
      parenStack.pop();
    }
  }

  return parenStack[parenStack.length - 1];
};

const isCursorInsideInParens = (editor: IEditorIns) => {
  const model = editor.getModel();
  const position = editor.getPosition();
  if (!model || !position) {
    return false;
  }

  const sql = model.getValue();
  const cursorOffset = model.getOffsetAt(position);
  const openParenOffset = getOpenInParenOffset(sql, cursorOffset);
  if (openParenOffset === undefined) {
    return false;
  }

  const beforeOpenParen = sql.slice(0, openParenOffset);
  return /\bin\s*$/i.test(beforeOpenParen);
};

export const registerVinPasteTransform = (editor: IEditorIns) => {
  const domNode = editor.getDomNode();
  if (!domNode) {
    return undefined;
  }

  const pasteHandler = (event: ClipboardEvent) => {
    const eventTarget = event.target;
    if (!(eventTarget instanceof Node) || !domNode.contains(eventTarget)) {
      return;
    }

    const clipboardText = event.clipboardData?.getData('text/plain') || '';
    const vinSqlValueList = buildVinSqlValueList(clipboardText);
    const isInsideInParens = isCursorInsideInParens(editor);
    if (!vinSqlValueList || !isInsideInParens) {
      return;
    }

    const position = editor.getPosition();
    const selection =
      editor.getSelection() ||
      (position
        ? new monaco.Selection(position.lineNumber, position.column, position.lineNumber, position.column)
        : null);
    if (!selection) {
      return;
    }

    event.preventDefault();
    event.stopPropagation();

    const endPosition = {
      lineNumber: selection.startLineNumber,
      column: selection.startColumn + vinSqlValueList.length,
    };
    editor.executeEdits(
      'vinPasteTransform',
      [
        {
          range: selection,
          text: vinSqlValueList,
          forceMoveMarkers: true,
        },
      ],
      () => [
        new monaco.Selection(endPosition.lineNumber, endPosition.column, endPosition.lineNumber, endPosition.column),
      ],
    );
    editor.focus();
  };

  document.addEventListener('paste', pasteHandler, true);
  return () => {
    document.removeEventListener('paste', pasteHandler, true);
  };
};

interface IProps {
  id: string;
  language?: string;
  className?: string;
  options?: IEditorOptions;
  needDestroy?: boolean;
  addAction?: Array<{ id: string; label: string; action: (selectedText: string, ext?: string) => void }>;
  defaultValue?: string;
  appendValue?: IAppendValue;
  didMount?: (editor: IEditorIns) => any;
  shortcutKey?: (editor, monaco, isActive: boolean) => void;
  focusChange?: (isActive: boolean) => void;
  boundInfo?: IBoundInfo;
  aiCompletion?: {
    id: string;
    text: string;
    range: monaco.IRange;
    replaceRange?: monaco.IRange;
    originalText?: string;
  } | null;
  onContentChange?: (event: IEditorContentChangeEvent) => void;
}

export interface IExportRefFunction {
  getCurrentSelectContent: () => string;
  getCurrentSelectInfo: () => ISelectedContentInfo | null;
  getAllContent: () => string;
  setValue: (text: any, range?: IRangeType) => void;
  locateStatement: (startLine: number, endLine?: number, errorLine?: number) => void;
  // toFocus: () => void;
}

function MonacoEditor(props: IProps, ref: ForwardedRef<IExportRefFunction>) {
  const {
    id,
    className,
    language = 'sql',
    didMount,
    options,
    defaultValue,
    appendValue,
    shortcutKey,
    boundInfo,
    aiCompletion,
    onContentChange,
  } = props;
  const editorRef = useRef<IEditorIns>();
  const quickInputCommand = useRef<any>();
  const sqlAutocompleteDisposable = useRef<ISqlAutocompleteDisposable | null>(null);
  const aiCompletionWidgetRef = useRef<monaco.editor.IContentWidget | null>(null);
  const aiCompletionDecorationsRef = useRef<string[]>([]);
  const statementDecorationsRef = useRef<string[]>([]);
  const [appTheme] = useTheme();
  const [isActive, setIsActive] = React.useState(false);

  // init
  useEffect(() => {
    const editorIns = monaco.editor.create(document.getElementById(`monaco-editor-${id}`)!, {
      ...editorDefaultOptions,
      ...options,
      value: defaultValue || '',
      language,
      theme: appTheme.backgroundColor,
    });
    editorRef.current = editorIns;
    didMount && didMount(editorIns);

    // Add a new command, for getting an accessor.
    quickInputCommand.current = editorIns.addCommand(0, (accessor, func) => {
      // a hacker way to get the input service
      const quickInputService = accessor.get(IQuickInputService);
      func(quickInputService);
    });

    monaco.editor.defineTheme(EditorThemeType.DashboardLightTheme, {
      base: 'vs',
      inherit: true,
      rules: [{ background: '#15161a' }] as any,
      colors: {
        'editor.foreground': '#000000',
        'editor.background': '#f8f9fa', //背景色
      },
    });

    monaco.editor.defineTheme(EditorThemeType.DashboardBlackTheme, {
      base: 'vs-dark',
      inherit: true,
      rules: [{ background: '#15161a' }] as any,
      colors: {
        'editor.foreground': '#ffffff',
        'editor.background': '#131418', //背景色
      },
    });

    createAction(editorIns);
    editorIns.onDidChangeModelContent((event) => {
      onContentChange?.(event);
    });

    // Initialize SQL autocomplete if boundInfo is provided
    if (boundInfo && language === 'sql') {
      sqlAutocompleteDisposable.current = initSqlAutocomplete({
        monaco,
        editor: editorIns,
        boundInfo,
      });
    }

    return () => {
      if (sqlAutocompleteDisposable.current) {
        sqlAutocompleteDisposable.current.dispose();
      }
      clearAiCompletionPreview(editorRef.current);
      if (props.needDestroy) {
        editorRef.current && editorRef.current.dispose();
      }
    };
  }, []);

  // 如果编辑器聚焦，就设置为true
  useEffect(() => {
    const focus = () => {
      setIsActive(true);
      props.focusChange && props.focusChange(true);
    };
    const blur = () => {
      setIsActive(false);
      props.focusChange && props.focusChange(false);
    };
    editorRef.current?.onDidFocusEditorText(focus);
    editorRef.current?.onDidBlurEditorText(blur);
    // 移除监听
    // return () => {
    //   editorRef.current?.removeEventListener('focus', focus);
    //   editorRef.current?.removeEventListener('blur', blur);
    // };
  }, []);

  useEffect(() => {
    if (editorRef.current) {
      // eg:
      // editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.KeyL, () => {
      // });
      shortcutKey?.(editorRef.current, monaco, isActive);
    }
  }, [editorRef.current, isActive]);

  useEffect(() => {
    if (!editorRef.current) {
      return;
    }

    renderAiCompletionPreview(editorRef.current, aiCompletion || null);
  }, [aiCompletion]);

  useEffect(() => {
    // 监听浏览器窗口大小变化，重新渲染编辑器
    const resize = () => {
      editorRef.current?.layout();
    };
    window.addEventListener('resize', resize);
    return () => {
      window.removeEventListener('resize', resize);
    };
  }, []);

  // 设置报表里面的编辑器的主题
  useEffect(() => {
    if (options?.theme) {
      monaco.editor.setTheme(options.theme);
    }
  }, [options?.theme]);

  // Reinitialize SQL autocomplete when boundInfo changes
  useEffect(() => {
    if (!boundInfo || language !== 'sql' || !editorRef.current) {
      return;
    }

    // Dispose old autocomplete
    if (sqlAutocompleteDisposable.current) {
      sqlAutocompleteDisposable.current.dispose();
    }

    // Initialize new autocomplete
    sqlAutocompleteDisposable.current = initSqlAutocomplete({
      monaco,
      editor: editorRef.current,
      boundInfo,
    });

    return () => {
      if (sqlAutocompleteDisposable.current) {
        sqlAutocompleteDisposable.current.dispose();
      }
    };
  }, [boundInfo, language]);

  useImperativeHandle(ref, () => ({
    getCurrentSelectContent,
    getCurrentSelectInfo,
    getAllContent,
    setValue,
    locateStatement,
    // toFocus,
  }));

  useEffect(() => {
    if (appendValue) {
      appendMonacoValue(editorRef.current, appendValue?.text, appendValue?.range);
    }
  }, [appendValue]);

  const setValue = (text: any, range?: IRangeType) => {
    appendMonacoValue(editorRef.current, text, range);
  };

  const locateStatement = (startLine: number, endLine?: number, errorLine?: number) => {
    const editor = editorRef.current;
    const model = editor?.getModel();
    if (!editor || !model || !startLine) {
      return;
    }
    const modelLastLine = model.getLineCount();
    const safeStartLine = Math.max(1, Math.min(startLine, modelLastLine));
    const safeEndLine = Math.max(safeStartLine, Math.min(endLine || safeStartLine, modelLastLine));
    const targetLine = Math.max(safeStartLine, Math.min(errorLine || safeStartLine, safeEndLine));
    const endColumn = model.getLineMaxColumn(safeEndLine);
    const range = new monaco.Range(safeStartLine, 1, safeEndLine, endColumn);

    statementDecorationsRef.current = editor.deltaDecorations(statementDecorationsRef.current, [
      {
        range,
        options: {
          isWholeLine: true,
          className: styles.statementLocateHighlight,
        },
      },
      {
        range: new monaco.Range(targetLine, 1, targetLine, model.getLineMaxColumn(targetLine)),
        options: {
          isWholeLine: true,
          className: styles.statementErrorLineHighlight,
        },
      },
    ]);
    editor.setSelection(range);
    editor.revealLineInCenterIfOutsideViewport(targetLine);
    editor.focus();
  };

  // const toFocus = () => {
  //   editorRef.current?.focus();
  // };

  /**
   * 获取当前选中的内容
   * @returns
   */
  const getCurrentSelectContent = () => {
    return getCurrentSelectInfo()?.text || '';
  };

  const getCurrentSelectInfo = (): ISelectedContentInfo | null => {
    const selection = editorRef.current?.getSelection();
    if (!selection || selection.isEmpty()) {
      return null;
    }

    const selectedText = editorRef.current?.getModel()?.getValueInRange(selection);
    if (!selectedText) {
      return null;
    }

    return {
      text: selectedText,
      startLine: selection.getStartPosition().lineNumber,
      endLine: selection.getEndPosition().lineNumber,
    };
  };

  /** 获取文本所有内容 */
  const getAllContent = () => {
    const model = editorRef.current?.getModel();
    const value = model?.getValue();
    return value || '';
  };

  const createAction = (editor: IEditorIns) => {
    // 用于控制切换该菜单键的显示
    editor.createContextKey('shouldShowSqlRunnerAction', true);

    if (!props.addAction || !props.addAction.length) {
      return;
    }

    props.addAction.forEach((action) => {
      const { id: _id, label, action: runFn } = action;
      editor.addAction({
        id: _id,
        label,
        // 控制该菜单键显示
        precondition: 'shouldShowSqlRunnerAction',
        // 该菜单键位置
        contextMenuGroupId: 'navigation',
        contextMenuOrder: 1.5,
        // 点击该菜单键后运行
        run: (ed: IEditorIns) => {
          const selectedText = editor.getModel()?.getValueInRange(editor.getSelection()!) || '';
          if (_id === 'changeSQL') {
            ed.trigger('', quickInputCommand.current, (quickInput) => {
              quickInput.pick(databaseTypeList).then((selected) => {
                runFn(selectedText, selected?.label);
              });
            });
          } else {
            runFn(selectedText);
          }
        },
      });
    });
  };

  const clearAiCompletionPreview = (editor?: IEditorIns) => {
    if (!editor) {
      return;
    }

    if (aiCompletionWidgetRef.current) {
      editor.removeContentWidget(aiCompletionWidgetRef.current);
      aiCompletionWidgetRef.current = null;
    }
    aiCompletionDecorationsRef.current = editor.deltaDecorations(aiCompletionDecorationsRef.current, []);
  };

  type AiDiffLineType = 'same' | 'remove' | 'add';

  interface IAiDiffLine {
    type: AiDiffLineType;
    line: string;
  }

  const normalizeSqlDiffLine = (line: string) => {
    const compactLine = line.trim().replace(/\s+/g, ' ');
    return compactLine.toLowerCase();
  };

  const buildLineDiff = (originalLines: string[], suggestionLines: string[]) => {
    const normalizedOriginalLines = originalLines.map(normalizeSqlDiffLine);
    const normalizedSuggestionLines = suggestionLines.map(normalizeSqlDiffLine);
    const rowCount = originalLines.length + 1;
    const columnCount = suggestionLines.length + 1;
    const lcsLengths = Array.from({ length: rowCount }, () => Array<number>(columnCount).fill(0));

    for (let rowIndex = originalLines.length - 1; rowIndex >= 0; rowIndex -= 1) {
      for (let columnIndex = suggestionLines.length - 1; columnIndex >= 0; columnIndex -= 1) {
        if (normalizedOriginalLines[rowIndex] === normalizedSuggestionLines[columnIndex]) {
          lcsLengths[rowIndex][columnIndex] = lcsLengths[rowIndex + 1][columnIndex + 1] + 1;
        } else {
          lcsLengths[rowIndex][columnIndex] = Math.max(
            lcsLengths[rowIndex + 1][columnIndex],
            lcsLengths[rowIndex][columnIndex + 1],
          );
        }
      }
    }

    const diffLines: IAiDiffLine[] = [];
    let originalIndex = 0;
    let suggestionIndex = 0;
    while (originalIndex < originalLines.length && suggestionIndex < suggestionLines.length) {
      if (normalizedOriginalLines[originalIndex] === normalizedSuggestionLines[suggestionIndex]) {
        diffLines.push({ type: 'same', line: originalLines[originalIndex] });
        originalIndex += 1;
        suggestionIndex += 1;
      } else if (lcsLengths[originalIndex + 1][suggestionIndex] >= lcsLengths[originalIndex][suggestionIndex + 1]) {
        diffLines.push({ type: 'remove', line: originalLines[originalIndex] });
        originalIndex += 1;
      } else {
        diffLines.push({ type: 'add', line: suggestionLines[suggestionIndex] });
        suggestionIndex += 1;
      }
    }

    while (originalIndex < originalLines.length) {
      diffLines.push({ type: 'remove', line: originalLines[originalIndex] });
      originalIndex += 1;
    }

    while (suggestionIndex < suggestionLines.length) {
      diffLines.push({ type: 'add', line: suggestionLines[suggestionIndex] });
      suggestionIndex += 1;
    }

    return diffLines;
  };

  const getRemovedLineDecorations = (replaceRange: monaco.IRange, diffLines: IAiDiffLine[]) => {
    const decorations: monaco.editor.IModelDeltaDecoration[] = [];
    let lineNumber = replaceRange.startLineNumber;

    diffLines.forEach((diffLine) => {
      if (diffLine.type === 'add') {
        return;
      }

      if (diffLine.type === 'remove') {
        decorations.push({
          range: new monaco.Range(lineNumber, 1, lineNumber, 1),
          options: {
            className: styles.aiDiffOriginalRange,
            isWholeLine: true,
          },
        });
      }

      lineNumber += 1;
    });

    return decorations;
  };

  const createDiffLine = (line: string, type: AiDiffLineType) => {
    const row = document.createElement('div');
    row.className =
      type === 'remove' ? styles.aiDiffRemoveLine : type === 'add' ? styles.aiDiffAddLine : styles.aiDiffSameLine;

    const markerNode = document.createElement('span');
    markerNode.className = styles.aiDiffMarker;
    markerNode.textContent = type === 'remove' ? '-' : type === 'add' ? '+' : ' ';

    const codeNode = document.createElement('span');
    codeNode.className = styles.aiDiffCode;
    codeNode.textContent = line || ' ';

    row.appendChild(markerNode);
    row.appendChild(codeNode);
    return row;
  };

  const renderAiCompletionPreview = (editor: IEditorIns, completion: IProps['aiCompletion']) => {
    clearAiCompletionPreview(editor);
    if (!completion) {
      return;
    }

    const replaceRange = completion.replaceRange || completion.range;
    const widgetNode = document.createElement('div');
    widgetNode.className = styles.aiDiffWidget;
    widgetNode.tabIndex = -1;

    const headerNode = document.createElement('div');
    headerNode.className = styles.aiDiffHeader;
    headerNode.textContent = 'AI SQL suggestion';
    widgetNode.appendChild(headerNode);

    const bodyNode = document.createElement('div');
    bodyNode.className = styles.aiDiffBody;

    const originalLines = (completion.originalText || '').split(/\r?\n/);
    const suggestionLines = completion.text.split(/\r?\n/);
    const diffLines = buildLineDiff(originalLines, suggestionLines);
    diffLines.forEach(({ line, type }) => bodyNode.appendChild(createDiffLine(line, type)));
    widgetNode.appendChild(bodyNode);

    const widget: monaco.editor.IContentWidget = {
      getId: () => `chat2db.aiDiff.${completion.id}`,
      getDomNode: () => widgetNode,
      getPosition: () => ({
        position: {
          lineNumber: replaceRange.endLineNumber,
          column: replaceRange.endColumn,
        },
        preference: [monaco.editor.ContentWidgetPositionPreference.BELOW],
      }),
    };

    aiCompletionWidgetRef.current = widget;
    editor.addContentWidget(widget);
    aiCompletionDecorationsRef.current = editor.deltaDecorations(
      aiCompletionDecorationsRef.current,
      getRemovedLineDecorations(replaceRange, diffLines),
    );
    editor.revealLineInCenterIfOutsideViewport(replaceRange.endLineNumber);
  };

  return <div ref={ref as any} id={`monaco-editor-${id}`} className={cs(className, styles.editorContainer)} />;
}

// text 需要添加的文本
// range 添加到的位置
// 'end' 末尾
// 'front' 开头
// 'cover' 覆盖掉原有的文字
// 自定义位置数组 new monaco.Range []
export type IRangeType = 'end' | 'front' | 'cover' | 'reset' | any;

export const appendMonacoValue = (editor: any, text: any, range: IRangeType = 'end') => {
  if (!editor) {
    return;
  }
  const model = editor?.getModel && editor.getModel(editor);
  // 创建编辑操作，将当前文档内容替换为新内容
  let newRange: IRangeType = range;
  if (range === 'reset') {
    editor.setValue(text || '');
    return;
  }
  let newText = text;
  const lastLine = editor.getModel().getLineCount();
  const lastLineLength = editor.getModel().getLineMaxColumn(lastLine);

  switch (range) {
    // 覆盖所有内容
    case 'cover':
      newRange = model.getFullModelRange();
      editor.revealLine(lastLine);
      break;
    // 在开头添加内容
    case 'front':
      newRange = new monaco.Range(1, 1, 1, 1);
      editor.revealLine(1);
      editor.setPosition({ lineNumber: 1, column: 1 });
      break;
    // 格式化选中区域的sql
    case 'select': {
      const selection = editor.getSelection();
      if (selection) {
        newRange = new monaco.Range(
          selection.startLineNumber,
          selection.startColumn,
          selection.endLineNumber,
          selection.endColumn,
        );
      }
      break;
    }
    // 在末尾添加内容
    case 'end':
      newRange = new monaco.Range(lastLine, lastLineLength, lastLine, lastLineLength);
      newText = `${text}`;
      break;
    // 在光标处添加内容
    case 'cursor':
      {
        const position = editor.getPosition();
        if (position) {
          newRange = new monaco.Range(position.lineNumber, position.column, position.lineNumber, position.column);
        }
      }
      break;
    default:
      break;
  }

  const op = {
    range: newRange,
    text: newText,
  };

  // decorations?: IModelDeltaDecoration[]: 一个数组类型的参数，用于指定插入的文本的装饰。可以用来设置文本的样式、颜色、背景色等。如果不需要设置装饰，可以忽略此参数。
  const decorations = [{}]; // 解决新增的文本默认背景色为灰色
  editor.executeEdits('setValue', [op], decorations);
  const addedLastLine = editor.getModel().getLineCount();
  // const addedLastLineLength = editor.getModel().getLineMaxColumn(lastLine);

  if (range === 'end') {
    setTimeout(() => {
      editor.revealLine(addedLastLine + 1);
      // editor.setPosition({ lineNumber: addedLastLine, column: addedLastLineLength });
      // editor.focus();
    }, 0);
  }
};

export default forwardRef(MonacoEditor);
