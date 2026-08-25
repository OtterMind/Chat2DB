import agentService, { agentEffectiveDataScopes, type AgentDefinition } from '@/service/agent';
import { decidePairing, listPendingPairings, type AgentConnectorPairing } from '@/service/agentConnector';
import { Alert, App, Avatar, Modal, Select, Space, Tag, Typography } from 'antd';
import { Bot, Database, ShieldCheck } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';

const { Text, Title } = Typography;

export default function AgentConnectorAuthorization() {
  const { message } = App.useApp();
  const [pairing, setPairing] = useState<AgentConnectorPairing>();
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [agentId, setAgentId] = useState<string>();
  const [submitting, setSubmitting] = useState(false);

  const refresh = useCallback(async (signal?: AbortSignal) => {
    try {
      const pending = await listPendingPairings(signal);
      setPairing((current) => pending.find((item) => item.pairingId === current?.pairingId) || pending[0]);
    } catch (error) {
      if (!signal?.aborted) console.warn('Failed to poll Agent Connector pairings', error);
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void refresh(controller.signal);
    const timer = window.setInterval(() => void refresh(controller.signal), 1500);
    return () => {
      controller.abort();
      window.clearInterval(timer);
    };
  }, [refresh]);

  useEffect(() => {
    if (!pairing) return;
    void agentService.listAgents().then((items) => {
      const available = items.filter((item) => item.status === 'ACTIVE');
      setAgents(available);
      setAgentId((current) => (current && available.some((item) => item.id === current) ? current : available[0]?.id));
    });
  }, [pairing?.pairingId]);

  const selected = useMemo(() => agents.find((item) => item.id === agentId), [agentId, agents]);
  const scopes = agentEffectiveDataScopes(selected);

  const decide = async (approved: boolean) => {
    if (!pairing || (approved && !agentId)) return;
    setSubmitting(true);
    try {
      await decidePairing(pairing, agentId, approved);
      message.success(approved ? '已授权 DeepSeek Harness 连接' : '已拒绝连接');
      setPairing(undefined);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '连接授权失败');
      await refresh();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={Boolean(pairing)}
      title={
        <Space>
          <Avatar size={32} icon={<Bot size={18} />} />
          <span>授权 Agent Connector</span>
        </Space>
      }
      okText="授权连接"
      cancelText="拒绝"
      confirmLoading={submitting}
      okButtonProps={{ disabled: !selected || scopes.length === 0 }}
      maskClosable={false}
      closable={false}
      onOk={() => void decide(true)}
      onCancel={() => void decide(false)}
    >
      {pairing && (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <div>
            <Text type="secondary">请求连接的应用</Text>
            <Title level={5} style={{ margin: '4px 0 0' }}>{pairing.clientName}</Title>
            <Text code>{pairing.userCode}</Text>
          </div>
          <div style={{ width: '100%' }}>
            <Text strong>选择授权使用的 Agent</Text>
            <Select
              value={agentId}
              style={{ width: '100%', marginTop: 8 }}
              placeholder="请选择 Agent"
              onChange={setAgentId}
              options={agents.map((agent) => ({
                value: agent.id,
                label: `${agent.name} · ${agent.runtimeType === 'EMBEDDED_SPRING_AI' ? 'Spring AI' : 'External Runtime'}`,
              }))}
            />
          </div>
          {selected && (
            <Space wrap>
              <Tag icon={<ShieldCheck size={12} />}>{selected.capabilities.join(' · ') || '无能力'}</Tag>
              <Tag icon={<Database size={12} />}>{scopes.length} 个有效数据范围</Tag>
              <Tag>{selected.dataWikiIds?.length || 0} 个 DataWiki</Tag>
            </Space>
          )}
          <Alert
            type={scopes.length ? 'warning' : 'error'}
            showIcon
            message={scopes.length ? '该连接将固定绑定所选 Agent' : '当前 Agent 尚未绑定数据范围'}
            description="权限取 Agent 显式数据范围与 DataWiki 派生范围的并集；SQL 行数、超时、生产环境与审批策略继续由 Chat2DB 强制执行。"
          />
        </Space>
      )}
    </Modal>
  );
}
