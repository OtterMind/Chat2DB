import {
  memo,
  useCallback,
  useEffect,
  useRef,
  useState,
  useMemo,
  forwardRef,
  ForwardedRef,
  useImperativeHandle,
} from 'react';

import {
  ReactFlow,
  // MiniMap,
  Controls,
  Background,
  useNodesState,
  useEdgesState,
  addEdge,
  BackgroundVariant,
  ConnectionLineType,
  ControlButton,
} from '@xyflow/react';

import '@xyflow/react/dist/style.css';
import {
  getLaidOutElements,
  getEdgesFromData,
  trimLayoutPositions,
  parseStoredLayout,
  type IStoredLayout,
} from './utils';
import ERModalTable from './components/ERModalTable';
import { IERTableDetail } from '@/typings/er';
import { useGlobalStore } from '@/store/global';
import { useStyles } from './style';
import { useUpdateEffect } from 'ahooks';
import { v4 as uuidv4 } from 'uuid';
import { Loading } from '@chat2db/ui';
import DownloadFlow from './components/DownloadFlow';

const proOptions = { hideAttribution: false };

interface IProps {
  className?: string;
  erModalData?: IERTableDetail[];
  storedLayout?: string;
  onSaveLayout: (nodes: IStoredLayout | null) => void;
}

export interface ERModalRef {
  handleResetLayout: () => void;
}

const ERModal = forwardRef((props: IProps, ref: ForwardedRef<ERModalRef>) => {
  const { className, erModalData, storedLayout: _storedLayout, onSaveLayout } = props;
  const [nodes, setNodes, onNodesChange] = useNodesState<any>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<any>([]);
  const [storedLayout, setStoredLayout] = useState<IStoredLayout | null>(parseStoredLayout(_storedLayout));
  const [loading, setLoading] = useState(true);
  const { styles, cx } = useStyles();
  const reactFlowRef = useRef(null);
  const [connectingNode, setConnectingNode] = useState<{
    nodeId: string;
    handleId: string;
    handleType: 'source' | 'target';
  } | null>(null);

  const appearance = useGlobalStore((s) => s.baseSetting.appearance);

  const reactFlowKey = useMemo(() => {
    return `er-flow-${uuidv4()}`;
  }, []);

  const nodeTypes = useMemo(() => {
    return {
      erModalTable: (p: any) => {
        const tableDetail = p.data.tableDetail;
        const virtualForeignKeys: any = [];
        storedLayout?.virtualEdges?.forEach((edge) => {
          if (edge.target === tableDetail.name) {
            virtualForeignKeys.push(edge.targetHandle);
          }
        });
        return (
          <ERModalTable
            tableDetail={tableDetail}
            virtualForeignKeys={virtualForeignKeys}
            connectingNode={connectingNode}
          />
        );
      },
    };
  }, [storedLayout, connectingNode]);

  useUpdateEffect(() => {
    onSaveLayout(storedLayout);
  }, [storedLayout]);

  useImperativeHandle(ref, () => ({
    handleResetLayout,
  }));

  // Extend edge-change handling to persist the layout.
  const handleEdgesChange = useCallback(
    (changes: any) => {
      const filteredChanges = changes.filter((change: any) => {
        if (change.type !== 'remove') {
          return true;
        }
        const edgeToRemove = edges.find((edge: any) => edge.id === change.id);
        return edgeToRemove?.style?.strokeDasharray;
      });

      // Check whether a dashed edge was deleted.
      const hasVirtualEdgeRemoval = changes.some((change: any) => {
        if (change.type === 'remove') {
          const edgeToRemove = edges.find((edge: any) => edge.id === change.id);
          return edgeToRemove?.style?.strokeDasharray;
        }
        return false;
      });

      onEdgesChange(filteredChanges);

      // Persist the layout after deleting a dashed edge.
      if (hasVirtualEdgeRemoval) {
        const newStoredLayout = {
          ...(storedLayout || {}),
          virtualEdges: storedLayout?.virtualEdges?.filter(
            (edge) => !changes.some((change) => change.type === 'remove' && change.id === edge.id),
          ),
        };
        setStoredLayout(newStoredLayout);
      }
    },
    [onEdgesChange, edges, nodes, storedLayout, onSaveLayout],
  );

  // Customize the initLayout function.
  useEffect(() => {
    let disposed = false;
    const initLayout = async () => {
      if (!erModalData) {
        return;
      }
      const { nodes: laidOutNodes, edges: laidOutEdges } = await getLaidOutElements(
        erModalData,
        getEdgesFromData(erModalData, storedLayout),
      );

      try {
        // Use persisted positions when a stored layout is available.
        if (storedLayout) {
          laidOutNodes.forEach((node) => {
            const storedNode = storedLayout.nodeList?.find((n: any) => n.id === node.id);
            if (storedNode) {
              node.position = storedNode.position;
            }
          });
        }
      } catch (error) {
        console.error('initLayout error', error);
      }

      if (disposed) {
        return;
      }
      setNodes(laidOutNodes);
      setEdges(laidOutEdges);
      setLoading(false);
    };

    initLayout();
    return () => {
      disposed = true;
    };
  }, [erModalData]);

  // Add a handler for the end of node dragging.
  const onNodeDragStop = useCallback(() => {
    const nodeList = trimLayoutPositions(nodes);
    setStoredLayout({
      ...storedLayout,
      nodeList,
    });
  }, [nodes, storedLayout]);

  // Extend connection handling to persist the layout.
  const onConnect = useCallback(
    (params) => {
      const newEdge = {
        id: uuidv4(),
        ...params,
        style: {
          strokeWidth: 2,
          strokeDasharray: '5 5',
        },
      };
      setEdges((eds) => addEdge(newEdge, eds));

      // Persist the layout after adding a dashed edge.
      const newStoredLayout = {
        ...(storedLayout || {}),
        virtualEdges: [...(storedLayout?.virtualEdges || []), newEdge],
      };
      setStoredLayout(newStoredLayout);
    },
    [setEdges, nodes, onSaveLayout, storedLayout],
  );

  const onConnectStart = useCallback((_, e: any) => {
    setConnectingNode(e);
  }, []);

  // Handle the end of a connection operation.
  const onConnectEnd = useCallback(() => {
    setConnectingNode(null);
  }, []);

  // Add a relayout handler.
  const handleResetLayout = useCallback(async () => {
    if (!erModalData) {
      return;
    }
    setLoading(true);
    const { nodes: laidOutNodes, edges: laidOutEdges } = await getLaidOutElements(
      erModalData,
      getEdgesFromData(erModalData, {
        ...storedLayout,
        nodeList: undefined, // Clear node positions.
      }),
    );

    setNodes(laidOutNodes);
    setEdges(laidOutEdges);

    // Update the stored layout while preserving virtual-edge data.
    const newStoredLayout = {
      ...storedLayout,
      nodeList: trimLayoutPositions(laidOutNodes),
    };
    setStoredLayout(newStoredLayout);

    setTimeout(() => {
      setLoading(false);
    }, 200);
  }, [erModalData, storedLayout]);

  if (loading) {
    return <Loading />;
  }

  return (
    <div className={cx(styles.container, className)}>
      <ReactFlow
        id={reactFlowKey}
        ref={reactFlowRef}
        colorMode={appearance.includes('dark') ? 'dark' : 'light'}
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={handleEdgesChange}
        onNodeDragStop={onNodeDragStop}
        onConnect={onConnect}
        onConnectStart={onConnectStart}
        onConnectEnd={onConnectEnd}
        nodeTypes={nodeTypes}
        proOptions={proOptions}
        connectionLineStyle={{
          strokeWidth: 2,
          strokeDasharray: '5 5',
        }}
        connectionLineType={ConnectionLineType.Step}
        defaultEdgeOptions={{
          animated: false,
          style: { strokeWidth: 3 },
        }}
        fitView
        minZoom={0.4}
        maxZoom={1.5}
      >
        <Controls>
          <ControlButton>
            <DownloadFlow reactFlowKey={reactFlowKey} />
          </ControlButton>
        </Controls>
        {/* <MiniMap /> */}
        <Background variant={BackgroundVariant.Dots} gap={12} size={1} />
      </ReactFlow>
    </div>
  );
});

export default memo(ERModal);
