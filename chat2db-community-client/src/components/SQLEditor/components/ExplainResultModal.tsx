import React, { useMemo } from 'react';
import { Alert, Modal, Spin, Tabs, Tree } from 'antd';
import type { DataNode } from 'antd/es/tree';
import i18n from '@/i18n';
import type { IExplainResult } from '@/service/sql';
import { parseExplainPlan, type ExplainPlanNode } from '../helper/explainPlan';

interface ExplainResultModalProps {
  open: boolean;
  loading: boolean;
  mode: 'json' | 'analyze';
  result?: IExplainResult | null;
  errorMessage?: string | null;
  onCancelRequest: () => void;
  onClose: () => void;
}

const panelStyle: React.CSSProperties = {
  maxHeight: 520,
  overflow: 'auto',
};

const rawStyle: React.CSSProperties = {
  ...panelStyle,
  margin: 0,
  padding: 12,
  border: '1px solid var(--color-border, #d9d9d9)',
  borderRadius: 6,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
};

const ExplainResultModal = ({
  open,
  loading,
  mode,
  result,
  errorMessage,
  onCancelRequest,
  onClose,
}: ExplainResultModalProps) => {
  const activeMode = result?.mode || mode;
  const parsedPlan = useMemo(() => parseExplainPlan(activeMode, result?.rawPlan), [activeMode, result?.rawPlan]);
  const treeData = useMemo(() => toTreeData(parsedPlan.nodes), [parsedPlan.nodes]);

  return (
    <Modal
      open={open}
      title={activeMode === 'analyze' ? i18n('common.button.explainAnalyze') : i18n('common.button.explainJson')}
      width={960}
      destroyOnClose
      onCancel={loading ? onCancelRequest : onClose}
      okText={loading ? i18n('common.button.cancel') : i18n('common.button.close')}
      okButtonProps={loading ? { danger: true } : undefined}
      onOk={loading ? onCancelRequest : onClose}
      cancelButtonProps={{ style: { display: 'none' } }}
    >
      {activeMode === 'analyze' && (
        <Alert
          showIcon
          type="warning"
          style={{ marginBottom: 12 }}
          message={i18n('common.explain.analyzeWarning')}
        />
      )}
      {loading && (
        <div style={{ padding: 32, textAlign: 'center' }}>
          <Spin />
        </div>
      )}
      {!loading && errorMessage && <Alert showIcon type="error" message={errorMessage} />}
      {!loading && !errorMessage && result && (
        <Tabs
          items={[
            {
              key: 'tree',
              label: i18n('common.explain.planTree'),
              children: treeData.length ? (
                <Tree treeData={treeData} defaultExpandAll selectable={false} />
              ) : (
                <Alert showIcon type="info" message={i18n('common.explain.noPlanNodes')} />
              ),
            },
            {
              key: 'raw',
              label: activeMode === 'json' ? i18n('common.explain.rawJson') : i18n('common.explain.rawOutput'),
              children: <pre style={rawStyle}>{parsedPlan.formattedRawText || result.rawPlan || ''}</pre>,
            },
          ]}
        />
      )}
    </Modal>
  );
};

export default ExplainResultModal;

function toTreeData(nodes: ExplainPlanNode[]): DataNode[] {
  return nodes.map((node) => ({
    key: node.key,
    title: (
      <div style={{ minWidth: 0 }}>
        <div style={{ fontWeight: 600, wordBreak: 'break-word' }}>{node.title}</div>
        <div style={{ color: 'var(--color-text-secondary, #8c8c8c)', fontSize: 12, margin: '2px 0 6px' }}>
          {i18n('common.explain.source')}: {node.sourcePath}
        </div>
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(128px, 1fr))',
            gap: 8,
            maxWidth: 760,
          }}
        >
          {node.metrics.map((metric) => (
            <div
              key={`${node.key}:${metric.label}`}
              style={{
                border: '1px solid var(--color-border, #d9d9d9)',
                borderRadius: 6,
                padding: '6px 8px',
                minWidth: 0,
              }}
            >
              <div style={{ color: 'var(--color-text-secondary, #8c8c8c)', fontSize: 12 }}>{metric.label}</div>
              <div style={{ wordBreak: 'break-word' }}>{metric.value}</div>
            </div>
          ))}
        </div>
      </div>
    ),
    children: toTreeData(node.children),
  }));
}
