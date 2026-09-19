import React, { useMemo, useState } from 'react';
import { SuggestionItem, SuggestionSelectionIntent } from './interface';
import { useEvent, useMergedState } from 'rc-util';
import { Cascader, CascaderProps } from 'antd';
import useActive from './useActive';
import { useStyles } from './style';
import { IconfontSvg } from '@chat2db/ui';
import { BookOpen, Command } from 'lucide-react';

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
  onSelect?: (item: SuggestionItem, intent: SuggestionSelectionIntent) => void;
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
  const onInternalChange = (valuePath: string[], intent: SuggestionSelectionIntent = 'execute') => {
    const value = valuePath.at(-1);
    const item = itemList.find((candidate) => candidate.value === value);
    if (onSelect && item) {
      onSelect(item, intent);
    }
    triggerOpen(false);
  };

  // =========================== Accessibility ===========================
  const [activePath, onKeyDown] = useActive(itemList, mergedOpen, onInternalChange, onClose);

  const optionRender: CascaderProps<SuggestionItem>['optionRender'] = (node) => {
    const icon = node.kind === 'skill' ? <BookOpen size={16} aria-hidden="true" />
      : node.kind === 'command' ? <Command size={16} aria-hidden="true" />
        : (
          <IconfontSvg
            size="md"
            existDark={true}
            appearance={appearance}
            code={node.tableType === 'TABLE' ? 'icon-colourful-table' : 'icon-colourful-table-view'}
          />
        );
    return (
      <div className={styles.optionRow}>
        <div className={styles.optionTitle}>
          {icon}
          <span className={styles.optionLabel} title={node.label}>
            {node.label}
          </span>
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
      onChange={(valuePath) => onInternalChange(valuePath)}
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
