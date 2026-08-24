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

  /** Opens the OS file picker and returns absolute paths of the chosen media. */
  pickMedia: () => ipcRenderer.invoke('media:pick') as Promise<string[]>,

  /** Ask the OS where to write the exported file. */
  saveDialog: (suggestedName: string) =>
    ipcRenderer.invoke('media:save-dialog', suggestedName) as Promise<string | null>,

  /** Fullscreen control; F11 and Escape do the same thing from the keyboard. */
  toggleFullscreen: () => ipcRenderer.invoke('window:fullscreen:toggle') as Promise<boolean>,
  isFullscreen: () => ipcRenderer.invoke('window:fullscreen:get') as Promise<boolean>,
  onFullscreenChange: (callback: (value: boolean) => void) => {
    const listener = (_event: IpcRendererEvent, value: boolean) => callback(value)
    ipcRenderer.on('window:fullscreen', listener)
    return () => ipcRenderer.removeListener('window:fullscreen', listener)
  },

  /** Is the bundled backend alive, and why not — plus the tail of its log. */
  backendStatus: () =>
    ipcRenderer.invoke('backend:status') as Promise<{
      running: boolean
      pid: number | null
      failure: string | null
      logPath: string
      tail: string[]
    }>,
  /** Try to start the backend again after a crash. */
  restartBackend: () => ipcRenderer.invoke('backend:restart') as Promise<{ running: boolean; failure: string | null }>,

  /** Absolute path of the log file, for the diagnostics screen. */
  logPath: () => ipcRenderer.invoke('log:path') as Promise<string>,
  /** Reveal the log file in Explorer so the user can attach it to a report. */
  openLogFolder: () => ipcRenderer.send('log:open'),
  /** Open an allowlisted https URL (the NVIDIA driver page) in the browser. */
  openExternal: (url: string) => ipcRenderer.send('shell:open', url),

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
