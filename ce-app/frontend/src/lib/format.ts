/**
 * Formatting helpers — small, dependency-free.
 */

/** Format a number with a fixed number of decimal places, no trailing zeros. */
export function formatNumber(value: number, decimals: number = 2): string {
  if (!Number.isFinite(value)) return '—'
  return value.toFixed(decimals).replace(/\.?0+$/, '')
}

/** Format a duration in seconds as MM:SS.ff */
export function formatDuration(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '00:00.00'
  const minutes = Math.floor(seconds / 60)
  const secs = seconds % 60
  return `${String(minutes).padStart(2, '0')}:${secs.toFixed(2).padStart(5, '0')}`
}

/** Format a byte size as human-readable. */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let n = bytes
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i += 1
  }
  return `${n.toFixed(n < 10 ? 1 : 0)} ${units[i]}`
}
