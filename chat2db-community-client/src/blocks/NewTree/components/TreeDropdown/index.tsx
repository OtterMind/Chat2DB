import React, { useState, memo, useImperativeHandle, forwardRef, useMemo } from 'react';
import { Dropdown } from 'antd';
import { TreeNodeData } from '@/typings';
import { OperationColumn } from '@/constants';
import { ShortcutAction } from '@/constants/shortcut';
import { useCreateRightClickMenu, canBeDoubleClicked } from '../../hooks/useCreateRightClickMenu';
import { IconfontSvg } from '@chat2db/ui';
import { useTreeStore } from '@/store/tree';
import ShortcutMenuLabel from '@/components/ShortcutMenuLabel';

interface IProps {
  className?: string;
  specialHandleLoadData?: any;
}

export interface TreeDropdownRef {
  setCurrentNode: (info: any) => void;
  // Returns true when this component handles the double-click.
  handleDoubleClick: (node: TreeNodeData) => boolean;
  handleShortcut: (node: TreeNodeData, action: ShortcutAction) => boolean;
}

const TreeDropdown = (props: IProps, ref) => {
  const { specialHandleLoadData } = props;

  const [currentNode, setCurrentNode] = useState<{
    event: React.MouseEvent;
    node: TreeNodeData;
  } | null>(null);

  const { createRightClickMenu } = useCreateRightClickMenu();

  const { handleLoadData } = useTreeStore((s) => ({
    handleLoadData: s.handleLoadData,
  }));

  // handles double-click events
  const handleDoubleClick = (node: TreeNodeData) => {
    if (canBeDoubleClicked.includes(node.treeNodeType)) {
      const menu = createRightClickMenu(node, specialHandleLoadData || handleLoadData);
      menu.forEach((item) => {
        if (item.doubleClickTrigger) {
          item.onClick?.();
        }
      });
      return true;
    }
    return false;
  };

  const executeShortcut = (items: ReturnType<typeof createRightClickMenu>, action: ShortcutAction): boolean => {
    for (const item of items) {
      if (item.shortcutAction === action && item.onClick) {
        item.onClick();
        return true;
      }
      if (item.children?.length && executeShortcut(item.children, action)) {
        return true;
      }
    }
    return false;
  };

  const handleShortcut = (node: TreeNodeData, action: ShortcutAction) => {
    const menu = createRightClickMenu(node, specialHandleLoadData || handleLoadData);
    const executed = executeShortcut(menu, action);
    if (executed) {
      setCurrentNode(null);
    }
    return executed;
  };

  const renderChildren = (children: any) => {
    return children?.map((t) => {
      // dividing line
      if (t.type === OperationColumn.Divider) {
        return { key: t.key, type: 'divider' as const };
      }
      return {
        key: t.key,
        onClick: () => {
          t.onClick?.();
        },
        icon: t.labelProps.icon && <IconfontSvg code={t.labelProps.icon} size="lg" />,
        label: <ShortcutMenuLabel label={t.labelProps.label} shortcutAction={t.shortcutAction} />,
        children: renderChildren(t.children),
      };
    });
  };

  const menu = useMemo(() => {
    if (!currentNode) {
      return {
        items: [],
        style: { display: 'none' },
      };
    }

    const dropdownsList = createRightClickMenu(currentNode!.node, specialHandleLoadData || handleLoadData);

    const dropdownsItems = renderChildren(dropdownsList);

    return {
      items: dropdownsItems,
      style: dropdownsItems?.length ? {} : { display: 'none' }, // is only displayed if there are menu items
    };
  }, [currentNode]);

  useImperativeHandle(ref, () => ({
    setCurrentNode,
    createRightClickMenu,
    handleDoubleClick,
    handleShortcut,
  }));

  return (
    <Dropdown
      menu={menu}
      trigger={['click']}
      open={!!currentNode}
      destroyPopupOnHide={true}
      onOpenChange={(next) => {
        if (!next) {
          setCurrentNode(null);
        }
      }}
    >
      <div
        style={{
          position: 'fixed',
          left: currentNode?.event.clientX,
          top: currentNode?.event.clientY,
          height: 1,
          pointerEvents: 'none',
        }}
      />
    </Dropdown>
  );
};

export default memo(forwardRef<TreeDropdownRef, IProps>(TreeDropdown));
