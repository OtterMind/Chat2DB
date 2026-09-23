type RouteLocation = Pick<Location, 'pathname' | 'hash'>;

export interface ChatSessionVersionSummary {
  id: string;
  title?: string;
  sessionVersion: 1 | 2;
}

const routePath = (location: RouteLocation) =>
  (location.hash.startsWith('#/') ? location.hash.slice(1) : location.pathname).split('?')[0];

export const getChatSessionId = (location: RouteLocation) => {
  const match = routePath(location).match(/^\/stream\/([^/]+)$/);
  return match ? decodeURIComponent(match[1]) : null;
};

export const getChatSessionUrl = (location: RouteLocation, sessionId?: string) => {
  if (!/^\/stream(?:\/|$)/.test(routePath(location))) return null;
  return `${location.hash.startsWith('#/') ? '#' : ''}/stream${sessionId ? `/${encodeURIComponent(sessionId)}` : ''}`;
};

/**
 * Resolve a session version for a deep link. The list endpoint is normally
 * authoritative, but a transient list failure must not make a V2 session
 * fall through to the legacy history loader. Probe the V2 endpoint before
 * treating an unknown session as V1.
 */
export async function resolveChatSessionVersion(
  sessionId: string,
  sessions: ChatSessionVersionSummary[] | undefined,
  probeV2: () => Promise<{ title?: string }>,
): Promise<ChatSessionVersionSummary> {
  const listed = sessions?.find((session) => session.id === sessionId);
  if (listed) return listed;
  try {
    const session = await probeV2();
    return { id: sessionId, title: session.title, sessionVersion: 2 };
  } catch {
    return { id: sessionId, sessionVersion: 1 };
  }
}
