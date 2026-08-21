import React, { useState, memo, useImperativeHandle, forwardRef, useMemo, useRef } from 'react';
import { Dropdown } from 'antd';
import { TreeNodeData } from '@/typings';
import { OperationColumn } from '@/constants';
import { ShortcutAction } from '@/constants/shortcut';
import { useCreateRightClickMenu, canBeDoubleClicked } from '../../hooks/useCreateRightClickMenu';
import { IconfontSvg } from '@chat2db/ui';
import { useTreeStore } from '@/store/tree';
import ShortcutMenuLabel from '@/components/ShortcutMenuLabel';

const INTERACTIVE_MENU_ITEM_CLASS_NAME = 'chat2db-interactive-menu-item';

interface IProps {
  className?: string;
  specialHandleLoadData?: any;
}

export interface TreeDropdownRef {
  openMenu: (info: { event: React.MouseEvent; node: TreeNodeData }) => void;
  closeMenu: () => void;
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
  const keepMenuOpenRef = useRef(false);
  const interactiveItemFocusRef = useRef<(() => void) | null>(null);

  const closeMenu = () => {
    keepMenuOpenRef.current = false;
    interactiveItemFocusRef.current = null;
    setCurrentNode(null);
  };

  const setInteractionOpen = (open: boolean) => {
    keepMenuOpenRef.current = open;
  };

  const openMenuForNode = (info: { event: React.MouseEvent; node: TreeNodeData }) => {
    keepMenuOpenRef.current = false;
    interactiveItemFocusRef.current = null;
    useTreeStore.getState().setCurrentTreeNode(info.node);
    setCurrentNode(info);
  };

  const registerFocusTarget = (focus: () => void) => {
    interactiveItemFocusRef.current = focus;
    return () => {
      if (interactiveItemFocusRef.current === focus) {
        interactiveItemFocusRef.current = null;
      }
    };
  };

  const { createRightClickMenu } = useCreateRightClickMenu();

  const { handleLoadData } = useTreeStore((s) => ({
    handleLoadData: s.handleLoadData,
  }));

  // handles double-click events
  const handleDoubleClick = (node: TreeNodeData) => {
    if (canBeDoubleClicked.includes(node.treeNodeType)) {
      useTreeStore.getState().setCurrentTreeNode(node);
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
    useTreeStore.getState().setCurrentTreeNode(node);
    const menu = createRightClickMenu(node, specialHandleLoadData || handleLoadData);
    const executed = executeShortcut(menu, action);
    if (executed) {
      closeMenu();
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
        className: t.keepOpen ? INTERACTIVE_MENU_ITEM_CLASS_NAME : undefined,
        role: t.keepOpen ? 'presentation' : undefined,
        onClick: ({ domEvent }) => {
          if (t.keepOpen) {
            domEvent.preventDefault();
            domEvent.stopPropagation();
            keepMenuOpenRef.current = true;
            interactiveItemFocusRef.current?.();
            return;
          }
          t.onClick?.();
        },
        danger: t.danger || undefined,
        icon:
          typeof t.labelProps.icon === 'string' ? (
            <IconfontSvg code={t.labelProps.icon} size="lg" />
          ) : (
            t.labelProps.icon
          ),
        label: t.labelProps.renderLabel ? (
          t.labelProps.renderLabel({ closeMenu, setInteractionOpen, registerFocusTarget })
        ) : (
          <ShortcutMenuLabel label={t.labelProps.label} shortcutAction={t.shortcutAction} />
        ),
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
      onKeyDown: (event: React.KeyboardEvent<HTMLElement>) => {
        const target = event.target as HTMLElement;
        const interactiveMenuItem = target.closest(`.${INTERACTIVE_MENU_ITEM_CLASS_NAME}`);
        if (
          !interactiveMenuItem ||
          target.closest('[role="toolbar"]') ||
          !['Enter', ' ', 'Spacebar'].includes(event.key)
        ) {
          return;
        }
        event.preventDefault();
        event.stopPropagation();
        keepMenuOpenRef.current = true;
        interactiveItemFocusRef.current?.();
      },
    };
  }, [currentNode]);

  useImperativeHandle(ref, () => ({
    openMenu: openMenuForNode,
    closeMenu,
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
        if (!next && !keepMenuOpenRef.current) {
          closeMenu();
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
