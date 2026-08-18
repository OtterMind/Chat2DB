import type { AgentDataScope } from '@/service/agent';

export type AgentApprovalMode = NonNullable<AgentDataScope['approvalMode']>;

export const DEFAULT_AGENT_APPROVAL_MODE: AgentApprovalMode = 'RISK_BASED';

export function normalizeApprovalMode(mode?: AgentDataScope['approvalMode']): AgentApprovalMode {
  return mode || DEFAULT_AGENT_APPROVAL_MODE;
}

export function approvalModeColor(mode?: AgentDataScope['approvalMode']): 'default' | 'blue' | 'orange' {
  switch (normalizeApprovalMode(mode)) {
    case 'ALWAYS':
      return 'orange';
    case 'RISK_BASED':
      return 'blue';
    default:
      return 'default';
  }
}

const approvalModeRank: Record<AgentApprovalMode, number> = {
  NEVER: 0,
  RISK_BASED: 1,
  ALWAYS: 2,
};

export function effectiveApprovalMode(
  snapshotMode?: AgentDataScope['approvalMode'],
  currentAgentMode?: AgentDataScope['approvalMode'],
): AgentApprovalMode {
  const snapshot = normalizeApprovalMode(snapshotMode);
  const current = normalizeApprovalMode(currentAgentMode);
  return approvalModeRank[snapshot] >= approvalModeRank[current] ? snapshot : current;
}
