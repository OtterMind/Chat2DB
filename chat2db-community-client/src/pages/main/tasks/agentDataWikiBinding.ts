import type { AgentDataWikiBinding, AgentDefinition } from '@/service/agent';

export const agentDataWikiBindings = (agent?: AgentDefinition): AgentDataWikiBinding[] =>
  agent?.dataWikiBindings?.length
    ? agent.dataWikiBindings.map((binding) => ({ ...binding }))
    : (agent?.dataWikiIds || []).map((dataWikiId) => ({
        dataWikiId,
        maxRows: 200,
        timeoutSeconds: 60,
        approvalMode: 'RISK_BASED',
        allowProduction: false,
      }));

export const dataWikiBindingIds = (bindings?: AgentDataWikiBinding[]): string[] =>
  (bindings || []).map((binding) => binding.dataWikiId);
