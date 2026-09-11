import { useRef, useEffect, useState, forwardRef, useImperativeHandle, useCallback } from 'react';
import * as monaco from 'monaco-editor';
import { useGlobalStore } from '@/store/global';
import { debounce } from 'lodash';
import './completionIcon.less';
import { handleSetValue } from '../../core/setValue';
import { ContentDiffKind, EditorSetValueType } from '../../type';
import './index.less';
import { useStyles } from './style';
import { runMonacoDisposalSafely, setupMonacoEnvironment } from '@/utils/monaco';
import {
  normalizeSqlInputPunctuation,
  shouldNormalizeSqlInputPunctuation,
  sqlPunctuationFromKeyboardEvent,
} from '../../helper/sqlInputPunctuation';
import {
  SQL_COMPLETION_ACCEPT_KEY_ENTER_CONTEXT,
  SQL_COMPLETION_ACCEPT_KEY_TAB_CONTEXT,
  getSqlCompletionAcceptKey,
  getSqlCompletionAcceptKeyOptions,
} from '../../core/sqlCompletionAcceptKey';
import { buildContentDiffHunks, ContentDiffHunk } from './contentDiff';
import { ContentDiffInlineView, createContentDiffInlineView } from './ContentDiffViewer';
import {
  ContentDiffSurface,
  getContentDiffDecorationCount,
  guardContentDiffTexts,
  isContentDiffHunkBudgetExceeded,
  isContentDiffSurfaceEnabled,
  hashContentDiffText,
} from '../../helper/contentDiffGuard';

interface ContentDiffGutterMarker {
  hunk: ContentDiffHunk;
  left: number;
  top: number;
  height: number;
}

interface ContentDiffSnapshot {
  baselineEpoch: number;
  baselineHash: string;
  currentHash: string;
  model: monaco.editor.ITextModel;
  modelVersionId: number;
}

monaco.editor.addKeybindingRules([
  {
    keybinding: monaco.KeyCode.Tab,
    command: 'noop',
    when: `${SQL_COMPLETION_ACCEPT_KEY_ENTER_CONTEXT} && suggestWidgetVisible && textInputFocus`,
  },
  {
    keybinding: monaco.KeyMod.Shift | monaco.KeyCode.Tab,
    command: 'noop',
    when: `${SQL_COMPLETION_ACCEPT_KEY_ENTER_CONTEXT} && suggestWidgetVisible && textInputFocus`,
  },
  {
    keybinding: monaco.KeyMod.Shift | monaco.KeyCode.Enter,
    command: 'noop',
    when: `${SQL_COMPLETION_ACCEPT_KEY_TAB_CONTEXT} && suggestWidgetVisible && textInputFocus`,
  },
]);

export type MonacoSQLEditorProps = {
  className?: string;
  /** Editor ID. */
  id: string;
  /** Default editor value. */
  defaultValue?: string;
  options?: monaco.editor.IStandaloneEditorConstructionOptions;
  disableFind?: boolean;
  shortcutKey?: (editor, monaco, isFocus: boolean) => void;
  /** Editor mount callback. */
  onMount?: (editor: monaco.editor.IStandaloneCodeEditor) => void;
  /** Editor value change callback. */
  onChange?: (
    value: string,
    editor: monaco.editor.IStandaloneCodeEditor,
    changePosition?: monaco.Position | null,
    changeRange?: monaco.IRange | null,
  ) => void;
  /** Immediate editor value change callback for lightweight UI state. */
  onContentChange?: (value: string) => void;
  /** Cursor change callback. */
  onCursorChange?: (editor: monaco.editor.IStandaloneCodeEditor) => void;
  /** Mouse click callback. */
  onMouseClick?: (editor: monaco.editor.IStandaloneCodeEditor, e: monaco.editor.IEditorMouseEvent) => void;
  /** Context menu callback. */
  onContextMenu?: (e: monaco.editor.IEditorMouseEvent) => void;
  /** Mouse hover callback. */
  onHover?: (editor: monaco.editor.IStandaloneCodeEditor, e: monaco.editor.IEditorMouseEvent) => void;
  /** Focus change callback. */
  focusChange?: (isFocus: boolean) => void;
  /** Show content-level change hints. */
  enableContentDiffHints?: boolean;
};

export interface MonacoEditorRef {
  getId: () => string;
  getInstance: () => monaco.editor.IStandaloneCodeEditor | null;
  getValue: () => string;
  setValue: (value: string, type?: EditorSetValueType) => void;
  getContentDiffBaseline: () => string;
  resetContentDiffBaseline: (value?: string) => void;
  getSelectedContent: () => string;
}

const MonacoSQLEditor = forwardRef<MonacoEditorRef, MonacoSQLEditorProps>(
  (
    {
      id,
      defaultValue,
      onChange,
      onContentChange,
      onCursorChange,
      onMouseClick,
      onMount,
      onContextMenu,
      onHover,
      shortcutKey,
      focusChange,
      className,
      options,
      disableFind = false,
      enableContentDiffHints = false,
      ...editorOptions
    },
    ref,
  ) => {
    const wrapperRef = useRef<HTMLDivElement>(null);
    /** Editor DOM element. */
    const editorRef = useRef<HTMLDivElement>(null);
    /** Editor instance. */
    const editorInstanceRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
    /** Decorations. */
    const decorationCollectionRef = useRef<monaco.editor.IEditorDecorationsCollection | null>(null);
    const contentDiffDecorationCollectionRef = useRef<monaco.editor.IEditorDecorationsCollection | null>(null);
    const baselineTextRef = useRef(defaultValue ?? '');
    const baselineEpochRef = useRef(0);
    const contentDiffHunksRef = useRef<ContentDiffHunk[]>([]);
    const contentDiffSnapshotRef = useRef<ContentDiffSnapshot | null>(null);
    const activeContentDiffViewRef = useRef<{ hunkId: string; view: ContentDiffInlineView } | null>(null);
    const contentDiffRefreshFrameRef = useRef<number | null>(null);
    const [contentDiffGutterMarkers, setContentDiffGutterMarkers] = useState<ContentDiffGutterMarker[]>([]);
    const completionAcceptKeyEnterContextRef = useRef<monaco.editor.IContextKey<boolean> | null>(null);
    const completionAcceptKeyTabContextRef = useRef<monaco.editor.IContextKey<boolean> | null>(null);
    /** Whether the editor is ready. */
    const [isEditorReady, setIsEditorReady] = useState(false);
    /** Whether the editor has focus. */
    const [isFocus, setIsFocus] = useState(false);
    const [hasContentDiffHints, setHasContentDiffHints] = useState(false);
    /** Global editor settings. */
    const { globalEditorSettings, getEditorTheme } = useGlobalStore((s) => {
      return {
        globalEditorSettings: s.editorSettings,
        getEditorTheme: s.getEditorTheme,
      };
    });
    const { theme } = useStyles();
    const { appearance } = theme;

    useEffect(() => {
      if (wrapperRef.current && shortcutKey) {
        shortcutKey(wrapperRef.current, monaco, isFocus);
      }
    }, [wrapperRef.current, isFocus]);

    const clearContentDiffState = useCallback((collection = contentDiffDecorationCollectionRef.current) => {
      contentDiffHunksRef.current = [];
      contentDiffSnapshotRef.current = null;
      runMonacoDisposalSafely(() => collection?.clear());
      setContentDiffGutterMarkers([]);
      runMonacoDisposalSafely(() => activeContentDiffViewRef.current?.view.dispose());
      activeContentDiffViewRef.current = null;
      setHasContentDiffHints(false);
    }, []);

    const resetContentDiffBaseline = useCallback(
      (value: string) => {
        if (contentDiffRefreshFrameRef.current !== null) {
          window.cancelAnimationFrame(contentDiffRefreshFrameRef.current);
          contentDiffRefreshFrameRef.current = null;
        }
        baselineEpochRef.current += 1;
        baselineTextRef.current = value;
        clearContentDiffState();
      },
      [clearContentDiffState],
    );

    useImperativeHandle(ref, () => ({
      getInstance: () => editorInstanceRef?.current ?? null,
      getId: () => id,
      getValue: () => editorInstanceRef.current?.getValue() ?? '',
      setValue: (value: string, type: EditorSetValueType = 'end') => {
        handleSetValue(editorInstanceRef.current!, value, type);
        if (type === 'reset') {
          resetContentDiffBaseline(value || '');
        }
      },
      getContentDiffBaseline: () => baselineTextRef.current,
      resetContentDiffBaseline: (value?: string) => {
        resetContentDiffBaseline(value ?? editorInstanceRef.current?.getValue() ?? '');
      },
      getSelectedContent: handleGetSelectedContent,
    }));

    // Initialize the editor.
    useEffect(() => {
      if (!editorRef.current) return;

      setupMonacoEnvironment();

      const defaultOptions: monaco.editor.IStandaloneEditorConstructionOptions = {
        ...globalEditorSettings,
        theme: getEditorTheme(appearance),
        value: defaultValue,
        contextmenu: false,
        unusualLineTerminators: 'off',

        // Undocumented see https://github.com/Microsoft/vscode/issues/30795#issuecomment-410998882
        glyphMargin: true,
        folding: false,
        lineDecorationsWidth: 24,
        lineNumbersMinChars: 2,
        automaticLayout: true,
        hover: {
          enabled: true,
          delay: 500,
        },
        bracketPairColorization: {
          enabled: false,
        },
        fixedOverflowWidgets: false,
      };

      const sqlCompletionOptions: Pick<
        monaco.editor.IStandaloneEditorConstructionOptions,
        | 'acceptSuggestionOnCommitCharacter'
        | 'acceptSuggestionOnEnter'
        | 'fixedOverflowWidgets'
        | 'tabCompletion'
        | 'wordBasedSuggestions'
        | 'suggest'
      > = {
        acceptSuggestionOnCommitCharacter: false,
        ...getSqlCompletionAcceptKeyOptions(globalEditorSettings.completionAcceptKey),
        fixedOverflowWidgets: false,
        wordBasedSuggestions: 'off',
        suggest: {
          showWords: false,
          snippetsPreventQuickSuggestions: false,
        },
      };

      const mergedOptions = {
        ...defaultOptions,
        ...editorOptions,
        ...options,
        ...sqlCompletionOptions,
        suggest: {
          ...defaultOptions.suggest,
          ...options?.suggest,
          ...sqlCompletionOptions.suggest,
          snippetsPreventQuickSuggestions: false,
        },
      };

      const editorIns = monaco.editor.create(editorRef.current, mergedOptions);

      editorInstanceRef.current = editorIns;
      const completionAcceptKey = getSqlCompletionAcceptKey(globalEditorSettings.completionAcceptKey);
      completionAcceptKeyEnterContextRef.current = editorIns.createContextKey(
        SQL_COMPLETION_ACCEPT_KEY_ENTER_CONTEXT,
        completionAcceptKey === 'enter',
      );
      completionAcceptKeyTabContextRef.current = editorIns.createContextKey(
        SQL_COMPLETION_ACCEPT_KEY_TAB_CONTEXT,
        completionAcceptKey === 'tab',
      );

      unBindShortcut(editorInstanceRef.current);

      const addActionDisposerList = addAction(editorIns);

      setIsEditorReady(true);

      decorationCollectionRef.current = editorInstanceRef.current.createDecorationsCollection();
      contentDiffDecorationCollectionRef.current = editorInstanceRef.current.createDecorationsCollection();

      // Call onMount callback
      onMount?.(editorInstanceRef.current);

      return () => {
        if (contentDiffRefreshFrameRef.current !== null) {
          window.cancelAnimationFrame(contentDiffRefreshFrameRef.current);
          contentDiffRefreshFrameRef.current = null;
        }
        runMonacoDisposalSafely(() => activeContentDiffViewRef.current?.view.dispose());
        activeContentDiffViewRef.current = null;
        const editorInstance = editorInstanceRef.current;
        editorInstanceRef.current = null;
        runMonacoDisposalSafely(() => editorInstance?.dispose());
        addActionDisposerList.forEach((disposer) => runMonacoDisposalSafely(() => disposer?.dispose?.()));
        runMonacoDisposalSafely(() => contentDiffDecorationCollectionRef.current?.clear());
        contentDiffDecorationCollectionRef.current = null;
      };
    }, [id]);

    // Listen for editor focus changes.
    useEffect(() => {
      const focus = () => {
        setIsFocus(true);
        focusChange?.(true);
      };
      const blur = () => {
        setIsFocus(false);
        focusChange?.(false);
      };
      const focusListener = editorInstanceRef.current?.onDidFocusEditorText(focus);
      const blurListener = editorInstanceRef.current?.onDidBlurEditorText(blur);
      // Remove listeners.
      return () => {
        focusListener?.dispose();
        blurListener?.dispose();
      };
    }, []);

    const addAction = (editorIns) => {
      const addActionDisposerList: any[] = [];
      if (disableFind) {
        const disableFindDisposer = editorIns.addAction({
          id: 'custom-action-cmd-k',
          label: 'Custom Action',
          keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyF],
          run: () => {},
        });
        addActionDisposerList.push(disableFindDisposer);
      }
      return addActionDisposerList;
    };

    // Update editor options.
    useEffect(() => {
      if (!isEditorReady || !editorInstanceRef.current) return;
      const { customFontFamily, fontFamily } = globalEditorSettings;
      const sqlCompletionAcceptKeyOptions = getSqlCompletionAcceptKeyOptions(globalEditorSettings.completionAcceptKey);

      const mergedOptions: monaco.editor.IEditorOptions & monaco.editor.IGlobalEditorOptions = {
        ...globalEditorSettings,
        theme: getEditorTheme(appearance),
        fontFamily: [customFontFamily, fontFamily].filter(Boolean).join(','), // Prefer the custom font when merging settings.
        ...editorOptions,
        ...options,
        acceptSuggestionOnCommitCharacter: false,
        ...sqlCompletionAcceptKeyOptions,
        fixedOverflowWidgets: false,
        wordBasedSuggestions: 'off',
        suggest: {
          ...options?.suggest,
          showWords: false,
          snippetsPreventQuickSuggestions: false,
        },
      };
      editorInstanceRef.current.updateOptions(mergedOptions);
      const completionAcceptKey = getSqlCompletionAcceptKey(globalEditorSettings.completionAcceptKey);
      completionAcceptKeyEnterContextRef.current?.set(completionAcceptKey === 'enter');
      completionAcceptKeyTabContextRef.current?.set(completionAcceptKey === 'tab');
    }, [isEditorReady, globalEditorSettings, appearance, editorOptions, options]);

    const updateContentDiffGutterMarkers = useCallback((hunks = contentDiffHunksRef.current) => {
      const editor = editorInstanceRef.current;
      if (!editor || !hunks.length) {
        setContentDiffGutterMarkers([]);
        return;
      }

      try {
        const scrollTop = editor.getScrollTop();
        const lineHeight = editor.getOption(monaco.editor.EditorOption.lineHeight) || 20;
        const layoutInfo = editor.getLayoutInfo();
        const markerLeft = layoutInfo.decorationsLeft + 18;

        setContentDiffGutterMarkers(
          hunks.map((hunk) => {
            return {
              hunk,
              left: markerLeft,
              top: editor.getTopForLineNumber(hunk.anchorLineNumber) - scrollTop,
              height: Math.max(hunk.displayLineCount, 1) * lineHeight,
            };
          }),
        );
      } catch {
        setContentDiffGutterMarkers([]);
      }
    }, []);

    const updateContentDiffHints = useCallback(() => {
      const editor = editorInstanceRef.current;
      const collection = contentDiffDecorationCollectionRef.current;

      if (
        !enableContentDiffHints ||
        !isContentDiffSurfaceEnabled(ContentDiffSurface.GutterHints) ||
        !editor ||
        !collection
      ) {
        clearContentDiffState();
        return;
      }

      const model = editor.getModel();
      if (!model) {
        clearContentDiffState(collection);
        return;
      }

      const currentText = model.getValue();
      const textGuard = guardContentDiffTexts(baselineTextRef.current, currentText);
      if (!textGuard.enabled) {
        clearContentDiffState(collection);
        return;
      }

      const snapshot: ContentDiffSnapshot = {
        baselineEpoch: baselineEpochRef.current,
        baselineHash: textGuard.baselineHash,
        currentHash: textGuard.currentHash,
        model,
        modelVersionId: model.getVersionId(),
      };
      let hunks: ContentDiffHunk[] = [];
      try {
        hunks = buildContentDiffHunks(baselineTextRef.current, currentText);
      } catch {
        clearContentDiffState(collection);
        return;
      }
      const decorationCount = getContentDiffDecorationCount(hunks);
      if (isContentDiffHunkBudgetExceeded({ hunkCount: hunks.length, decorationCount })) {
        clearContentDiffState(collection);
        return;
      }
      const activeEditor = editorInstanceRef.current;
      const activeModel = activeEditor?.getModel();
      const isSnapshotCurrent =
        activeEditor === editor &&
        activeModel === snapshot.model &&
        activeModel?.getVersionId() === snapshot.modelVersionId &&
        baselineEpochRef.current === snapshot.baselineEpoch &&
        hashContentDiffText(baselineTextRef.current) === snapshot.baselineHash &&
        hashContentDiffText(activeModel?.getValue() ?? '') === snapshot.currentHash;

      if (!isSnapshotCurrent) {
        return;
      }

      const hasHints = hunks.length > 0;
      contentDiffHunksRef.current = hunks;
      contentDiffSnapshotRef.current = snapshot;
      setHasContentDiffHints(hasHints);
      updateContentDiffGutterMarkers(hunks);

      if (
        activeContentDiffViewRef.current &&
        !hunks.some((hunk) => hunk.id === activeContentDiffViewRef.current?.hunkId)
      ) {
        runMonacoDisposalSafely(() => activeContentDiffViewRef.current?.view.dispose());
        activeContentDiffViewRef.current = null;
      }

      try {
        collection.set(
          hunks.flatMap((hunk) => {
            const decorations: monaco.editor.IModelDeltaDecoration[] = [];

            for (
              let lineNumber = hunk.currentRange.startLineNumber;
              lineNumber <= hunk.currentRange.endLineNumber;
              lineNumber += 1
            ) {
              decorations.push({
                range: new monaco.Range(lineNumber, 1, lineNumber, 1),
                options: {
                  isWholeLine: true,
                  minimap: {
                    color: getContentDiffOverviewColor(hunk.kind),
                    position: monaco.editor.MinimapPosition.Gutter,
                  },
                  overviewRuler: {
                    color: getContentDiffOverviewColor(hunk.kind),
                    position: monaco.editor.OverviewRulerLane.Left,
                  },
                },
              });
            }

            return decorations;
          }),
        );
      } catch {
        clearContentDiffState(collection);
      }
    }, [clearContentDiffState, enableContentDiffHints, updateContentDiffGutterMarkers]);

    const scheduleContentDiffHintsRefresh = useCallback(() => {
      if (!enableContentDiffHints || contentDiffRefreshFrameRef.current !== null) {
        return;
      }

      contentDiffRefreshFrameRef.current = window.requestAnimationFrame(() => {
        contentDiffRefreshFrameRef.current = null;
        updateContentDiffHints();
      });
    }, [enableContentDiffHints, updateContentDiffHints]);

    useEffect(() => {
      if (!enableContentDiffHints) {
        clearContentDiffState();
      }
    }, [clearContentDiffState, enableContentDiffHints]);

    const isContentDiffSnapshotCurrent = useCallback((model?: monaco.editor.ITextModel | null) => {
      const snapshot = contentDiffSnapshotRef.current;

      return Boolean(
        model &&
          snapshot &&
          snapshot.model === model &&
          snapshot.modelVersionId === model.getVersionId() &&
          snapshot.baselineEpoch === baselineEpochRef.current &&
          snapshot.baselineHash === hashContentDiffText(baselineTextRef.current) &&
          snapshot.currentHash === hashContentDiffText(model.getValue()),
      );
    }, []);

    const revertContentDiffHunk = useCallback(
      (hunk: ContentDiffHunk) => {
        const editor = editorInstanceRef.current;
        const model = editor?.getModel();

        if (!editor || !model || hunk.kind === ContentDiffKind.Added) {
          return;
        }

        if (!isContentDiffSnapshotCurrent(model)) {
          runMonacoDisposalSafely(() => activeContentDiffViewRef.current?.view.dispose());
          activeContentDiffViewRef.current = null;
          scheduleContentDiffHintsRefresh();
          return;
        }

        const lineCount = model.getLineCount();
        let range: monaco.Range;
        let text: string;

        if (hunk.kind === ContentDiffKind.Deleted) {
          const shouldAppendAtEnd = hunk.baselineRange.startLineNumber > lineCount;
          if (!model.getValue()) {
            range = new monaco.Range(1, 1, 1, 1);
            text = hunk.baselineText;
          } else if (shouldAppendAtEnd) {
            const lastLineNumber = Math.max(lineCount, 1);
            const lastColumn = model.getLineMaxColumn(lastLineNumber);
            range = new monaco.Range(lastLineNumber, lastColumn, lastLineNumber, lastColumn);
            text = `\n${hunk.baselineText}`;
          } else {
            const insertLineNumber = Math.min(Math.max(hunk.currentRange.startLineNumber, 1), lineCount);
            range = new monaco.Range(insertLineNumber, 1, insertLineNumber, 1);
            text = `${hunk.baselineText}\n`;
          }
        } else {
          range = new monaco.Range(
            hunk.currentRange.startLineNumber,
            1,
            hunk.currentRange.endLineNumber,
            model.getLineMaxColumn(hunk.currentRange.endLineNumber),
          );
          text = hunk.baselineText;
        }

        try {
          runMonacoDisposalSafely(() => activeContentDiffViewRef.current?.view.dispose());
          activeContentDiffViewRef.current = null;
          editor.pushUndoStop();
          editor.executeEdits('content-diff-revert', [{ range, text, forceMoveMarkers: true }]);
          editor.pushUndoStop();
          scheduleContentDiffHintsRefresh();
          editor.focus();
        } catch {
          runMonacoDisposalSafely(() => activeContentDiffViewRef.current?.view.dispose());
          activeContentDiffViewRef.current = null;
          scheduleContentDiffHintsRefresh();
        }
      },
      [isContentDiffSnapshotCurrent, scheduleContentDiffHintsRefresh],
    );

    const openContentDiffViewer = useCallback(
      (hunk: ContentDiffHunk) => {
        const editor = editorInstanceRef.current;
        const model = editor?.getModel();

        if (
          !enableContentDiffHints ||
          !editor ||
          !isContentDiffSurfaceEnabled(ContentDiffSurface.InlineViewer) ||
          !isContentDiffSnapshotCurrent(model) ||
          hunk.kind === ContentDiffKind.Added ||
          !contentDiffHunksRef.current.some((currentHunk) => currentHunk.id === hunk.id)
        ) {
          return;
        }

        if (activeContentDiffViewRef.current?.hunkId === hunk.id) {
          runMonacoDisposalSafely(() => activeContentDiffViewRef.current?.view.dispose());
          activeContentDiffViewRef.current = null;
          return;
        }

        runMonacoDisposalSafely(() => activeContentDiffViewRef.current?.view.dispose());
        try {
          const afterLineNumber = getContentDiffInlineAfterLineNumber(editor, hunk);
          const nextHunk = getAdjacentContentDiffHunk(contentDiffHunksRef.current, hunk, 'next');
          const previousHunk = getAdjacentContentDiffHunk(contentDiffHunksRef.current, hunk, 'previous');
          const navigateContentDiffHunk = (targetHunk: ContentDiffHunk) => {
            if (!targetHunk) {
              return;
            }

            editor.setPosition({ lineNumber: targetHunk.anchorLineNumber, column: 1 });
            editor.revealLineInCenterIfOutsideViewport(targetHunk.anchorLineNumber);
            openContentDiffViewer(targetHunk);
          };
          activeContentDiffViewRef.current = {
            hunkId: hunk.id,
            view: createContentDiffInlineView(
              editor,
              {
                afterLineNumber,
                baselineText: hunk.baselineText,
                currentText: hunk.currentText,
                heightInLines: Math.max(hunk.baselineLineCount + hunk.currentLineCount, 3),
                onNext: nextHunk ? () => navigateContentDiffHunk(nextHunk) : undefined,
                onPrevious: previousHunk ? () => navigateContentDiffHunk(previousHunk) : undefined,
                onRevert: () => revertContentDiffHunk(hunk),
              },
              () => {
                activeContentDiffViewRef.current = null;
              },
            ),
          };
        } catch {
          activeContentDiffViewRef.current = null;
        }
      },
      [enableContentDiffHints, isContentDiffSnapshotCurrent, revertContentDiffHunk],
    );

    const handleMouseClick = useCallback(
      (e: monaco.editor.IEditorMouseEvent) => {
        if (!editorInstanceRef?.current) return;

        onMouseClick?.(editorInstanceRef?.current, e);
      },
      [onMouseClick],
    );

    const handleContextMenu = useCallback(
      (e: monaco.editor.IEditorMouseEvent) => {
        if (!editorInstanceRef?.current) return;

        onContextMenu?.(e);
      },
      [onContextMenu],
    );

    const handleCursorChange = useCallback(() => {
      if (!editorInstanceRef.current) return;

      onCursorChange?.(editorInstanceRef.current);
    }, [onCursorChange]);

    const handleValueChange = useCallback(
      debounce((changePosition?: monaco.Position | null, changeRange?: monaco.IRange | null) => {
        if (!editorInstanceRef.current) return;

        const newValue = editorInstanceRef.current?.getValue() || '';

        onChange?.(newValue, editorInstanceRef.current, changePosition, changeRange);
      }, 300),
      [onChange],
    );

    // Listen to the editor.
    // Listen to the editor.
    useEffect(() => {
      if (!isEditorReady || !editorInstanceRef.current) return;

      const editor = editorInstanceRef.current;

      const punctuationKeyDownDisposer = editor.onKeyDown((event) => {
        const model = editor.getModel();
        const position = editor.getPosition();
        if (!model || !position || !shouldNormalizeSqlInputPunctuation(model.getValue(), model.getOffsetAt(position))) {
          return;
        }

        const punctuation = sqlPunctuationFromKeyboardEvent(event.browserEvent);
        if (!punctuation) return;

        event.preventDefault();
        event.stopPropagation();
        editor.trigger('sql-input-punctuation', 'type', { text: punctuation });
      });

      // Handle content changes.
      const didChangeModelContentDisposer = editor.onDidChangeModelContent((event) => {
        onContentChange?.(editor.getValue());

        const lastChange = event.changes[event.changes.length - 1];
        if (
          event.changes.length === 1 &&
          !event.isFlush &&
          !event.isUndoing &&
          !event.isRedoing &&
          lastChange?.rangeLength === 0
        ) {
          const normalizedPunctuation = normalizeSqlInputPunctuation(lastChange.text);
          const model = editor.getModel();
          const insertedPosition = new monaco.Position(lastChange.range.startLineNumber, lastChange.range.startColumn);
          const shouldNormalize =
            model &&
            normalizedPunctuation &&
            shouldNormalizeSqlInputPunctuation(model.getValue(), model.getOffsetAt(insertedPosition));
          if (shouldNormalize) {
            const insertedRange = new monaco.Range(
              lastChange.range.startLineNumber,
              lastChange.range.startColumn,
              lastChange.range.startLineNumber,
              lastChange.range.startColumn + lastChange.text.length,
            );

            editor.executeEdits('sql-input-punctuation-normalize', [
              {
                range: insertedRange,
                text: normalizedPunctuation,
                forceMoveMarkers: true,
              },
            ]);
            editor.setPosition(
              new monaco.Position(
                lastChange.range.startLineNumber,
                lastChange.range.startColumn + normalizedPunctuation.length,
              ),
            );
            scheduleContentDiffHintsRefresh();
            return;
          }
        }

        scheduleContentDiffHintsRefresh();
        handleValueChange(editor.getPosition(), lastChange?.range || null);
      });

      // Handle cursor position changes.
      const didChangeCursorPositionDisposer = editor.onDidChangeCursorPosition(handleCursorChange);

      // Listen for clicks.
      const mouseDownDisposer = editor.onMouseDown(handleMouseClick);

      // Handle the custom context menu.
      const contextMenuDisposer = editor.onContextMenu(handleContextMenu);
      const scrollDisposer = editor.onDidScrollChange(() => updateContentDiffGutterMarkers());
      const layoutDisposer = editor.onDidLayoutChange(() => updateContentDiffGutterMarkers());

      return () => {
        handleValueChange.cancel();
        punctuationKeyDownDisposer.dispose();
        didChangeModelContentDisposer.dispose();
        didChangeCursorPositionDisposer.dispose();
        mouseDownDisposer.dispose();
        contextMenuDisposer.dispose();
        scrollDisposer.dispose();
        layoutDisposer.dispose();
      };
    }, [
      isEditorReady,
      handleMouseClick,
      handleContextMenu,
      handleCursorChange,
      handleValueChange,
      onContentChange,
      updateContentDiffGutterMarkers,
      scheduleContentDiffHintsRefresh,
    ]);

    const unBindShortcut = (editor: monaco.editor.IStandaloneCodeEditor) => {
      editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyK, () => {
        const event = new KeyboardEvent('keydown', {
          key: 'k',
          code: 'KeyK',
          metaKey: true,
          bubbles: true,
        });
        wrapperRef.current?.dispatchEvent(event);
      });
      editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Slash, () => {});
      editor.addCommand(monaco.KeyMod.Shift | monaco.KeyMod.Alt | monaco.KeyCode.KeyA, () => {});
    };

    const handleHover = useCallback(
      debounce((e: monaco.editor.IEditorMouseEvent) => {
        if (e.target.type === monaco.editor.MouseTargetType.CONTENT_TEXT) {
          const editor = editorInstanceRef.current;
          if (!editor) return;

          onHover && onHover?.(editor, e);
        }
      }, 300),
      [onHover],
    );
    useEffect(() => {
      if (!isEditorReady || !editorInstanceRef.current) return;

      const editor = editorInstanceRef.current;

      const hoverDispose = editor.onMouseMove(handleHover);
      return () => {
        hoverDispose.dispose();
      };
    }, [isEditorReady, handleHover]);

    const handleGetSelectedContent = () => {
      const editor = editorInstanceRef.current;
      if (!editor) return '';

      const selection = editor.getSelection();
      if (!selection) return '';
      return editor.getModel()?.getValueInRange(selection) ?? '';
    };

    return (
      <div key={id} ref={wrapperRef} style={{ width: '100%', height: '100%', position: 'relative' }}>
        <div
          ref={editorRef}
          style={{ width: '100%', height: '100%' }}
          className={[className, hasContentDiffHints ? 'content-diff-active' : undefined].filter(Boolean).join(' ')}
        />
        {hasContentDiffHints && (
          <div className="content-diff-gutter-overlay">
            {contentDiffGutterMarkers.map(({ hunk, left, top, height }) => {
              const markerClassName = getContentDiffGutterMarkerClassName(hunk.kind);
              const style = { left, top, height };

              if (hunk.kind === ContentDiffKind.Added) {
                return <span key={hunk.id} className={markerClassName} style={style} />;
              }

              return (
                <button
                  key={hunk.id}
                  type="button"
                  className={markerClassName}
                  style={style}
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={(event) => {
                    event.preventDefault();
                    event.stopPropagation();
                    openContentDiffViewer(hunk);
                  }}
                />
              );
            })}
          </div>
        )}
      </div>
    );
  },
);

export default MonacoSQLEditor;

const getContentDiffGutterMarkerClassName = (kind: ContentDiffHunk['kind']) => {
  switch (kind) {
    case ContentDiffKind.Added:
      return 'content-diff-gutter-marker content-diff-gutter-marker-added';
    case ContentDiffKind.Deleted:
      return 'content-diff-gutter-marker content-diff-gutter-marker-deleted';
    default:
      return 'content-diff-gutter-marker content-diff-gutter-marker-modified';
  }
};

const getContentDiffOverviewColor = (kind: ContentDiffHunk['kind']) => {
  switch (kind) {
    case ContentDiffKind.Added:
      return '#3fb950';
    case ContentDiffKind.Deleted:
      return '#f85149';
    default:
      return '#d29922';
  }
};

const getContentDiffInlineAfterLineNumber = (
  editor: monaco.editor.IStandaloneCodeEditor,
  hunk: ContentDiffHunk,
) => {
  const cursorLineNumber = editor.getPosition()?.lineNumber;
  const fallbackLineNumber = hunk.currentRange.endLineNumber;

  if (!cursorLineNumber) {
    return fallbackLineNumber;
  }

  return Math.min(Math.max(cursorLineNumber, hunk.currentRange.startLineNumber), hunk.currentRange.endLineNumber);
};

const getAdjacentContentDiffHunk = (
  hunks: ContentDiffHunk[],
  currentHunk: ContentDiffHunk,
  direction: 'next' | 'previous',
) => {
  const navigableHunks = hunks.filter((hunk) => hunk.kind !== ContentDiffKind.Added);
  const currentIndex = navigableHunks.findIndex((hunk) => hunk.id === currentHunk.id);

  if (currentIndex < 0 || !navigableHunks.length) {
    return null;
  }

  if (navigableHunks.length === 1) {
    return null;
  }

  const offset = direction === 'next' ? 1 : -1;
  return navigableHunks[(currentIndex + offset + navigableHunks.length) % navigableHunks.length];
};
