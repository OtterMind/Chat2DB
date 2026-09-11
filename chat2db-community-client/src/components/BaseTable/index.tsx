import React, {
  memo,
  useState,
  useMemo,
  forwardRef,
  ForwardedRef,
  useImperativeHandle,
  useEffect,
  useRef,
} from 'react';
import { useStyles } from './style';
import { useTableStyles } from '@/styles/table';
import i18n from '@/i18n';
import {
  BaseTable as ALiBaseTable,
  ArtColumn,
  useTablePipeline,
  features,
} from 'ali-react-table';
import { Tooltip, Spin } from 'antd';
import { IconButton, EditText, Empty } from '@chat2db/ui';
import { copyToClipboard } from '@/utils';
import {
  getDirectoryJumpIndex,
  getTableKeyboardNavigationIndex,
  shouldActivateTableAction,
  shouldToggleTreeRowWithArrow,
} from './treeInteraction';

type TreeModeFeatureOptions = NonNullable<Parameters<typeof features.treeMode>[0]>;
interface BaseTableTreeMeta {
  depth: number;
  isLeaf: boolean;
  expanded: boolean;
  rowKey: string;
}
interface BaseTableKeyboardRow {
  rowKey: string;
  rowIndex: number;
  isDirectory: boolean;
}
const BASE_TABLE_TREE_META_KEY = Symbol('baseTableTreeMeta');
const BASE_TABLE_KEYBOARD_ACTION_SELECTOR = '[data-base-table-keyboard-action="true"]';
const BASE_TABLE_ROW_HEIGHT = 32;
const KEYBOARD_FOCUS_MAX_FRAME_ATTEMPTS = 5;

function findTableKeyboardAction(root: HTMLElement, rowKey: string) {
  return Array.from(
    root.querySelectorAll<HTMLElement>(BASE_TABLE_KEYBOARD_ACTION_SELECTOR),
  ).find((action) => action.dataset.treeRowKey === rowKey);
}

function handleTableRootFocus(event: React.FocusEvent<HTMLDivElement>) {
  if (event.target !== event.currentTarget) {
    return;
  }
  const previousTarget = event.relatedTarget as Node | null;
  if (previousTarget && event.currentTarget.contains(previousTarget)) {
    return;
  }
  event.currentTarget.querySelector<HTMLElement>(BASE_TABLE_KEYBOARD_ACTION_SELECTOR)?.focus();
}

function handleTreeToggleKeyDown(event: React.KeyboardEvent<HTMLDivElement>, expanded: boolean) {
  if (shouldActivateTableAction(event.key) || shouldToggleTreeRowWithArrow(event.key, expanded)) {
    event.preventDefault();
    event.currentTarget.click();
  }
}

interface IProps {
  className?: string;
  // Table data.
  tableData?: any[] | null;
  // Table column configuration.
  columns: {
    title: string;
    name: string;
    lock?: boolean;
    onlyNumber?: boolean;
    editable?: boolean | undefined | ((rowData: any) => boolean | undefined);
    render?: (value: any, rowData: any, index: number) => React.ReactNode;
    transitionValue?: (value: any, rowData: any) => string;
  }[];
  // Column width.
  sizes?: number[];
  // Whether to show tooltips.
  tooltip?: boolean;
  // Whether the first column is draggable.
  immutableFirstColumn?: boolean;
  // Whether columns are draggable.
  draggableColumn?: boolean;
  // Whether cells are editable.
  editable?: boolean;
  // Whether the header is required.
  hasHeader?: boolean;
  // Whether the table is loading.
  loading?: boolean;
  // Stable string key required by treeMode.
  primaryKey?: string;
  // Optional hierarchical row behavior supplied by ali-react-table.
  treeMode?: TreeModeFeatureOptions;
  // Maps a visible row back to its source row. Returning undefined disables row interaction.
  getRowIndex?: (rowData: any, visibleRowIndex: number) => number | undefined;
  // Whether to render the default empty-state illustration.
  showEmptyState?: boolean;
  // Activates a source row from its first cell with Enter or Space.
  onActivateRow?: (index: number, rowData: any) => void;
  // Handles clicks anywhere inside a selectable source row.
  onRowClick?: (index: number, rowData: any) => void;
  // Handles Escape while focus is inside the table.
  onEscapeKey?: () => void;
  // Cell-edit callback.
  onChangeCell?: (index, columnName, value) => void;
  // Selected rows.
  selectedRows?: number[];
  // Selection-change callback.
  onSelectedRowsChange?: (selectedRows: number[]) => void;
}

export interface BaseTableRef {
  scrollTo: (index: number) => void;
  scrollToTop: () => void;
  scrollToBottom: () => void;
  scrollToRight: () => void;
  scrollToLeft: () => void;
}

const BaseTable = forwardRef((props: IProps, ref: ForwardedRef<BaseTableRef>) => {
  const {
    className,
    tableData,
    columns: entheticColumns,
    sizes: entheticSizes,
    immutableFirstColumn,
    tooltip = false,
    draggableColumn = true,
    hasHeader = true,
    editable = false,
    onChangeCell,
    selectedRows,
    onSelectedRowsChange,
    loading,
    primaryKey,
    treeMode,
    getRowIndex,
    showEmptyState = true,
    onActivateRow,
    onRowClick,
    onEscapeKey,
  } = props;
  const { styles, cx, theme } = useStyles();
  const { styles: tableStyles } = useTableStyles();
  const [sizes, setColumnResize] = useState<number[]>(entheticSizes || []);
  const supportBaseTableBoxRef = useRef<HTMLDivElement>(null);
  const aLiBaseTableRef = useRef<any>(null);
  const keyboardFocusRequestIdRef = useRef(0);
  const keyboardFocusFrameRef = useRef<number | null>(null);

  useEffect(() => {
    return () => {
      aLiBaseTableRef.current = null;
      keyboardFocusRequestIdRef.current += 1;
      if (keyboardFocusFrameRef.current !== null) {
        window.cancelAnimationFrame(keyboardFocusFrameRef.current);
      }
    };
  }, []);

  // Table column configuration.
  const columns: ArtColumn[] = useMemo(() => {
    return entheticColumns.map((columnConfig, columnIndex) => {
      //
      const render = (value, rowData, index) => {
        const rowIndex = getRowIndex ? getRowIndex(rowData, index) : index;
        let _value = value;
        if (columnConfig.transitionValue) {
          _value = columnConfig.transitionValue(value, rowData);
        }

        const isSelected = rowIndex !== undefined && (selectedRows?.includes(rowIndex) || false);

        return (
          <TableCell
            columnConfig={columnConfig}
            rowData={rowData}
            index={rowIndex ?? index}
            selectable={rowIndex !== undefined}
            keyboardAction={columnIndex === 0 && rowIndex !== undefined && Boolean(onActivateRow)}
            keyboardRowKey={primaryKey ? String(rowData[primaryKey]) : String(rowIndex ?? index)}
            tooltip={tooltip}
            editable={editable}
            onChangeCell={onChangeCell}
            onActivateRow={onActivateRow}
            value={_value}
            isSelected={isSelected}
            setSelectedRows={onSelectedRowsChange}
            styles={styles}
            cx={cx}
          />
        );
      };

      return {
        title: columnConfig.title,
        name: columnConfig.name,
        code: columnConfig.name,
        lock: columnConfig.lock || false,
        render: columnConfig.render || render,
      };
    });
  }, [
    entheticColumns,
    selectedRows,
    getRowIndex,
    tooltip,
    editable,
    onChangeCell,
    onActivateRow,
    onSelectedRowsChange,
    primaryKey,
    styles,
    cx,
  ]);

  // Table rendering configuration.
  const pipeline = useTablePipeline().input({ dataSource: tableData || [], columns });

  if (primaryKey) {
    pipeline.primaryKey(primaryKey);
  }
  const treeMetaKey = treeMode?.treeMetaKey ?? BASE_TABLE_TREE_META_KEY;
  if (treeMode) {
    pipeline.use(features.treeMode({ ...treeMode, treeMetaKey }));
    pipeline.mapColumns((treeColumns) => {
      const [firstColumn, ...otherColumns] = treeColumns;
      if (!firstColumn) {
        return treeColumns;
      }
      const renderCell = firstColumn.render;
      return [
        {
          ...firstColumn,
          render(value, rowData, rowIndex) {
            const content = renderCell ? renderCell(value, rowData, rowIndex) : value;
            const treeMeta = rowData[treeMetaKey] as BaseTableTreeMeta | undefined;
            if (!treeMeta || treeMeta.isLeaf) {
              return content;
            }
            return (
              <div
                role="button"
                aria-expanded={treeMeta.expanded}
                data-base-table-keyboard-action="true"
                data-base-table-tree-toggle="true"
                data-tree-row-key={treeMeta.rowKey}
                tabIndex={-1}
                onKeyDown={(event) => handleTreeToggleKeyDown(event, treeMeta.expanded)}
                style={{
                  background: 'transparent',
                  border: 0,
                  color: 'inherit',
                  cursor: 'pointer',
                  display: 'block',
                  font: 'inherit',
                  height: '100%',
                  lineHeight: 'inherit',
                  minWidth: 0,
                  padding: 0,
                  textAlign: 'inherit',
                  width: '100%',
                }}
              >
                {content}
              </div>
            );
          },
        },
        ...otherColumns,
      ];
    });
  }
  if (onRowClick) {
    pipeline.appendRowPropsGetter((rowData, rowIndex) => {
      const sourceRowIndex = getRowIndex ? getRowIndex(rowData, rowIndex) : rowIndex;
      if (sourceRowIndex === undefined) {
        return {};
      }
      return {
        onClick: () => onRowClick(sourceRowIndex, rowData),
        style: { cursor: 'pointer' },
      };
    });
  }
  pipeline.use(
    features.columnResize({
      handleHoverBackground: theme.colorPrimaryBgHover,
      handleActiveBackground: theme.colorPrimaryBgHover,
      minSize: 60,
      maxSize: 500,
      sizes,
      onChangeSizes: (_sizes) => {
        if (draggableColumn) {
          if (immutableFirstColumn) {
            _sizes[0] = sizes[0];
          }
          setColumnResize(_sizes);
        }
      },
    }),
  );

  const keyboardRows: BaseTableKeyboardRow[] = pipeline
    .getDataSource()
    .flatMap((rowData, rowIndex): BaseTableKeyboardRow[] => {
      const treeMeta = treeMode ? (rowData[treeMetaKey] as BaseTableTreeMeta | undefined) : undefined;
      if (treeMeta && !treeMeta.isLeaf) {
        return [{ rowKey: String(treeMeta.rowKey), rowIndex, isDirectory: true }];
      }

      const sourceRowIndex = getRowIndex ? getRowIndex(rowData, rowIndex) : rowIndex;
      if (sourceRowIndex === undefined || !onActivateRow) {
        return [];
      }
      const rowKey = treeMeta?.rowKey ?? (primaryKey ? rowData[primaryKey] : sourceRowIndex);
      return [{ rowKey: String(rowKey), rowIndex, isDirectory: false }];
    });

  const scheduleTableKeyboardFocus = (
    rowKey: string,
    requestId: number,
    remainingAttempts = KEYBOARD_FOCUS_MAX_FRAME_ATTEMPTS,
  ) => {
    if (keyboardFocusRequestIdRef.current !== requestId) {
      return;
    }
    const root = supportBaseTableBoxRef.current;
    const action = root ? findTableKeyboardAction(root, rowKey) : undefined;
    if (action) {
      action.focus({ preventScroll: true });
      action.scrollIntoView({ block: 'nearest', inline: 'nearest' });
      keyboardFocusFrameRef.current = null;
      return;
    }
    if (remainingAttempts <= 0) {
      keyboardFocusFrameRef.current = null;
      return;
    }
    keyboardFocusFrameRef.current = window.requestAnimationFrame(() => {
      scheduleTableKeyboardFocus(rowKey, requestId, remainingAttempts - 1);
    });
  };

  const requestTableKeyboardFocus = (rowKey: string, defer = false) => {
    keyboardFocusRequestIdRef.current += 1;
    const requestId = keyboardFocusRequestIdRef.current;
    if (keyboardFocusFrameRef.current !== null) {
      window.cancelAnimationFrame(keyboardFocusFrameRef.current);
      keyboardFocusFrameRef.current = null;
    }
    if (defer) {
      keyboardFocusFrameRef.current = window.requestAnimationFrame(() => {
        scheduleTableKeyboardFocus(rowKey, requestId);
      });
      return;
    }
    scheduleTableKeyboardFocus(rowKey, requestId);
  };

  const moveTableKeyboardFocus = (event: React.KeyboardEvent<HTMLDivElement>) => {
    const eventTarget = event.target as HTMLElement;
    const currentAction = eventTarget.closest?.<HTMLElement>(BASE_TABLE_KEYBOARD_ACTION_SELECTOR);
    const currentRowKey = currentAction?.dataset.treeRowKey;
    const currentIndex = currentRowKey
      ? keyboardRows.findIndex((row) => row.rowKey === currentRowKey)
      : -1;
    const directoryJump =
      event.metaKey && (event.key === 'ArrowUp' || event.key === 'ArrowDown');
    const nextIndex = directoryJump
      ? getDirectoryJumpIndex(
          event.key,
          currentIndex,
          keyboardRows.map((row) => row.isDirectory),
        )
      : getTableKeyboardNavigationIndex(event.key, currentIndex, keyboardRows.length);
    if (nextIndex === null) {
      return;
    }
    const nextRow = keyboardRows[nextIndex];
    if (!nextRow) {
      return;
    }
    event.preventDefault();
    const root = supportBaseTableBoxRef.current;
    const mountedAction = root ? findTableKeyboardAction(root, nextRow.rowKey) : undefined;
    if (mountedAction) {
      requestTableKeyboardFocus(nextRow.rowKey);
      return;
    }
    root?.scrollTo({
      top: nextRow.rowIndex * BASE_TABLE_ROW_HEIGHT,
      behavior: 'auto',
    });
    requestTableKeyboardFocus(nextRow.rowKey, true);
  };

  const scrollTo = (index) => {
    supportBaseTableBoxRef.current?.scrollTo({
      top: index * BASE_TABLE_ROW_HEIGHT,
      behavior: 'smooth',
    });
  };

  const scrollToTop = () => {
    supportBaseTableBoxRef.current?.scrollTo({
      top: 0,
      behavior: 'smooth',
    });
  };

  const scrollToBottom = () => {
    supportBaseTableBoxRef.current?.scrollTo({
      top: supportBaseTableBoxRef.current.scrollHeight,
      behavior: 'smooth',
    });
  };

  const scrollToRight = () => {
    const tables = supportBaseTableBoxRef.current?.querySelectorAll('table');
    const tableBody = supportBaseTableBoxRef.current?.querySelector('.art-table-body');
    const secondTable = tables?.[1];
    if (secondTable && tableBody) {
      tableBody.scrollLeft = secondTable.scrollWidth;
    }
  };

  const scrollToLeft = () => {
    const tables = supportBaseTableBoxRef.current?.querySelectorAll('table');
    const secondTable = tables?.[1];
    if (secondTable) {
      secondTable.scrollLeft = 0;
    }
  };

  useImperativeHandle(ref, () => ({
    scrollTo,
    scrollToTop,
    scrollToBottom,
    scrollToRight,
    scrollToLeft,
  }));

  return (
    <div
      className={cx(tableStyles.supportBaseTableBox, className)}
      aria-label={treeMode || onActivateRow ? entheticColumns[0]?.title : undefined}
      data-base-table-root
      onFocus={treeMode || onActivateRow ? handleTableRootFocus : undefined}
      onKeyDownCapture={(event) => {
        if (event.key === 'Escape' && onEscapeKey) {
          const eventTarget = event.target as HTMLElement;
          const currentRowKey = eventTarget.closest?.<HTMLElement>(
            BASE_TABLE_KEYBOARD_ACTION_SELECTOR,
          )?.dataset.treeRowKey;
          event.preventDefault();
          onEscapeKey();
          if (currentRowKey) {
            requestTableKeyboardFocus(currentRowKey, true);
          }
          return;
        }
        moveTableKeyboardFocus(event);
      }}
      ref={supportBaseTableBoxRef}
      role={treeMode || onActivateRow ? 'group' : undefined}
      tabIndex={treeMode || onActivateRow ? 0 : undefined}
    >
      {loading ? (
        <div className={styles.spinBox}>
          <Spin />
        </div>
      ) : (
        <>
          <ALiBaseTable
            ref={aLiBaseTableRef}
            className={tableStyles.baseTable}
            components={{
              EmptyContent: () => (showEmptyState ? <Empty title={i18n('common.text.noData')} /> : null),
            }}
            isStickyHeader
            estimatedRowHeight={BASE_TABLE_ROW_HEIGHT}
            stickyTop={32}
            // useVirtual={false}
            hasHeader={hasHeader}
            {...pipeline.getProps()}
          />
        </>
      )}
    </div>
  );
});

export default memo(BaseTable);

export interface TableCellProps {
  columnConfig: any;
  rowData: any;
  value: any;
  index: number;
  selectable?: boolean;
  keyboardAction?: boolean;
  keyboardRowKey?: string;
  tooltip?: boolean;
  editable?: boolean;
  onChangeCell?: (index, columnName, value) => void;
  onActivateRow?: (index: number, rowData: any) => void;
  isSelected?: boolean;
  setSelectedRows?: (selectedRows: number[]) => void;
  styles: any;
  cx: any;
}

const TableCell = memo<TableCellProps>(
  ({
    columnConfig,
    value,
    rowData,
    index,
    selectable,
    keyboardAction,
    keyboardRowKey,
    tooltip,
    editable,
    onChangeCell,
    onActivateRow,
    isSelected,
    setSelectedRows,
    styles,
    cx,
  }) => {
    // Delay tooltip rendering to avoid virtual-scroll performance issues.
    const [goodTiming, setGoodTiming] = useState(false);

    useEffect(() => {
      if (!tooltip) {
        return;
      }
      const timer = setTimeout(() => {
        setGoodTiming(true);
      }, 500);

      return () => {
        clearTimeout(timer);
      };
    }, [tooltip]);

    // Determine whether the cell is controlled in edit/read mode or uncontrolled.
    const isEditable = () => {
      if (!selectable) {
        return false;
      }
      if (typeof columnConfig.editable === 'function') {
        return columnConfig.editable(rowData);
      }

      if (columnConfig.editable === true) {
        return undefined;
      }

      if (columnConfig.editable === false) {
        return false;
      }

      if (editable === true) {
        return undefined;
      }

      return false;
    };

    const handelClickCell = () => {
      if (selectable) {
        setSelectedRows?.([index]);
      }
    };

    const handleKeyDownCell = (event: React.KeyboardEvent<HTMLDivElement>) => {
      if (keyboardAction && shouldActivateTableAction(event.key)) {
        event.preventDefault();
        onActivateRow?.(index, rowData);
      }
    };

    const renderTooltipTitle = (_value) => {
      return (
        <div className={styles.tooltipTitle}>
          {_value}
          <Tooltip title={i18n('common.button.copy')} mouseEnterDelay={1}>
            <IconButton
              onClick={() => {
                copyToClipboard(_value);
              }}
              className={styles.copyButton}
              code="icon-copy"
              size="xs"
            />
          </Tooltip>
        </div>
      );
    };

    const editing = isEditable();
    const editText = (
      <EditText
        onlyNumber={columnConfig.onlyNumber}
        editing={editing}
        className={cx(styles.tableCell, { [styles.isSelectedTableCell]: isSelected })}
        textClassName={styles.editTextTextClass}
        inputClassName={styles.editTextInputClass}
        hoverShowBorder={editing !== false}
        placeholder={value === null ? '<null>' : undefined}
        data-base-table-keyboard-action={keyboardAction ? 'true' : undefined}
        data-tree-row-key={keyboardAction ? keyboardRowKey : undefined}
        onClick={handelClickCell}
        onKeyDown={keyboardAction ? handleKeyDownCell : undefined}
        onBlur={(_value) => {
          onChangeCell?.(index, columnConfig.name, _value);
        }}
        role={keyboardAction ? 'button' : undefined}
        tabIndex={keyboardAction ? -1 : undefined}
      >
        {value}
      </EditText>
    );

    if (tooltip && !goodTiming) {
      return <div className={styles.plainText}>{value}</div>;
    }

    return tooltip ? (
      <Tooltip placement="topLeft" title={renderTooltipTitle(value)} mouseEnterDelay={0.5}>
        {editText}
      </Tooltip>
    ) : (
      editText
    );
    // return editText;
  },
);
