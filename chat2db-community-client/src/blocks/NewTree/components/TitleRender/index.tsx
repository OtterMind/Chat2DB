import React, { memo, useMemo, useState } from 'react';
import { TreeNodeData } from '@/typings';
import { TreeNodeType, getDatabaseInfo } from '@/constants';
import { EditText, IconfontSvg } from '@chat2db/ui';
import { useTreeStore } from '@/store/tree';
import { setFocusedContent, getFocusedContent } from '@/store/common/copyFocusedContent';
import { switchIcon, treeConfig } from '../../treeConfig';
import LoadingGracile from '@/components/Loading/LoadingGracile';
import { type ThemeAppearance } from 'antd-style';
import { Activity, ChevronRight, SquareActivity, User, Users } from 'lucide-react';
import { ContextMenuRef } from '@/components/ContextMenu';
import Filtration from '../Filtration';
import { splitSearchHighlight } from './highlightSearchText';
import { resolveTreeSwitcherAction } from './switcherAction';
import { resolveTreeNodeSelection } from '../../utils/treeNodePath';
import {
  mergeWorkspaceTreeSearchExpandedKeys,
  resolveWorkspaceTreeExpandedKeys,
} from '@/pages/main/workspace/components/WorkspaceTreeSearch/lifecycle';

interface IProps {
  className?: string;
  nodeData: TreeNodeData;
  nodeFilteringRef: React.RefObject<ContextMenuRef>;
  treeDropdownRef: React.RefObject<any>;
  appearance: ThemeAppearance;
  styles: any;
  cx: any;
}

const TitleRender = (props: IProps) => {
  const { nodeData, treeDropdownRef, styles, cx, appearance, nodeFilteringRef } = props;
  const [isLoading, setIsLoading] = useState(false);

  const {
    editingTreeNode,
    setEditingTreeNode,
    setTreeData,
    handleLoadData,
    persistentExpandedKeys,
    searchRequiredExpandedKeys,
    invalidatedTreeNodeKeys,
    selectedKeys,
    setSelectedKeys,
    setSearchRequiredExpandedKeys,
    setCurrentTreeNode,
    searchBarValue,
    regularSearchBarValue,
    toggleExpandedKeys,
    currentLoadingTreeNode,
    treeData,
    userConfigTree,
  } = useTreeStore((state) => ({
    editingTreeNode: state.editingTreeNode,
    setEditingTreeNode: state.setEditingTreeNode,
    setTreeData: state.setTreeData,
    handleLoadData: state.handleLoadData,
    persistentExpandedKeys: state.expandedKeys,
    searchRequiredExpandedKeys: state.searchRequiredExpandedKeys,
    invalidatedTreeNodeKeys: state.invalidatedTreeNodeKeys,
    setSelectedKeys: state.setSelectedKeys,
    selectedKeys: state.selectedKeys,
    setSearchRequiredExpandedKeys: state.setSearchRequiredExpandedKeys,
    setCurrentTreeNode: state.setCurrentTreeNode,
    searchBarValue: state.searchBarValue,
    regularSearchBarValue: state.regularSearchBarValue,
    toggleExpandedKeys: state.toggleExpandedKeys,
    currentLoadingTreeNode: state.currentLoadingTreeNode,
    treeData: state.treeData,
    userConfigTree: state.userConfigTree,
  }));
  const expandedKeys = useMemo(
    () =>
      resolveWorkspaceTreeExpandedKeys(
        persistentExpandedKeys,
        searchRequiredExpandedKeys,
        invalidatedTreeNodeKeys,
      ),
    [persistentExpandedKeys, searchRequiredExpandedKeys, invalidatedTreeNodeKeys],
  );

  const isExpanded = useMemo(() => expandedKeys.includes(nodeData.key), [expandedKeys, nodeData.key]);

  const handleClickTreeNode = () => {
    const selection = resolveTreeNodeSelection(treeData, nodeData, Boolean(searchBarValue));
    const selectedNode = selection.node;

    if (selectedNode.originalTitle !== getFocusedContent()) {
      setFocusedContent(selectedNode.originalTitle || '');
    }

    if (searchBarValue && selection.ancestors.length) {
      // Search renders a filtered copy. Keep the search session active while
      // rebinding selection to the source node and its stable ancestor path.
      setSearchRequiredExpandedKeys(
        mergeWorkspaceTreeSearchExpandedKeys(searchRequiredExpandedKeys, selection.ancestors),
      );
    }

    setCurrentTreeNode(selectedNode);
    if (selectedNode.key !== selectedKeys[0]) {
      setSelectedKeys([selectedNode.key]);
    }
  };

  const handleDoubleTreeNode = async () => {
    const flag = await treeDropdownRef.current?.handleDoubleClick(nodeData as any);
    // The dropdown returns true after handling the double-click, so no further action is needed.
    // nodeData.isLeaf represents leaf nodes and does not need to handle double-click events.
    if (flag || nodeData.isLeaf) {
      return;
    }
    // Expanded node, collapse
    if (expandedKeys.includes(nodeData.key)) {
      toggleExpandedKeys(nodeData.key);
      return;
    }
    setIsLoading(true);
    // Unexpanded node, expand
    handleLoadData(nodeData as any).finally(() => {
      setIsLoading(false);
    });
  };

  const renderSwitcherIcon = () => {
    if (nodeData.isLeaf) {
      return null;
    }
    if (isLoading || currentLoadingTreeNode?.key === nodeData.key) {
      return <LoadingGracile />;
    }

    return (
      <ChevronRight
        className={cx(styles.switcherIcon, { [styles.unfoldSwitcherIcon]: isExpanded })}
        size={13}
      />
    );
  };

  const renderIcon = () => {
    const pinnedTableIcon = 'icon-colourful-excel-ding';
    const tableIcon = 'icon-colourful-table';

    if (!nodeData) return null;
    if (nodeData.treeNodeType === TreeNodeType.DATA_SOURCE) {
      const databaseInfo = getDatabaseInfo(nodeData.extraParams.databaseType);

      if (!databaseInfo?.icon) {
        return null;
      }

      return (
        <IconfontSvg
          size={19}
          existDark={databaseInfo?.iconExistDark}
          appearance={appearance}
          code={databaseInfo?.icon}
        />
      );
    }

    if (nodeData.treeNodeType === TreeNodeType.TABLE) {
      return (
        <IconfontSvg
          size={19}
          existDark
          appearance={appearance}
          code={nodeData?.decorativeParams?.pinned ? pinnedTableIcon : tableIcon}
        />
      );
    }

    if (nodeData.treeNodeType === TreeNodeType.DATABASE_ACCOUNTS) {
      return <Users className={styles.customizeIcon} size={19} />;
    }

    if (nodeData.treeNodeType === TreeNodeType.DATABASE_ACCOUNT) {
      return <User className={cx(styles.customizeIconIsLeaf, styles.customizeIcon)} size={19} />;
    }

    if (nodeData.treeNodeType === TreeNodeType.MONITOR) {
      return <SquareActivity className={styles.customizeIcon} size={19} />;
    }

    if (nodeData.treeNodeType === TreeNodeType.ACTIVE_TRANSACTIONS) {
      return <Activity className={cx(styles.customizeIconIsLeaf, styles.customizeIcon)} size={19} />;
    }

    if (isExpanded && switchIcon[nodeData.treeNodeType]!.unfoldIcon) {
      return (
        <IconfontSvg
          size={19}
          existDark
          appearance={appearance}
          code={switchIcon[nodeData.treeNodeType]!.unfoldIcon!}
        />
      );
    }

    return (
      <IconfontSvg
        className={cx({ [styles.customizeIconIsLeaf]: nodeData.isLeaf }, styles.customizeIcon)}
        code={switchIcon[nodeData.treeNodeType]!.icon}
        existDark={switchIcon[nodeData.treeNodeType]!.iconExistDark}
        appearance={appearance}
        size={19}
      />
    );
  };

  const handleClickSwitcherIcon = () => {
    if (nodeData.key !== selectedKeys[0]) {
      setSelectedKeys([nodeData.key]);
    }
    const action = resolveTreeSwitcherAction(isLoading, isExpanded);
    if (action === 'ignore') return;
    if (action === 'collapse') {
      toggleExpandedKeys(nodeData.key);
      return;
    }
    setIsLoading(true);
    handleLoadData(nodeData as any).finally(() => {
      setIsLoading(false);
    });
  };

  const renderDescribe = () => {
    if (!nodeData.describe && !nodeData.columnType) {
      return null;
    }

    if (userConfigTree.showComment === false && nodeData.treeNodeType === TreeNodeType.COLUMN) {
      return <span className={styles.treeNodeDescribe}>{nodeData.columnType}</span>;
    }

    return <span className={styles.treeNodeDescribe}>{nodeData.describe}</span>;
  };

  const renderChildCount = () => {
    if (searchBarValue || nodeData.childCount === undefined) {
      return null;
    }

    return <span className={styles.treeNodeCount}>{nodeData.childCount}</span>;
  };

  const renderContent = () => {
    const regular: any = () => (
      <span>
        {splitSearchHighlight(nodeData.originalTitle || '', regularSearchBarValue).map((segment, index) =>
          segment.highlighted ? (
            <span key={index} style={{ color: 'red' }}>
              {segment.text}
            </span>
          ) : (
            segment.text
          ),
        )}
      </span>
    );

    const editTextContent: any = searchBarValue ? regular() : nodeData.originalTitle || '';

    if (
      nodeData.treeNodeType === TreeNodeType.GROUP ||
      nodeData.treeNodeType === TreeNodeType.SAVE_CONSOLE
    ) {
      return (
        <EditText
          className={styles.originalTitle}
          editing={editingTreeNode?.key === nodeData.key}
          onBlur={(text: string) => {
            setEditingTreeNode(null);
            treeConfig[nodeData.treeNodeType]?.renameCallback(text, nodeData, setTreeData);
          }}
        >
          {editTextContent}
        </EditText>
      );
    }

    return <div className={styles.originalTitle}>{editTextContent}</div>;
  };

  return (
    <>
      <div className={styles.customTitle}>
        <div className={styles.switcherIconBox} onClick={handleClickSwitcherIcon}>
          {renderSwitcherIcon()}
        </div>
        <div className={styles.customIconBox}>{renderIcon()}</div>
        {renderContent()}
        {renderChildCount()}
        {renderDescribe()}
        <Filtration styles={styles} nodeData={nodeData} nodeFilteringRef={nodeFilteringRef} />
      </div>
      <div
        data-chat2db-general-can-copy-element
        onClick={() => {
          handleClickTreeNode();
        }}
        onDoubleClick={() => handleDoubleTreeNode()}
        className={styles.treeNodeMask}
      />
    </>
  );
};

export default memo(TitleRender);
