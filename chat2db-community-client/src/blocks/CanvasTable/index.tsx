import React, {
  memo,
  forwardRef,
  useImperativeHandle,
  ForwardedRef,
  useEffect,
  useState,
  useRef,
  useMemo,
  useCallback,
} from 'react';
import { useStyles } from './style';
import * as VTable from '@visactor/vtable';
import useTableTheme from './hooks/useTableTheme';
import registeredEditor from './editor/InputIEditor';
import ContextMenu, { ContextMenuRef } from '@/components/ContextMenu';
import { ITableInstance } from './typings';
import useTooltip from './hooks/useTooltip';
import { copyToClipboard } from '@/utils';
import {
  ShortcutAction,
  ShortcutOverrides,
  getEffectiveShortcutConfigMap,
  isShortcutEventMatch,
} from '@/constants/shortcut';
import { useGlobalStore } from '@/store/global';
import { isCurrentTableInstance, releaseTableInstanceSafely, type TableViewport } from './lifecycle';

export interface ICustomOptions {
  // Whether to display the left border
  showLeftBorder?: boolean;
}

interface IProps {
  className?: string;
  records: any[];
  columns: any[];
  tooltip?: boolean;
  options?: VTable.ListTableConstructorOptions;
  customOptions?: ICustomOptions;
  // callback after initialization is completed
  onInit?: (tableInstance: ITableInstance) => void;
  onRelease?: () => void;
  active?: boolean;
  onCopy?: () => void;
  onPaste?: () => void;
}

export interface CanvasTableRef {
  getInstance: () => ITableInstance | null;
}

const NON_TEXT_INPUT_TYPES = new Set([
  'button',
  'checkbox',
  'color',
  'file',
  'hidden',
  'image',
  'radio',
  'range',
  'reset',
  'submit',
]);

function isEditableElement(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false;
  }

  const editable = target.closest('input, textarea, [contenteditable="true"], [contenteditable=""]');
  if (!(editable instanceof HTMLElement)) {
    return false;
  }

  if (editable instanceof HTMLInputElement) {
    return !editable.disabled && !editable.readOnly && !NON_TEXT_INPUT_TYPES.has(editable.type);
  }

  if (editable instanceof HTMLTextAreaElement) {
    return !editable.disabled && !editable.readOnly;
  }

  return editable.isContentEditable;
}

const CanvasTable = forwardRef((props: IProps, ref: ForwardedRef<CanvasTableRef>) => {
  const {
    className,
    records,
    columns,
    onInit,
    onRelease,
    active = true,
    tooltip,
    options = null,
    customOptions,
    onCopy,
    onPaste,
  } = props;
  const { styles, theme, cx } = useStyles();
  const [tableInstance, setTableInstance] = useState<ITableInstance | null>(null);
  const tableInstanceRef = useRef<ITableInstance | null>(null);
  const viewportRef = useRef<TableViewport>({ scrollLeft: 0, scrollTop: 0 });
  const restoreViewportFrameRef = useRef<number | null>(null);
  const onInitRef = useRef(onInit);
  const onReleaseRef = useRef(onRelease);
  const tableRef = useRef<HTMLDivElement>(null);
  const tableTheme = useTableTheme({ antdTheme: theme, options, customOptions });
  const contextMenuRef = useRef<ContextMenuRef>(null);
  const tooltipTongs = useTooltip({ tableInstance, tooltip });
  const containerRef = React.useRef<HTMLDivElement>(null);
  const shortcutOverrides = useGlobalStore((s) => s.shortcutOverrides);
  const shortcutConfig = useMemo(
    () => getEffectiveShortcutConfigMap(shortcutOverrides as ShortcutOverrides),
    [shortcutOverrides],
  );

  onInitRef.current = onInit;
  onReleaseRef.current = onRelease;

  const cancelViewportRestore = useCallback(() => {
    if (restoreViewportFrameRef.current !== null) {
      cancelAnimationFrame(restoreViewportFrameRef.current);
      restoreViewportFrameRef.current = null;
    }
  }, []);

  const releaseCurrentTable = useCallback(() => {
    cancelViewportRestore();
    const result = releaseTableInstanceSafely(tableInstanceRef, () => onReleaseRef.current?.());
    if (result.viewport) {
      viewportRef.current = result.viewport;
    }
    if (result.error) {
      console.error('Failed to release CanvasTable instance', result.error);
    }
    return result.released;
  }, [cancelViewportRestore]);

  useEffect(() => {
    // On Windows, this area conflicts with the table's horizontal scrollbar.
    // CSS user-select does not prevent it, so disable dragging with JavaScript.
    const dragStart = (e: DragEvent) => {
      e.preventDefault();
    };

    // Be careful and assign containerRef.current to a variable, otherwise it will not be able to be uninstalled.
    const currentContainer = containerRef.current;
    currentContainer?.addEventListener('dragstart', dragStart);

    return () => {
      currentContainer?.removeEventListener('dragstart', dragStart);
    };
  }, []);

  useEffect(() => {
    // Registration Editor
    registeredEditor();
  }, []);

  useEffect(
    () => () => {
      releaseCurrentTable();
    },
    [releaseCurrentTable],
  );

  // This only takes effect once, subsequent changes in tableTheme and options have special useEffect processing
  const tableOption = useMemo(() => {
    if (!tableTheme || !records || !columns) {
      return null;
    }

    return {
      records,
      columns,
      dragHeaderMode: 'all' as any, // Enable column drag
      widthMode: 'autoWidth' as any, // Automatically calculate column width ignoring width attribute
      defaultRowHeight: 28,
      theme: tableTheme,
      select: {
        highlightMode: 'row' as any,
      },
      rowResizeMode: 'body' as any,
      /*
       * Displays the frozen-column icon. Custom implementations may need to place and pin this column first.
       */
      // showFrozenIcon: true,
      // allowFrozenColCount: 2000, // Number of frozen columns allowed
      /* https://visactor.io/vtable/option/ListTable#keyboardOptions */
      keyboardOptions: {
        copySelected: false,
        pasteValueToCell: false,
        selectAllOnCtrlA: false,
      },
      enableLineBreak: true, // turns on line wrapping
      // transpose: true, // Turn on transpose Vtable seems to have a bug
      tooltip: {
        isShowOverflowTextTooltip: false,
      },
      ...(options || {}),
    };
  }, [tableTheme, records, columns]);

  // initialize table according to option
  useEffect(() => {
    if (!active) {
      if (releaseCurrentTable()) {
        setTableInstance(null);
      }
      return;
    }

    if (!tableRef.current || !tableOption) {
      return;
    }

    if (!tableInstanceRef.current) {
      // Release measurement text
      // VTable.restoreMeasureText();
      const _tableInstance: ITableInstance = new VTable.ListTable(tableRef.current, tableOption);

      // Add right-click menu/click pop-up window
      _tableInstance.contextMenuRef = contextMenuRef;

      tableInstanceRef.current = _tableInstance;
      setTableInstance(_tableInstance);

      onInitRef.current?.(_tableInstance);

      const viewport = viewportRef.current;
      if (viewport.scrollLeft || viewport.scrollTop) {
        restoreViewportFrameRef.current = requestAnimationFrame(() => {
          restoreViewportFrameRef.current = null;
          if (tableInstanceRef.current !== _tableInstance) {
            return;
          }
          _tableInstance.setScrollLeft(viewport.scrollLeft);
          _tableInstance.setScrollTop(viewport.scrollTop);
        });
      }
    }
  }, [active, releaseCurrentTable, tableOption]);

  // To update options, all options need to be passed in. This will not work.
  // useEffect(() => {
  //   if (!tableInstance || !options) return;
  //   tableInstance.updateOption(options);
  // }, [options]);

  // update theme
  useEffect(() => {
    if (!isCurrentTableInstance(active, tableInstance, tableInstanceRef)) return;
    tableInstance.theme = tableTheme;
  }, [active, tableInstance, tableTheme]);

  // update records
  useEffect(() => {
    if (!isCurrentTableInstance(active, tableInstance, tableInstanceRef)) return;
    tableInstance.setRecords(records);
  }, [active, records, tableInstance]);

  // update columns
  useEffect(() => {
    if (!isCurrentTableInstance(active, tableInstance, tableInstanceRef)) return;
    const oldColumns = tableInstance.columns;
    // Keep the original headerIcon after refreshing
    const resColumns = columns.map((column) => {
      oldColumns.forEach((oldColumn) => {
        if (oldColumn.field === column.field) {
          column.headerIcon = oldColumn.headerIcon;
        }
      });
      return column;
    });
    tableInstance.updateColumns(resColumns);
  }, [active, columns, tableInstance]);

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (isEditableElement(e.target)) {
        return;
      }
      if (isShortcutEventMatch(e, shortcutConfig[ShortcutAction.TableCopy].binding) && onCopy) {
        e.preventDefault();
        onCopy();
        return;
      }
      if (isShortcutEventMatch(e, shortcutConfig[ShortcutAction.TableCopy].binding)) {
        e.preventDefault();
        const copyValue = tableInstance?.getCopyValue?.();
        if (copyValue !== null && copyValue !== undefined) {
          copyToClipboard(copyValue);
        }
        return;
      }
      if (isShortcutEventMatch(e, shortcutConfig[ShortcutAction.TablePaste].binding) && onPaste) {
        e.preventDefault();
        onPaste();
        return;
      }
      if (isShortcutEventMatch(e, shortcutConfig[ShortcutAction.TableSelectAll].binding)) {
        if (!tableInstance?.colCount || !tableInstance?.rowCount) {
          return;
        }
        e.preventDefault();
        tableInstance?.selectCells?.([
          {
            start: {
              col: 0,
              row: 0,
            },
            end: {
              col: tableInstance.colCount - 1,
              row: tableInstance.rowCount - 1,
            },
          },
        ]);
      }
    };

    // Add keyboard event monitoring
    containerRef.current?.addEventListener('keydown', handleKeyDown);

    return () => {
      // clean up event monitoring
      containerRef.current?.removeEventListener('keydown', handleKeyDown);
    };
  }, [onCopy, onPaste, shortcutConfig, tableInstance]);

  useImperativeHandle(ref, () => ({
    getInstance: () => {
      return tableInstance;
    },
  }));

  return (
    <div className={cx(className, styles.container)} ref={containerRef}>
      <div ref={tableRef} className={styles.table} />
      <ContextMenu ref={contextMenuRef} />
      {tooltipTongs}
    </div>
  );
});

export default memo(CanvasTable);
