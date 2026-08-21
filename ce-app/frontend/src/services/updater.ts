/**
 * Renderer-side wrapper around the Electron update bridge.
 * In the browser preview the bridge is absent, so every call degrades to a
 * friendly message instead of throwing.
 */
export interface UpdatePayload {
  type: 'checking' | 'available' | 'not-available' | 'progress' | 'downloaded' | 'error'
  version?: string
  percent?: number
  transferred?: number
  total?: number
  bytesPerSecond?: number
  error?: string
  notes?: string | null
}

interface Bridge {
  runUpdate: () => void
  installUpdate: () => void
  onUpdateEvent: (cb: (p: UpdatePayload) => void) => () => void
}

export function updateBridge(): Bridge | null {
  const w = window as unknown as { cuttingEdge?: Partial<Bridge> }
  const b = w.cuttingEdge
  if (!b?.runUpdate || !b.onUpdateEvent || !b.installUpdate) return null
  return b as Bridge
}

export const isDesktop = () => updateBridge() !== null

export function formatBytes(bytes?: number) {
  if (!bytes || bytes < 0) return '—'
  const mb = bytes / 1024 / 1024
  if (mb < 1) return `${Math.round(bytes / 1024)} KB`
  if (mb < 1024) return `${mb.toFixed(1)} MB`
  return `${(mb / 1024).toFixed(2)} GB`
}
