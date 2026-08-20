import { contextBridge, ipcRenderer } from 'electron'

contextBridge.exposeInMainWorld('cuttingEdge', {
  platform: process.platform,
  versions: { electron: process.versions.electron, chrome: process.versions.chrome, node: process.versions.node },
  // Auto-update IPC
  checkUpdate: () => ipcRenderer.send('update:check'),
  downloadUpdate: () => ipcRenderer.send('update:download'),
  installUpdate: () => ipcRenderer.send('update:install'),
})