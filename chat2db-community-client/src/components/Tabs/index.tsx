import React, { memo, useEffect, useState, useRef } from 'react';
import Iconfont from '@/components/Iconfont';
import DropdownChevronTrigger from '@/components/DropdownChevronTrigger';
import { IconButton, IconfontSvg } from '@chat2db/ui';
import { Popover, Dropdown } from 'antd';
import PortalContextMenu from '@/components/ContextMenu/PortalContextMenu';
import {
  isContextMenuAction,
  type ContextMenuAction,
  type ContextMenuEntry,
  type ContextMenuIntent,
} from '@/components/ContextMenu/core';
import i18n from '@/i18n';
import { isValid } from '@/utils/check';
import { useStyles } from './style';
import { isMatched } from '@/utils';
import { useUpdateEffect } from 'ahooks';
import { ShortcutAction } from '@/constants/shortcut';
import {
  closestCenter,
  DndContext,
  PointerSensor,
  type DragEndEvent,
  type DragStartEvent,
  useDroppable,
  useSensor,
  useSensors,
} from '@dnd-kit/core';
import { horizontalListSortingStrategy, SortableContext, useSortable } from '@dnd-kit/sortable';
import {
  CloseActiveResultTabHandlerResult,
  registerCloseActiveResultTabHandler,
} from '@/service/resultTabShortcut';
import { shouldProcessTabScrollRequest, type ProcessedTabScrollRequest } from './activeTabScroll';
import { getTabAccentStyle } from './tabAccentColor';
import { getTabWheelScrollAmount } from './wheelScroll';

export interface ITabItem {
  prefixIcon?: string | React.ReactNode;
  label: React.ReactNode;
  key: number | string;
  popover?: string | React.ReactNode;
  children?: React.ReactNode;
  editableName?: boolean;
  canClosed?: boolean;
  styles?: React.CSSProperties;
  accentColor?: string | null;
  pinned?: boolean;
  destroyOnHide?: boolean;
}

export interface ITabContextActions {
  closeLeft?: boolean;
  closeRight?: boolean;
  pin?: boolean;
  duplicate?: boolean;
  copyReference?: boolean;
  reopenClosed?: boolean;
  splitRight?: boolean;
  splitDown?: boolean;
  splitAndMoveRight?: boolean;
  splitAndMoveDown?: boolean;
}

export interface ITabContextActionHandlers {
  closeLeft?: (tab: ITabItem, tabs: ITabItem[]) => void;
  closeRight?: (tab: ITabItem, tabs: ITabItem[]) => void;
  pin?: (tab: ITabItem, tabs: ITabItem[]) => void;
  duplicate?: (tab: ITabItem, tabs: ITabItem[]) => void;
  copyReference?: (tab: ITabItem, tabs: ITabItem[]) => void;
  reopenClosed?: (tab: ITabItem, tabs: ITabItem[]) => void;
  splitRight?: (tab: ITabItem, tabs: ITabItem[]) => void;
  splitDown?: (tab: ITabItem, tabs: ITabItem[]) => void;
  splitAndMoveRight?: (tab: ITabItem, tabs: ITabItem[]) => void;
  splitAndMoveDown?: (tab: ITabItem, tabs: ITabItem[]) => void;
  reorder?: (tabs: ITabItem[], sourceTab: ITabItem, targetTab: ITabItem) => void;
}

export interface IOnchangeProps {
  type: 'add' | 'delete' | 'switch';
  data?: ITabItem;
}

const MAX_TABS = 100;

interface IProps {
  className?: string;
  items?: ITabItem[];
  activeKey?: number | string | null;
  height?: number;
  onChange: (key: string | number | null) => void;
  onEdit?: (action: 'add' | 'remove', data?: ITabItem[], list?: ITabItem[]) => void;
  beforeRemove?: (tabs: ITabItem[]) => boolean | Promise<boolean>;
  hideAdd?: boolean;
  editableNameOnBlur?: (option: ITabItem) => void;
  concealTabHeader?: boolean;
  concealTabContent?: boolean;
  // Keep the final tab open.
  lastTabCannotClosed?: boolean;
  destroyInactiveTabPane?: boolean;
  tabMaxWidth?: string;
  uniformTabWidth?: boolean;
  contextActions?: ITabContextActions;
  contextActionAvailability?: (tab: ITabItem) => ITabContextActions;
  contextActionHandlers?: ITabContextActionHandlers;
  tabBarExtraContent?: React.ReactNode;
  activateOnContextMenu?: boolean;
  activeTabScrollKey?: string | number;
  closeActiveTabOnCloseShortcut?: boolean;
  closeShortcutAction?: ShortcutAction;
  useExternalSortableContext?: boolean;
  draggingTabKey?: string;
  onDraggingTabKeyChange?: (key?: string) => void;
  tabPaneDroppableId?: string;
}

interface TabContextSnapshot {
  key: ITabItem['key'];
  editableName?: boolean;
  canClosed?: boolean;
  pinned?: boolean;
}

type TabContextIntent = ContextMenuIntent<TabContextSnapshot>;

type TabKey = ITabItem['key'];

interface SortableTabItemProps extends React.HTMLAttributes<HTMLDivElement> {
  children: React.ReactNode;
  className: string;
  disabled: boolean;
  itemKey: TabKey;
}

interface TabPaneDroppableNavProps {
  children: React.ReactNode;
  className: string;
  droppableId: string;
  dropOverClassName: string;
}

const SortableTabItem = React.forwardRef<HTMLDivElement, SortableTabItemProps>(
  ({ children, className, disabled, itemKey, style, ...restProps }, forwardedRef) => {
  const { attributes, listeners, setNodeRef, isDragging } = useSortable({
    id: String(itemKey),
    disabled,
  });
  const mergedRef = React.useCallback(
    (node: HTMLDivElement | null) => {
      setNodeRef(node);
      if (typeof forwardedRef === 'function') {
        forwardedRef(node);
      } else if (forwardedRef) {
        forwardedRef.current = node;
      }
    },
    [forwardedRef, setNodeRef],
  );

  const sortableStyle: React.CSSProperties = {
    ...style,
    transform: undefined,
    transition: undefined,
    touchAction: 'none',
    ...(isDragging ? { opacity: 0.36, zIndex: 1 } : {}),
  };

  return (
    <div
      ref={mergedRef}
      style={sortableStyle}
      className={className}
      {...attributes}
      {...(!disabled ? listeners : {})}
      {...restProps}
    >
      {children}
    </div>
  );
  },
);
SortableTabItem.displayName = 'SortableTabItem';

function TabPaneDroppableNav({ children, className, droppableId, dropOverClassName }: TabPaneDroppableNavProps) {
  const { setNodeRef, isOver } = useDroppable({
    id: droppableId,
  });

  return (
    <div
      ref={setNodeRef}
      className={[className, isOver ? dropOverClassName : ''].filter(Boolean).join(' ')}
    >
      {children}
    </div>
  );
}

function getTabContextActions(
  contextActions?: ITabContextActions,
  availability?: ITabContextActions,
): ITabContextActions {
  return {
    closeLeft: !!contextActions?.closeLeft && availability?.closeLeft !== false,
    closeRight: !!contextActions?.closeRight && availability?.closeRight !== false,
    pin: !!contextActions?.pin && availability?.pin !== false,
    duplicate: !!contextActions?.duplicate && availability?.duplicate !== false,
    copyReference: !!contextActions?.copyReference && availability?.copyReference !== false,
    reopenClosed: !!contextActions?.reopenClosed && availability?.reopenClosed !== false,
    splitRight: !!contextActions?.splitRight && availability?.splitRight !== false,
    splitDown: !!contextActions?.splitDown && availability?.splitDown !== false,
    splitAndMoveRight: !!contextActions?.splitAndMoveRight && availability?.splitAndMoveRight !== false,
    splitAndMoveDown: !!contextActions?.splitAndMoveDown && availability?.splitAndMoveDown !== false,
  };
}

export default memo<IProps>((props) => {
  const {
    className,
    items,
    onChange,
    onEdit,
    beforeRemove,
    activeKey,
    hideAdd,
    lastTabCannotClosed,
    editableNameOnBlur,
    concealTabHeader,
    concealTabContent = false,
    destroyInactiveTabPane = false,
    height = 40,
    tabMaxWidth = 'none',
    uniformTabWidth = false,
    contextActions,
    contextActionAvailability,
    contextActionHandlers,
    tabBarExtraContent,
    activateOnContextMenu = false,
    activeTabScrollKey,
    closeActiveTabOnCloseShortcut = false,
    closeShortcutAction,
    useExternalSortableContext = false,
    draggingTabKey: controlledDraggingTabKey,
    onDraggingTabKeyChange,
    tabPaneDroppableId,
  } = props;
  const [internalTabs, setInternalTabs] = useState<ITabItem[]>([]);
  const [editingTab, setEditingTab] = useState<ITabItem['key'] | undefined>();
  const tabBoxRef = useRef<HTMLDivElement>(null);
  const tabListBoxRef = useRef<HTMLDivElement>(null);
  const tabItemRefs = useRef(new Map<TabKey, HTMLDivElement>());
  const closeActiveTabOnShortcutRef = useRef<() => CloseActiveResultTabHandlerResult>(
    () => 'inactive',
  );
  const lastTabScrollRequestRef = useRef<ProcessedTabScrollRequest>();
  const [showAddButton, setShowAddButton] = useState<boolean>(!hideAdd);
  const { styles, cx } = useStyles({
    height,
    showAddButton,
    showTabBarExtraContent: !!tabBarExtraContent,
    tabMaxWidth,
    uniformTabWidth,
  });
  const [moreTabsDropdownOpen, setMoreTabsDropdownOpen] = useState<false | undefined>(undefined);
  const [searchValue, setSearchValue] = useState<string>('');
  const [searchInternalTabs, setSearchInternalTabs] = useState<ITabItem[] | undefined>(undefined);
  const [contextMenu, setContextMenu] = useState<TabContextIntent | null>(null);
  const [uncontrolledDraggingTabKey, setUncontrolledDraggingTabKey] = useState<string | undefined>();
  const draggingTabKey = controlledDraggingTabKey ?? uncontrolledDraggingTabKey;
  const setDraggingTabKey = onDraggingTabKeyChange ?? setUncontrolledDraggingTabKey;
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }));

  useUpdateEffect(() => {
    setShowAddButton(!hideAdd);
  }, [hideAdd]);

  useEffect(() => {
    setInternalTabs(items || []);
    if (items?.length && !isValid(activeKey) && !activeKey) {
      onChange(items[0]?.key);
    }
  }, [items]);

  useUpdateEffect(() => {
    const handleWheel = (event: WheelEvent) => {
      const scrollAmount = getTabWheelScrollAmount(event.deltaX, event.deltaY);
      if (scrollAmount === null || !tabListBoxRef.current) {
        return;
      }

      event.preventDefault();
      tabListBoxRef.current.scrollLeft += scrollAmount;
    };
    const tabListBoxContent = tabListBoxRef.current;
    tabListBoxContent?.addEventListener('wheel', handleWheel, { passive: false });
    return () => {
      tabListBoxContent?.removeEventListener('wheel', handleWheel);
    };
  }, [internalTabs]);

  useEffect(() => {
    const nextTabs = items || [];
    const renderedOrderIsCurrent =
      internalTabs.length === nextTabs.length &&
      internalTabs.every((tab, index) => tab.key === nextTabs[index]?.key);
    if (!renderedOrderIsCurrent) {
      return;
    }

    if (!shouldProcessTabScrollRequest(lastTabScrollRequestRef.current, activeKey, activeTabScrollKey)) {
      return;
    }

    const animationFrame = window.requestAnimationFrame(() => {
      const activeTab = activeKey === null || activeKey === undefined ? undefined : tabItemRefs.current.get(activeKey);
      if (!activeTab) {
        return;
      }
      activeTab.scrollIntoView({
        block: 'nearest',
        inline: 'nearest',
      });
      lastTabScrollRequestRef.current = {
        activeKey,
        scrollKey: activeTabScrollKey,
      };
    });

    if (internalTabs.length >= MAX_TABS) {
      setShowAddButton(false);
    }

    return () => window.cancelAnimationFrame(animationFrame);
  }, [activeKey, activeTabScrollKey, internalTabs, items]);

  // useEffect(() => {
  //   // from copilot
  //   if (tabListBoxRef.current) {
  //     const tabsNavWidth = tabsNavRef.current?.getBoundingClientRect().width || 0;
  //     const tabListBoxWidth = tabListBoxRef.current?.getBoundingClientRect().width || 0;
  //     setShowMoreTabs(tabsNavWidth < tabListBoxWidth);
  //   }
  // }, [internalTabs]);

  const deleteTab = async (data: ITabItem) => {
    if (!showClosed(data)) {
      return;
    }
    if (beforeRemove && !(await beforeRemove([data]))) {
      return;
    }
    const newInternalTabs = internalTabs?.filter((t) => t.key !== data.key);
    let activeKeyTemp = activeKey;
      // When closing the active tab, select the previous tab or the next one if no previous tab exists.
    if (data.key === activeKey) {
      const index = internalTabs.findIndex((t) => t.key === data.key);
      if (index === 0) {
        activeKeyTemp = internalTabs[1]?.key;
      } else if (internalTabs.length > index + 1) {
        activeKeyTemp = internalTabs[index + 1]?.key;
      } else {
        activeKeyTemp = internalTabs[index - 1]?.key;
      }
    }
    changeTab(activeKeyTemp ?? null);
    setInternalTabs(newInternalTabs);
    onEdit?.('remove', [data], newInternalTabs);
  };

  const deleteOtherTab = async (data: ITabItem) => {
    const newInternalTabs = internalTabs?.filter((t) => t.key === data.key || t.pinned || t.canClosed === false);
    const deleteTabs = internalTabs?.filter((t) => t.key !== data.key && !t.pinned && t.canClosed !== false);
    if (beforeRemove && !(await beforeRemove(deleteTabs))) {
      return;
    }
    changeTab(data.key);
    setInternalTabs(newInternalTabs);
    onEdit?.('remove', deleteTabs, newInternalTabs);
  };

  // Close all tabs.
  const deleteAllTab = async () => {
    const deleteTabs = internalTabs.filter((tab) => !tab.pinned && tab.canClosed !== false);
    const newInternalTabs = internalTabs.filter((tab) => tab.pinned || tab.canClosed === false);
    if (beforeRemove && !(await beforeRemove(deleteTabs))) {
      return;
    }
    changeTab(newInternalTabs[0]?.key ?? null);
    setInternalTabs(newInternalTabs);
    onEdit?.('remove', deleteTabs, newInternalTabs);
  };

  closeActiveTabOnShortcutRef.current = () => {
    const tabBox = tabBoxRef.current;
    const tabBoxRect = tabBox?.getBoundingClientRect();
    if (!tabBox || !tabBoxRect?.width || !tabBoxRect.height) {
      return 'inactive';
    }
    const activeTab = internalTabs.find((tab) => tab.key === activeKey);
    if (!activeTab || !showClosed(activeTab)) {
      return 'pass-through';
    }
    deleteTab(activeTab);
    return 'closed';
  };

  useEffect(() => {
    if (!closeActiveTabOnCloseShortcut) {
      return undefined;
    }
    return registerCloseActiveResultTabHandler(() => closeActiveTabOnShortcutRef.current());
  }, [closeActiveTabOnCloseShortcut]);

  const changeTab = (key: string | number | null) => {
    onChange(key);
  };

  const handleAdd = () => {
    if (internalTabs.length >= MAX_TABS) {
      return;
    }
    onEdit?.('add');
  };

  const onDoubleClick = (t: ITabItem) => {
    if (t.editableName) {
      setEditingTab(t.key);
    }
  };

  function showClosed(t) {
    if (lastTabCannotClosed && internalTabs.length === 1) {
      return false;
    }
    if (t.pinned) {
      return false;
    }
    if (t.canClosed === false) {
      return false;
    }
    return true;
  }

  function closeContextMenu() {
    setContextMenu(null);
  }

  function isContextMenuTargetCurrent(intent: TabContextIntent) {
    const tab = internalTabs.find((item) => item.key === intent.targetSnapshot.key);
    return (
      !!tab &&
      tab.editableName === intent.targetSnapshot.editableName &&
      tab.canClosed === intent.targetSnapshot.canClosed &&
      tab.pinned === intent.targetSnapshot.pinned
    );
  }

  function createContextMenuAction(
    action: Omit<ContextMenuAction<TabContextIntent>, 'validateBeforeExecute'>,
  ): ContextMenuAction<TabContextIntent> {
    return {
      ...action,
      validateBeforeExecute: isContextMenuTargetCurrent,
    };
  }

  function createTabContextMenuActions(tab: ITabItem): ContextMenuEntry<TabContextIntent>[] {
    const actions: ContextMenuEntry<TabContextIntent>[] = [];
    const tabContextActions = getTabContextActions(contextActions, contextActionAvailability?.(tab));

    if (tab.editableName) {
      actions.push(
        createContextMenuAction({
          id: 'rename',
          label: i18n('common.text.rename'),
          execute: () => setEditingTab(tab.key),
        }),
      );
    }

    if (tabContextActions?.pin && contextActionHandlers?.pin) {
      actions.push(
        createContextMenuAction({
          id: 'pin',
          label: tab.pinned ? i18n('workspace.menu.unPin') : i18n('workspace.menu.pin'),
          execute: () => contextActionHandlers.pin?.(tab, internalTabs),
        }),
      );
    }

    if (showClosed(tab)) {
      if (actions.length) {
        actions.push({ type: 'divider', id: 'divider-before-close' });
      }
      actions.push(
        createContextMenuAction({
          id: 'close',
          label: i18n('common.button.close'),
          shortcutAction:
            closeShortcutAction &&
            (tab.key === activeKey ||
              (activateOnContextMenu && tab.key === contextMenu?.targetSnapshot.key))
              ? closeShortcutAction
              : undefined,
          execute: () => deleteTab(tab),
        }),
        createContextMenuAction({
          id: 'closeOther',
          label: i18n('common.button.closeOthers'),
          execute: () => deleteOtherTab(tab),
        }),
        ...(tabContextActions?.closeLeft && contextActionHandlers?.closeLeft
          ? [
              createContextMenuAction({
                id: 'closeLeft',
                label: i18n('common.button.closeLeft'),
                execute: () => contextActionHandlers.closeLeft?.(tab, internalTabs),
              }),
            ]
          : []),
        ...(tabContextActions?.closeRight && contextActionHandlers?.closeRight
          ? [
              createContextMenuAction({
                id: 'closeRight',
                label: i18n('common.button.closeRight'),
                execute: () => contextActionHandlers.closeRight?.(tab, internalTabs),
              }),
            ]
          : []),
        createContextMenuAction({
          id: 'closeAll',
          label: i18n('common.button.closeAll'),
          execute: deleteAllTab,
        }),
      );
    }

    if (
      (tabContextActions?.duplicate && contextActionHandlers?.duplicate) ||
      (tabContextActions?.copyReference && contextActionHandlers?.copyReference) ||
      (tabContextActions?.reopenClosed && contextActionHandlers?.reopenClosed)
    ) {
      actions.push({ type: 'divider', id: 'divider-workspace-actions' });
    }

    if (tabContextActions?.duplicate && contextActionHandlers?.duplicate) {
      actions.push(
        createContextMenuAction({
          id: 'duplicate',
          label: i18n('common.button.duplicateTab'),
          execute: () => contextActionHandlers.duplicate?.(tab, internalTabs),
        }),
      );
    }

    if (tabContextActions?.copyReference && contextActionHandlers?.copyReference) {
      actions.push(
        createContextMenuAction({
          id: 'copyReference',
          label: i18n('common.button.copyReference'),
          execute: () => contextActionHandlers.copyReference?.(tab, internalTabs),
        }),
      );
    }

    if (
      (tabContextActions?.splitRight && contextActionHandlers?.splitRight) ||
      (tabContextActions?.splitAndMoveRight && contextActionHandlers?.splitAndMoveRight) ||
      (tabContextActions?.splitDown && contextActionHandlers?.splitDown) ||
      (tabContextActions?.splitAndMoveDown && contextActionHandlers?.splitAndMoveDown)
    ) {
      actions.push({ type: 'divider', id: 'divider-split-actions' });
    }

    if (tabContextActions?.splitRight && contextActionHandlers?.splitRight) {
      actions.push(
        createContextMenuAction({
          id: 'splitRight',
          label: i18n('common.button.splitRight'),
          execute: () => contextActionHandlers.splitRight?.(tab, internalTabs),
        }),
      );
    }

    if (tabContextActions?.splitAndMoveRight && contextActionHandlers?.splitAndMoveRight) {
      actions.push(
        createContextMenuAction({
          id: 'splitAndMoveRight',
          label: i18n('common.button.splitAndMoveRight'),
          execute: () => contextActionHandlers.splitAndMoveRight?.(tab, internalTabs),
        }),
      );
    }

    if (tabContextActions?.splitDown && contextActionHandlers?.splitDown) {
      actions.push(
        createContextMenuAction({
          id: 'splitDown',
          label: i18n('common.button.splitDown'),
          execute: () => contextActionHandlers.splitDown?.(tab, internalTabs),
        }),
      );
    }

    if (tabContextActions?.splitAndMoveDown && contextActionHandlers?.splitAndMoveDown) {
      actions.push(
        createContextMenuAction({
          id: 'splitAndMoveDown',
          label: i18n('common.button.splitAndMoveDown'),
          execute: () => contextActionHandlers.splitAndMoveDown?.(tab, internalTabs),
        }),
      );
    }

    if (tabContextActions?.reopenClosed && contextActionHandlers?.reopenClosed) {
      actions.push(
        createContextMenuAction({
          id: 'reopenClosed',
          label: i18n('common.button.reopenClosedTab'),
          execute: () => contextActionHandlers.reopenClosed?.(tab, internalTabs),
        }),
      );
    }

    return actions;
  }

  const contextMenuTab = contextMenu
    ? internalTabs.find((tab) => tab.key === contextMenu.targetSnapshot.key)
    : undefined;
  const contextMenuActions = contextMenuTab ? createTabContextMenuActions(contextMenuTab) : [];
  const enableReorder = !!contextActionHandlers?.reorder;
  const enableInternalSortableContext = enableReorder && !useExternalSortableContext;

  function handleDragStart(event: DragStartEvent) {
    if (!contextActionHandlers?.reorder) {
      return;
    }
    setDraggingTabKey(String(event.active.id));
  }

  function handleDragEnd(event: DragEndEvent) {
    if (!contextActionHandlers?.reorder) {
      setDraggingTabKey(undefined);
      return;
    }
    const { active, over } = event;
    setDraggingTabKey(undefined);
    if (!over || active.id === over.id) {
      return;
    }
    const sourceIndex = internalTabs.findIndex((tab) => String(tab.key) === String(active.id));
    const targetIndex = internalTabs.findIndex((tab) => String(tab.key) === String(over.id));
    if (sourceIndex === -1 || targetIndex === -1) {
      return;
    }
    const sourceTab = internalTabs[sourceIndex];
    const targetTab = internalTabs[targetIndex];
    const nextTabs = [...internalTabs];
    nextTabs.splice(sourceIndex, 1);
    nextTabs.splice(targetIndex, 0, sourceTab);
    setInternalTabs(nextTabs);
    contextActionHandlers.reorder(nextTabs, sourceTab, targetTab);
  }

  const renderTabItem = (t: ITabItem, index: number) => {
    const setTabItemRef = (node: HTMLDivElement | null) => {
      if (node) {
        tabItemRefs.current.set(t.key, node);
      } else {
        tabItemRefs.current.delete(t.key);
      }
    };

    function inputOnChange(value: string) {
      internalTabs[index].label = value;
      setInternalTabs([...internalTabs]);
    }

    function onBlur() {
      editableNameOnBlur?.(t);
      setEditingTab(undefined);
    }

    function handleContextMenu(event: React.MouseEvent) {
      event.preventDefault();
      event.stopPropagation();
      if (!createTabContextMenuActions(t).some(isContextMenuAction)) {
        return;
      }
      if (activateOnContextMenu && t.key !== activeKey) {
        changeTab(t.key);
      }
      setContextMenu({
        surface: 'workspaceTab',
        pointer: {
          x: event.clientX,
          y: event.clientY,
        },
        targetSnapshot: {
          key: t.key,
          editableName: t.editableName,
          canClosed: t.canClosed,
          pinned: t.pinned,
        },
        version: `${t.key}:${t.editableName ? 'editable' : 'fixed'}:${t.canClosed === false ? 'locked' : 'closable'}:${t.pinned ? 'pinned' : 'normal'}`,
      });
    }

    const tabStyle = {
      ...t.styles,
      ...getTabAccentStyle(t.accentColor),
    };
    const tabNode = enableReorder ? (
      <SortableTabItem
        ref={setTabItemRef}
        disabled={false}
        itemKey={t.key}
        onContextMenu={handleContextMenu}
        onDoubleClick={() => {
          onDoubleClick(t);
        }}
        onMouseDown={(e) => {
    // 1 is middle, 0 is left, and 2 is right mouse button.
          if (e.button === 1) {
            deleteTab(t);
          }
        }}
        style={tabStyle}
        className={cx(styles.tabItem, {
          [styles.activeTab]: t.key === activeKey,
          [styles.draggingTab]: String(t.key) === draggingTabKey,
        })}
      >
        {renderTabInner(t)}
      </SortableTabItem>
    ) : (
      <div
        ref={setTabItemRef}
        onContextMenu={handleContextMenu}
        onDoubleClick={() => {
          onDoubleClick(t);
        }}
        onMouseDown={(e) => {
    // 1 is middle, 0 is left, and 2 is right mouse button.
          if (e.button === 1) {
            deleteTab(t);
          }
        }}
        style={tabStyle}
        className={cx(styles.tabItem, {
          [styles.activeTab]: t.key === activeKey,
        })}
      >
        {renderTabInner(t)}
      </div>
    );

    return t.popover ? (
      <Popover mouseEnterDelay={1} content={t.popover} key={t.key} overlayClassName={styles.popoverOverlay}>
        {tabNode}
      </Popover>
    ) : (
      <React.Fragment key={t.key}>{tabNode}</React.Fragment>
    );

    function renderTabInner(tab: ITabItem) {
      return (
        <>
          <div
            className={styles.tabItemTextBox}
            key={tab.key}
            onClick={() => {
              changeTab(tab.key);
            }}
          >
            {tab.pinned && <IconfontSvg className={styles.pinnedIcon} code="icon-pin" />}
            {tab.prefixIcon &&
              (typeof tab.prefixIcon == 'string' ? (
                <IconfontSvg className={styles.prefixIcon} code={tab.prefixIcon} />
              ) : (
                tab.prefixIcon
              ))}
            {tab.key === editingTab ? (
              <input
                value={tab.label as string}
                onChange={(e) => {
                  inputOnChange(e.target.value);
                }}
                className={styles.input}
                autoFocus
                onBlur={onBlur}
                type="text"
              />
            ) : (
              <div className={styles.tabItemText}>{tab.label}</div>
            )}
          </div>
          {showClosed(tab) ? (
            <IconButton
              className={styles.tabItemIcon}
              size="xs"
              onClick={(e) => {
                e.stopPropagation();
                deleteTab(tab);
              }}
              code="icon-close"
            />
          ) : (
            <div className={styles.placeholderTabItemIcon} />
          )}
        </>
      );
    }
  };

  useUpdateEffect(() => {
    if (!searchValue) {
      setSearchInternalTabs(undefined);
    } else {
      setSearchInternalTabs(internalTabs.filter((t) => isMatched(searchValue, t.label?.toString() || '')));
    }
  }, [searchValue]);

  const moreTabsDropdownRender = () => {
    return (
      <div className={styles.moreTabsBox}>
        <div className={styles.searchBar}>
          <div className={styles.iconContainer}>
            <IconButton
              size={
                {
                  boxSize: 20,
                  iconSize: 18,
                  borderRadius: 3,
                } as any
              }
              code="icon-search"
            />
          </div>
          <input
            type="text"
            value={searchValue}
            onChange={(e) => {
              setSearchValue(e.target.value);
            }}
            placeholder={i18n('common.text.search')}
          />
        </div>
        <div className={styles.moreTabsMenu}>
          {searchInternalTabs?.length !== 0 &&
            (searchInternalTabs || internalTabs).map((t) => {
              return (
                <div
                  key={t.key}
                  className={cx(styles.moreTabsMenuItem, {
                    [styles.moreTabsMenuItemActive]: t.key === activeKey,
                  })}
                  onClick={() => {
                    changeTab(t.key);
                    setMoreTabsDropdownOpen(false);
                    setTimeout(() => {
                      setSearchInternalTabs(undefined);
                      setSearchValue('');
                      setMoreTabsDropdownOpen(undefined);
                    }, 0);
                  }}
                >
                  {t.prefixIcon &&
                    (typeof t.prefixIcon == 'string' ? (
                      <IconfontSvg className={styles.prefixIcon} code={t.prefixIcon} />
                    ) : (
                      t.prefixIcon
                    ))}
                  <div className={styles.moreTabsMenuItemText}>{t.label}</div>
                  {showClosed(t) ? (
                    <div
                      className={cx(styles.tabItemIconActive, styles.tabItemIcon)}
                      onClick={(e) => {
                        e.stopPropagation();
                        deleteTab(t);
                      }}
                    >
                      <Iconfont code="&#xe634;" />
                    </div>
                  ) : (
                    <div className={styles.placeholderTabItemIcon} />
                  )}
                </div>
              );
            })}
          {searchInternalTabs?.length === 0 && (
            <div className={styles.noMatch}>{i18n('common.text.noSearchResult')}</div>
          )}
        </div>
      </div>
    );
  };

  function renderTabsNav() {
    const tabsNavContent = (
      <>
          {!!internalTabs?.length && (
            <div className={cx(styles.tabList)} ref={tabListBoxRef}>
              {enableInternalSortableContext ? (
                <DndContext
                  sensors={sensors}
                  collisionDetection={closestCenter}
                  onDragStart={handleDragStart}
                  onDragEnd={handleDragEnd}
                  onDragCancel={() => setDraggingTabKey(undefined)}
                >
                  <SortableContext
                    items={internalTabs.map((tab) => String(tab.key))}
                    strategy={horizontalListSortingStrategy}
                  >
                    {internalTabs.map((t, index) => {
                      return renderTabItem(t, index);
                    })}
                  </SortableContext>
                </DndContext>
              ) : useExternalSortableContext && enableReorder ? (
                <SortableContext
                  items={internalTabs.map((tab) => String(tab.key))}
                  strategy={horizontalListSortingStrategy}
                >
                  {internalTabs.map((t, index) => {
                    return renderTabItem(t, index);
                  })}
                </SortableContext>
              ) : (
                internalTabs.map((t, index) => {
                  return renderTabItem(t, index);
                })
              )}
            </div>
          )}
          <div className={styles.rightBox}>
            {showAddButton && (
              <div className={cx(styles.addIcon)} onClick={handleAdd}>
                <IconButton size="sm" code="icon-add" />
              </div>
            )}
          </div>
          {tabBarExtraContent && <div className={styles.tabBarExtra}>{tabBarExtraContent}</div>}
          <div className={styles.moreTabs}>
            <Dropdown
              open={moreTabsDropdownOpen}
              dropdownRender={moreTabsDropdownRender}
              placement="bottomRight"
              trigger={['click']}
            >
              <DropdownChevronTrigger aria-label={i18n('common.text.moreTabs')} />
            </Dropdown>
          </div>
      </>
    );

    if (tabPaneDroppableId) {
      return (
        <TabPaneDroppableNav
          className={styles.tabsNav}
          droppableId={tabPaneDroppableId}
          dropOverClassName={styles.tabPaneDropOver}
        >
          {tabsNavContent}
        </TabPaneDroppableNav>
      );
    }

    return <div className={styles.tabsNav}>{tabsNavContent}</div>;
  }

  return (
    <div ref={tabBoxRef} className={cx(styles.tabBox, className)}>
      <PortalContextMenu
        intent={contextMenu}
        actions={contextMenuActions}
        onClose={closeContextMenu}
      />
      {!concealTabHeader && renderTabsNav()}
      {/* Hidden implementation. */}
      {!concealTabContent && (!destroyInactiveTabPane ? (
        <div className={styles.tabsContent}>
          {internalTabs?.map((t) => {
            if (t.destroyOnHide && t.key !== activeKey) {
              return null;
            }
            return (
              <div
                key={t.key}
                className={cx(styles.tabsContentItem, {
                  [styles.tabsContentItemActive]: t.key === activeKey,
                })}
              >
                {t.children}
              </div>
            );
          })}
        </div>
      ) : (
        <div className={styles.tabsContent}>
          <div className={cx(styles.tabsContentItem, styles.tabsContentItemActive)}>
            {internalTabs.find((t) => t.key === activeKey)?.children}
          </div>
        </div>
      ))}
    </div>
  );
});
