/**
 * Resolves the backend origin for both dev and packaged (file://) runtimes.
 *
 * - dev (`http://localhost:5173`): use relative URLs, Vite proxies /api and /ws
 *   to the backend.
 * - packaged (`file://`): there is no origin to be relative to, so talk to the
 *   bundled backend directly.
 */
const injected = (window as unknown as { cuttingEdge?: { backendPort?: number } }).cuttingEdge?.backendPort
export const BACKEND_PORT = typeof injected === 'number' && injected > 0 ? injected : 8742

const isFileProtocol = typeof window !== 'undefined' && window.location.protocol === 'file:'

/** e.g. `http://127.0.0.1:8742` when packaged, `''` (relative) in dev. */
export const backendOrigin = isFileProtocol ? `http://127.0.0.1:${BACKEND_PORT}` : ''

/** e.g. `ws://127.0.0.1:8742/ws` */
export function backendWebSocketUrl(path = '/ws'): string {
  if (isFileProtocol) return `ws://127.0.0.1:${BACKEND_PORT}${path}`
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${protocol}://${window.location.host}${path}`
}
