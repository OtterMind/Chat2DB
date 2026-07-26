import React, { memo, useMemo, useState } from 'react';
import { TreeNodeData } from '@/typings';
import { TreeNodeType, getDatabaseInfo } from '@/constants';
import { EditText, IconfontSvg } from '@chat2db/ui';
import { useTreeStore } from '@/store/tree';
import { setFocusedContent, getFocusedContent } from '@/store/common/copyFocusedContent';
import { switchIcon, treeConfig } from '../../treeConfig';
import LoadingGracile from '@/components/Loading/LoadingGracile';
import { type ThemeAppearance } from 'antd-style';
import { ContextMenuRef } from '@/components/ContextMenu';
import Filtration from '../Filtration';
import { splitSearchHighlight } from './highlightSearchText';

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
    expandedKeys,
    selectedKeys,
    setSelectedKeys,
    setExpandedKeys,
    setCurrentTreeNode,
    searchBarValue,
    regularSearchBarValue,
    toggleExpandedKeys,
    currentLoadingTreeNode,
    userConfigTree,
  } = useTreeStore((state) => ({
    editingTreeNode: state.editingTreeNode,
    setEditingTreeNode: state.setEditingTreeNode,
    setTreeData: state.setTreeData,
    handleLoadData: state.handleLoadData,
    expandedKeys: state.expandedKeys,
    setSelectedKeys: state.setSelectedKeys,
    selectedKeys: state.selectedKeys,
    setExpandedKeys: state.setExpandedKeys,
    setCurrentTreeNode: state.setCurrentTreeNode,
    searchBarValue: state.searchBarValue,
    regularSearchBarValue: state.regularSearchBarValue,
    toggleExpandedKeys: state.toggleExpandedKeys,
    currentLoadingTreeNode: state.currentLoadingTreeNode,
    userConfigTree: state.userConfigTree,
  }));

  const isExpanded = useMemo(() => expandedKeys.includes(nodeData.key), [expandedKeys, nodeData.key]);

  const handleClickTreeNode = () => {
    if (nodeData.originalTitle !== getFocusedContent()) {
      setFocusedContent(nodeData.originalTitle || '');
    }
    if (nodeData.key !== selectedKeys[0]) {
      setCurrentTreeNode(nodeData);
      setSelectedKeys([nodeData.key]);
    }
  };

  const handleDoubleTreeNode = () => {
    const flag = treeDropdownRef.current?.handleDoubleClick(nodeData as any);
    // The dropdown returns true after handling the double-click, so no further action is needed.
    // nodeData.isLeaf represents leaf nodes and does not need to handle double-click events.
    if (flag || nodeData.isLeaf) {
      return;
    }
    // Expanded node, collapse
    if (expandedKeys.includes(nodeData.key)) {
      setExpandedKeys(expandedKeys.filter((key) => key !== nodeData.key));
      return;
    }
    setIsLoading(true);
    // Unexpanded node, expand
    handleLoadData(nodeData as any)
      .then(() => {
        setExpandedKeys([...expandedKeys, nodeData.key]);
      })
      .finally(() => {
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
      <IconfontSvg
        className={cx(styles.switcherIcon, { [styles.unfoldSwitcherIcon]: isExpanded })}
        size={13}
        code="icon-chevron-right"
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
    if (isLoading) return;
    if (!isExpanded) {
      setIsLoading(true);
      handleLoadData(nodeData as any).finally(() => {
        setIsLoading(false);
      });
    }
    toggleExpandedKeys(nodeData.key);
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
      nodeData.treeNodeType === TreeNodeType.SAVE_CONSOLE ||
      nodeData.treeNodeType === TreeNodeType.AI_DATA_COLLECTION
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
