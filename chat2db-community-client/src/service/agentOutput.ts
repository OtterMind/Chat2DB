import pi from './pi';
import type { AgentOutputPage, AgentOutputQuery } from '@/types/agentOutput';

export const readAgentOutput = async (
  sessionId: string, artifactId: string, query: AgentOutputQuery, signal: AbortSignal,
): Promise<AgentOutputPage> => {
  const reference = { sessionId, artifactId };
  if (query.pattern) {
    const page = await pi.outputs.search({ ...reference, pattern: query.pattern,
      cursor: query.cursor, limit: 100, literal: true, ignoreCase: true }, { signal });
    return { content: page.matches.map((match) => `${match.line}: ${match.content}`).join('\n'),
      nextCursor: page.nextCursor, hasMore: page.hasMore, warning: page.warning };
  }
  return pi.outputs.read({ ...reference, cursor: query.cursor, limit: 100 }, { signal });
};
