import React, { ForwardedRef, forwardRef, useEffect, useImperativeHandle, useMemo, useRef, useState } from 'react';
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import cs from 'classnames';
import { EditorThemeType } from '@/constants';
import { editorDefaultOptions } from './monacoEditorConfig';
import ContextMenu, { IMenuItem } from './components/ContextMenu';
import MonacoEditorErrorTips from '@/components/MonacoEditor/components/MonacoEditorErrorTips';
import { useStyles } from './style';
import { useGlobalStore } from '@/store/global';
import { runMonacoDisposalSafely, setupMonacoEnvironment } from '@/utils/monaco';

export type IEditorIns = monaco.editor.IStandaloneCodeEditor;
export type IEditorOptions = monaco.editor.IStandaloneEditorConstructionOptions;
export type IEditorContentChangeEvent = monaco.editor.IModelContentChangedEvent;

export type IAppendValue = {
  text: any;
  range?: IRangeType;
};

export type ContextMenuItem = IMenuItem;

export interface IMonacoEditorProps {
  id: string;
  language?: string;
  className?: string;
  // Adapt to the container dimensions.
  dynamicHeight?: {
    minHeight: number;
    maxHeight: number;
  };
  options?: IEditorOptions;
  needDestroy?: boolean;
  addAction?: Array<{ id: string; label: string; action: (selectedText: string, ext?: string) => void }>;
  defaultValue?: string;
  appendValue?: IAppendValue;
  didMount?: (editor: IEditorIns) => any;
  shortcutKey?: (editor, monaco, isFocus: boolean) => void;
  focusChange?: (isFocus: boolean) => void;
  contextMenu?: {
    menu: IMenuItem[];
  };
  autoFocus?: boolean;
  disableFind?: boolean;
}

export interface IExportRefFunction {
  getCurrentSelectContent: () => string;
  getAllContent: () => string;
  setValue: (text: any, range?: IRangeType) => void;
  arouseErrorTips: (errorMessage: string | null) => void;
}

    // Custom overlay element.
const overlay = document.createElement('div');
overlay.style.position = 'absolute';
overlay.style.display = 'none';
overlay.style.background = 'white';
overlay.style.border = '1px solid black';
overlay.textContent = 'Custom Overlay';
    // Add the overlay to the DOM.
document.body.appendChild(overlay);

function MonacoEditor(props: IMonacoEditorProps, ref: ForwardedRef<IExportRefFunction>) {
  const {
    id,
    className,
    language = 'sql',
    didMount,
    options,
    defaultValue,
    appendValue,
    shortcutKey,
    contextMenu,
    autoFocus = false,
    dynamicHeight,
    disableFind = false,
  } = props;
  const [editorHeight, setEditorHeight] = useState(dynamicHeight ? '0px' : '100%');
  const {
    styles,
    theme: { appearance },
  } = useStyles();
  /** Global editor settings. */
  const { getEditorTheme } = useGlobalStore((s) => {
    return {
      getEditorTheme: s.getEditorTheme,
    };
  });
  const editorRef = useRef<IEditorIns>();
  const [isFocus, setIsFocus] = useState(false);
  const [hasOverlay, setHasOverlay] = useState(false);
  const [overlayStyle, setOverlayStyle] = useState<React.CSSProperties>({
    left: '0',
    top: '0',
    position: 'fixed',
  });
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const editorOptions = useMemo(() => {
    return {
      ...editorDefaultOptions,
      ...options,
      value: defaultValue || '',
      language,
      theme: getEditorTheme(appearance),
      inlineSuggest: {
        enabled: true,
      },
    };
  }, [options, defaultValue, language, appearance]);

  // init
  useEffect(() => {
    console.log('[DEBUG:MonacoEditor] Initializing editor', { id, autoFocus, language });
    setupMonacoEnvironment();
    const editorIns = monaco.editor.create(document.getElementById(`monaco-editor-${id}`)!, editorOptions);

    editorRef.current = editorIns;

    didMount && didMount(editorIns);
    console.log('[DEBUG:MonacoEditor] Editor created successfully');

    // Disable the context menu.
    editorIns.updateOptions({ contextmenu: false });

    // Add the editor mouse listener.
    const contextMenuDisposer = editorIns.onContextMenu((mouseEvent) => {
      // Prevent the default context menu.
      if (contextMenu) {
        mouseEvent.event.preventDefault();
      // Get pointer coordinates relative to the browser viewport.
        const { posx: cursorLeft, posy: cursorTop } = mouseEvent.event;
      // Show the custom context menu.
        setHasOverlay(true);
        updateOverlay(cursorTop, cursorLeft);
      }
    });

    let addActionDisplay: any = null;

    if (disableFind) {
      addActionDisplay = editorIns.addAction({
        id: 'custom-action-cmd-k',
        label: 'Custom Action',
        keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyF],
        run: () => {},
      });
    }

    // Listen for content changes.
    const didChangeModelContentDisposer = editorIns.onDidChangeModelContent(() => {
    // Track line-count changes to adjust the height.
      const lineCount = editorIns?.getModel()?.getLineCount();
      if (lineCount && editorOptions?.lineHeight && dynamicHeight) {
        let height = lineCount * editorOptions.lineHeight;
        height = Math.min(Math.max(height, dynamicHeight.minHeight), dynamicHeight.maxHeight);
        height = Math.round(height);
        setEditorHeight(`${height}px`);
      }
    });

    monaco.editor.defineTheme(EditorThemeType.DashboardLightTheme, {
      base: 'vs',
      inherit: true,
      rules: [{ background: '#15161a' }] as any,
      colors: {
        'editor.foreground': '#000000',
        'editor.background': '#f8f9fa', // Background color.
      },
    });

    monaco.editor.defineTheme(EditorThemeType.DashboardBlackTheme, {
      base: 'vs-dark',
      inherit: true,
      rules: [{ background: '#15161a' }] as any,
      colors: {
        'editor.foreground': '#ffffff',
        'editor.background': '#131418', // Background color.
      },
    });

    return () => {
      const editorInstance = editorRef.current;
      editorRef.current = undefined;

      runMonacoDisposalSafely(() => didChangeModelContentDisposer.dispose());
      runMonacoDisposalSafely(() => contextMenuDisposer.dispose());

      if (addActionDisplay) {
        runMonacoDisposalSafely(() => addActionDisplay.dispose());
      }

      runMonacoDisposalSafely(() => editorInstance?.dispose());
    };
  }, []);

    // Listen for editor focus changes.
  useEffect(() => {
    const focus = () => {
      console.log('[DEBUG:Focus] MonacoEditor gained focus', {
        id,
        activeElement: document.activeElement,
        hasOverlay,
      });
      setIsFocus(true);
      props.focusChange && props.focusChange(true);
    };
    const blur = () => {
      console.log('[DEBUG:Focus] MonacoEditor lost focus', {
        id,
        activeElement: document.activeElement,
        hasOverlay,
      });
      setIsFocus(false);
      props.focusChange && props.focusChange(false);
    };
    const focusListener = editorRef.current?.onDidFocusEditorText(focus);
    const blurListener = editorRef.current?.onDidBlurEditorText(blur);
    console.log('[DEBUG:MonacoEditor] Focus listeners attached');
      // Remove listeners.
    return () => {
      console.log('[DEBUG:MonacoEditor] Focus listeners disposed');
      focusListener?.dispose();
      blurListener?.dispose();
    };
  }, [id, hasOverlay]);

    // Focus automatically.
  useEffect(() => {
    if (!hasOverlay && autoFocus) {
      console.log('[DEBUG:Focus] Auto-focusing editor', { id, hasOverlay, autoFocus });
      editorRef.current?.focus();
    }
  }, [hasOverlay, autoFocus, id]);

  useEffect(() => {
    if (editorRef.current && shortcutKey) {
      shortcutKey(editorRef.current, monaco, isFocus);
    }
  }, [editorRef.current, isFocus]);

  useImperativeHandle(ref, () => ({
    getCurrentSelectContent,
    getAllContent,
    setValue,
    arouseErrorTips,
  }));

  // Track Monaco Editor line-count changes.

  useEffect(() => {
    if (appendValue) {
      appendMonacoValue(editorRef.current, appendValue?.text, appendValue?.range);
    }
  }, [appendValue]);

  const arouseErrorTips = (error: string | null) => {
    setErrorMessage(error);
  };

  const setValue = (text: any, range?: IRangeType) => {
    appendMonacoValue(editorRef.current, text, range);
  };

  /**
   * Get the current selection.
   * @returns
   */
  const getCurrentSelectContent = () => {
    const selection = editorRef.current?.getSelection();
    if (!selection || selection.isEmpty()) {
      return '';
    } else {
      const selectedText = editorRef.current?.getModel()?.getValueInRange(selection);
      return selectedText || '';
    }
  };

  /** Get all text. */
  const getAllContent = () => {
    const model = editorRef.current?.getModel();
    const value = model?.getValue();
    return value || '';
  };

  const updateOverlay = (top, left) => {
    setOverlayStyle({
      ...overlayStyle,
      left: `${left}px`,
      top: `${top}px`,
    });
  };

  return (
    <div
      className={cs(className, styles.editorContainerBox)}
      style={{
        height: editorHeight,
      }}
    >
      <div ref={ref as any} id={`monaco-editor-${id}`} className={cs(styles.editorContainer)} />
      <MonacoEditorErrorTips errorMessage={errorMessage} />
      {editorRef.current && contextMenu && (
        <ContextMenu
          open={hasOverlay}
          triggerStyle={overlayStyle}
          onCloseContextMenu={() => {
            setHasOverlay(false);
          }}
          getCurrentSelectContent={getCurrentSelectContent}
          contextMenu={contextMenu}
        />
      )}
    </div>
  );
}

      // text is the content to add.
      // range is the insertion position.
      // 'end' appends to the end.
      // 'front' inserts at the beginning.
      // 'cover' replaces existing text.
      // Custom positions use an array of new monaco.Range values.
export type IRangeType = 'end' | 'front' | 'cover' | 'reset' | 'cursor' | any;

export const appendMonacoValue = (editor: any, text: any, range: IRangeType = 'end') => {
  if (!editor) {
    return;
  }
  const model = editor?.getModel && editor.getModel(editor);
      // Create an edit operation that replaces the current document content.
  let newRange: IRangeType = range;
  if (range === 'reset') {
    editor.setValue(text || '');
    return;
  }
  let newText = text;
  const lastLine = editor.getModel().getLineCount();
  const lastLineLength = editor.getModel().getLineMaxColumn(lastLine);

  switch (range) {
      // Replace all content.
    case 'cover':
      newRange = model.getFullModelRange();
      editor.revealLine(lastLine);
      break;
      // Insert content at the beginning.
    case 'front':
      newRange = new monaco.Range(1, 1, 1, 1);
      editor.revealLine(1);
      editor.setPosition({ lineNumber: 1, column: 1 });
      break;
      // Format SQL in the selection.
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
      // Append content.
    case 'end':
      newRange = new monaco.Range(lastLine, lastLineLength, lastLine, lastLineLength);
      newText = `${text}`;
      break;
      // Insert content at the cursor.
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

  // decorations optionally styles inserted text with colors, backgrounds, and other presentation.
  const decorations = [{}]; // Prevent newly inserted text from receiving the default gray background.
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
