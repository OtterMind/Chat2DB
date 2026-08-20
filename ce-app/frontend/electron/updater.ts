import { autoUpdater } from 'electron-updater'
import { BrowserWindow, ipcMain } from 'electron'

// Electron-updater works ONLY in packaged (installed) mode.
// In dev mode it will throw "Skip checkForUpdates because application is not packed".

export function initUpdater(mainWindow: BrowserWindow) {
  autoUpdater.autoDownload = false
  autoUpdater.autoInstallOnAppQuit = true

  autoUpdater.on('checking-for-update', () => {
    mainWindow.webContents.send('update:checking')
  })
  autoUpdater.on('update-available', (info) => {
    mainWindow.webContents.send('update:available', {
      version: info.version,
      releaseDate: info.releaseDate,
      releaseNotes: info.releaseNotes,
    })
  })
  autoUpdater.on('update-not-available', () => {
    mainWindow.webContents.send('update:not-available')
  })
  autoUpdater.on('download-progress', (p) => {
    mainWindow.webContents.send('update:progress', {
      percent: p.percent,
      bytesPerSecond: p.bytesPerSecond,
      downloaded: p.transferred,
      total: p.total,
    })
  })
  autoUpdater.on('update-downloaded', () => {
    mainWindow.webContents.send('update:downloaded')
  })
  autoUpdater.on('error', (err) => {
    mainWindow.webContents.send('update:error', { error: err.message })
  })

  ipcMain.on('update:check', () => {
    try { autoUpdater.checkForUpdates() } catch (e: any) {
      mainWindow.webContents.send('update:error', { error: e.message })
    }
  })
  ipcMain.on('update:download', () => {
    autoUpdater.downloadUpdate()
  })
  ipcMain.on('update:install', () => {
    autoUpdater.quitAndInstall(true, true)
  })
}