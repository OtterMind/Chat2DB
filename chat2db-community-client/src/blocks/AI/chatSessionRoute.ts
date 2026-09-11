type RouteLocation = Pick<Location, 'pathname' | 'hash'>;

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
