import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron'

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

// Any uncaught renderer error is written to the same log file as the main process.
window.addEventListener('error', (e) =>
  ipcRenderer.send('log:renderer', 'error', `${e.message} @ ${e.filename}:${e.lineno}`)
)
window.addEventListener('unhandledrejection', (e) =>
  ipcRenderer.send('log:renderer', 'error', `unhandled rejection: ${String(e.reason)}`)
)

contextBridge.exposeInMainWorld('cuttingEdge', {
  platform: process.platform,
  versions: {
    electron: process.versions.electron,
    chrome: process.versions.chrome,
    node: process.versions.node,
  },

  /** Check + download in one shot. */
  runUpdate: () => ipcRenderer.send('update:run'),
  /** Restart into the freshly downloaded version. */
  installUpdate: () => ipcRenderer.send('update:install'),

  /**
   * Subscribe to update progress. Returns an unsubscribe function.
   * Previously the renderer listened for window 'message' events, which the main
   * process never emits — so the UI stayed silent no matter what happened.
   */
  onUpdateEvent: (callback: (payload: UpdatePayload) => void) => {
    const listener = (_event: IpcRendererEvent, payload: UpdatePayload) => callback(payload)
    ipcRenderer.on('update:event', listener)
    return () => ipcRenderer.removeListener('update:event', listener)
  },
})
