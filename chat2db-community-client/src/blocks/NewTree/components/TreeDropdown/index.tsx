import React, { useState, memo, useImperativeHandle, forwardRef, useRef } from 'react';
import { Dropdown } from 'antd';
import { TreeNodeData } from '@/typings';
import { OperationColumn } from '@/constants';
import { ShortcutAction } from '@/constants/shortcut';
import { useCreateRightClickMenu, canBeDoubleClicked } from '../../hooks/useCreateRightClickMenu';
import { IconfontSvg } from '@chat2db/ui';
import { useTreeStore } from '@/store/tree';
import ShortcutMenuLabel from '@/components/ShortcutMenuLabel';
import { loadResourceOperationCapabilities } from '@/client-extension/resourceOperationCapabilities';
import type { ResourceOperationCapabilities } from '@/client-extension/types';

const INTERACTIVE_MENU_ITEM_CLASS_NAME = 'chat2db-interactive-menu-item';

interface IProps {
  className?: string;
  specialHandleLoadData?: any;
}

export interface TreeDropdownRef {
  setCurrentNode: (info: { event: React.MouseEvent; node: TreeNodeData } | null) => Promise<void>;
  openMenu: (info: { event: React.MouseEvent; node: TreeNodeData }) => void;
  closeMenu: () => void;
  // Returns true when this component handles the double-click.
  handleDoubleClick: (node: TreeNodeData) => Promise<boolean>;
  handleShortcut: (node: TreeNodeData, action: ShortcutAction) => Promise<boolean>;
}

const TreeDropdown = (props: IProps, ref) => {
  const { specialHandleLoadData } = props;

  const [currentNode, setCurrentNode] = useState<{
    clientX: number;
    clientY: number;
    menu: {
      items: any[];
      style: React.CSSProperties;
    };
  } | null>(null);
  const authorizationSequence = useRef(0);
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
    void setAuthorizedCurrentNode(info);
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
  const handleDoubleClick = async (node: TreeNodeData) => {
    if (canBeDoubleClicked.includes(node.treeNodeType)) {
      const capabilities = await loadResourceOperationCapabilities(node);
      const menu = createRightClickMenu(node, specialHandleLoadData || handleLoadData, capabilities);
      let handled = false;
      useTreeStore.getState().setCurrentTreeNode(node);
      menu.forEach((item) => {
        if (item.doubleClickTrigger) {
          item.onClick?.();
          handled = true;
        }
      });
      return handled;
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

  const handleShortcut = async (node: TreeNodeData, action: ShortcutAction) => {
    const capabilities = await loadResourceOperationCapabilities(node);
    const menu = createRightClickMenu(node, specialHandleLoadData || handleLoadData, capabilities);
    useTreeStore.getState().setCurrentTreeNode(node);
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

  const buildMenu = (node: TreeNodeData, capabilities?: ResourceOperationCapabilities) => {
    const dropdownsList = createRightClickMenu(
      node,
      specialHandleLoadData || handleLoadData,
      capabilities,
    );
    const dropdownsItems = renderChildren(dropdownsList);
    return {
      items: dropdownsItems,
      style: dropdownsItems?.length ? {} : { display: 'none' as const }, // is only displayed if there are menu items
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
  };

  const setAuthorizedCurrentNode = async (info: { event: React.MouseEvent; node: TreeNodeData } | null) => {
    const sequence = ++authorizationSequence.current;
    setCurrentNode(null);
    if (!info) {
      return;
    }
    const clientX = info.event.clientX;
    const clientY = info.event.clientY;
    const capabilities = await loadResourceOperationCapabilities(info.node);
    if (sequence !== authorizationSequence.current) {
      return;
    }
    const menu = buildMenu(info.node, capabilities);
    setCurrentNode({ clientX, clientY, menu });
  };

  useImperativeHandle(ref, () => ({
    setCurrentNode: setAuthorizedCurrentNode,
    openMenu: openMenuForNode,
    closeMenu,
    createRightClickMenu,
    handleDoubleClick,
    handleShortcut,
  }));

  return (
    <Dropdown
      menu={currentNode?.menu || { items: [], style: { display: 'none' } }}
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
          left: currentNode?.clientX,
          top: currentNode?.clientY,
          height: 1,
          pointerEvents: 'none',
        }}
      />
    </Dropdown>
  );
};

export default memo(forwardRef<TreeDropdownRef, IProps>(TreeDropdown));
