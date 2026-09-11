import { useLayoutEffect, useRef, useState } from 'react';
import { Tree, Checkbox } from 'antd';
import SearchBar from '@/components/SearchBar';
import { useStyles } from './style';
import i18n from '@/i18n';
import { parseChat2dbSpecificSymbolIdentifier, createChat2dbSpecificSymbolIdentifier } from '@/utils/chat2dbIdentifier';

interface IData {
  title: string;
  key: string | null;
  count: number | null;
}

interface IProps {
  className?: string;
  filterTitle?: string;
  selectedKeys?: (string | null | undefined)[];
    // The second argument identifies changed nodes.
  onChangeSelect: (
    keys: (string | null | undefined)[],
    changedKeys?: { add: (string | null | undefined)[]; delete: (string | null | undefined)[] },
  ) => void;
  data: IData[];
}

export interface NodeFilteringRef {
  demo: () => void;
}

const NodeFiltering = (props: IProps) => {
  const { data, filterTitle, selectedKeys, onChangeSelect } = props;
  const { styles } = useStyles();
  const treeBoxRef = useRef<HTMLDivElement>(null);
  // rc-virtual-list enables horizontal wheel handling from the initial scrollWidth.
  const [scrollWidth, setScrollWidth] = useState(1);
  const [treeData, setTreeData] = useState<IData[]>(data);
  const [checkedKeys, setCheckedKeys] = useState<string[]>(
    selectedKeys?.map((item) => createChat2dbSpecificSymbolIdentifier(item)) || [],
  );

  const measureScrollWidth = () => {
    const nodes = treeBoxRef.current?.querySelectorAll<HTMLElement>('.ant-tree-treenode');
    return Array.from(nodes || []).reduce(
      (width, node) => Math.max(width, node.scrollWidth),
      treeBoxRef.current?.clientWidth || 1,
    );
  };

  useLayoutEffect(() => {
    const treeBox = treeBoxRef.current;
    if (!treeBox) {
      return;
    }
    const observer = new ResizeObserver(() => setScrollWidth(measureScrollWidth()));
    observer.observe(treeBox);
    return () => observer.disconnect();
  }, [treeData]);

  const onCheck = (_checkedKeys, halfChecked) => {
    const { key, checked } = halfChecked.node;
      // Toggle off a previously checked node; otherwise select it.
    const newCheckedKeys = checked ? checkedKeys.filter((item) => item !== key) : [...checkedKeys, key];

      // Calculate the changed nodes.
    const changedKeys = {
      add: checked ? [] : [parseChat2dbSpecificSymbolIdentifier(key)?.value],
      delete: checked ? [parseChat2dbSpecificSymbolIdentifier(key)?.value] : [],
    };

    setCheckedKeys(newCheckedKeys);
    const _changeSelect = newCheckedKeys.map((_key) => parseChat2dbSpecificSymbolIdentifier(_key)?.value);
    onChangeSelect(_changeSelect, changedKeys);
  };

  const titleRender = (nodeData) => {
    return (
      <div className={styles.treeTitle}>
        {parseChat2dbSpecificSymbolIdentifier(nodeData.title)?.display}
        {nodeData.count && <span className={styles.treeTitleCount}>({nodeData.count})</span>}
      </div>
    );
  };

  const onChangeSelectAll = (e) => {
    if (e.target.checked) {
      const newCheckedKeys = treeData.map((item) => item.key || '');
      setCheckedKeys(newCheckedKeys);
      const _changeSelect = newCheckedKeys.map((key) => parseChat2dbSpecificSymbolIdentifier(key)?.value);
      // Calculate newly added keys.
      const currentSelectedKeys = checkedKeys.map((key) => parseChat2dbSpecificSymbolIdentifier(key)?.value);
      const actuallyAddedKeys = _changeSelect.filter((key) => !currentSelectedKeys.includes(key));
      const changedKeys = {
        add: actuallyAddedKeys,
        delete: [],
      };
      onChangeSelect(_changeSelect, changedKeys);
    } else {
      const previousKeys = checkedKeys.map((key) => parseChat2dbSpecificSymbolIdentifier(key)?.value);
      setCheckedKeys([]);
      const changedKeys = {
        add: [],
        delete: previousKeys,
      };
      onChangeSelect([], changedKeys);
    }
  };

  const handleSearchChange = (e) => {
    const value = e.target.value;
    const newData = data.filter((item) => item.title.includes(value));
    setTreeData(newData);
  };

  const handleClearFilter = () => {
    const previousKeys = checkedKeys.map((key) => parseChat2dbSpecificSymbolIdentifier(key)?.value);
    setCheckedKeys([]);
    const changedKeys = {
      add: [],
      delete: previousKeys,
    };
    onChangeSelect([], changedKeys);
  };

  return (
    <div className={styles.nodeFilteringContainer}>
      {/* <div className={styles.nodeFilterTitle}>{`Local Filter for '${'car_name'}'`}</div> */}
      <div className={styles.nodeFilteringHeader}>
        <SearchBar className={styles.searchBar} placeholder={filterTitle || ''} onChange={handleSearchChange} />
        {/* {searching ? (
        ) : (
          <>
            <div className={styles.nodeFilteringName}>
              <IconfontSvg className={styles.nodeFilteringLogo} code="icon-MySQL" size="lg" />
              <span>=design&node-id=1017-36898&mode=design&t=eouxpQ1vdTHtsgj7-0</span>
            </div>
            <div className={styles.actionBar}>
              <IconButton code="icon-refresh" size="lg" />
              <IconButton code="icon-search" size="lg" />
            </div>
          </>
        )} */}
      </div>
      <div className={styles.nodeFilteringBody}>
        <div className={styles.nodeFilteringBodyHeader}>
          <div className={styles.allSchema}>
            <Checkbox checked={checkedKeys.length === data.length && data.length !== 0} onChange={onChangeSelectAll} />
            <span className={styles.allSchemaText}>{i18n('workspace.text.selectAll')}</span>
          </div>
          <div className={styles.clearSelected} onClick={handleClearFilter}>
            {i18n('workspace.text.clearFilter')}
          </div>
        </div>
        <div ref={treeBoxRef} className={styles.treeBox}>
          {!!treeData?.length && (
            <Tree
              height={279}
              scrollWidth={scrollWidth}
              onScroll={() => setScrollWidth((width) => Math.max(width, measureScrollWidth()))}
              className={styles.treeSelect}
              checkable
              switcherIcon={null}
              onCheck={onCheck}
              checkedKeys={checkedKeys}
              treeData={treeData}
              titleRender={titleRender}
            />
          )}
        </div>
        {/* <div className={styles.bottomTips}>Press Enter or click outside the list to apply</div> */}
      </div>
    </div>
  );
};

export default NodeFiltering;
