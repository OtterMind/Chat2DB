import React, { memo, useCallback, useEffect, useLayoutEffect, useRef, useMemo, forwardRef, useState } from 'react';
import { Tree, TreeProps, ConfigProvider, Spin } from 'antd';
import { useStyles } from './style';
import { useStyles as renderTitleUseStyles } from './renderTitleStyle';

import { TreeNodeData } from '@/typings';
import { TreeNodeType } from '@/constants';
import {
  ShortcutAction,
  ShortcutOverrides,
  getEffectiveShortcutConfigMap,
  isShortcutEventMatch,
} from '@/constants/shortcut';

import TreeDropdown, { TreeDropdownRef } from './components/TreeDropdown';
import ContextMenu, { ContextMenuRef } from '@/components/ContextMenu';
import NoConnectionContent from './components/NoConnectionContent';
import TitleRender from './components/TitleRender';
import useTrimTreeData from './hooks/useTrimTreeData';
// import LoadingGracile from '@/components/Loading/LoadingGracile';
import { useTreeStore } from '@/store/tree';
import { useGlobalStore } from '@/store/global';
import connectionService from '@/service/connection';
import { useSize } from 'ahooks';
import { decorateDataSourceIdentityTree } from './dataSourceIdentity';
import { measureTreeScrollWidth, resolveNextTreeScrollWidth } from './treeScrollWidth';
import { TreePositionMutationCoordinator, TreePositionRefreshError } from './treePositionMutation';
import i18n from '@/i18n';
import { staticMessage } from '@chat2db/ui';

interface IProps extends TreeProps<TreeNodeData> {
  className?: string;
  hiddenNoPermission?: boolean;
  // Treat the xxx node as a leaf node, remove its child nodes, and expand the icon
  leafNodes?: string[];
  excludeNodes?: string[];
}

export interface NewTreeRef {}

const DATABASE_TREE_SHORTCUT_ACTIONS = [
  ShortcutAction.DatabaseTreeOpenTable,
  ShortcutAction.DatabaseTreeEditTable,
  ShortcutAction.DatabaseTreeCreateTable,
  ShortcutAction.DatabaseTreeRefresh,
];

const isEditableTarget = (target: EventTarget | null) => {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  return !!target.closest('input, textarea, [contenteditable="true"], [contenteditable=""]');
};

const NewTree = (props: IProps, ref: React.ForwardedRef<NewTreeRef>) => {
  void ref;
  const { className, leafNodes, hiddenNoPermission, excludeNodes, ...restProps } = props;
  const {
    styles,
    cx,
    theme: { appearance },
  } = useStyles();
  const { styles: renderTitleStyles } = renderTitleUseStyles();
  // Tree drop-down menu Ref
  const treeDropdownRef = useRef<TreeDropdownRef>(null);
  const nodeFilteringRef = useRef<ContextMenuRef>(null);
  // TreeBoxRef is used to get the height of Tree
  const treeBoxRef = useRef<HTMLDivElement>(null);
  // TreeRef
  const treeRef = useRef<any>(null);
  const lastTreeSize = useRef<{ width: number; height: number }>();
  const horizontalMeasureFrameRef = useRef<number>();
  const horizontalMeasureResetRef = useRef(false);
  const horizontalScrollHideTimerRef = useRef<number>();
  const treePositionMutationRef = useRef(new TreePositionMutationCoordinator());
  const [treeScrollWidth, setTreeScrollWidth] = useState<number>();
  const [isHorizontalScrolling, setIsHorizontalScrolling] = useState(false);
  const filteredTreeData = useTrimTreeData({ leafNodes, hiddenNoPermission, excludeNodes });
  const {
    editingTreeNode,
    selectedKeys,
    setSelectedKeys,
    setTreeRef,
    expandedKeys,
    scrollTargetKey,
    setScrollTargetKey,
    searchBarValue,
    dataSourceList,
    getTreeData,
  } = useTreeStore((state) => ({
    editingTreeNode: state.editingTreeNode,
    selectedKeys: state.selectedKeys,
    setSelectedKeys: state.setSelectedKeys,
    setTreeRef: state.setTreeRef,
    expandedKeys: state.expandedKeys,
    scrollTargetKey: state.scrollTargetKey,
    setScrollTargetKey: state.setScrollTargetKey,
    searchBarValue: state.searchBarValue,
    dataSourceList: state.dataSourceList,
    getTreeData: state.getTreeData,
  }));
  const identityTreeData = useMemo(
    () => decorateDataSourceIdentityTree(filteredTreeData, dataSourceList),
    [filteredTreeData, dataSourceList],
  );
  const shortcutOverrides = useGlobalStore((state) => state.shortcutOverrides);
  const shortcutConfig = useMemo(
    () => getEffectiveShortcutConfigMap(shortcutOverrides as ShortcutOverrides),
    [shortcutOverrides],
  );

  useEffect(() => {
    if (treeRef.current) {
      setTreeRef(treeRef);
    }
  }, [setTreeRef]);

  useEffect(() => {
    if (searchBarValue || !scrollTargetKey || !filteredTreeData?.length) {
      return;
    }

    const visibleIndex = findVisibleTreeNodeIndex(filteredTreeData, expandedKeys, scrollTargetKey);
    if (visibleIndex === -1) {
      return;
    }

    let frameId: number | null = null;
    const scroll = () => {
      treeRef.current?.scrollTo({ index: visibleIndex, align: 'top' });
      setScrollTargetKey(null);
    };

    frameId = window.requestAnimationFrame(scroll);
    return () => {
      if (frameId !== null) {
        window.cancelAnimationFrame(frameId);
      }
    };
  }, [searchBarValue, scrollTargetKey, filteredTreeData, expandedKeys, setScrollTargetKey]);

  const treeSize = useSize(treeBoxRef);

  const measureHorizontalScrollWidth = useCallback((resetMeasurement = false) => {
    horizontalMeasureResetRef.current ||= resetMeasurement;
    if (horizontalMeasureFrameRef.current !== undefined) {
      window.cancelAnimationFrame(horizontalMeasureFrameRef.current);
    }
    horizontalMeasureFrameRef.current = window.requestAnimationFrame(() => {
      horizontalMeasureFrameRef.current = undefined;
      const shouldResetMeasurement = horizontalMeasureResetRef.current;
      horizontalMeasureResetRef.current = false;
      const container = treeBoxRef.current;
      if (!container) {
        return;
      }
      const measuredWidth = measureTreeScrollWidth(container);
      setTreeScrollWidth((currentWidth) =>
        resolveNextTreeScrollWidth(currentWidth, measuredWidth, shouldResetMeasurement),
      );
    });
  }, []);

  useLayoutEffect(() => {
    if (treeBoxRef.current?.scrollLeft) {
      treeBoxRef.current.scrollLeft = 0;
    }
    measureHorizontalScrollWidth(true);
    return () => {
      if (horizontalMeasureFrameRef.current !== undefined) {
        window.cancelAnimationFrame(horizontalMeasureFrameRef.current);
        horizontalMeasureFrameRef.current = undefined;
      }
    };
  }, [editingTreeNode, expandedKeys, identityTreeData, measureHorizontalScrollWidth, treeSize?.width]);

  useEffect(() => {
    return () => {
      if (horizontalScrollHideTimerRef.current !== undefined) {
        window.clearTimeout(horizontalScrollHideTimerRef.current);
      }
    };
  }, []);

  const handleHorizontalWheel = useCallback((event: React.WheelEvent<HTMLDivElement>) => {
    const isShiftScroll = event.shiftKey && event.deltaY !== 0 && event.deltaX === 0;
    const isHorizontalScroll = isShiftScroll || Math.abs(event.deltaX) > Math.abs(event.deltaY);
    if (!isHorizontalScroll) {
      return;
    }
    setIsHorizontalScrolling(true);
    if (horizontalScrollHideTimerRef.current !== undefined) {
      window.clearTimeout(horizontalScrollHideTimerRef.current);
    }
    horizontalScrollHideTimerRef.current = window.setTimeout(() => {
      horizontalScrollHideTimerRef.current = undefined;
      setIsHorizontalScrolling(false);
    }, 600);
  }, []);

  // right-click menu
  const onRightClick = ({ event, node }) => {
    event.preventDefault();
    setSelectedKeys([node.key]);
    treeDropdownRef.current?.openMenu({ event, node });
  };

  const onDrop: TreeProps['onDrop'] = (info: any) => {
    const dragKey = info.dragNode.key;
    const dropPos = info.node.pos.split('-');
    const dropPosition = info.dropPosition - Number(dropPos[dropPos.length - 1]);
    // the drop position relative to the drop node, inside 0, top -1, bottom 1

    void treePositionMutationRef.current
      .run(
        dragKey,
        () =>
          connectionService.updatePosition({
            dragNode: {
              id: info.dragNode.id,
              type: info.dragNode.treeNodeType === TreeNodeType.GROUP ? 'NAMESPACE' : 'DATA_SOURCE',
            },
            dropToNode: {
              id: info.node.id,
              type: info.node.treeNodeType === TreeNodeType.GROUP ? 'NAMESPACE' : 'DATA_SOURCE',
            },
            dropPosition: dropPosition as 0 | 1 | -1,
          }),
        () => getTreeData({ refresh: true, throwOnError: true }),
      )
      .catch((error) => {
        if (error instanceof TreePositionRefreshError) {
          staticMessage.error(i18n('workspace.tips.treePositionRefreshFailed'));
        }
      });
  };

  const titleRender = (nodeData: TreeNodeData) => {
    return (
      <TitleRender
        cx={cx}
        styles={renderTitleStyles}
        treeDropdownRef={treeDropdownRef}
        nodeData={nodeData}
        nodeFilteringRef={nodeFilteringRef}
        appearance={appearance}
      />
    );
  };

  const handleDatabaseTreeShortcut = async (event: React.KeyboardEvent<HTMLDivElement>) => {
    const selectedTreeNode = findTreeNodeByKey(filteredTreeData, selectedKeys[0]);
    if (!selectedTreeNode || isEditableTarget(event.target)) {
      return;
    }

    const action = DATABASE_TREE_SHORTCUT_ACTIONS.find((shortcutAction) =>
      isShortcutEventMatch(event, shortcutConfig[shortcutAction].binding),
    );
    if (!action) {
      return;
    }

    event.preventDefault();
    event.stopPropagation();
    await treeDropdownRef.current?.handleShortcut(selectedTreeNode, action);
  };

  const antdTreeProps: TreeProps<TreeNodeData> = useMemo(() => {
    if (treeSize?.height) {
      lastTreeSize.current = treeSize;
    }

    // Preserve the last non-zero height while the page is hidden.
    // This avoids an expensive tree recalculation when the page becomes visible again.
    // Actual tree height
    const treeHeight = treeSize?.height || lastTreeSize.current?.height;

    return {
      treeData: identityTreeData || [],
      blockNode: true,
      motion: false,
      itemHeight: 26,
      height: treeHeight,
      scrollWidth: treeScrollWidth,
      selectedKeys,
      expandedKeys,
      // Ant Design 5.21.5 supports custom loading and switcher icons.
      // Any future implementation must preserve the current expand/collapse behavior.
      // switcherLoadingIcon: <LoadingGracile />,
      // switcherIcon: ({ isLeaf, expanded }) => {
      //   if (isLeaf) {
      //     return null;
      //   }
      //   return (
      //     <IconfontSvg
      //       className={cx(styles.switcherIcon, { [styles.unfoldSwitcherIcon]: expanded })}
      //       size={12}
      //       code="icon-chevron-right"
      //     />
      //   );
      // },
      switcherIcon: false,
      draggable: {
        icon: false,
        nodeDraggable: (node: any) => {
          return node.treeNodeType === TreeNodeType.DATA_SOURCE || node.treeNodeType === TreeNodeType.GROUP;
        },
      },
      allowDrop: (info) => {
        // is only allowed to be related to GROUP and DATA_SOURCE
        if (
          info.dropNode.treeNodeType !== TreeNodeType.GROUP &&
          info.dropNode.treeNodeType !== TreeNodeType.DATA_SOURCE
        ) {
          return false;
        }
        // Only GROUP accepts being dragged into itself
        if (info.dropNode.treeNodeType !== TreeNodeType.GROUP && info.dropPosition === 0) {
          return false;
        }
        return true;
      },
      onRightClick,
      onDrop,
      onScroll: () => {
        treeDropdownRef.current?.closeMenu();
        measureHorizontalScrollWidth();
      },
      titleRender,
      ...restProps,
    };
  }, [
    selectedKeys,
    expandedKeys,
    treeSize?.height,
    editingTreeNode,
    identityTreeData,
    restProps,
    treeScrollWidth,
    measureHorizontalScrollWidth,
  ]);

  return (
    <div
      ref={treeBoxRef}
      className={cx(styles.treeBox, isHorizontalScrolling && styles.horizontalScrolling, className)}
      tabIndex={0}
      onKeyDown={handleDatabaseTreeShortcut}
      onWheelCapture={handleHorizontalWheel}
    >
      <ConfigProvider
        theme={{
          components: {
            Tree: {
              titleHeight: 24,
              paddingXS: 0,
            },
          },
        }}
      >
        {filteredTreeData === null ? (
          <div className={styles.spinBox}>
            <Spin />
          </div>
        ) : (
          <>{filteredTreeData?.length === 0 ? <NoConnectionContent /> : <Tree {...antdTreeProps} ref={treeRef} />}</>
        )}
        <TreeDropdown ref={treeDropdownRef} />
        <ContextMenu ref={nodeFilteringRef} />
      </ConfigProvider>
    </div>
  );
};

export default memo(forwardRef<NewTreeRef, IProps>(NewTree));

const findVisibleTreeNodeIndex = (
  treeData: TreeNodeData[],
  expandedKeys: React.Key[],
  targetKey: React.Key,
): number => {
  const expandedKeySet = new Set(expandedKeys);
  let index = 0;

  const walk = (nodes: TreeNodeData[]): number => {
    for (const node of nodes) {
      if (node.key === targetKey) {
        return index;
      }

      index += 1;
      if (node.children?.length && expandedKeySet.has(node.key)) {
        const childIndex = walk(node.children);
        if (childIndex !== -1) {
          return childIndex;
        }
      }
    }

    return -1;
  };

  return walk(treeData);
};

const findTreeNodeByKey = (
  treeData: TreeNodeData[] | null | undefined,
  targetKey: React.Key | undefined,
): TreeNodeData | undefined => {
  if (!treeData || targetKey === undefined) {
    return undefined;
  }

  for (const node of treeData) {
    if (node.key === targetKey) {
      return node;
    }
    const childNode = findTreeNodeByKey(node.children, targetKey);
    if (childNode) {
      return childNode;
    }
  }
  return undefined;
};
