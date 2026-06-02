import React, { memo, useEffect, useMemo, useState, createContext, useContext, useRef } from 'react';
import styles from './index.less';
import classnames from 'classnames';
import Iconfont from '@/components/Iconfont';
import { Tooltip, Dropdown } from 'antd';
import { ITreeNode } from '@/typings';
import { TreeNodeType, databaseMap } from '@/constants';
import { treeConfig, switchIcon, ITreeConfigItem } from './treeConfig';
import { useCommonStore } from '@/store/common';
import { setCurrentWorkspaceGlobalExtend } from '@/pages/main/workspace/store/common';
import { useWorkspaceStore } from '@/pages/main/workspace/store';
import LoadingGracile from '@/components/Loading/LoadingGracile';
import { setFocusId, setFocusTreeNode, useTreeStore, clearTreeStore } from './treeStore';
import { useGetRightClickMenu } from './hooks/useGetRightClickMenu';
import MenuLabel from '@/components/MenuLabel';
import LoadingContent from '@/components/Loading/LoadingContent';
import { cloneDeep } from 'lodash';
import sqlService from '@/service/sql';
// import { flushSync } from 'react-dom';

interface IProps {
  className?: string;
  treeData: ITreeNode[] | null;
  searchValue: string;
  refreshRootData?: (refresh?: boolean) => void;
}

interface TreeNodeIProps {
  data: ITreeNode;
  level: number;
  refreshRootData?: (refresh?: boolean) => void;
}

interface IContext {
  treeData: ITreeNode[];
  setTreeData: (value: ITreeNode[] | null) => void;
  searchTreeData: ITreeNode[] | null;
  setSearchTreeData: (value: ITreeNode[] | null) => void;
}

export const Context = createContext<IContext>({} as any);

// 树转平级
const smoothTree = (treeData: ITreeNode[], result: ITreeNode[] = [], parentNode?: ITreeNode) => {
  treeData.forEach((item) => {
    if (parentNode) {
      item.parentNode = parentNode;
      item.level = (parentNode.level || 0) + 1;
    }
    result.push(item);
    if (item.children) {
      smoothTree(item.children, result, item);
    }
  });
  return result;
};

// 平级转树
function tranListToTreeData(list:ITreeNode[], rootValue) {
  const arr:ITreeNode[] = []
  list.forEach((item:ITreeNode) => {
    if (item.parentNode?.uuid === rootValue) {
      arr.push(item)
      const children = tranListToTreeData(list, item.uuid)
      if (children.length) {
        item.children = children
      }
    }
  })
  return arr
}

// 判断是否匹配
const isMatch = (target: string, searchValue: string) => {
  const reg = new RegExp(searchValue, 'i');
  return reg.test(target || '');
};

// 树结构搜索
function searchTree(treeData: ITreeNode[], searchValue: string): ITreeNode[] {
  let result: ITreeNode[] = [];

  // 深度优先遍历
  function dfs(node: ITreeNode, path: ITreeNode[] = []) {
    if (isMatch(node.name, searchValue)) {
      result = [...result, ...path, node];
      return;
    }
    if (!node.children) return;
    node.children.forEach((child) => {
      dfs(child, [...path, node]);
    });
  }

  // 遍历树
  treeData.forEach((node) => dfs(node));

  // 根据uuid去重
  const deWeightList: ITreeNode[] = [];
  result.forEach((item) => {
    // 如果不匹配，说明该节点为path，不需要保留该节点的子元素，就把children置空
    if (!isMatch(item.name, searchValue)) {
      item.children = null;
    }
    deWeightList.findIndex((i) => i.uuid === item.uuid) === -1 && deWeightList.push(item);
  });

  return tranListToTreeData(deWeightList, undefined);
}

const itemHeight = 26; // 每个 item 的高度
const paddingCount = 2;

const Tree = (props: IProps) => {
  const { className, treeData: outerTreeData, searchValue, refreshRootData } = props;
  const [treeData, setTreeData] = useState<ITreeNode[] | null>(null);
  const [smoothTreeData, setSmoothTreeData] = useState<ITreeNode[]>([]);
  const [searchTreeData, setSearchTreeData] = useState<ITreeNode[] | null>(null); // 前端搜索结果
  const [searchSmoothTreeData, setSearchSmoothTreeData] = useState<ITreeNode[] | null>(null); // 前端搜索结果平级
  const [backendSmoothTreeData, setBackendSmoothTreeData] = useState<ITreeNode[] | null>(null); // 后端搜索结果平级
  const [backendSearchLoading, setBackendSearchLoading] = useState(false);
  const searchValueRef = useRef<string>('');

  const currentConnectionDetails = useWorkspaceStore((state) => state.currentConnectionDetails);

  const [scrollTop, setScrollTop] = useState(0); // 滚动位置 // 继续需要渲染的 item 索引有哪些

  const startIdx = useMemo(() => {
    let _startIdx = Math.floor(scrollTop / itemHeight);
    _startIdx = Math.max(_startIdx - paddingCount, 0); // 处理越界情况
    return _startIdx;
  }, [scrollTop]);

  const top = itemHeight * startIdx; // 第一个渲染的 item 到顶部距离

  // 清空treeStore
  useEffect(() => {
    return () => {
      clearTreeStore();
    }
  }, [searchValue]);

  useEffect(() => {
    setTreeData(outerTreeData);
    setScrollTop(0);
  }, [outerTreeData]);

  useEffect(() => {
    if (treeData) {
      const result: ITreeNode[] = [];
      smoothTree(treeData, result);
      setSmoothTreeData(result);
    } else {
      setSmoothTreeData([]);
    }
  }, [treeData]);

  // 搜索结果转平级
  useEffect(() => {
    if (searchTreeData) {
      const result: ITreeNode[] = [];
      smoothTree(searchTreeData, result);
      setSearchSmoothTreeData(result);
    } else {
      setSearchSmoothTreeData(null);
    }
  }, [searchTreeData]);

  const treeNodes = useMemo(() => {
    const realNodeList = (backendSmoothTreeData || searchSmoothTreeData || smoothTreeData)
      .slice(startIdx, startIdx + 50);
    return realNodeList.map((item) => {
      return <TreeNode key={item.uuid} level={item.level || 0} data={item} refreshRootData={refreshRootData} />;
    });
  }, [smoothTreeData, searchSmoothTreeData, backendSmoothTreeData, startIdx, refreshRootData]);

  useEffect(() => {
    if (searchValue && treeData) {
      searchValueRef.current = searchValue;
      const _searchTreeData = searchTree(cloneDeep(treeData), searchValue);
      
      const flatResult: ITreeNode[] = [];
      smoothTree(_searchTreeData, flatResult);
      const matchCount = flatResult.filter(item => isMatch(item.name, searchValue)).length;
      
      if (matchCount > 0) {
        setSearchTreeData(_searchTreeData);
        setBackendSmoothTreeData(null);
        setScrollTop(0);
      } else if (currentConnectionDetails?.id) {
        setSearchTreeData(null);
        setBackendSearchLoading(true);
        sqlService.searchTree({
          dataSourceId: currentConnectionDetails.id,
          dataSourceName: currentConnectionDetails.alias,
          databaseType: currentConnectionDetails.type,
          searchKey: searchValue,
        })
          .then((res) => {
            if (searchValueRef.current === searchValue) {
              const enrichedNodes = res.map(node => ({
                ...node,
                treeNodeType: node.treeNodeType?.toLowerCase() || node.treeNodeType,
                pretendNodeType: node.pretendNodeType?.toLowerCase() || node.pretendNodeType,
                extraParams: {
                  ...node.extraParams,
                  dataSourceId: currentConnectionDetails.id,
                  dataSourceName: currentConnectionDetails.alias,
                  databaseType: currentConnectionDetails.type,
                },
              }));
              const treeResult = buildTreeFromFlatData(enrichedNodes);
              const smoothResult: ITreeNode[] = [];
              smoothTree(treeResult, smoothResult);
              setBackendSmoothTreeData(smoothResult);
              setScrollTop(0);
            }
          })
          .catch(() => {
            if (searchValueRef.current === searchValue) {
              setBackendSmoothTreeData([]);
            }
          })
          .finally(() => {
            if (searchValueRef.current === searchValue) {
              setBackendSearchLoading(false);
            }
          });
      } else {
        setSearchTreeData(_searchTreeData);
        setBackendSmoothTreeData(null);
        setScrollTop(0);
      }
    } else {
      searchValueRef.current = '';
      setSearchTreeData(null);
      setBackendSmoothTreeData(null);
    }
  }, [searchValue, treeData, currentConnectionDetails]);

  function buildTreeFromFlatData(flatNodes: ITreeNode[]): ITreeNode[] {
    if (!flatNodes || flatNodes.length === 0) return [];
    
    const firstNode = flatNodes[0];
    const baseExtraParams = firstNode.extraParams || {};
    
    const map = new Map<string, ITreeNode>();
    const pathMap = new Map<string, ITreeNode>();
    const roots: ITreeNode[] = [];

    flatNodes.forEach(item => {
      map.set(item.uuid, { ...item, children: [] });
    });

    flatNodes.forEach(node => {
      if (node.parentPath && node.parentPath.length > 0) {
        let currentPath = '';
        node.parentPath.forEach((pathItem, index) => {
          const prevPath = currentPath;
          currentPath = prevPath ? `${prevPath}/${pathItem}` : pathItem;
          
          if (!pathMap.has(currentPath)) {
            const pathNode: ITreeNode = {
              uuid: `path-${currentPath}`,
              key: `path-${currentPath}`,
              name: pathItem,
              treeNodeType: index === 0 ? TreeNodeType.DATABASE : TreeNodeType.SCHEMA,
              pretendNodeType: index === 0 ? TreeNodeType.DATABASE : TreeNodeType.SCHEMA,
              isLeaf: false,
              children: [],
              extraParams: {
                ...baseExtraParams,
                databaseName: index === 0 ? pathItem : baseExtraParams.databaseName,
                schemaName: index === 1 ? pathItem : baseExtraParams.schemaName,
              },
            };
            pathMap.set(currentPath, pathNode);
          }
        });
      }
    });

    pathMap.forEach((pathNode, path) => {
      const pathParts = path.split('/');
      if (pathParts.length > 1) {
        const parentPath = pathParts.slice(0, -1).join('/');
        const parentNode = pathMap.get(parentPath);
        if (parentNode && parentNode.children) {
          parentNode.children.push(pathNode);
          pathNode.parentNode = parentNode;
        }
      } else {
        roots.push(pathNode);
      }
    });

    flatNodes.forEach(node => {
      const mappedNode = map.get(node.uuid);
      if (mappedNode) {
        if (node.parentPath && node.parentPath.length > 0) {
          const parentKey = node.parentPath.join('/');
          const parentNode = pathMap.get(parentKey);
          if (parentNode && parentNode.children) {
            parentNode.children.push(mappedNode);
            mappedNode.parentNode = parentNode;
          }
        } else {
          roots.push(mappedNode);
        }
      }
    });

    return roots;
  }

  return (
    <LoadingContent isLoading={!treeData || backendSearchLoading} className={classnames(className)}>
      <Context.Provider
        value={{
          treeData: treeData!,
          setTreeData: setTreeData!,
          searchTreeData, 
          setSearchTreeData
        }}
      >
        <div
          className={classnames(styles.scrollBox)}
          onScroll={(e: any) => {
            setScrollTop(e.target.scrollTop);
          }}
        >
          <div
            className={styles.treeListHolder}
            style={{ '--tree-node-count': (backendSmoothTreeData || searchSmoothTreeData || smoothTreeData)?.length } as any}
          >
            <div style={{ height: top }} />
            {treeNodes}
          </div>
        </div>
      </Context.Provider>
    </LoadingContent>
  );
};

const TreeNode = memo((props: TreeNodeIProps) => {
  const { data: treeNodeData, level, refreshRootData } = props;
  const [isLoading, setIsLoading] = useState(false);
  const indentArr = new Array(level).fill('indent');
  const { treeData, setTreeData, searchTreeData, setSearchTreeData } = useContext(Context);
  const abortControllerRef = useRef<AbortController | null>(null);
  const loadRequestIdRef = useRef(0);

  // 加载数据
  function loadData(_props?: {
    refresh?: boolean;
    pageNo?: number;
    lastDocId?: number;
    treeNodeData?: ITreeNode;
    deletedNodeName?: string;
  }) {
    const _treeNodeData = _props?.treeNodeData || props.data;
    const treeNodeConfig: ITreeConfigItem = treeConfig[_treeNodeData.pretendNodeType || _treeNodeData.treeNodeType];

    if (_props?.refresh) {
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
      }
      abortControllerRef.current = new AbortController();
    }

    const signal = abortControllerRef.current?.signal;
    const loadRequestId = loadRequestIdRef.current + 1;
    loadRequestIdRef.current = loadRequestId;

    setIsLoading(true);
    if (_props?.pageNo === 1 || !_props?.pageNo) {
      insertData(treeData!, _treeNodeData.uuid!, null,[treeData, setTreeData]);
      if(searchTreeData){
        insertData(searchTreeData!, _treeNodeData.uuid!, null,[searchTreeData, setSearchTreeData]);
      }
    }

    Promise.resolve()
      .then(
        () =>
          treeNodeConfig.getChildren?.({
            ..._treeNodeData.extraParams,
            extraParams: {
              ..._treeNodeData.extraParams,
            },
            refresh: _props?.refresh || false,
            pageNo: _props?.pageNo || 1,
            lastDocId: _props?.lastDocId,
          }, { signal }) || [],
      )
      .then((res: any) => {
        if (signal?.aborted) return;
        const filteredRes = filterDeletedNode(res, _props?.deletedNodeName);
        if (filteredRes?.length || filteredRes?.data) {
          if (filteredRes.data) {
            insertData(treeData!, _treeNodeData.uuid!, filteredRes.data, [treeData, setTreeData]);
            if(searchTreeData){
              insertData(searchTreeData!, _treeNodeData.uuid!, filteredRes.data,[searchTreeData, setSearchTreeData]);
            }
            if (filteredRes.hasNextPage) {
              loadData({
                refresh: _props?.refresh || false,
                pageNo: filteredRes.pageNo + 1,
                lastDocId: filteredRes.lastDocId,
                deletedNodeName: _props?.deletedNodeName,
              });
              return;
            }
          } else {
            insertData(treeData!, _treeNodeData.uuid!, filteredRes,[treeData, setTreeData]);
            if(searchTreeData){
              insertData(searchTreeData!, _treeNodeData.uuid!, filteredRes,[searchTreeData, setSearchTreeData]);
            }
          }
        } else {
          if (signal?.aborted) return;
          if (treeNodeConfig.next) {
            _treeNodeData.pretendNodeType = treeNodeConfig.next;
            loadData();
            return;
          } else {
            insertData(treeData!, _treeNodeData.uuid!, [],[treeData, setTreeData]);
            if(searchTreeData){
              insertData(searchTreeData!, _treeNodeData.uuid!, [],[searchTreeData, setSearchTreeData]);
            }
          }
        }
      })
      .catch((error) => {
        if (signal?.aborted || error?.name === 'AbortError') return;
      })
      .finally(() => {
        if (loadRequestIdRef.current !== loadRequestId) return;
        setIsLoading(false);
      });
  }

  useEffect(() => {
    return () => {
      loadRequestIdRef.current += 1;
      abortControllerRef.current?.abort();
    };
  }, []);

  const filterDeletedNode = (res: any, deletedNodeName?: string) => {
    if (!deletedNodeName) {
      return res;
    }
    if (res?.data) {
      return {
        ...res,
        data: res.data.filter((item: ITreeNode) => item.name !== deletedNodeName),
      };
    }
    if (Array.isArray(res)) {
      return res.filter((item: ITreeNode) => item.name !== deletedNodeName);
    }
    return res;
  };

  // 当前节点是否是focus
  const isFocus = useTreeStore((state) => state.focusId) === treeNodeData.uuid;

  //  在treeData中找到对应的节点，插入数据
  const insertData = (_treeData: ITreeNode[], uuid: string, data: any, originalDataList:any): ITreeNode | null => {
    const [originalData,setOriginalData] = originalDataList
    let result: ITreeNode | null = null;
    for (let i = 0; i < _treeData?.length; i++) {
      if (_treeData[i].uuid === uuid) {
        result = _treeData[i];
        if (data) {
          data.map((item: any) => {
            item.parentNode = result;
          });
          result.children = [...(result.children || []), ...(data || [])];
        } else {
          result.children = null;
        }
        setOriginalData?.(cloneDeep([...(originalData || [])]));
        break;
      } else {
        if (_treeData[i].children) {
          result = insertData(_treeData[i].children!, uuid, data, originalDataList);
          if (result) {
            break;
          }
        }
      }
    }
    return result;
  };

  //展开-收起
  const handleClick = () => {
    if (treeNodeData?.children) {
      insertData(treeData!, treeNodeData.uuid!, null,[treeData, setTreeData]);
      if(searchTreeData){
        insertData(searchTreeData!, treeNodeData.uuid!, null,[searchTreeData, setSearchTreeData]);
      }
    } else {
      loadData();
    }
  };

  // 找到对应的icon
  const recognizeIcon = (treeNodeType: TreeNodeType) => {
    if (treeNodeType === TreeNodeType.DATA_SOURCE) {
      return databaseMap[treeNodeData.extraParams!.databaseType!]?.icon;
    } else {
      return (
        switchIcon[treeNodeType]?.[treeNodeData.children ? 'unfoldIcon' : 'icon'] || switchIcon[treeNodeType]?.icon
      );
    }
  };

  // 点击节点
  const handelClickTreeNode = () => {
    useCommonStore.setState({
      focusedContent: (treeNodeData.name || '') as any,
    });
    if(treeNodeData.treeNodeType === TreeNodeType.TABLE){
      setCurrentWorkspaceGlobalExtend({
        code: 'viewDDL',
        uniqueData: {
          dataSourceId: treeNodeData.extraParams?.dataSourceId,
          dataSourceName: treeNodeData.extraParams?.dataSourceName,
          databaseName: treeNodeData.extraParams?.databaseName,
          databaseType: treeNodeData.extraParams?.databaseType,
          schemaName: treeNodeData.extraParams?.schemaName,
          tableName: treeNodeData.name,
        }
      });
    }
    setFocusId(treeNodeData.uuid || '');

    setFocusTreeNode({
      dataSourceId: treeNodeData.extraParams!.dataSourceId,
      dataSourceName: treeNodeData.extraParams!.dataSourceName,
      databaseType: treeNodeData.extraParams!.databaseType,
      databaseName: treeNodeData.extraParams?.databaseName,
      schemaName: treeNodeData.extraParams?.schemaName,
    });
  };

  // 双击节点
  const handelDoubleClickTreeNode = () => {
    if (
      treeNodeData.treeNodeType === TreeNodeType.TABLE ||
      treeNodeData.treeNodeType === TreeNodeType.VIEW ||
      treeNodeData.treeNodeType === TreeNodeType.PROCEDURE ||
      treeNodeData.treeNodeType === TreeNodeType.FUNCTION ||
      treeNodeData.treeNodeType === TreeNodeType.TRIGGER
    ) {
      rightClickMenu.find((item) => item.doubleClickTrigger)?.onClick(treeNodeData);
    } else {
      handleClick();
    }
  };

  const rightClickMenu = useGetRightClickMenu({
    treeNodeData,
    loadData,
    refreshRootData,
  });

  const treeNodeDom = useMemo(() => {
    const buildMenuItem = (item: any): any => {
      const menuItem: any = {
        key: item.key,
        label: <MenuLabel icon={item.labelProps.icon} label={item.labelProps.label} />,
      };
      if (item.children && item.children.length > 0) {
        menuItem.children = item.children.map(buildMenuItem);
      } else {
        menuItem.onClick = () => {
          item.onClick(treeNodeData);
        };
      }
      return menuItem;
    };
    const dropdownsItems: any = rightClickMenu.map(buildMenuItem);
    return (
      <Dropdown
        trigger={['contextMenu']}
        menu={{
          items: dropdownsItems,
          style: dropdownsItems?.length ? {} : { display: 'none' }, // 有菜单项才显示
        }}
        overlayStyle={{
          zIndex: 1080,
        }}
      >
        <Tooltip placement="right" color={window._AppThemePack?.colorPrimary} title={treeNodeData.comment}>
          <div
            className={classnames(styles.treeNode, { [styles.treeNodeFocus]: isFocus })}
            onClick={handelClickTreeNode}
            onContextMenu={handelClickTreeNode}
            onDoubleClick={handelDoubleClickTreeNode}
            data-chat2db-general-can-copy-element
          >
            <div className={styles.left}>
              {indentArr.map((item, i) => {
                return <div key={i} className={styles.indent} />;
              })}
            </div>
            <div className={styles.right}>
              {!treeNodeData.isLeaf && (
                <div onClick={handleClick} className={classnames(styles.arrows, { [styles.loadingArrows]: isLoading })}>
                  {isLoading ? (
                    <LoadingGracile />
                  ) : (
                    <Iconfont
                      className={classnames(styles.arrowsIcon, {
                        [styles.rotateArrowsIcon]: treeNodeData.children,
                      })}
                      code="&#xe641;"
                    />
                  )}
                </div>
              )}
              <div className={styles.dblclickArea}>
                <div className={styles.typeIcon}>
                  <Iconfont code={recognizeIcon(treeNodeData.treeNodeType)!} />
                </div>
                <div className={styles.contentText}>
                  <div className={styles.name} dangerouslySetInnerHTML={{ __html: treeNodeData.name }} />
                  {treeNodeData.treeNodeType === TreeNodeType.COLUMN && (
                    <div className={styles.type}>
                      {/* 转小写 */}
                      {treeNodeData.columnType?.toLowerCase()}
                    </div>
                  )}
                </div>
              </div>
            </div>
          </div>
        </Tooltip>
      </Dropdown>
    );
  }, [isFocus, isLoading, rightClickMenu, treeNodeData.children]);

  return treeNodeDom;
});

export default memo(Tree);
