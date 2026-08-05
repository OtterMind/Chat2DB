import feedback from '@/utils/feedback';
import { IconButton, IconfontSvg } from '@chat2db/ui';
import { useSize } from 'ahooks';
import { Button, Dropdown, Input, Modal, Tooltip, type InputRef, type MenuProps } from 'antd';
import {
  ChevronRight,
  ChevronsDownUp,
  ChevronsUpDown,
  FilePlus,
  Folder,
  FolderInput,
  FolderOpen,
  FolderPlus,
  MoreHorizontal,
  RefreshCw,
} from 'lucide-react';
import React, { forwardRef, useEffect, useImperativeHandle, useMemo, useState } from 'react';

import PortalContextMenu from '@/components/ContextMenu/PortalContextMenu';
import type { ContextMenuAction, ContextMenuEntry, ContextMenuIntent } from '@/components/ContextMenu/core';
import { LOCAL_SQL_FILE_SAVED_EVENT, LOCAL_SQL_SESSION_DRAG_TYPE, WorkspaceTabType } from '@/constants';
import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import { DEFAULT_TERMINAL_SETTINGS } from '@/constants/terminal';
import {
  getEffectiveShortcutConfigMap,
  isShortcutEventMatch,
  ShortcutAction,
  ShortcutOverrides,
} from '@/constants/shortcut';
import i18n from '@/i18n';
import jcefApi from '@/jcef';
import { useGlobalStore } from '@/store/global';
import { useWorkspaceStore } from '@/store/workspace';
import { getFileManagerLabelKey } from '@/utils/fileManagerLabel';
import { getLocalTextFileIcon, LOCAL_TEXT_FILE_ICON_MAP, SQL_FILE_EXTENSION_NAME } from '../../utils/localTextFile';
import { useStyles } from './style';
import { LocalSQLFileTreeCreateType, LocalSQLFileTreeNode, LocalSQLFileTreeNodeType } from './type';

interface LocalSQLFileTreeNodeSearchResult {
  node: LocalSQLFileTreeNode;
  parent?: LocalSQLFileTreeNode;
}

interface CreatingNode {
  parentKey: string;
  parentRelativePath: string;
  rootToken: string;
  type: LocalSQLFileTreeCreateType;
  name: string;
  loading?: boolean;
}

interface RenamingNode {
  key: string;
  rootToken: string;
  relativePath: string;
  type: LocalSQLFileTreeNodeType;
  name: string;
  loading?: boolean;
}

interface CreateSqlDirectoryChildResult {
  createdNode: LocalSQLFileTreeNode;
  children: LocalSQLFileTreeNode[];
}

interface RenameSqlDirectoryChildResult {
  renamedNode: LocalSQLFileTreeNode;
  parentRelativePath: string;
  children: LocalSQLFileTreeNode[];
}

interface DeleteSqlDirectoryChildResult {
  parentRelativePath: string;
  children: LocalSQLFileTreeNode[];
}

interface SaveSqlDirectoryFileResult {
  createdNode: LocalSQLFileTreeNode;
  children: LocalSQLFileTreeNode[];
}

interface DraggedSessionPayload {
  id: string | number;
  title: string;
  content: string;
}

interface LocalSQLFileTreeContextSnapshot {
  nodeKey: string;
  nodeType: LocalSQLFileTreeNodeType;
  path: string;
  relativePath: string;
  rootToken: string;
}

type LocalSQLFileTreeContextIntent = ContextMenuIntent<LocalSQLFileTreeContextSnapshot>;

interface LocalSQLFileTreeProps {
  active?: boolean;
}

export interface LocalSQLFileTreeRef {
  locateFile: (filePath: string) => boolean;
}

const SQL_FILE_EXTENSION = '.sql';
const LOCAL_SQL_DIRECTORY_PATH_STORAGE_KEY = runtimeEditionConfig.localSqlDirectoryPathStorageKey;
const LOCAL_SQL_DIRECTORY_PATHS_STORAGE_KEY = runtimeEditionConfig.localSqlDirectoryPathsStorageKey;
const LOCAL_SQL_TOOLBAR_EXPANDED_MIN_WIDTH = 150;
const LOCAL_SQL_TOOLBAR_BUTTON_SIZE = { boxSize: 24, iconSize: 16 };
const LOCAL_SQL_TREE_BASE_INDENT = 0;
const LOCAL_SQL_TREE_LEVEL_INDENT = 14;
const LOCAL_SQL_FILE_TREE_REVEAL_LABEL = getFileManagerLabelKey('workspace');

const normalizeComparablePath = (value: string) => value.replace(/\\/g, '/').replace(/\/+$/, '');

const isSameOrChildPath = (filePath: string, targetPath: string, targetType: LocalSQLFileTreeNodeType) => {
  const normalizedFilePath = normalizeComparablePath(filePath);
  const normalizedTargetPath = normalizeComparablePath(targetPath);
  if (targetType === 'file') {
    return normalizedFilePath === normalizedTargetPath;
  }
  return normalizedFilePath === normalizedTargetPath || normalizedFilePath.startsWith(`${normalizedTargetPath}/`);
};

const replacePathPrefix = (filePath: string, sourcePath: string, targetPath: string) => {
  const normalizedFilePath = filePath.replace(/\\/g, '/');
  const normalizedSourcePath = normalizeComparablePath(sourcePath);
  const suffix =
    normalizeComparablePath(filePath) === normalizedSourcePath
      ? ''
      : normalizedFilePath.slice(normalizedSourcePath.length);
  const separator = targetPath.includes('\\') ? '\\' : '/';
  return `${targetPath}${suffix.replace(/\//g, separator)}`;
};

const getParentPath = (filePath: string) => {
  const separatorIndex = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
  return separatorIndex > 0 ? filePath.slice(0, separatorIndex) : '';
};

const isSameOrChildKey = (key: string, targetKey: string) =>
  key === targetKey ||
  (targetKey.endsWith(':') && key.startsWith(targetKey)) ||
  key.startsWith(`${targetKey}/`) ||
  key.startsWith(`${targetKey}\\`);

const hasFileExtension = (name: string) => {
  const dotIndex = name.lastIndexOf('.');
  return dotIndex >= 0 && dotIndex < name.length - 1;
};

const normalizeNodeName = (name: string, type: LocalSQLFileTreeNodeType) => {
  const trimmedName = name.trim();
  if (type === 'file' && !hasFileExtension(trimmedName)) {
    return `${trimmedName}${SQL_FILE_EXTENSION}`;
  }
  return trimmedName;
};

const getFileNameSelectionEnd = (name: string) => {
  if (!hasFileExtension(name)) {
    return name.length;
  }
  return name.lastIndexOf('.');
};

const getLocalTextFileNodeIcon = (node: Pick<LocalSQLFileTreeNode, 'name' | 'fileExtension'>) => {
  return getLocalTextFileIcon(node.fileExtension);
};

const isEditableTarget = (target: EventTarget | null) => {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  return !!target.closest('input, textarea, [contenteditable="true"], [contenteditable=""]');
};

const selectInputName = (input: HTMLInputElement, name: string, type: LocalSQLFileTreeNodeType) => {
  input.focus();
  if (type === 'file') {
    input.setSelectionRange(0, getFileNameSelectionEnd(name));
    return;
  }
  input.select();
};

const getRootPath = (node: LocalSQLFileTreeNode) => node.rootPath || node.path;

const getComparableRootPath = (path?: string) => normalizeComparablePath(path || '').toLowerCase();

const normalizeRootNode = (node: LocalSQLFileTreeNode): LocalSQLFileTreeNode => ({
  ...node,
  hasChildren: !!node.children?.length,
});

const dedupeRootNodes = (nodes: LocalSQLFileTreeNode[]) => {
  const visitedPaths = new Set<string>();
  return nodes.filter((node) => {
    const comparablePath = getComparableRootPath(getRootPath(node));
    if (!comparablePath || visitedPaths.has(comparablePath)) {
      return false;
    }
    visitedPaths.add(comparablePath);
    return true;
  });
};

const readPersistedRootPaths = () => {
  const rawPaths = window.localStorage.getItem(LOCAL_SQL_DIRECTORY_PATHS_STORAGE_KEY);
  const legacyPath = window.localStorage.getItem(LOCAL_SQL_DIRECTORY_PATH_STORAGE_KEY);
  const paths: string[] = [];

  if (rawPaths) {
    try {
      const parsedPaths = JSON.parse(rawPaths);
      if (Array.isArray(parsedPaths)) {
        paths.push(...parsedPaths.filter((path) => typeof path === 'string' && path.trim()));
      }
    } catch (error) {
      console.error('parse sql directory paths error', error);
    }
  }

  if (legacyPath) {
    paths.push(legacyPath);
  }

  const visitedPaths = new Set<string>();
  return paths.filter((path) => {
    const comparablePath = getComparableRootPath(path);
    if (!comparablePath || visitedPaths.has(comparablePath)) {
      return false;
    }
    visitedPaths.add(comparablePath);
    return true;
  });
};

function updateTreeNode(
  nodes: LocalSQLFileTreeNode[],
  key: string,
  updater: (node: LocalSQLFileTreeNode) => LocalSQLFileTreeNode,
): LocalSQLFileTreeNode[] {
  return nodes.map((node) => {
    if (node.key === key) {
      return updater(node);
    }

    if (node.children?.length) {
      return {
        ...node,
        children: updateTreeNode(node.children, key, updater),
      };
    }

    return node;
  });
}

function findTreeNode(
  nodes: LocalSQLFileTreeNode[],
  predicate: (node: LocalSQLFileTreeNode) => boolean,
  parent?: LocalSQLFileTreeNode,
): LocalSQLFileTreeNodeSearchResult | undefined {
  for (const node of nodes) {
    if (predicate(node)) {
      return { node, parent };
    }

    if (node.children?.length) {
      const result = findTreeNode(node.children, predicate, node);
      if (result) {
        return result;
      }
    }
  }
  return undefined;
}

function findTreeNodeWithAncestors(
  nodes: LocalSQLFileTreeNode[],
  predicate: (node: LocalSQLFileTreeNode) => boolean,
  ancestors: string[] = [],
): { node: LocalSQLFileTreeNode; ancestors: string[] } | undefined {
  for (const node of nodes) {
    if (predicate(node)) {
      return { node, ancestors };
    }

    if (node.children?.length) {
      const result = findTreeNodeWithAncestors(node.children, predicate, [...ancestors, node.key]);
      if (result) {
        return result;
      }
    }
  }
  return undefined;
}

function getExpandableDirectoryKeys(nodes: LocalSQLFileTreeNode[]) {
  const keys: string[] = [];

  nodes.forEach((node) => {
    if (node.type !== 'directory' || !node.children?.length) {
      return;
    }
    keys.push(node.key);
    keys.push(...getExpandableDirectoryKeys(node.children));
  });

  return keys;
}

function getDefaultCreateName(parent: LocalSQLFileTreeNode, type: LocalSQLFileTreeCreateType) {
  const childNames = new Set((parent.children || []).map((child) => child.name.toLowerCase()));
  const baseName = type === 'file' ? 'untitled' : 'New Folder';
  const extension = type === 'file' ? '.sql' : '';
  let index = 0;
  let nextName = `${baseName}${extension}`;

  while (childNames.has(nextName.toLowerCase())) {
    index += 1;
    nextName = `${baseName}-${index}${extension}`;
  }

  return nextName;
}

const LocalSQLFileTree = forwardRef<LocalSQLFileTreeRef, LocalSQLFileTreeProps>(({ active = true }, ref) => {
  const { styles } = useStyles();
  const [modal, modalContextHolder] = Modal.useModal();
  const fileTreeModuleRef = React.useRef<HTMLDivElement>(null);
  const headerRef = React.useRef<HTMLDivElement>(null);
  const headerSize = useSize(headerRef);
  const createInputRef = React.useRef<InputRef>(null);
  const renameInputRef = React.useRef<InputRef>(null);
  const creatingRef = React.useRef<CreatingNode | null>(null);
  const renamingRef = React.useRef<RenamingNode | null>(null);
  const [rootNodes, setRootNodes] = useState<LocalSQLFileTreeNode[]>([]);
  const [expandedKeys, setExpandedKeys] = useState<string[]>([]);
  const [selectedNodeKey, setSelectedNodeKey] = useState<string>();
  const [selecting, setSelecting] = useState(false);
  const [addingRoot, setAddingRoot] = useState(false);
  const [restoring, setRestoring] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [creatingNode, setCreatingNode] = useState<CreatingNode | null>(null);
  const [renamingNode, setRenamingNode] = useState<RenamingNode | null>(null);
  const [contextMenu, setContextMenu] = useState<LocalSQLFileTreeContextIntent | null>(null);
  const [dropTargetKey, setDropTargetKey] = useState<string>();
  const selectedRowRef = React.useRef<HTMLDivElement | null>(null);
  const shortcutOverrides = useGlobalStore((state) => state.shortcutOverrides);
  const terminalShellId = useGlobalStore((state) => state.terminalSettings.shellId);
  const terminalOpenPosition = useGlobalStore(
    (state) => state.terminalSettings.openPosition || DEFAULT_TERMINAL_SETTINGS.openPosition,
  );
  const shortcutConfig = useMemo(
    () => getEffectiveShortcutConfigMap(shortcutOverrides as ShortcutOverrides),
    [shortcutOverrides],
  );
  const readFile = useWorkspaceStore((state) => state.readFile);
  const { activeConsoleId, workspaceTabList, setWorkspaceTabList, setActiveConsoleId, deleteEditor } =
    useWorkspaceStore((state) => ({
      activeConsoleId: state.activeConsoleId,
      workspaceTabList: state.workspaceTabList,
      setWorkspaceTabList: state.setWorkspaceTabList,
      setActiveConsoleId: state.setActiveConsoleId,
      deleteEditor: state.deleteEditor,
    }));

  const selectedNodeResult = useMemo(() => {
    if (!rootNodes.length || !selectedNodeKey) {
      return undefined;
    }
    return findTreeNode(rootNodes, (node) => node.key === selectedNodeKey);
  }, [rootNodes, selectedNodeKey]);
  const expandableDirectoryKeys = useMemo(() => getExpandableDirectoryKeys(rootNodes), [rootNodes]);
  const shouldExpandAll = expandableDirectoryKeys.some((key) => !expandedKeys.includes(key));

  const contextMenuNode = contextMenu
    ? findTreeNode(rootNodes, (node) => node.key === contextMenu.targetSnapshot.nodeKey)?.node
    : undefined;
  const contextMenuActions = contextMenuNode ? createContextMenuActions(contextMenuNode) : [];

  function locateFile(filePath: string) {
    if (!rootNodes.length || !filePath) {
      return false;
    }

    const normalizedTargetPath = normalizeComparablePath(filePath).toLowerCase();
    const result = findTreeNodeWithAncestors(
      rootNodes,
      (node) => node.type === 'file' && normalizeComparablePath(node.path).toLowerCase() === normalizedTargetPath,
    );

    if (!result) {
      return false;
    }

    setSelectedNodeKey(result.node.key);
    setExpandedKeys((keys) => Array.from(new Set([...keys, ...result.ancestors])));
    return true;
  }

  useImperativeHandle(
    ref,
    () => ({
      locateFile,
    }),
    [rootNodes],
  );

  useEffect(() => {
    const selectedRow = selectedRowRef.current;
    if (!selectedRow) {
      return;
    }

    window.requestAnimationFrame(() => {
      try {
        selectedRow.scrollIntoView({ block: 'nearest' });
      } catch {
        selectedRow.scrollIntoView(false);
      }
    });
  }, [selectedNodeKey, expandedKeys]);

  useEffect(() => {
    if (!creatingNode) {
      return;
    }
    window.setTimeout(() => {
      const input = createInputRef.current?.input;
      if (!input) {
        return;
      }
      selectInputName(input, creatingNode.name, creatingNode.type);
      window.requestAnimationFrame(() => selectInputName(input, creatingNode.name, creatingNode.type));
    }, 0);
  }, [creatingNode?.parentKey, creatingNode?.type]);

  useEffect(() => {
    creatingRef.current = creatingNode;
  }, [creatingNode]);

  useEffect(() => {
    if (!renamingNode) {
      return;
    }
    window.setTimeout(() => {
      const input = renameInputRef.current?.input;
      if (!input) {
        return;
      }
      selectInputName(input, renamingNode.name, renamingNode.type);
      window.requestAnimationFrame(() => selectInputName(input, renamingNode.name, renamingNode.type));
    }, 0);
  }, [renamingNode?.key]);

  useEffect(() => {
    renamingRef.current = renamingNode;
  }, [renamingNode]);

  useEffect(() => {
    const savedRootPaths = readPersistedRootPaths();
    if (!savedRootPaths.length) {
      return;
    }

    let disposed = false;
    setRestoring(true);
    Promise.allSettled(savedRootPaths.map((path) => jcefApi.openSqlDirectory({ path })))
      .then((results) => {
        if (disposed) {
          return;
        }

        const restoredRoots = results
          .map((result) => (result.status === 'fulfilled' ? result.value : null))
          .filter(Boolean)
          .map((node) => normalizeRootNode(node as LocalSQLFileTreeNode));

        if (restoredRoots.length) {
          applyRootNodes(restoredRoots);
          return;
        }

        persistRootPaths([]);
      })
      .finally(() => {
        if (!disposed) {
          setRestoring(false);
        }
      });

    return () => {
      disposed = true;
    };
  }, []);

  useEffect(() => {
    const handleSavedFile = (event: Event) => {
      const filePath = (event as CustomEvent<{ filePath?: string }>).detail?.filePath;
      if (!filePath) {
        return;
      }
      void syncSavedFileToTree(filePath);
    };

    window.addEventListener(LOCAL_SQL_FILE_SAVED_EVENT, handleSavedFile);
    return () => {
      window.removeEventListener(LOCAL_SQL_FILE_SAVED_EVENT, handleSavedFile);
    };
  }, [rootNodes, refreshing]);

  function persistRootPaths(rootPaths: string[]) {
    const normalizedPaths = rootPaths.filter(Boolean);
    if (normalizedPaths.length) {
      window.localStorage.setItem(LOCAL_SQL_DIRECTORY_PATHS_STORAGE_KEY, JSON.stringify(normalizedPaths));
      window.localStorage.setItem(LOCAL_SQL_DIRECTORY_PATH_STORAGE_KEY, normalizedPaths[0]);
      return;
    }
    window.localStorage.removeItem(LOCAL_SQL_DIRECTORY_PATH_STORAGE_KEY);
    window.localStorage.removeItem(LOCAL_SQL_DIRECTORY_PATHS_STORAGE_KEY);
  }

  function persistRoots(nodes: LocalSQLFileTreeNode[]) {
    persistRootPaths(nodes.map((node) => getRootPath(node)));
  }

  function applyRootNodes(nextRoots: LocalSQLFileTreeNode[]) {
    const roots = dedupeRootNodes(nextRoots.map(normalizeRootNode));
    setRootNodes(roots);
    setExpandedKeys(roots.map((node) => node.key));
    setSelectedNodeKey(roots[0]?.key);
    setCreatingNode(null);
    setRenamingNode(null);
    persistRoots(roots);
  }

  async function selectSqlDirectory(mode: 'replace' | 'add' = 'replace') {
    const loading = mode === 'add' ? addingRoot : selecting;
    if (loading || restoring) {
      return;
    }

    if (mode === 'add') {
      setAddingRoot(true);
    } else {
      setSelecting(true);
    }
    try {
      const data = await jcefApi.selectSqlDirectory();
      if (!data) {
        return;
      }
      const nextRoot = normalizeRootNode(data as LocalSQLFileTreeNode);
      if (mode === 'replace') {
        applyRootNodes([nextRoot]);
        return;
      }

      setRootNodes((currentRoots) => {
        const nextRoots = dedupeRootNodes([...currentRoots, nextRoot]);
        setExpandedKeys((keys) => (keys.includes(nextRoot.key) ? keys : [...keys, nextRoot.key]));
        setSelectedNodeKey(nextRoot.key);
        setCreatingNode(null);
        setRenamingNode(null);
        persistRoots(nextRoots);
        return nextRoots;
      });
    } catch (error) {
      console.error('select sql directory error', error);
      feedback.error(i18n('workspace.localSqlFileTree.selectFailed'));
    } finally {
      if (mode === 'add') {
        setAddingRoot(false);
      } else {
        setSelecting(false);
      }
    }
  }

  async function syncSavedFileToTree(filePath: string) {
    const parentPath = getParentPath(filePath);
    const parentResult = parentPath
      ? findTreeNode(
          rootNodes,
          (node) =>
            node.type === 'directory' &&
            normalizeComparablePath(node.path).toLowerCase() === normalizeComparablePath(parentPath).toLowerCase(),
        )
      : undefined;
    if (parentResult?.node) {
      await refreshDirectory(parentResult.node);
      setExpandedKeys((keys) => (keys.includes(parentResult.node.key) ? keys : [...keys, parentResult.node.key]));
      setSelectedNodeKey(parentResult.node.key);
      return;
    }

    const rootNode = rootNodes.find((node) => isSameOrChildPath(filePath, getRootPath(node), 'directory'));
    if (rootNode) {
      await refreshDirectory(rootNode);
      setExpandedKeys((keys) => (keys.includes(rootNode.key) ? keys : [...keys, rootNode.key]));
      setSelectedNodeKey(rootNode.key);
      return;
    }

    if (!parentPath) {
      return;
    }

    try {
      const data = await jcefApi.openSqlDirectory({ path: parentPath });
      if (!data) {
        return;
      }
      const nextRoot = normalizeRootNode(data as LocalSQLFileTreeNode);
      setRootNodes((currentRoots) => {
        const nextRoots = dedupeRootNodes([...currentRoots, nextRoot]);
        persistRoots(nextRoots);
        return nextRoots;
      });
      setExpandedKeys((keys) => (keys.includes(nextRoot.key) ? keys : [...keys, nextRoot.key]));
      setSelectedNodeKey(nextRoot.key);
    } catch (error) {
      console.error('sync saved sql file tree error', error);
    }
  }

  async function loadDirectoryChildren(node: LocalSQLFileTreeNode) {
    let nextChildren: LocalSQLFileTreeNode[] = [];
    setRootNodes((current) => updateTreeNode(current, node.key, (item) => ({ ...item, loading: true })));

    try {
      const children = await jcefApi.getSqlDirectoryChildren({
        rootToken: node.rootToken,
        relativePath: node.relativePath,
      });
      nextChildren = children as LocalSQLFileTreeNode[];
      setRootNodes((current) =>
        updateTreeNode(current, node.key, (item) => ({
          ...item,
          children: nextChildren,
          hasChildren: !!nextChildren.length,
          loaded: true,
          loading: false,
        })),
      );
    } catch (error) {
      console.error('load sql directory children error', error);
      feedback.error(i18n('workspace.localSqlFileTree.loadFailed'));
      setRootNodes((current) => updateTreeNode(current, node.key, (item) => ({ ...item, loading: false })));
    }

    return nextChildren;
  }

  async function refreshDirectory(node?: LocalSQLFileTreeNode) {
    const directory = node || getTargetDirectory();
    if (!directory || directory.type !== 'directory' || refreshing) {
      return;
    }

    setRefreshing(true);
    setCreatingNode(null);
    setRenamingNode(null);
    try {
      await loadDirectoryChildren(directory);
      setExpandedKeys((keys) => (keys.includes(directory.key) ? keys : [...keys, directory.key]));
    } finally {
      setRefreshing(false);
    }
  }

  function getDraggedSessionPayload(event: React.DragEvent<HTMLElement>) {
    const rawPayload = event.dataTransfer.getData(LOCAL_SQL_SESSION_DRAG_TYPE);
    if (!rawPayload) {
      return undefined;
    }

    try {
      const payload = JSON.parse(rawPayload) as DraggedSessionPayload;
      if (!payload.title) {
        return undefined;
      }
      return payload;
    } catch {
      return undefined;
    }
  }

  async function saveDraggedSessionToDirectory(targetDirectory: LocalSQLFileTreeNode, payload: DraggedSessionPayload) {
    if (targetDirectory.disabled || targetDirectory.loading) {
      return;
    }

    setSelectedNodeKey(targetDirectory.key);
    setExpandedKeys((keys) => (keys.includes(targetDirectory.key) ? keys : [...keys, targetDirectory.key]));
    setCreatingNode(null);
    setRenamingNode(null);

    try {
      const result = (await jcefApi.saveSqlDirectoryFile({
        rootToken: targetDirectory.rootToken,
        parentRelativePath: targetDirectory.relativePath,
        name: payload.title,
        content: payload.content || '',
      })) as SaveSqlDirectoryFileResult;

      setRootNodes((current) =>
        updateTreeNode(current, targetDirectory.key, (item) => ({
          ...item,
          children: result.children,
          hasChildren: !!result.children.length,
          loaded: true,
          loading: false,
        })),
      );
      setSelectedNodeKey(result.createdNode.key);
      readFile(result.createdNode.path, result.createdNode.fileExtension);
      feedback.success(i18n('workspace.openSessions.saveAsFileSuccess'));
    } catch (error) {
      console.error('drop session to sql directory error', error);
      feedback.error(i18n('workspace.openSessions.saveAsFileFailed'));
    }
  }

  function handleDirectoryDragOver(event: React.DragEvent<HTMLDivElement>, node: LocalSQLFileTreeNode) {
    if (node.type !== 'directory' || node.disabled || node.loading) {
      return;
    }

    if (!Array.from(event.dataTransfer.types).includes(LOCAL_SQL_SESSION_DRAG_TYPE)) {
      return;
    }

    event.preventDefault();
    event.stopPropagation();
    event.dataTransfer.dropEffect = 'copy';
    setDropTargetKey(node.key);
  }

  function handleDirectoryDragLeave(event: React.DragEvent<HTMLDivElement>, node: LocalSQLFileTreeNode) {
    const relatedTarget = event.relatedTarget;
    if (!(relatedTarget instanceof Node) || !event.currentTarget.contains(relatedTarget)) {
      setDropTargetKey((key) => (key === node.key ? undefined : key));
    }
  }

  function handleDirectoryDrop(event: React.DragEvent<HTMLDivElement>, node: LocalSQLFileTreeNode) {
    const payload = getDraggedSessionPayload(event);
    if (!payload || node.type !== 'directory') {
      return;
    }

    event.preventDefault();
    event.stopPropagation();
    setDropTargetKey(undefined);
    void saveDraggedSessionToDirectory(node, payload);
  }

  function handleTreeDragEnd() {
    setDropTargetKey(undefined);
  }

  function getTargetDirectory() {
    if (!rootNodes.length) {
      return undefined;
    }

    const selectedNode = selectedNodeResult?.node;
    if (!selectedNode) {
      return rootNodes[0];
    }

    if (selectedNode.type === 'directory') {
      return selectedNode;
    }

    return selectedNodeResult?.parent || rootNodes[0];
  }

  async function startCreate(type: LocalSQLFileTreeCreateType, targetDirectory?: LocalSQLFileTreeNode) {
    const parent = targetDirectory || getTargetDirectory();
    if (!parent || parent.disabled || parent.loading) {
      return;
    }

    const children = parent.loaded ? parent.children || [] : await loadDirectoryChildren(parent);
    const hydratedParent = { ...parent, children };
    setExpandedKeys((keys) => (keys.includes(parent.key) ? keys : [...keys, parent.key]));
    setRenamingNode(null);
    setCreatingNode({
      parentKey: parent.key,
      parentRelativePath: parent.relativePath,
      rootToken: parent.rootToken,
      type,
      name: getDefaultCreateName(hydratedParent, type),
    });
    setSelectedNodeKey(parent.key);
  }

  async function confirmCreate() {
    const currentCreatingNode = creatingRef.current;
    if (!currentCreatingNode || currentCreatingNode.loading) {
      return;
    }

    const name = currentCreatingNode.name.trim();
    if (!name) {
      cancelCreate();
      return;
    }

    const loadingCreatingNode = { ...currentCreatingNode, loading: true };
    creatingRef.current = loadingCreatingNode;
    setCreatingNode(loadingCreatingNode);
    try {
      const result = (await jcefApi.createSqlDirectoryChild({
        rootToken: currentCreatingNode.rootToken,
        parentRelativePath: currentCreatingNode.parentRelativePath,
        name,
        type: currentCreatingNode.type,
      })) as CreateSqlDirectoryChildResult;

      setRootNodes((current) =>
        updateTreeNode(current, currentCreatingNode.parentKey, (item) => ({
          ...item,
          children: result.children,
          hasChildren: !!result.children.length,
          loaded: true,
          loading: false,
        })),
      );
      setExpandedKeys((keys) =>
        keys.includes(currentCreatingNode.parentKey) ? keys : [...keys, currentCreatingNode.parentKey],
      );
      setSelectedNodeKey(result.createdNode.key);
      creatingRef.current = null;
      setCreatingNode(null);
      if (result.createdNode.type === 'file') {
        readFile(result.createdNode.path, result.createdNode.fileExtension);
      }
    } catch (error) {
      console.error('create sql directory child error', error);
      feedback.error(i18n('workspace.localSqlFileTree.createFailed'));
      setCreatingNode((current) => (current ? { ...current, loading: false } : current));
    }
  }

  function cancelCreate() {
    creatingRef.current = null;
    setCreatingNode(null);
  }

  function startRename(node: LocalSQLFileTreeNode) {
    if (node.disabled || node.rootPath || creatingNode) {
      return;
    }
    setSelectedNodeKey(node.key);
    setCreatingNode(null);
    setRenamingNode({
      key: node.key,
      rootToken: node.rootToken,
      relativePath: node.relativePath,
      type: node.type,
      name: node.name,
    });
  }

  async function confirmRename() {
    const currentRenamingNode = renamingRef.current;
    if (!currentRenamingNode || currentRenamingNode.loading) {
      return;
    }

    const name = currentRenamingNode.name.trim();
    if (!name) {
      cancelRename();
      return;
    }

    const originalNode = findTreeNode(rootNodes, (node) => node.key === currentRenamingNode.key)?.node;
    if (originalNode && normalizeNodeName(name, originalNode.type) === originalNode.name) {
      cancelRename();
      return;
    }

    const loadingRenamingNode = { ...currentRenamingNode, loading: true };
    renamingRef.current = loadingRenamingNode;
    setRenamingNode(loadingRenamingNode);
    try {
      const result = (await jcefApi.renameSqlDirectoryChild({
        rootToken: currentRenamingNode.rootToken,
        relativePath: currentRenamingNode.relativePath,
        name,
      })) as RenameSqlDirectoryChildResult;

      const parentResult = findTreeNode(
        rootNodes,
        (node) => node.rootToken === currentRenamingNode.rootToken && node.relativePath === result.parentRelativePath,
      );
      const parentKey = parentResult?.node.key;
      setRootNodes((current) => {
        if (!parentKey) {
          return current;
        }
        return updateTreeNode(current, parentKey, (item) => ({
          ...item,
          children: result.children,
          hasChildren: !!result.children.length,
          loaded: true,
          loading: false,
        }));
      });
      setExpandedKeys((keys) => (parentKey && !keys.includes(parentKey) ? [...keys, parentKey] : keys));
      setSelectedNodeKey(result.renamedNode.key);
      if (originalNode) {
        syncRenamedWorkspaceTabs(originalNode, result.renamedNode);
      }
      renamingRef.current = null;
      setRenamingNode(null);
    } catch (error) {
      console.error('rename sql directory child error', error);
      feedback.error(i18n('workspace.localSqlFileTree.renameFailed'));
      setRenamingNode((current) => (current ? { ...current, loading: false } : current));
    }
  }

  function cancelRename() {
    renamingRef.current = null;
    setRenamingNode(null);
  }

  function focusFileTree() {
    fileTreeModuleRef.current?.focus({ preventScroll: true });
  }

  function syncRenamedWorkspaceTabs(sourceNode: LocalSQLFileTreeNode, targetNode: LocalSQLFileTreeNode) {
    if (!workspaceTabList?.length) {
      return;
    }

    let nextActiveConsoleId = activeConsoleId;
    let changed = false;
    const nextWorkspaceTabList = workspaceTabList.map((tab) => {
      if (tab.type !== WorkspaceTabType.LocalSQLFile || !tab.uniqueData?.filePath) {
        return tab;
      }
      if (!isSameOrChildPath(tab.uniqueData.filePath, sourceNode.path, sourceNode.type)) {
        return tab;
      }

      changed = true;
      const filePath = replacePathPrefix(tab.uniqueData.filePath, sourceNode.path, targetNode.path);
      if (tab.id === activeConsoleId) {
        nextActiveConsoleId = tab.id;
      }
      return {
        ...tab,
        title: filePath,
        uniqueData: {
          ...tab.uniqueData,
          filePath,
          fileExtension: sourceNode.type === 'file' ? targetNode.fileExtension : tab.uniqueData.fileExtension,
        },
      };
    });

    if (changed) {
      setWorkspaceTabList(nextWorkspaceTabList);
      setActiveConsoleId(nextActiveConsoleId);
    }
  }

  function removeDeletedWorkspaceTabs(targetNode: LocalSQLFileTreeNode) {
    if (!workspaceTabList?.length) {
      return;
    }

    const removedTabIds: Array<string | number> = [];
    const nextWorkspaceTabList = workspaceTabList.filter((tab) => {
      if (tab.type !== WorkspaceTabType.LocalSQLFile || !tab.uniqueData?.filePath) {
        return true;
      }
      const removed = isSameOrChildPath(tab.uniqueData.filePath, targetNode.path, targetNode.type);
      if (removed) {
        removedTabIds.push(tab.id);
      }
      return !removed;
    });

    if (!removedTabIds.length) {
      return;
    }

    removedTabIds.forEach((tabId) => {
      if (typeof tabId === 'number') {
        deleteEditor(tabId);
      }
    });
    setWorkspaceTabList(nextWorkspaceTabList);
    if (removedTabIds.includes(activeConsoleId as string | number)) {
      const nextActiveId = nextWorkspaceTabList.length
        ? nextWorkspaceTabList[nextWorkspaceTabList.length - 1].id
        : null;
      setActiveConsoleId(nextActiveId);
    }
  }

  async function deleteNode(node: LocalSQLFileTreeNode) {
    if (node.disabled || node.rootPath) {
      return;
    }

    try {
      const result = (await jcefApi.deleteSqlDirectoryChild({
        rootToken: node.rootToken,
        relativePath: node.relativePath,
      })) as DeleteSqlDirectoryChildResult;

      const parentResult = findTreeNode(
        rootNodes,
        (item) => item.rootToken === node.rootToken && item.relativePath === result.parentRelativePath,
      );
      const parentKey = parentResult?.node.key;
      setRootNodes((current) => {
        if (!parentKey) {
          return current;
        }
        return updateTreeNode(current, parentKey, (item) => ({
          ...item,
          children: result.children,
          hasChildren: !!result.children.length,
          loaded: true,
          loading: false,
        }));
      });
      setExpandedKeys((keys) => keys.filter((key) => !isSameOrChildKey(key, node.key)));
      setSelectedNodeKey(parentKey);
      setCreatingNode(null);
      setRenamingNode(null);
      removeDeletedWorkspaceTabs(node);
    } catch (error) {
      console.error('delete sql directory child error', error);
      feedback.error(i18n('workspace.localSqlFileTree.deleteFailed'));
    }
  }

  function confirmDeleteNode(node: LocalSQLFileTreeNode) {
    modal.confirm({
      title: i18n('common.text.deleteConfirmTitle'),
      content: i18n('common.text.deleteConfirmTip', node.name),
      okText: i18n('common.button.delete'),
      okButtonProps: { danger: true },
      cancelText: i18n('common.button.cancel'),
      onOk: () => deleteNode(node),
    });
  }

  function removeRootNode(node: LocalSQLFileTreeNode) {
    if (!node.rootPath) {
      return;
    }

    setRootNodes((currentRoots) => {
      const nextRoots = currentRoots.filter((root) => root.key !== node.key);
      persistRoots(nextRoots);
      if (selectedNodeKey && isSameOrChildKey(selectedNodeKey, node.key)) {
        setSelectedNodeKey(nextRoots[0]?.key);
      }
      setExpandedKeys((keys) => keys.filter((key) => !isSameOrChildKey(key, node.key)));
      return nextRoots;
    });
    setCreatingNode(null);
    setRenamingNode(null);
    removeDeletedWorkspaceTabs(node);
  }

  async function copyText(text: string) {
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text);
      } else {
        const textarea = document.createElement('textarea');
        textarea.value = text;
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        document.execCommand('copy');
        document.body.removeChild(textarea);
      }
      feedback.success(i18n('workspace.localSqlFileTree.copySuccess'));
    } catch (error) {
      console.error('copy sql file tree text error', error);
      feedback.error(i18n('workspace.localSqlFileTree.copyFailed'));
    }
  }

  async function openNodeTerminal(node: LocalSQLFileTreeNode) {
    try {
      const terminal = await jcefApi.createSqlDirectoryTerminal({
        rootToken: node.rootToken,
        relativePath: node.relativePath,
        columns: 100,
        rows: 30,
        shellId: terminalShellId,
      });
      const id = `${WorkspaceTabType.Terminal}:${terminal.sessionId}`;
      const nextTabs = [
        ...(workspaceTabList || []),
        {
          id,
          type: WorkspaceTabType.Terminal,
          title: `${i18n('workspace.terminal.title')}: ${node.type === 'directory' ? node.name : terminal.cwd}`,
          uniqueData: {
            terminalSessionId: terminal.sessionId,
            terminalCwd: terminal.cwd,
            terminalShell: terminal.shell,
            terminalShellId: terminal.shellId,
            terminalOpenPosition,
          },
        },
      ];
      setWorkspaceTabList(nextTabs);
      if (terminalOpenPosition === 'tab') {
        setActiveConsoleId(id);
      }
    } catch (error) {
      console.error('open sql directory terminal error', error);
      feedback.error(i18n('workspace.localSqlFileTree.openTerminalFailed'));
    }
  }

  function revealNode(node: LocalSQLFileTreeNode) {
    jcefApi.revealInExplorer(node.path).catch((error) => {
      console.error('reveal sql file tree node error', error);
      feedback.error(i18n('workspace.localSqlFileTree.revealFailed'));
    });
  }

  async function toggleTreeNode(node: LocalSQLFileTreeNode) {
    if (node.type !== 'directory') {
      return;
    }

    const expanded = expandedKeys.includes(node.key);
    if (expanded) {
      setExpandedKeys((keys) => keys.filter((item) => item !== node.key));
      return;
    }

    setExpandedKeys((keys) => (keys.includes(node.key) ? keys : [...keys, node.key]));
    if (!node.loaded && !node.loading) {
      await loadDirectoryChildren(node);
    }
  }

  function openFile(node: LocalSQLFileTreeNode) {
    if (node.type !== 'file' || node.disabled || (!node.textFile && !node.sqlFile && !node.previewFile)) {
      return;
    }
    setSelectedNodeKey(node.key);
    readFile(node.path, node.fileExtension, {
      rootToken: node.rootToken,
      relativePath: node.relativePath,
      previewFile: node.previewFile,
    });
  }

  function handleSelectedNodeOpen() {
    const selectedNode = selectedNodeResult?.node;
    if (!selectedNode) {
      return;
    }

    if (selectedNode.type === 'directory') {
      toggleTreeNode(selectedNode);
      return;
    }

    openFile(selectedNode);
  }

  function getShortcutTargetNode() {
    const selectedNode = selectedNodeResult?.node;
    if (!selectedNode || selectedNode.disabled) {
      return undefined;
    }
    return selectedNode;
  }

  function acceptLocalShortcut(event: React.KeyboardEvent<HTMLDivElement>) {
    event.preventDefault();
    event.stopPropagation();
    closeContextMenu();
  }

  function isLocalShortcut(event: React.KeyboardEvent<HTMLDivElement>, action: ShortcutAction) {
    return isShortcutEventMatch(event, shortcutConfig[action].binding);
  }

  function handleRootFolderShortcut(event: React.KeyboardEvent<HTMLDivElement>) {
    const openFolderMatched = isLocalShortcut(event, ShortcutAction.LocalSqlFileTreeOpenFolder);
    const addFolderMatched = isLocalShortcut(event, ShortcutAction.LocalSqlFileTreeAddFolder);

    if (!openFolderMatched && !addFolderMatched) {
      return false;
    }

    acceptLocalShortcut(event);
    if (openFolderMatched) {
      selectSqlDirectory('replace');
      return true;
    }
    if (addFolderMatched) {
      selectSqlDirectory('add');
      return true;
    }
    return true;
  }

  function handleLocalShortcut(event: React.KeyboardEvent<HTMLDivElement>) {
    if (!active || isEditableTarget(event.target) || creatingNode || renamingNode) {
      return;
    }

    const selectedNode = getShortcutTargetNode();

    if (handleRootFolderShortcut(event)) {
      return;
    }

    if (isLocalShortcut(event, ShortcutAction.LocalSqlFileTreeOpen)) {
      acceptLocalShortcut(event);
      handleSelectedNodeOpen();
      return;
    }

    if (isLocalShortcut(event, ShortcutAction.LocalSqlFileTreeRename) && selectedNode && !selectedNode.rootPath) {
      acceptLocalShortcut(event);
      startRename(selectedNode);
      return;
    }

    if (isLocalShortcut(event, ShortcutAction.LocalSqlFileTreeRemoveRoot) && selectedNode?.rootPath) {
      acceptLocalShortcut(event);
      removeRootNode(selectedNode);
      return;
    }

    if (isLocalShortcut(event, ShortcutAction.LocalSqlFileTreeDelete) && selectedNode) {
      acceptLocalShortcut(event);
      if (selectedNode.rootPath) {
        return;
      }
      confirmDeleteNode(selectedNode);
      return;
    }

    if (isLocalShortcut(event, ShortcutAction.LocalSqlFileTreeNewFolder)) {
      acceptLocalShortcut(event);
      startCreate('directory');
      return;
    }

    if (isLocalShortcut(event, ShortcutAction.LocalSqlFileTreeNewFile)) {
      acceptLocalShortcut(event);
      startCreate('file');
      return;
    }

    if (isLocalShortcut(event, ShortcutAction.LocalSqlFileTreeRefresh)) {
      acceptLocalShortcut(event);
      refreshDirectory(getTargetDirectory());
      return;
    }

    if (isLocalShortcut(event, ShortcutAction.LocalSqlFileTreeCollapseAll)) {
      acceptLocalShortcut(event);
      collapseAll();
      return;
    }

    if (isLocalShortcut(event, ShortcutAction.LocalSqlFileTreeRevealInFinder) && selectedNode) {
      acceptLocalShortcut(event);
      revealNode(selectedNode);
      return;
    }

    if (isLocalShortcut(event, ShortcutAction.LocalSqlFileTreeOpenTerminal) && selectedNode) {
      acceptLocalShortcut(event);
      openNodeTerminal(selectedNode);
      return;
    }

    if (isLocalShortcut(event, ShortcutAction.LocalSqlFileTreeCopyPath) && selectedNode) {
      acceptLocalShortcut(event);
      copyText(selectedNode.path);
      return;
    }

    if (isLocalShortcut(event, ShortcutAction.LocalSqlFileTreeCopyRelativePath) && selectedNode) {
      acceptLocalShortcut(event);
      copyText(selectedNode.relativePath || selectedNode.name);
    }
  }

  function handleFileTreeContextMenu(event: React.MouseEvent<HTMLDivElement>) {
    const target = event.target;
    if (!(target instanceof HTMLElement) || isEditableTarget(target)) {
      return;
    }

    event.preventDefault();
    event.stopPropagation();

    if (target.closest('[data-local-sql-context-menu="true"]')) {
      return;
    }

    if (creatingNode || renamingNode || !rootNodes.length) {
      closeContextMenu();
      return;
    }

    const row = target.closest<HTMLElement>('[data-local-sql-tree-node-key]');
    const nodeKey = row?.dataset.localSqlTreeNodeKey;
    const rowNode = nodeKey ? findTreeNode(rootNodes, (node) => node.key === nodeKey)?.node : undefined;
    const targetNode = rowNode || getTargetDirectory() || rootNodes[0];

    if (!targetNode || targetNode.disabled) {
      closeContextMenu();
      return;
    }

    focusFileTree();
    setSelectedNodeKey(targetNode.key);
    setContextMenu({
      surface: 'localSqlFileTree',
      pointer: {
        x: event.clientX,
        y: event.clientY,
      },
      targetSnapshot: {
        nodeKey: targetNode.key,
        nodeType: targetNode.type,
        path: targetNode.path,
        relativePath: targetNode.relativePath,
        rootToken: targetNode.rootToken,
      },
      version: `${targetNode.key}:${targetNode.path}:${targetNode.type}`,
    });
  }

  function closeContextMenu() {
    setContextMenu(null);
  }

  function isContextMenuTargetCurrent(intent: LocalSQLFileTreeContextIntent) {
    const currentNode = findTreeNode(rootNodes, (node) => node.key === intent.targetSnapshot.nodeKey)?.node;
    return (
      !!currentNode &&
      !currentNode.disabled &&
      currentNode.type === intent.targetSnapshot.nodeType &&
      currentNode.path === intent.targetSnapshot.path &&
      currentNode.relativePath === intent.targetSnapshot.relativePath &&
      currentNode.rootToken === intent.targetSnapshot.rootToken
    );
  }

  function createContextMenuAction(
    action: Omit<ContextMenuAction<LocalSQLFileTreeContextIntent>, 'validateBeforeExecute'>,
  ): ContextMenuAction<LocalSQLFileTreeContextIntent> {
    return {
      ...action,
      validateBeforeExecute: isContextMenuTargetCurrent,
    };
  }

  function createContextMenuActions(node: LocalSQLFileTreeNode): ContextMenuEntry<LocalSQLFileTreeContextIntent>[] {
    if (node.disabled) {
      return [];
    }

    const commonItems: ContextMenuEntry<LocalSQLFileTreeContextIntent>[] = [
      createContextMenuAction({
        id: 'reveal',
        label: i18n(LOCAL_SQL_FILE_TREE_REVEAL_LABEL),
        shortcutAction: ShortcutAction.LocalSqlFileTreeRevealInFinder,
        execute: () => revealNode(node),
      }),
      createContextMenuAction({
        id: 'terminal',
        label: i18n('workspace.localSqlFileTree.openTerminal'),
        shortcutAction: ShortcutAction.LocalSqlFileTreeOpenTerminal,
        execute: () => openNodeTerminal(node),
      }),
      {
        type: 'divider',
      },
      createContextMenuAction({
        id: 'copyPath',
        label: i18n('workspace.localSqlFileTree.copyPath'),
        shortcutAction: ShortcutAction.LocalSqlFileTreeCopyPath,
        execute: () => copyText(node.path),
      }),
      createContextMenuAction({
        id: 'copyRelativePath',
        label: i18n('workspace.localSqlFileTree.copyRelativePath'),
        shortcutAction: ShortcutAction.LocalSqlFileTreeCopyRelativePath,
        execute: () => copyText(node.relativePath || node.name),
      }),
    ];

    const mutationItems: ContextMenuEntry<LocalSQLFileTreeContextIntent>[] = node.rootPath
      ? [
          {
            type: 'divider',
          },
          createContextMenuAction({
            id: 'removeRoot',
            label: i18n('workspace.localSqlFileTree.removeRoot'),
            shortcutAction: ShortcutAction.LocalSqlFileTreeRemoveRoot,
            execute: () => removeRootNode(node),
          }),
        ]
      : [
          {
            type: 'divider',
          },
          createContextMenuAction({
            id: 'rename',
            label: i18n('workspace.localSqlFileTree.rename'),
            shortcutAction: ShortcutAction.LocalSqlFileTreeRename,
            disabled: !!creatingNode || !!renamingNode,
            execute: () => startRename(node),
          }),
          createContextMenuAction({
            id: 'delete',
            label: i18n('workspace.localSqlFileTree.delete'),
            shortcutAction: ShortcutAction.LocalSqlFileTreeDelete,
            danger: true,
            execute: () => confirmDeleteNode(node),
          }),
        ];

    if (node.type === 'file') {
      const canOpen = node.textFile || node.sqlFile || node.previewFile;
      return [
        createContextMenuAction({
          id: 'open',
          label: i18n('workspace.localSqlFileTree.open'),
          shortcutAction: ShortcutAction.LocalSqlFileTreeOpen,
          disabled: !canOpen,
          execute: () => openFile(node),
        }),
        {
          type: 'divider',
        },
        ...commonItems,
        ...mutationItems,
      ];
    }

    return [
      createContextMenuAction({
        id: 'newFile',
        label: i18n('workspace.localSqlFileTree.newFile'),
        shortcutAction: ShortcutAction.LocalSqlFileTreeNewFile,
        disabled: !!creatingNode || !!renamingNode,
        execute: () => startCreate('file', node),
      }),
      createContextMenuAction({
        id: 'newFolder',
        label: i18n('workspace.localSqlFileTree.newFolder'),
        shortcutAction: ShortcutAction.LocalSqlFileTreeNewFolder,
        disabled: !!creatingNode || !!renamingNode,
        execute: () => startCreate('directory', node),
      }),
      {
        type: 'divider',
      },
      ...commonItems,
      {
        type: 'divider',
      },
      createContextMenuAction({
        id: 'refresh',
        label: i18n('workspace.localSqlFileTree.refresh'),
        shortcutAction: ShortcutAction.LocalSqlFileTreeRefresh,
        disabled: refreshing,
        execute: () => refreshDirectory(node),
      }),
      createContextMenuAction({
        id: shouldExpandAll ? 'expandAll' : 'collapseAll',
        label: i18n(
          shouldExpandAll ? 'workspace.localSqlFileTree.expandAll' : 'workspace.localSqlFileTree.collapseAll',
        ),
        shortcutAction: shouldExpandAll ? undefined : ShortcutAction.LocalSqlFileTreeCollapseAll,
        execute: shouldExpandAll ? expandAll : collapseAll,
      }),
      ...mutationItems,
    ];
  }

  function renderNodeIcon(node: LocalSQLFileTreeNode, expanded: boolean) {
    if (node.type === 'directory') {
      const Icon = expanded ? FolderOpen : Folder;
      return <Icon size={15} />;
    }

    return <IconfontSvg code={getLocalTextFileNodeIcon(node)} size={15} />;
  }

  function renderCreateInput(level: number) {
    if (!creatingNode) {
      return null;
    }

    return (
      <div
        className={[styles.treeRow, styles.createRow].join(' ')}
        key={`${creatingNode.parentKey}:creating`}
        style={{ paddingLeft: LOCAL_SQL_TREE_BASE_INDENT + level * LOCAL_SQL_TREE_LEVEL_INDENT }}
        onClick={(event) => event.stopPropagation()}
      >
        <span className={styles.switcherButton} />
        <span className={[styles.treeNodeIcon, 'local-sql-tree-icon'].join(' ')}>
          {creatingNode.type === 'directory' ? (
            <Folder size={15} />
          ) : (
            <IconfontSvg code={LOCAL_TEXT_FILE_ICON_MAP[SQL_FILE_EXTENSION_NAME]} size={15} />
          )}
        </span>
        <Input
          ref={createInputRef}
          className={styles.createInput}
          size="small"
          value={creatingNode.name}
          disabled={creatingNode.loading}
          onChange={(event) => {
            const { value } = event.target;
            setCreatingNode((current) => (current ? { ...current, name: value } : current));
          }}
          onBlur={confirmCreate}
          onPressEnter={confirmCreate}
          onKeyDown={(event) => {
            if (event.key === 'Escape') {
              event.stopPropagation();
              cancelCreate();
            }
          }}
        />
        {creatingNode.loading && <span className={styles.loadingText}>...</span>}
      </div>
    );
  }

  function renderRenameTitle(node: LocalSQLFileTreeNode) {
    if (renamingNode?.key !== node.key) {
      return (
        <Tooltip title={node.path} mouseEnterDelay={0.5}>
          <span className={[styles.treeNodeTitle, 'local-sql-tree-title'].join(' ')}>{node.name}</span>
        </Tooltip>
      );
    }

    return (
      <>
        <Input
          ref={renameInputRef}
          className={styles.createInput}
          size="small"
          value={renamingNode.name}
          disabled={renamingNode.loading}
          onChange={(event) => {
            const { value } = event.target;
            setRenamingNode((current) => (current ? { ...current, name: value } : current));
          }}
          onClick={(event) => event.stopPropagation()}
          onBlur={confirmRename}
          onPressEnter={confirmRename}
          onKeyDown={(event) => {
            if (event.key === 'Escape') {
              event.stopPropagation();
              cancelRename();
            }
          }}
        />
        {renamingNode.loading && <span className={styles.loadingText}>...</span>}
      </>
    );
  }

  function renderTreeNode(node: LocalSQLFileTreeNode, level = 0): React.ReactNode {
    const expanded = expandedKeys.includes(node.key);
    const hasCreatingChild = creatingNode?.parentKey === node.key;
    const hasChildren = node.type === 'directory' && (node.hasChildren || hasCreatingChild);
    const selected = selectedNodeKey === node.key;
    const isRenaming = renamingNode?.key === node.key;
    const isDropTarget = dropTargetKey === node.key;
    const row = (
      <div
        className={[
          styles.treeRow,
          selected ? styles.selectedRow : '',
          isDropTarget ? styles.dropTargetRow : '',
          node.disabled ? styles.disabledRow : '',
          isRenaming ? styles.createRow : '',
        ]
          .filter(Boolean)
          .join(' ')}
        style={{ paddingLeft: LOCAL_SQL_TREE_BASE_INDENT + level * LOCAL_SQL_TREE_LEVEL_INDENT }}
        data-local-sql-tree-node-key={node.key}
        ref={(element) => {
          if (selected) {
            selectedRowRef.current = element;
          }
        }}
        onClick={() => {
          closeContextMenu();
          focusFileTree();
          if (isRenaming) {
            return;
          }
          setSelectedNodeKey(node.key);
          if (node.type === 'directory') {
            toggleTreeNode(node);
          } else {
            openFile(node);
          }
        }}
        onDragOver={(event) => handleDirectoryDragOver(event, node)}
        onDragLeave={(event) => handleDirectoryDragLeave(event, node)}
        onDrop={(event) => handleDirectoryDrop(event, node)}
      >
        <button
          className={styles.switcherButton}
          type="button"
          onClick={(event) => {
            event.stopPropagation();
            closeContextMenu();
            focusFileTree();
            if (isRenaming) {
              return;
            }
            setSelectedNodeKey(node.key);
            toggleTreeNode(node);
          }}
          onDoubleClick={(event) => {
            event.stopPropagation();
          }}
        >
          {hasChildren && (
            <ChevronRight
              size={14}
              className={[styles.switcherIcon, expanded ? styles.switcherIconExpanded : ''].filter(Boolean).join(' ')}
            />
          )}
        </button>
        <span className={[styles.treeNodeIcon, 'local-sql-tree-icon'].join(' ')}>{renderNodeIcon(node, expanded)}</span>
        {renderRenameTitle(node)}
        {node.loading && <span className={styles.loadingText}>...</span>}
      </div>
    );

    return (
      <div key={node.key}>
        {row}
        {hasChildren && expanded && (
          <>
            {hasCreatingChild && renderCreateInput(level + 1)}
            {node.children?.map((child) => renderTreeNode(child, level + 1))}
          </>
        )}
      </div>
    );
  }

  function collapseAll() {
    if (!rootNodes.length) {
      return;
    }
    setCreatingNode(null);
    setExpandedKeys([]);
    setSelectedNodeKey(rootNodes[0].key);
  }

  function expandAll() {
    if (!expandableDirectoryKeys.length) {
      return;
    }
    setCreatingNode(null);
    setExpandedKeys((keys) => Array.from(new Set([...keys, ...expandableDirectoryKeys])));
  }

  const toolbarDisabled = !rootNodes.length || !!creatingNode || !!renamingNode;
  const folderActionMode = rootNodes.length ? 'add' : 'replace';
  const folderActionTitle = rootNodes.length
    ? i18n('workspace.localSqlFileTree.addFolder')
    : i18n('workspace.localSqlFileTree.openFolder');
  const folderActionLoading = rootNodes.length ? addingRoot : selecting;
  const folderActionIcon = rootNodes.length ? FolderInput : FolderOpen;
  const showExpandedToolbar = (headerSize?.width || 0) >= LOCAL_SQL_TOOLBAR_EXPANDED_MIN_WIDTH;
  const toolbarMoreItems: MenuProps['items'] = [
    {
      key: 'newFolder',
      label: i18n('workspace.localSqlFileTree.newFolder'),
      disabled: toolbarDisabled,
      onClick: () => startCreate('directory'),
    },
    {
      key: shouldExpandAll ? 'expandAll' : 'collapseAll',
      label: i18n(
        shouldExpandAll ? 'workspace.localSqlFileTree.expandAll' : 'workspace.localSqlFileTree.collapseAll',
      ),
      disabled: !rootNodes.length,
      onClick: shouldExpandAll ? expandAll : collapseAll,
    },
  ];

  return (
    <div
      ref={fileTreeModuleRef}
      className={styles.fileTreeModule}
      tabIndex={active ? 0 : -1}
      aria-hidden={!active}
      onKeyDown={handleLocalShortcut}
      onContextMenuCapture={handleFileTreeContextMenu}
      onDragEnd={handleTreeDragEnd}
      onDrop={handleTreeDragEnd}
    >
      {modalContextHolder}
      <PortalContextMenu
        intent={contextMenu}
        className={styles.contextMenu}
        actions={contextMenuActions}
        onClose={closeContextMenu}
      />
      <div ref={headerRef} className={styles.header}>
        <div className={styles.headerContent}>
          <div className={styles.headerActions}>
            <IconButton
              title={folderActionTitle}
              size={LOCAL_SQL_TOOLBAR_BUTTON_SIZE}
              disabled={selecting || addingRoot || restoring}
              spin={folderActionLoading}
              onClick={() => selectSqlDirectory(folderActionMode)}
              icon={folderActionIcon}
            />
            <IconButton
              title={i18n('workspace.localSqlFileTree.newFile')}
              size={LOCAL_SQL_TOOLBAR_BUTTON_SIZE}
              disabled={toolbarDisabled}
              onClick={() => startCreate('file')}
              icon={FilePlus}
            />
            <IconButton
              title={i18n('workspace.localSqlFileTree.refresh')}
              size={LOCAL_SQL_TOOLBAR_BUTTON_SIZE}
              disabled={!rootNodes.length || refreshing}
              spin={refreshing}
              onClick={() => refreshDirectory(getTargetDirectory())}
              icon={RefreshCw}
            />
            {showExpandedToolbar ? (
              <>
                <IconButton
                  title={i18n('workspace.localSqlFileTree.newFolder')}
                  size={LOCAL_SQL_TOOLBAR_BUTTON_SIZE}
                  disabled={toolbarDisabled}
                  onClick={() => startCreate('directory')}
                  icon={FolderPlus}
                />
                <IconButton
                  title={i18n(
                    shouldExpandAll
                      ? 'workspace.localSqlFileTree.expandAll'
                      : 'workspace.localSqlFileTree.collapseAll',
                  )}
                  size={LOCAL_SQL_TOOLBAR_BUTTON_SIZE}
                  disabled={!rootNodes.length}
                  onClick={shouldExpandAll ? expandAll : collapseAll}
                  icon={shouldExpandAll ? ChevronsUpDown : ChevronsDownUp}
                />
              </>
            ) : (
              <Dropdown menu={{ items: toolbarMoreItems }} trigger={['click']}>
                <IconButton
                  title={i18n('workspace.localSqlFileTree.moreActions')}
                  size={LOCAL_SQL_TOOLBAR_BUTTON_SIZE}
                  icon={MoreHorizontal}
                />
              </Dropdown>
            )}
          </div>
        </div>
      </div>
      <div className={styles.treeBox}>
        {!rootNodes.length ? (
          <div className={styles.emptyBox}>
            <div>{i18n('workspace.localSqlFileTree.empty')}</div>
            <Button
              className={styles.emptyButton}
              size="small"
              type="primary"
              loading={selecting || restoring}
              onClick={() => selectSqlDirectory('replace')}
            >
              {i18n('workspace.localSqlFileTree.openFolder')}
            </Button>
          </div>
        ) : (
          <div className={styles.treeList}>{rootNodes.map((node) => renderTreeNode(node))}</div>
        )}
      </div>
    </div>
  );
});

export default LocalSQLFileTree;
