import { isDesktop } from '@/utils/env';

export interface AgentConnectorPairing {
  pairingId: string;
  clientName: string;
  userCode: string;
  status: 'pending' | 'approved' | 'denied' | 'exchanged' | 'expired';
  expiresAt: string;
  createdAt: string;
  revision: number;
}

export interface AgentConnectorSession {
  sessionId: string;
  clientName: string;
  agentId: string;
  agentName: string;
  taskId?: string;
  runId?: string;
  status: 'active' | 'revoked' | 'expired';
  createdAt: string;
  lastUsedAt: string;
  refreshTokenExpiresAt: string;
  revokedAt?: string;
  legacyAudit: boolean;
  conversationCount: number;
  pendingApprovalCount: number;
}

export interface AgentConnectorConversation {
  conversationId: string;
  externalSessionId: string;
  taskId: string;
  status: 'active' | 'closed';
  createdAt: string;
  lastUsedAt: string;
  closedAt?: string;
  pendingApprovalCount: number;
}

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  const url = isDesktop ? `http://127.0.0.1:10825${path}` : path;
  const response = await fetch(url, {
    credentials: 'include',
    headers: { 'content-type': 'application/json', ...init?.headers },
    ...init,
  });
  if (!response.ok) throw new Error(`Connector API failed with HTTP ${response.status}`);
  return response.json() as Promise<T>;
}

export function listPendingPairings(signal?: AbortSignal) {
  return json<AgentConnectorPairing[]>('/api/agent/connectors/pairings/pending', { signal });
}

export function decidePairing(pairing: AgentConnectorPairing, agentId: string | undefined, approved: boolean) {
  return json<AgentConnectorPairing>(`/api/agent/connectors/pairings/${pairing.pairingId}/decision`, {
    method: 'POST',
    body: JSON.stringify({ agentId, approved, expectedRevision: pairing.revision }),
  });
}

export function listConnectorSessions(signal?: AbortSignal) {
  return json<AgentConnectorSession[]>('/api/agent/connectors/sessions', { signal });
}

export function revokeConnectorSession(sessionId: string) {
  return json<AgentConnectorSession>(`/api/agent/connectors/sessions/${sessionId}/revoke`, { method: 'POST' });
}

export async function deleteConnectorSession(sessionId: string) {
  const result = await json<{ deleted?: boolean }>(`/api/agent/connectors/sessions/${encodeURIComponent(sessionId)}`, {
    method: 'DELETE',
  });
  if (result.deleted !== true) throw new Error('Connector Session was not deleted');
  return result;
}

export function listConnectorConversations(sessionId: string, signal?: AbortSignal) {
  return json<AgentConnectorConversation[]>(
    `/api/agent/connectors/sessions/${encodeURIComponent(sessionId)}/conversations`,
    { signal },
  );
}
