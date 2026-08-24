import { autoUpdater } from 'electron-updater'
import { BrowserWindow, ipcMain, app } from 'electron'

/**
 * One-click update flow.
 *
 * The renderer talks to a single channel: it sends `update:run` and receives
 * `update:event` messages. Everything in between — checking the feed, deciding
 * whether a differential download is possible, fetching only the changed blocks
 * and staging the installer — is handled here.
 *
 * Note: electron-updater is a no-op in dev; it only works from an installed build.
 */
export type UpdateEvent =
  | { type: 'checking' }
  | { type: 'available'; version: string; releaseDate?: string; notes?: string | null }
  | { type: 'not-available'; version: string }
  | { type: 'progress'; percent: number; transferred: number; total: number; bytesPerSecond: number }
  | { type: 'downloaded'; version: string }
  | { type: 'error'; error: string }

export function initUpdater(mainWindow: BrowserWindow) {
  // We drive the flow ourselves so a single click can check → download → install.
  autoUpdater.autoDownload = false
  autoUpdater.autoInstallOnAppQuit = true
  autoUpdater.allowDowngrade = false
  autoUpdater.logger = null

  const send = (event: UpdateEvent) => {
    if (!mainWindow.isDestroyed()) mainWindow.webContents.send('update:event', event)
  }

  let downloading = false

  autoUpdater.on('checking-for-update', () => send({ type: 'checking' }))

  autoUpdater.on('update-available', (info) => {
    send({
      type: 'available',
      version: info.version,
      releaseDate: info.releaseDate,
      notes: typeof info.releaseNotes === 'string' ? info.releaseNotes : null,
    })
    // one click means: don't ask twice, just fetch the patch
    if (!downloading) {
      downloading = true
      autoUpdater.downloadUpdate().catch((e: Error) => {
        downloading = false
        send({ type: 'error', error: e.message })
      })
    }
  })

  autoUpdater.on('update-not-available', (info) =>
    send({ type: 'not-available', version: info?.version ?? app.getVersion() })
  )

  autoUpdater.on('download-progress', (p) =>
    send({
      type: 'progress',
      percent: p.percent,
      transferred: p.transferred,
      total: p.total,
      bytesPerSecond: p.bytesPerSecond,
    })
  )

  autoUpdater.on('update-downloaded', (info) => {
    downloading = false
    send({ type: 'downloaded', version: info.version })
  })

  autoUpdater.on('error', (err) => {
    downloading = false
    send({ type: 'error', error: err?.message ?? String(err) })
  })

  /** Check, and download automatically when something is found. */
  ipcMain.on('update:run', async () => {
    if (!app.isPackaged) {
      send({ type: 'error', error: 'به‌روزرسانی فقط در نسخه‌ی نصب‌شده کار می‌کند' })
      return
    }
    try {
      await autoUpdater.checkForUpdates()
    } catch (e) {
      send({ type: 'error', error: (e as Error).message })
    }
  })

  ipcMain.on('update:install', () => {
    // Free the files *before* the installer runs. The uninstaller deletes the
    // old version's folder, and it cannot while Python — or an FFmpeg it
    // started — still has a handle on `resources\\ffmpeg\\ffmpeg.exe`. That is
    // the "failed to remove the previous version" reported from the installed
    // app; the update itself had downloaded and verified fine.
    try {
      ;(globalThis as unknown as { __ceStopBackend?: () => void }).__ceStopBackend?.()
    } catch (error) {
      console.warn('[CE] could not stop the backend before installing:', error)
    }
    // A beat for Windows to release the handles, then hand over.
    setTimeout(() => autoUpdater.quitAndInstall(true, true), 1200)
  })

  // The updater can also quit the app by itself (auto-install on quit).
  autoUpdater.on('before-quit-for-update', () => {
    ;(globalThis as unknown as { __ceStopBackend?: () => void }).__ceStopBackend?.()
  })

  // Silent check shortly after launch so the user sees a badge without asking.
  if (app.isPackaged) {
    setTimeout(() => {
      autoUpdater.checkForUpdates().catch(() => undefined)
    }, 8000)
  }
}
