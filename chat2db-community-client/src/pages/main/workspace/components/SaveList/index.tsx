import React, { useState, useEffect, useRef, useMemo } from 'react';
import i18n from '@/i18n';
import { Input, Modal } from 'antd';
import { ChevronRight, Search, Trash2 } from 'lucide-react';
import { IconButton, IconfontSvg } from '@chat2db/ui';
import PortalContextMenu from '@/components/ContextMenu/PortalContextMenu';
import type { ContextMenuAction, ContextMenuEntry, ContextMenuIntent } from '@/components/ContextMenu/core';
import LoadingContent from '@/components/Loading/LoadingContent';
import historyServer from '@/service/history';
import { ConsoleOpenedStatus, getDatabaseInfo } from '@/constants';
import { IConsole } from '@/typings';
import { useStyles } from './style';
import { useWorkspaceStore } from '@/store/workspace';
import MenuLabel from '@/components/MenuLabel';
import { emitSavedConsoleRecordUpdated } from '@/utils/savedConsoleEvents';

type SavedConsoleTreeNodeType = 'dataSource' | 'database' | 'schema' | 'console';

interface SavedConsoleTreeNode {
  key: string;
  title: string;
  type: SavedConsoleTreeNodeType;
  databaseType?: IConsole['type'];
  console?: IConsole;
  children?: SavedConsoleTreeNode[];
}

interface SavedConsoleContextSnapshot {
  consoleId: IConsole['id'];
  name: string;
}

type SavedConsoleContextIntent = ContextMenuIntent<SavedConsoleContextSnapshot>;

const escapeRegExp = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

function collectGroupKeys(nodes: SavedConsoleTreeNode[]) {
  const keys: string[] = [];
  nodes.forEach((node) => {
    if (node.children?.length) {
      keys.push(node.key);
      keys.push(...collectGroupKeys(node.children));
    }
  });
  return keys;
}

function filterTree(nodes: SavedConsoleTreeNode[], keyword: string): SavedConsoleTreeNode[] {
  const normalizedKeyword = keyword.trim().toLowerCase();
  if (!normalizedKeyword) {
    return nodes;
  }

  return nodes
    .map((node) => {
      const selfMatched = node.title.toLowerCase().includes(normalizedKeyword);
      if (selfMatched) {
        return node;
      }

      const children = node.children ? filterTree(node.children, keyword) : [];
      if (children.length) {
        return {
          ...node,
          children,
        };
      }

      return null;
    })
    .filter(Boolean) as SavedConsoleTreeNode[];
}

function createConsoleTree(consoleList?: IConsole[] | null): SavedConsoleTreeNode[] {
  const dataSourceMap = new Map<string, SavedConsoleTreeNode>();

  (consoleList || []).forEach((item) => {
    const dataSourceId = item.dataSourceId ?? 'unknown';
    const dataSourceName = item.dataSourceName || i18n('workspace.savedConsole.unknownDataSource');
    const databaseName = item.databaseName || i18n('workspace.savedConsole.unknownDatabase');
    const schemaName = item.schemaName;

    const dataSourceKey = `dataSource_${dataSourceId}`;
    if (!dataSourceMap.has(dataSourceKey)) {
      dataSourceMap.set(dataSourceKey, {
        key: dataSourceKey,
        title: dataSourceName,
        type: 'dataSource',
        databaseType: item.type,
        children: [],
      });
    }

    const dataSourceNode = dataSourceMap.get(dataSourceKey)!;
    if (!dataSourceNode.databaseType && item.type) {
      dataSourceNode.databaseType = item.type;
    }

    const databaseKey = `${dataSourceKey}-database_${databaseName}`;
    let databaseNode = dataSourceNode.children!.find((node) => node.key === databaseKey);
    if (!databaseNode) {
      databaseNode = {
        key: databaseKey,
        title: databaseName,
        type: 'database',
        databaseType: item.type,
        children: [],
      };
      dataSourceNode.children!.push(databaseNode);
    }

    const consoleNode: SavedConsoleTreeNode = {
      key: `${schemaName ? `${databaseKey}-schema_${schemaName}` : databaseKey}-console_${item.id}`,
      title: item.name,
      type: 'console',
      console: item,
    };

    if (!schemaName) {
      databaseNode.children!.push(consoleNode);
      return;
    }

    const schemaKey = `${databaseKey}-schema_${schemaName}`;
    let schemaNode = databaseNode.children!.find((node) => node.key === schemaKey);
    if (!schemaNode) {
      schemaNode = {
        key: schemaKey,
        title: schemaName,
        type: 'schema',
        children: [],
      };
      databaseNode.children!.push(schemaNode);
    }
    schemaNode.children!.push(consoleNode);
  });

  return Array.from(dataSourceMap.values());
}

const SaveList = () => {
  const {
    styles,
    cx,
    theme: { appearance },
  } = useStyles();
  const [searching, setSearching] = useState<boolean>(false);
  const [searchKeyword, setSearchKeyword] = useState('');
  const [expandedKeys, setExpandedKeys] = useState<string[]>([]);
  const inputRef = useRef<any>();
  const hasInitializedExpandedKeysRef = useRef(false);
  const previousGroupKeysRef = useRef<string[]>([]);
  const consoleList = useWorkspaceStore((state) => state.savedConsoleList);
  const addWorkspaceTab = useWorkspaceStore((state) => state.addWorkspaceTab);
  const getSavedConsoleList = useWorkspaceStore((state) => state.getSavedConsoleList);
  const removeSavedConsole = useWorkspaceStore((state) => state.removeSavedConsole);
  const [editData, setEditData] = useState<IConsole | null>(null);
  const [contextMenu, setContextMenu] = useState<SavedConsoleContextIntent | null>(null);

  const consoleTree = useMemo(() => createConsoleTree(consoleList), [consoleList]);
  const filteredTree = useMemo(() => filterTree(consoleTree, searchKeyword), [consoleTree, searchKeyword]);
  const visibleExpandedKeys = useMemo(() => {
    if (searchKeyword.trim()) {
      return collectGroupKeys(filteredTree);
    }
    return expandedKeys;
  }, [expandedKeys, filteredTree, searchKeyword]);
  const contextMenuConsole = contextMenu
    ? consoleList?.find((item) => item.id === contextMenu.targetSnapshot.consoleId)
    : undefined;
  const contextMenuActions = contextMenuConsole ? createSavedConsoleContextActions(contextMenuConsole) : [];

  useEffect(() => {
    getSavedConsoleList();
  }, []);

  useEffect(() => {
    const groupKeys = collectGroupKeys(consoleTree);
    const previousGroupKeys = previousGroupKeysRef.current;
    previousGroupKeysRef.current = groupKeys;

    if (!hasInitializedExpandedKeysRef.current) {
      hasInitializedExpandedKeysRef.current = true;
      setExpandedKeys(groupKeys);
      return;
    }

    const groupKeySet = new Set(groupKeys);
    const previousGroupKeySet = new Set(previousGroupKeys);
    const newGroupKeys = groupKeys.filter((key) => !previousGroupKeySet.has(key));

    setExpandedKeys((keys) => {
      const nextKeys = keys.filter((key) => groupKeySet.has(key));
      newGroupKeys.forEach((key) => {
        if (!nextKeys.includes(key)) {
          nextKeys.push(key);
        }
      });
      return nextKeys;
    });
  }, [consoleTree]);

  useEffect(() => {
    if (searching) {
      inputRef.current!.focus({ cursor: 'start' });
    }
  }, [searching]);

  function onBlur() {
    if (!inputRef.current.input.value) {
      setSearching(false);
      setSearchKeyword('');
    }
  }

  function onChange(value: string) {
    setSearchKeyword(value);
  }

  function openConsole(item: IConsole) {
    const params: any = {
      id: item.id,
      tabOpened: ConsoleOpenedStatus.IS_OPEN,
    };
    historyServer.updateSavedConsole(params).then(() => {
      addWorkspaceTab({
        id: item.id,
        type: item.operationType,
        title: item.name,
        uniqueData: {
          consoleId: item.id,
          dataSourceId: item.dataSourceId,
          dataSourceName: item.dataSourceName,
          databaseType: item.type,
          databaseName: item.databaseName,
          schemaName: item.schemaName,
          status: item.status,
          ddl: item.ddl,
          connectable: item.connectable,
        },
      });
    });
  }

  function deleteSaved(data: IConsole) {
    removeSavedConsole(data.id).then(() => {
      emitSavedConsoleRecordUpdated(data);
    });
  }

  const editSaved = (data: IConsole) => {
    setEditData(data);
  };

  function closeContextMenu() {
    setContextMenu(null);
  }

  function isContextMenuTargetCurrent(intent: SavedConsoleContextIntent) {
    const currentConsole = consoleList?.find((item) => item.id === intent.targetSnapshot.consoleId);
    return !!currentConsole && currentConsole.name === intent.targetSnapshot.name;
  }

  function createContextMenuAction(
    action: Omit<ContextMenuAction<SavedConsoleContextIntent>, 'validateBeforeExecute'>,
  ): ContextMenuAction<SavedConsoleContextIntent> {
    return {
      ...action,
      validateBeforeExecute: isContextMenuTargetCurrent,
    };
  }

  function createSavedConsoleContextActions(consoleItem: IConsole): ContextMenuEntry<SavedConsoleContextIntent>[] {
    return [
      createContextMenuAction({
        id: 'open',
        label: <MenuLabel icon="&#xec83;" label={i18n('common.button.open')} />,
        execute: () => openConsole(consoleItem),
      }),
      createContextMenuAction({
        id: 'edit',
        label: <MenuLabel icon="&#xe602;" label={i18n('common.text.rename')} />,
        execute: () => editSaved(consoleItem),
      }),
      createContextMenuAction({
        id: 'delete',
        label: <MenuLabel icon="&#xe6a7;" label={i18n('common.button.delete')} />,
        execute: () => deleteSaved(consoleItem),
      }),
    ];
  }

  function toggleTreeNode(key: string) {
    setExpandedKeys((keys) => {
      if (keys.includes(key)) {
        return keys.filter((item) => item !== key);
      }
      return [...keys, key];
    });
  }

  function renderHighlightedTitle(text: string) {
    const keyword = searchKeyword.trim();
    if (!keyword) {
      return text;
    }

    const parts = text.split(new RegExp(`(${escapeRegExp(keyword)})`, 'gi'));
    return parts.map((part, index) => {
      if (part.toLowerCase() === keyword.toLowerCase()) {
        return (
          <span className={styles.searchHighlight} key={`${part}-${index}`}>
            {part}
          </span>
        );
      }
      return <React.Fragment key={`${part}-${index}`}>{part}</React.Fragment>;
    });
  }

  function renderNodeIcon(node: SavedConsoleTreeNode) {
    if (node.type === 'dataSource') {
      const databaseInfo = getDatabaseInfo(node.databaseType);

      if (databaseInfo?.icon) {
        return (
          <IconfontSvg
            size={19}
            code={databaseInfo.icon}
            existDark={databaseInfo.iconExistDark}
            appearance={appearance}
          />
        );
      }

      return <IconfontSvg size={19} code="icon-newdatabase" />;
    }

    if (node.type === 'database') {
      return <IconfontSvg size={19} code="icon-database" />;
    }

    if (node.type === 'schema') {
      return <IconfontSvg size={19} code="icon-schema" appearance={appearance} />;
    }

    return <IconfontSvg size={19} code="icon-code-rimless" />;
  }

  function renderTreeNode(node: SavedConsoleTreeNode, level = 0): React.ReactNode {
    const hasChildren = !!node.children?.length;
    const expanded = visibleExpandedKeys.includes(node.key);
    const row = (
      <div
        className={cx(
          styles.treeRow,
          node.type === 'dataSource' && styles.dataSourceRow,
          node.type === 'console' && styles.consoleRow,
        )}
        style={{ paddingLeft: 8 + level * 14 }}
        onDoubleClick={() => {
          if (hasChildren) {
            toggleTreeNode(node.key);
          } else if (node.console) {
            openConsole(node.console);
          }
        }}
        onContextMenu={(event) => {
          if (!node.console) {
            return;
          }
          event.preventDefault();
          event.stopPropagation();
          setContextMenu({
            surface: 'savedConsole',
            pointer: {
              x: event.clientX,
              y: event.clientY,
            },
            targetSnapshot: {
              consoleId: node.console.id,
              name: node.console.name,
            },
            version: `${node.console.id}:${node.console.name}`,
          });
        }}
      >
        <button
          className={styles.switcherButton}
          type="button"
          onClick={(event) => {
            event.stopPropagation();
            if (hasChildren) {
              toggleTreeNode(node.key);
            }
          }}
          onDoubleClick={(event) => {
            event.stopPropagation();
          }}
        >
          {hasChildren && (
            <ChevronRight size={14} className={cx(styles.switcherIcon, expanded && styles.switcherIconExpanded)} />
          )}
        </button>
        <span className={styles.treeNodeIcon}>{renderNodeIcon(node)}</span>
        <span className={cx(styles.treeNodeTitle, 'save-tree-title')}>{renderHighlightedTitle(node.title)}</span>
        {node.console && (
          <span
            className={cx(styles.saveItemDelete, 'save-item-delete')}
            onClick={(event) => {
              event.stopPropagation();
              deleteSaved(node.console!);
            }}
          >
            <Trash2 size={14} />
          </span>
        )}
      </div>
    );

    return (
      <div key={node.key}>
        {row}
        {hasChildren && expanded && <div>{node.children!.map((child) => renderTreeNode(child, level + 1))}</div>}
      </div>
    );
  }

  return (
    <>
      <PortalContextMenu intent={contextMenu} actions={contextMenuActions} onClose={closeContextMenu} />
      <div className={styles.saveModule}>
        <div className={styles.header}>
          <div className={cx(styles.headerContent, searching && styles.headerContentHidden)}>
            <div>{i18n('workspace.title.savedConsole')}</div>
            <IconButton size={{ boxSize: 24, iconSize: 14 }} onClick={() => setSearching(true)} icon={Search} />
          </div>
          <div className={cx(styles.headerSearch, !searching && styles.headerSearchHidden)}>
            <Input
              ref={inputRef}
              size="small"
              placeholder={i18n('common.text.search')}
              prefix={<Search size={14} />}
              onBlur={onBlur}
              onChange={(e) => onChange(e.target.value)}
              allowClear
            />
          </div>
        </div>
        <div className={styles.saveBoxList}>
          <LoadingContent className={styles.loadingContent} data={consoleList ? filteredTree : consoleList} handleEmpty>
            <div className={styles.treeList}>{filteredTree.map((node) => renderTreeNode(node))}</div>
          </LoadingContent>
        </div>
      </div>
      <Modal
        title={i18n('common.text.rename')}
        open={!!editData}
        onOk={() => {
          const renamedConsole = editData;
          if (!renamedConsole) {
            return;
          }
          const params: any = {
            id: renamedConsole.id,
            name: renamedConsole.name,
          };
          historyServer.updateSavedConsole(params).then(() => {
            getSavedConsoleList();
            emitSavedConsoleRecordUpdated(renamedConsole);
            setEditData(null);
          });
        }}
        maskClosable={false}
        onCancel={() => setEditData(null)}
      >
        <Input
          value={editData?.name}
          onChange={(e) => {
            setEditData((current) => (current ? { ...current, name: e.target.value } : current));
          }}
        />
      </Modal>
    </>
  );
};

export default SaveList;
