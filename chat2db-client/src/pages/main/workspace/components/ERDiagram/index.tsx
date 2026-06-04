/**
 * ER图主组件
 * 使用ReactFlow渲染数据库表之间的外键关系图
 * 支持表过滤、布局切换、虚拟外键显示/推断/删除、缩放控制、PNG导出等功能
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Empty, message, Modal, Spin } from 'antd';
import {
  ReactFlow,
  ReactFlowProvider,
  Background,
  Controls,
  MiniMap,
  useNodesState,
  useEdgesState,
  Node,
  Edge,
  Connection,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import dagre from '@dagrejs/dagre';
import { toPng } from 'html-to-image';
import { WorkspaceTabType } from '@/constants';
import i18n from '@/i18n';
import sqlService, { IColumn, IInferVirtualFkItem } from '@/service/sql';
import { createConsole } from '@/pages/main/workspace/store/console';
import { useWorkspaceStore } from '@/pages/main/workspace/store';
import { compatibleDataBaseName } from '@/utils/database';
import useErDiagramStore, { LayoutType } from './store';
import TableNode, { IErDiagramFieldHandlePayload } from './components/TableNode';
import RelationEdge from './components/RelationEdge';
import Toolbar from './components/Toolbar';
import TableFilter from './components/TableFilter';
import Legend from './components/Legend';
import styles from './index.less';

/** ER图组件Props */
interface IERDiagramProps {
  uniqueData: {
    databaseName: string;
    dataSourceId: number;
    schemaName?: string;
    tableName: string;
    dataSourceName?: string;
    databaseType?: any;
  };
}

/** 自定义节点和边类型映射 */
const nodeTypes = { tableNode: TableNode };
const edgeTypes = { relationEdge: RelationEdge };
const FIELD_HANDLE_PREFIX = 'field:';

/** dagre图对象，用于层级布局计算 */
const dagreGraph = new dagre.graphlib.Graph({ multigraph: true, compound: false });
dagreGraph.setDefaultEdgeLabel(() => ({}));

/**
 * 使用dagre算法计算层级布局
 */
const getDagreLayout = (nodes: Node[], edges: Edge[], direction: 'TB' | 'LR' = 'TB') => {
  // 1. 先分堆
  const connectedNodeIds = new Set<string>();
  edges.forEach((e) => {
    connectedNodeIds.add(e.source);
    connectedNodeIds.add(e.target);
  });

  const connectedNodes = nodes.filter((n) => connectedNodeIds.has(n.id));
  const isolatedNodes = nodes.filter((n) => !connectedNodeIds.has(n.id));

  // 2. 【关键】只把“有关系的表”交给 Dagre 去算
  dagreGraph.setGraph({ rankdir: direction, nodesep: 50, ranksep: 80 });

  connectedNodes.forEach((node) => {
    const nodeData = node.data as any;
    const columnLength = nodeData?.columnsExpanded ? Math.min(nodeData?.columns?.length || 4, 8) : 0;
    dagreGraph.setNode(node.id, { width: 220, height: 70 + columnLength * 24 });
  });

  edges.forEach((edge) => {
    dagreGraph.setEdge(edge.source, edge.target);
  });

  dagre.layout(dagreGraph); // 此时 Dagre 眼里只有业务表，字典表不存在

  // 3. 收集业务表的位置
  const layoutedNodes = connectedNodes.map((node) => {
    const pos = dagreGraph.node(node.id);
    return { ...node, position: { x: pos.x - 80, y: pos.y - 30 } };
  });

  // 4. 【关键】手动放置孤立节点（放在最右边，远离业务流）
  if (isolatedNodes.length > 0) {
    const lastX = layoutedNodes.reduce((max, n) => Math.max(max, n.position.x), 0);
    const startX = lastX + 300; // 留出足够空隙

    isolatedNodes.forEach((node, i) => {
      layoutedNodes.push({
        ...node,
        position: {
          x: startX,
          y: i * 100, // 纵向排列
        },
      });
    });
  }

  return layoutedNodes;
};

/**
 * 使用力导向算法计算布局
 */
const getForceLayout = (nodes: Node[], edges: Edge[]) => {
  const nodeMap = new Map<string, { x: number; y: number; vx: number; vy: number }>();
  const iterations = 300;
  const repulsion = 5000;
  const attraction = 0.001;
  const damping = 0.85;
  const idealEdgeLength = 150;

  const connectedNodeIds = new Set<string>();
  edges.forEach((e) => {
    connectedNodeIds.add(e.source);
    connectedNodeIds.add(e.target);
  });

  nodes.forEach((node, i) => {
    const angle = (2 * Math.PI * i) / nodes.length;
    const radius = connectedNodeIds.has(node.id) ? 200 : 400;
    nodeMap.set(node.id, {
      x: Math.cos(angle) * radius,
      y: Math.sin(angle) * radius,
      vx: 0,
      vy: 0,
    });
  });

  for (let iter = 0; iter < iterations; iter++) {
    nodes.forEach((n1) => {
      const p1 = nodeMap.get(n1.id)!;
      nodes.forEach((n2) => {
        if (n1.id === n2.id) return;
        const p2 = nodeMap.get(n2.id)!;
        const dx = p1.x - p2.x;
        const dy = p1.y - p2.y;
        const dist = Math.sqrt(dx * dx + dy * dy) || 1;
        const force = repulsion / (dist * dist);
        p1.vx += (dx / dist) * force;
        p1.vy += (dy / dist) * force;
      });
    });

    edges.forEach((edge) => {
      const p1 = nodeMap.get(edge.source);
      const p2 = nodeMap.get(edge.target);
      if (!p1 || !p2) return;
      const dx = p2.x - p1.x;
      const dy = p2.y - p1.y;
      const dist = Math.sqrt(dx * dx + dy * dy) || 1;
      const displacement = (dist - idealEdgeLength) * attraction;
      p1.vx += (dx / dist) * displacement;
      p1.vy += (dy / dist) * displacement;
      p2.vx -= (dx / dist) * displacement;
      p2.vy -= (dy / dist) * displacement;
    });

    const isIsolated = (nodeId: string) => !connectedNodeIds.has(nodeId);

    nodes.forEach((node) => {
      const p = nodeMap.get(node.id)!;
      if (isIsolated(node.id)) {
        const distFromCenter = Math.sqrt(p.x * p.x + p.y * p.y);
        if (distFromCenter > 0) {
          p.vx += (p.x / distFromCenter) * 2;
          p.vy += (p.y / distFromCenter) * 2;
        }
      }
      p.vx *= damping;
      p.vy *= damping;
      p.x += p.vx;
      p.y += p.vy;
    });
  }

  return nodes.map((node) => {
    const p = nodeMap.get(node.id)!;
    return { ...node, position: { x: p.x, y: p.y } };
  });
};

/**
 * 根据布局类型应用相应布局算法
 */
const applyLayout = (nodes: Node[], edges: Edge[], layoutType: LayoutType): Node[] => {
  if (nodes.length === 0) return nodes;
  if (layoutType === 'dagre') {
    return getDagreLayout(nodes, edges);
  }
  return getForceLayout(nodes, edges);
};

const parseFieldHandle = (handleId?: string | null): IErDiagramFieldHandlePayload | null => {
  if (!handleId?.startsWith(FIELD_HANDLE_PREFIX)) return null;

  const [, encodedTableName, encodedColumnName] = handleId.split(':');
  if (!encodedTableName || !encodedColumnName) return null;

  try {
    return {
      tableName: decodeURIComponent(encodedTableName),
      columnName: decodeURIComponent(encodedColumnName),
    };
  } catch {
    return null;
  }
};

/** ER图内部组件，需要ReactFlowProvider包裹 */
const ERDiagramInner: React.FC<IERDiagramProps> = ({ uniqueData }) => {
  const chartRef = useRef<HTMLDivElement>(null);

  const {
    erDiagramData,
    loading,
    filterText,
    layoutType,
    includeVirtualFk,
    showOnlyRelatedTables,
    selectedTableId,
    fetchErDiagram,
    inferVirtualForeignKeys,
    deleteVirtualForeignKey,
    setFilterText,
    setLayoutType,
    setSelectedTableId,
    setIncludeVirtualFk,
    setShowOnlyRelatedTables,
  } = useErDiagramStore();

  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [inferring, setInferring] = React.useState(false);
  const [expandedTableNames, setExpandedTableNames] = useState<Set<string>>(new Set());
  const [columnMap, setColumnMap] = useState<Record<string, IColumn[]>>({});
  const [columnLoadingMap, setColumnLoadingMap] = useState<Record<string, boolean>>({});
  const currentConnectionDetails = useWorkspaceStore((state) => state.currentConnectionDetails);

  const fetchData = useCallback(
    (syncForeignKeys?: boolean) => {
      fetchErDiagram({
        dataSourceId: uniqueData.dataSourceId,
        databaseName: uniqueData.databaseName,
        schemaName: uniqueData.schemaName,
        tableNameFilter: filterText || undefined,
        includeVirtualFk,
        syncForeignKeys,
        onlyRelatedTables: showOnlyRelatedTables,
      });
    },
    [uniqueData, filterText, includeVirtualFk, showOnlyRelatedTables, fetchErDiagram],
  );

  useEffect(() => {
    fetchData();
  }, [uniqueData.dataSourceId, uniqueData.databaseName, uniqueData.schemaName, showOnlyRelatedTables]);

  const getIdentifier = useCallback(
    (name: string) => compatibleDataBaseName(name, (uniqueData.databaseType || currentConnectionDetails?.type) as any),
    [currentConnectionDetails?.type, uniqueData.databaseType],
  );

  const handleCopyTableName = useCallback(async (tableName: string) => {
    try {
      await navigator.clipboard.writeText(tableName);
      message.success(i18n('workspace.erDiagram.copyTableNameSuccess'));
    } catch {
      const textarea = document.createElement('textarea');
      textarea.value = tableName;
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand('copy');
      document.body.removeChild(textarea);
      message.success(i18n('workspace.erDiagram.copyTableNameSuccess'));
    }
  }, []);

  const handleToggleColumns = useCallback(
    async (tableName: string) => {
      const shouldExpand = !expandedTableNames.has(tableName);
      setExpandedTableNames((prev) => {
        const next = new Set(prev);
        if (shouldExpand) {
          next.add(tableName);
        } else {
          next.delete(tableName);
        }
        return next;
      });

      if (!shouldExpand || columnMap[tableName]) return;

      setColumnLoadingMap((prev) => ({ ...prev, [tableName]: true }));
      try {
        const columns = await sqlService.getColumnList({
          dataSourceId: uniqueData.dataSourceId,
          databaseName: uniqueData.databaseName,
          schemaName: uniqueData.schemaName,
          tableName,
        });
        setColumnMap((prev) => ({ ...prev, [tableName]: columns || [] }));
      } catch {
        message.error(i18n('workspace.erDiagram.loadColumnsError'));
      } finally {
        setColumnLoadingMap((prev) => ({ ...prev, [tableName]: false }));
      }
    },
    [columnMap, expandedTableNames, uniqueData],
  );

  const buildJoinQuery = useCallback(
    (tableName: string) => {
      const relatedEdges = (erDiagramData?.edges || []).filter(
        (edge) => edge.source === tableName || edge.target === tableName,
      );
      const baseAlias = 't0';
      const aliasMap = new Map<string, string>([[tableName, baseAlias]]);
      const joinMap = new Map<string, string[]>();

      relatedEdges.forEach((edge) => {
        const joinTable = edge.source === tableName ? edge.target : edge.source;
        if (!aliasMap.has(joinTable)) {
          aliasMap.set(joinTable, `t${aliasMap.size}`);
        }
        const joinAlias = aliasMap.get(joinTable)!;
        const condition =
          edge.source === tableName
            ? `${baseAlias}.${getIdentifier(edge.sourceColumn)} = ${joinAlias}.${getIdentifier(edge.targetColumn)}`
            : `${joinAlias}.${getIdentifier(edge.sourceColumn)} = ${baseAlias}.${getIdentifier(
                edge.targetColumn,
              )}`;
        const conditions = joinMap.get(joinTable);
        if (conditions) {
          conditions.push(condition);
        } else {
          joinMap.set(joinTable, [condition]);
        }
      });

      const lines = [`SELECT ${baseAlias}.*`, `FROM ${getIdentifier(tableName)} ${baseAlias}`];
      joinMap.forEach((conditions, joinTable) => {
        lines.push(`LEFT JOIN ${getIdentifier(joinTable)} ${aliasMap.get(joinTable)} ON ${conditions.join(' AND ')}`);
      });

      return `${lines.join('\n')};`;
    },
    [erDiagramData?.edges, getIdentifier],
  );

  const handleCreateQuery = useCallback(
    (tableName: string) => {
      createConsole({
        name: `${tableName} join query`,
        ddl: buildJoinQuery(tableName),
        dataSourceId: uniqueData.dataSourceId,
        dataSourceName: uniqueData.dataSourceName || currentConnectionDetails?.alias || '',
        databaseType: uniqueData.databaseType || currentConnectionDetails?.type,
        databaseName: uniqueData.databaseName,
        schemaName: uniqueData.schemaName,
        operationType: WorkspaceTabType.CONSOLE,
      });
    },
    [buildJoinQuery, currentConnectionDetails, uniqueData],
  );

  const createVirtualForeignKeyByFields = useCallback(
    (sourceField: IErDiagramFieldHandlePayload, targetField: IErDiagramFieldHandlePayload) => {
      if (sourceField.tableName === targetField.tableName && sourceField.columnName === targetField.columnName) {
        message.warning(i18n('workspace.erDiagram.invalidVirtualFkSameField'));
        return;
      }

      Modal.confirm({
        title: i18n('workspace.erDiagram.createVirtualFk'),
        content: `${sourceField.tableName}.${sourceField.columnName} -> ${targetField.tableName}.${
          targetField.columnName
        }`,
        okText: i18n('common.button.confirm'),
        cancelText: i18n('common.button.cancel'),
        onOk: async () => {
          try {
            await sqlService.createVirtualForeignKey({
              dataSourceId: uniqueData.dataSourceId,
              databaseName: uniqueData.databaseName,
              schemaName: uniqueData.schemaName,
              tableName: sourceField.tableName,
              columnName: sourceField.columnName,
              referencedTable: targetField.tableName,
              referencedColumnName: targetField.columnName,
            });
            message.success(i18n('workspace.erDiagram.createVirtualFkSuccess'));
            setIncludeVirtualFk(true);
            fetchErDiagram({
              dataSourceId: uniqueData.dataSourceId,
              databaseName: uniqueData.databaseName,
              schemaName: uniqueData.schemaName,
              tableNameFilter: filterText || undefined,
              includeVirtualFk: true,
              onlyRelatedTables: showOnlyRelatedTables,
            });
          } catch {
            message.error(i18n('workspace.erDiagram.createVirtualFkError'));
          }
        },
      });
    },
    [fetchErDiagram, filterText, setIncludeVirtualFk, showOnlyRelatedTables, uniqueData],
  );

  const handleConnect = useCallback(
    (connection: Connection) => {
      const sourceField = parseFieldHandle(connection.sourceHandle);
      const targetField = parseFieldHandle(connection.targetHandle);

      if (!connection.source || !connection.target || !sourceField || !targetField) {
        message.warning(i18n('workspace.erDiagram.invalidVirtualFkConnection'));
        return;
      }

      createVirtualForeignKeyByFields(sourceField, targetField);
    },
    [createVirtualForeignKeyByFields],
  );

  useEffect(() => {
    if (!erDiagramData) return;

    let filteredNodes = filterText
      ? erDiagramData.nodes.filter(
          (n) =>
            n.name.toLowerCase().includes(filterText.toLowerCase()) ||
            (n.comment && n.comment.toLowerCase().includes(filterText.toLowerCase())),
        )
      : erDiagramData.nodes;

    let filteredEdges = erDiagramData.edges.filter(
      (e) => filteredNodes.some((n) => n.id === e.source) && filteredNodes.some((n) => n.id === e.target),
    );

    if (showOnlyRelatedTables) {
      const relatedTableIds = new Set<string>();
      filteredEdges.forEach((e) => {
        relatedTableIds.add(e.source);
        relatedTableIds.add(e.target);
      });
      filteredNodes = filteredNodes.filter((n) => relatedTableIds.has(n.id));
    }

    const filteredNodeIds = new Set(filteredNodes.map((n) => n.id));
    filteredEdges = filteredEdges.filter((e) => filteredNodeIds.has(e.source) && filteredNodeIds.has(e.target));

    const selectedRelatedNodeIds = new Set<string>();
    if (selectedTableId) {
      selectedRelatedNodeIds.add(selectedTableId);
      filteredEdges.forEach((e) => {
        if (e.source === selectedTableId) selectedRelatedNodeIds.add(e.target);
        if (e.target === selectedTableId) selectedRelatedNodeIds.add(e.source);
      });
    }

    const rfNodes: Node[] = filteredNodes.map((n) => ({
      id: n.id,
      type: 'tableNode',
      position: { x: 0, y: 0 },
      data: {
        ...n,
        isHighlighted: selectedTableId ? selectedRelatedNodeIds.has(n.id) : false,
        isDimmed: selectedTableId ? !selectedRelatedNodeIds.has(n.id) : false,
        columns: columnMap[n.name],
        columnsExpanded: expandedTableNames.has(n.name),
        columnsLoading: columnLoadingMap[n.name],
        onCopyTableName: handleCopyTableName,
        onToggleColumns: handleToggleColumns,
        onCreateQuery: handleCreateQuery,
      },
    }));

    const rfEdges: Edge[] = filteredEdges.map((e) => ({
      id: e.id,
      source: e.source,
      target: e.target,
      type: 'relationEdge',
      data: {
        label: e.label,
        virtual: e.virtual,
        sourceColumn: e.sourceColumn,
        targetColumn: e.targetColumn,
      },
      markerEnd: {
        type: 'arrowclosed' as const,
        color: e.virtual ? 'var(--color-warning)' : 'var(--color-text-tertiary)',
      },
      style: e.virtual
        ? { stroke: 'var(--color-warning)', strokeWidth: 1.5, strokeDasharray: '5 3' }
        : { stroke: 'var(--color-text-tertiary)', strokeWidth: 2 },
    }));

    const laidOutNodes = applyLayout(rfNodes, rfEdges, layoutType);
    setNodes(laidOutNodes);
    setEdges(rfEdges);
  }, [
    erDiagramData,
    filterText,
    layoutType,
    selectedTableId,
    showOnlyRelatedTables,
    columnMap,
    expandedTableNames,
    columnLoadingMap,
    handleCopyTableName,
    handleToggleColumns,
    handleCreateQuery,
    setNodes,
    setEdges,
  ]);

  const handleNodeClick = useCallback(
    (_event, node) => {
      setSelectedTableId(selectedTableId === node.id ? null : node.id);
    },
    [selectedTableId, setSelectedTableId],
  );

  const handlePaneClick = useCallback(() => {
    setSelectedTableId(null);
  }, [setSelectedTableId]);

  const handleEdgeContextMenu = useCallback(
    (event: React.MouseEvent, edge: Edge) => {
      event.preventDefault();
      const edgeData = edge.data as any;
      if (edgeData?.virtual) {
        if (window.confirm(i18n('workspace.erDiagram.confirmDeleteVirtualFk', [edge.id]))) {
          deleteVirtualForeignKey(edge.id, {
            dataSourceId: uniqueData.dataSourceId,
            databaseName: uniqueData.databaseName,
            schemaName: uniqueData.schemaName,
          }).then(() => {
            fetchData();
          });
        }
      }
    },
    [deleteVirtualForeignKey, uniqueData, fetchData],
  );

  const handleRefresh = useCallback(() => {
    fetchData(true);
  }, [fetchData]);

  const handleInferVirtualFk = useCallback(async () => {
    setInferring(true);
    try {
      const result = await inferVirtualForeignKeys({
        dataSourceId: uniqueData.dataSourceId,
        databaseName: uniqueData.databaseName,
        schemaName: uniqueData.schemaName,
        tableNameFilter: filterText || undefined,
      });

      if (result.addedCount > 0 || result.deletedCount > 0) {
        const formatItem = (item: IInferVirtualFkItem) =>
          `${item.tableName}.${item.columnName} → ${item.referencedTable}.${item.referencedColumnName}`;

        const content = (
          <div style={{ maxHeight: '400px', overflowY: 'auto' }}>
            {result.addedCount > 0 && (
              <div style={{ marginBottom: 16 }}>
                <div style={{ color: 'var(--color-success)', fontWeight: 'bold', marginBottom: 8 }}>
                  {i18n('workspace.erDiagram.inferResult.added', [result.addedCount])}
                </div>
                <ul style={{ margin: 0, paddingLeft: 20 }}>
                  {result.added.map((item, i) => (
                    <li key={`add-${i}`} style={{ marginBottom: 4, fontFamily: 'monospace' }}>
                      {formatItem(item)}
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {result.deletedCount > 0 && (
              <div>
                <div style={{ color: 'var(--color-error)', fontWeight: 'bold', marginBottom: 8 }}>
                  {i18n('workspace.erDiagram.inferResult.deleted', [result.deletedCount])}
                </div>
                <ul style={{ margin: 0, paddingLeft: 20 }}>
                  {result.deleted.map((item, i) => (
                    <li key={`del-${i}`} style={{ marginBottom: 4, fontFamily: 'monospace' }}>
                      {formatItem(item)}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        );

        Modal.info({
          title: i18n('workspace.erDiagram.inferResult.title'),
          content,
          width: 520,
          okText: i18n('common.button.confirm'),
        });
      } else {
        message.info(i18n('workspace.erDiagram.inferVirtualFkNoResult'));
      }
    } catch (error) {
      message.error(i18n('workspace.erDiagram.inferVirtualFkError'));
    } finally {
      setInferring(false);
    }
  }, [inferVirtualForeignKeys, uniqueData, filterText]);

  const handleLayoutChange = useCallback(
    (type: LayoutType) => {
      setLayoutType(type);
    },
    [setLayoutType],
  );

  const handleExport = useCallback(() => {
    if (!chartRef.current) return;
    toPng(chartRef.current.querySelector('.react-flow') as HTMLElement, {
      backgroundColor: getComputedStyle(document.documentElement).getPropertyValue('--color-bg-base') || '#fff',
      quality: 1,
    }).then((dataUrl) => {
      const link = document.createElement('a');
      link.download = `er-diagram-${uniqueData.databaseName}.png`;
      link.href = dataUrl;
      link.click();
    });
  }, [uniqueData.databaseName]);

  const miniMapNodeColor = useCallback(
    (node: Node) => {
      if (selectedTableId) {
        const data = node.data as any;
        if (data?.isHighlighted) return 'var(--color-primary)';
        if (data?.isDimmed) return 'var(--color-fill-quaternary)';
      }
      return 'var(--color-primary-bg, #91caff)';
    },
    [selectedTableId],
  );

  return (
    <div className={styles.erDiagramContainer} ref={chartRef}>
      <TableFilter value={filterText} onChange={setFilterText} disabled={showOnlyRelatedTables} />
      <Toolbar
        loading={loading}
        layoutType={layoutType}
        includeVirtualFk={includeVirtualFk}
        showOnlyRelatedTables={showOnlyRelatedTables}
        onRefresh={handleRefresh}
        onLayoutChange={handleLayoutChange}
        onIncludeVirtualFkChange={setIncludeVirtualFk}
        onShowOnlyRelatedTablesChange={setShowOnlyRelatedTables}
        onInferVirtualFk={handleInferVirtualFk}
        inferring={inferring}
        onExport={handleExport}
      />
      <Legend />
      {loading && !erDiagramData ? (
        <div className={styles.loadingContainer}>
          <Spin size="large" tip={i18n('workspace.erDiagram.loading')} />
        </div>
      ) : !erDiagramData || erDiagramData.nodes.length === 0 ? (
        <div className={styles.emptyContainer}>
          <Empty description={i18n('workspace.erDiagram.noData')} />
        </div>
      ) : (
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onNodeClick={handleNodeClick}
          onPaneClick={handlePaneClick}
          onEdgeContextMenu={handleEdgeContextMenu}
          onConnect={handleConnect}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          fitView
          fitViewOptions={{ padding: 0.2 }}
          minZoom={0.1}
          maxZoom={2}
          proOptions={{ hideAttribution: true }}
        >
          <Background color="var(--color-border)" gap={16} />
          <Controls position="bottom-right" showInteractive={false} />
          <MiniMap
            nodeColor={miniMapNodeColor}
            maskColor="rgba(0, 0, 0, 0.1)"
            style={{ background: 'var(--color-bg-elevated)' }}
            position="bottom-right"
          />
        </ReactFlow>
      )}
    </div>
  );
};

/** ER图组件，包裹ReactFlowProvider */
const ERDiagram: React.FC<IERDiagramProps> = (props) => {
  return (
    <ReactFlowProvider>
      <ERDiagramInner {...props} />
    </ReactFlowProvider>
  );
};

export default ERDiagram;
