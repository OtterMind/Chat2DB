import React, { useMemo, useState } from 'react';
import { SuggestionItem } from './interface';
import { useEvent, useMergedState } from 'rc-util';
import { Cascader, CascaderProps, Flex } from 'antd';
import useActive from './useActive';
import { useStyles } from './style';
import { IconfontSvg } from '@chat2db/ui';
import { Bot } from 'lucide-react';

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
  onSelect?: (value: string) => void;
  children?: (props: RenderChildrenProps<T>) => React.ReactElement;
  /**
   * list of suggestions
   * @param items can be a static array or a function that returns an array based on trigger information
   * @param info Contextual information when triggering suggestions
   */
  items: SuggestionItem[] | ((info?: T) => SuggestionItem[]);
}

function AIAtMetion<T>(props: AIAtMetionProps<T>) {
  const { className, rootClassName, open, onOpenChange, onSelect, items, children } = props;

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

  const optionRender: CascaderProps<SuggestionItem>['optionRender'] = (node) => {
    return (
      <Flex align="center" gap={4} justify="space-between">
        <Flex align="center" gap={4} className={styles.optionTitle}>
          {node.kind === 'AGENT' ? (
            node.icon || <Bot size={16} />
          ) : (
            <IconfontSvg
              size="md"
              existDark={true}
              appearance={appearance}
              code={node.tableType === 'TABLE' ? 'icon-colourful-table' : 'icon-colourful-table-view'}
            />
          )}
          {node.label}
        </Flex>
        <div className={styles.optionExtra}>{node.extra}</div>
      </Flex>
    );
  };

  // =========================== Cascader ===========================
  const onInternalChange = (valuePath: string[]) => {
    if (onSelect) {
      onSelect(valuePath.at(-1) ?? '');
    }
    triggerOpen(false);
  };

  // =========================== Accessibility ===========================
  const [activePath, onKeyDown] = useActive(itemList, mergedOpen, onInternalChange, onClose);

  // =========================== Children ===========================
  const childNode = children?.({
    onTrigger,
    onKeyDown,
    isOpen: mergedOpen,
  });

  return (
    <Cascader
      size="small"
      rootClassName={cx(styles.container, rootClassName)}
      options={itemList}
      open={mergedOpen}
      value={activePath}
      optionRender={optionRender}
      onChange={onInternalChange}
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
