import React, { memo, useState, useEffect } from 'react';
import { TreeNodeType } from '@/constants';
import { TreeNodeData } from '@/typings';
import { getDatabaseSupport } from '@/utils/database';
import NodeFiltering from '@/components/NodeFiltering';
import { ContextMenuRef } from '@/components/ContextMenu';
import { useTreeStore } from '@/store/tree';

interface IProps {
  className?: string;
  nodeData: TreeNodeData;
  styles: any;
  nodeFilteringRef: React.RefObject<ContextMenuRef>;
}

const getHiddenTreeNodeIds = (hiddenTreeNodeIds, nodeData: TreeNodeData) => {
  if (
    (nodeData.treeNodeType === TreeNodeType.DATA_SOURCE || nodeData.treeNodeType === TreeNodeType.DATABASE) &&
    nodeData.extraParams.dataSourceId
  ) {
    return hiddenTreeNodeIds?.[nodeData.extraParams.dataSourceId] || [];
  }
  return [];
};

export default memo<IProps>((props) => {
  const { styles, nodeData, nodeFilteringRef } = props;
  const [isOpen, setIsOpen] = useState(false);

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (isOpen && !(event.target as Element)?.closest('.nodeFilteringContainer')) {
        setIsOpen(false);
      }
    };

    document.addEventListener('click', handleClickOutside);
    return () => {
      document.removeEventListener('click', handleClickOutside);
    };
  }, [isOpen]);

  const { getChildrenByNodeId, addOrDeleteShowTreeNodeIds, hiddenTreeNodeIds } = useTreeStore((state) => ({
    getChildrenByNodeId: state.getChildrenByNodeId,
    addOrDeleteShowTreeNodeIds: state.addOrDeleteShowTreeNodeIds,
    hiddenTreeNodeIds: getHiddenTreeNodeIds(state.hiddenTreeNodeIds, nodeData),
  }));

  if ([TreeNodeType.DATABASE, TreeNodeType.DATA_SOURCE].includes(nodeData.treeNodeType)) {
    const { supportSchema } = getDatabaseSupport(nodeData?.extraParams?.databaseType);

    if (!supportSchema && nodeData.treeNodeType === TreeNodeType.DATABASE) {
      return null;
    }

    const children = getChildrenByNodeId(nodeData.key);

    if (children.length === 0) {
      return null;
    }

    const data = children.map((item) => ({
        title: item.originalTitle,
        key: item.key,
        count: null,
      }));

    const handleOnChangeSelect = (
      keys: (string | null | undefined)[],
      changedKeys?: {
        add: (string | null | undefined)[];
        delete: (string | null | undefined)[];
      },
    ) => {
      if (nodeData.extraParams.dataSourceId && changedKeys) {
        const reversedChangedKeys = {
          add: changedKeys.delete,
          delete: changedKeys.add,
        };
        addOrDeleteShowTreeNodeIds(nodeData.extraParams.dataSourceId, reversedChangedKeys);
      }
    };

    const calcHiddenNum = () => {
      return (hiddenTreeNodeIds || []).filter((id) => data?.some((item) => item.key === id)).length;
    };

    return (
      <div
        className={styles.filtration}
        onClick={(event) => {
          event.stopPropagation();
          if (isOpen) {
            nodeFilteringRef.current?.closeDropdown();
            setIsOpen(false);
          } else {
            nodeFilteringRef.current?.openDropdown({
              event,
              dropdownRender: (
                <div className="nodeFilteringContainer">
                  <NodeFiltering
                    selectedKeys={data.map((item) => item.key).filter((key) => !hiddenTreeNodeIds.includes(key))}
                    onChangeSelect={handleOnChangeSelect}
                    data={data}
                  />
                </div>
              ),
            });
            setIsOpen(true);
          }
        }}
      >
        {data.length - calcHiddenNum()} of {data.length}
      </div>
    );
  }

  return null;
});
