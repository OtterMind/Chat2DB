import { memo, useMemo } from 'react';
import { Menu, type MenuProps } from 'antd';

import ShortcutMenuLabel from '@/components/ShortcutMenuLabel';
import MainContextMenu from '@/pages/main/components/MainContextMenu';
import {
  ContextMenuAction,
  ContextMenuEntry,
  ContextMenuIntent,
  executeContextMenuAction,
  isContextMenuAction,
} from './core';
import { useStyles } from './style';

interface PortalContextMenuProps<TIntent extends ContextMenuIntent> {
  intent: TIntent | null;
  actions: ContextMenuEntry<TIntent>[];
  className?: string;
  onClose: () => void;
  onStaleAction?: (action: ContextMenuAction<TIntent>, intent: TIntent) => void;
}

const PortalContextMenu = <TIntent extends ContextMenuIntent>(props: PortalContextMenuProps<TIntent>) => {
  const { intent, actions, className, onClose, onStaleAction } = props;
  const { styles, cx } = useStyles();

  const items = useMemo<MenuProps['items']>(() => {
    const renderActions = (entries: ContextMenuEntry<TIntent>[] | undefined): MenuProps['items'] => {
      const reserveIconSpace = entries?.some(
        (entry) => isContextMenuAction(entry) && entry.icon !== undefined,
      );
      return entries?.map((entry, index) => {
        if (!isContextMenuAction(entry)) {
          return {
            type: 'divider',
            key: entry.id || `divider-${index}`,
          };
        }

        return {
          key: entry.id,
          label: <ShortcutMenuLabel label={entry.label} shortcutAction={entry.shortcutAction} />,
          icon:
            entry.icon ||
            (reserveIconSpace ? <span aria-hidden className={styles.menuIconPlaceholder} /> : undefined),
          danger: entry.danger,
          disabled: entry.disabled,
          children: renderActions(entry.children),
        };
      });
    };

    return renderActions(actions);
  }, [actions, styles.menuIconPlaceholder]);

  if (!intent || !actions.some(isContextMenuAction)) {
    return null;
  }

  const handleClick: MenuProps['onClick'] = ({ key }) => {
    const findAction = (entries: ContextMenuEntry<TIntent>[]): ContextMenuAction<TIntent> | null => {
      for (const entry of entries) {
        if (!isContextMenuAction(entry)) {
          continue;
        }
        if (entry.id === key) {
          return entry;
        }
        const childAction = findAction(entry.children || []);
        if (childAction) {
          return childAction;
        }
      }
      return null;
    };

    const action = findAction(actions);
    if (!action) {
      return;
    }

    const executed = executeContextMenuAction(action, intent);
    if (!executed) {
      onStaleAction?.(action, intent);
    }
    onClose();
  };

  return (
    <MainContextMenu x={intent.pointer.x} y={intent.pointer.y} onClose={onClose}>
      <Menu
        className={cx(styles.menu, !className && styles.defaultMenuWidth, className)}
        items={items}
        onClick={handleClick}
      />
    </MainContextMenu>
  );
};

export default memo(PortalContextMenu) as typeof PortalContextMenu;
