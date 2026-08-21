import React, { useMemo, useState } from 'react';
import { SuggestionItem } from './interface';
import { useEvent, useMergedState } from 'rc-util';
import { Cascader, CascaderProps, Spin } from 'antd';
import useActive from './useActive';
import { useStyles } from './style';
import { IconfontSvg } from '@chat2db/ui';
import { BookOpenText } from 'lucide-react';

export interface RenderChildrenProps<T> {
  /**
   * trigger suggestion window
   * @param info trigger information
   */
  onTrigger: (info?: T | false) => void;
  /** keyboard event */
  onKeyDown: (e: React.KeyboardEvent) => void;
  isOpen: boolean;
}

export interface AIAtMetionProps<T> {
  className?: string;
  rootClassName?: string;
  style?: React.CSSProperties;

  open?: boolean;
  onOpenChange?: (open: boolean) => void;
  onSelect?: (item: SuggestionItem) => void;
  hasMore?: boolean;
  loadingMore?: boolean;
  onLoadMore?: () => void;
  children?: (props: RenderChildrenProps<T>) => React.ReactElement;
  /**
   * list of suggestions
   * @param items can be a static array or a function that returns an array based on trigger information
   * @param info Contextual information when triggering suggestions
   */
  items: SuggestionItem[] | ((info?: T) => SuggestionItem[]);
}

function AIAtMetion<T>(props: AIAtMetionProps<T>) {
  const {
    className,
    rootClassName,
    open,
    onOpenChange,
    onSelect,
    hasMore,
    loadingMore,
    onLoadMore,
    items,
    children,
  } = props;

  const {
    styles,
    cx,
    theme: { appearance },
  } = useStyles();

  const [mergedOpen, setOpen] = useMergedState(false, {
    value: open,
  });
  const [info, setInfo] = useState<T | undefined>();

  const triggerOpen = (nextOpen: boolean) => {
    setOpen(nextOpen);
    onOpenChange?.(nextOpen);
  };

  const onTrigger: RenderChildrenProps<T>['onTrigger'] = useEvent((nextInfo) => {
    if (nextInfo === false) {
      triggerOpen(false);
    } else {
      setInfo(nextInfo);
      triggerOpen(true);
    }
  });

  const onClose = () => {
    triggerOpen(false);
  };

  // ============================ Suggestion Items =============================
  const itemList = useMemo(() => (typeof items === 'function' ? items(info) : items), [items, info]);

  // =========================== Cascader ===========================
  const onInternalChange = (valuePath: string[]) => {
    const value = valuePath.at(-1);
    const item = itemList.find((candidate) => candidate.value === value);
    if (onSelect && item) {
      onSelect(item);
    }
    triggerOpen(false);
  };

  // =========================== Accessibility ===========================
  const [activePath, onKeyDown, previewItem, setPreviewValue] = useActive(
    itemList,
    mergedOpen,
    onInternalChange,
    onClose,
  );

  const knowledgeTypeClassName = (item: SuggestionItem) => {
    switch (item.knowledge?.type) {
      case 'BUSINESS_LOGIC':
        return styles.businessLogic;
      case 'SQL_TEMPLATE':
        return styles.sqlTemplate;
      default:
        return styles.knowledgeTerm;
    }
  };

  const optionRender: CascaderProps<SuggestionItem>['optionRender'] = (node) => {
    return (
      <div
        className={styles.optionRow}
        onMouseEnter={() => setPreviewValue(node.kind === 'knowledge' ? node.value : undefined)}
      >
        <div className={styles.optionTitle}>
          {node.kind === 'knowledge' ? (
            <BookOpenText size={15} className={knowledgeTypeClassName(node)} />
          ) : (
            <IconfontSvg
              size="md"
              existDark={true}
              appearance={appearance}
              code={node.tableType === 'TABLE' ? 'icon-colourful-table' : 'icon-colourful-table-view'}
            />
          )}
          <span className={styles.optionLabel}>{node.label}</span>
        </div>
        <div className={styles.optionExtra}>{node.extra}</div>
      </div>
    );
  };

  // =========================== Children ===========================
  const childNode = children?.({
    onTrigger,
    onKeyDown,
    isOpen: mergedOpen,
  });

  return (
    <Cascader
      size="small"
      placement="topLeft"
      rootClassName={cx(styles.container, rootClassName)}
      options={itemList}
      open={mergedOpen}
      value={activePath}
      optionRender={optionRender}
      onChange={onInternalChange}
      dropdownRender={(menus) =>
        mergedOpen ? (
          <div className={styles.dropdownLayout}>
            <div
              className={styles.menuPane}
              onScrollCapture={(event) => {
                const target = event.target as HTMLElement;
                if (!target.classList.contains('ant-cascader-menu')) return;
                if (
                  hasMore &&
                  !loadingMore &&
                  target.scrollHeight - target.scrollTop - target.clientHeight <= 24
                ) {
                  onLoadMore?.();
                }
              }}
            >
              {menus}
              {loadingMore ? (
                <div className={styles.loadingMore} aria-live="polite">
                  <Spin size="small" />
                </div>
              ) : null}
            </div>
            {previewItem?.knowledge && (
              <div className={styles.previewPane}>
                <div className={styles.previewHeader}>
                  <span className={`${styles.previewType} ${knowledgeTypeClassName(previewItem)}`}>
                    {previewItem.extra}
                  </span>
                  <strong className={styles.previewTitle}>{previewItem.knowledge.key}</strong>
                </div>
                <div
                  className={`${styles.previewContent} ${
                    previewItem.knowledge.type === 'SQL_TEMPLATE' ? styles.previewSql : ''
                  }`}
                >
                  {previewItem.knowledge.value || '暂无说明'}
                </div>
              </div>
            )}
          </div>
        ) : null
      }
      onDropdownVisibleChange={(nextOpen) => {
        if (!nextOpen) {
          onClose();
        }
      }}
    >
      <div className={cx(styles.content, className)}>{childNode}</div>
    </Cascader>
  );
}

export default AIAtMetion;
