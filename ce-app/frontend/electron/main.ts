import { app, BrowserWindow, shell, ipcMain, dialog } from 'electron'
import path from 'path'
import { spawn } from 'child_process'
import { existsSync, createWriteStream } from 'fs'
import log from 'electron-log/main'

/**
 * Persistent logging.
 *
 * Debugging the installed app used to mean guessing: a black window told us
 * nothing and the bundled backend wrote to a console nobody could see. Now
 * everything lands in a file the user can send us in one click:
 *   %APPDATA%\Cutting Edge\logs\main.log
 */
log.initialize()
log.transports.file.level = 'info'
log.transports.file.maxSize = 5 * 1024 * 1024
log.errorHandler.startCatching({ showDialog: false })
Object.assign(console, log.functions)

let backendProcess: ReturnType<typeof spawn> | null = null
let mainWindow: BrowserWindow | null = null

function startBackend() {
  if (process.env.CE_MANUAL_BACKEND === '1') return
  if (backendProcess) return
  const resourcesBackend = path.join(process.resourcesPath, 'backend')
  const exePath = path.join(resourcesBackend, 'cutting-edge-backend.exe')
  const pythonPath = path.join(resourcesBackend, 'python', 'python.exe')

  let cmd: string; let args: string[]; let cwd: string | undefined
  if (existsSync(exePath)) { cmd = exePath; args = []; cwd = resourcesBackend }
  else if (existsSync(pythonPath)) { cmd = pythonPath; args = ['run_backend.py']; cwd = resourcesBackend }
  else { console.warn('[CE] Bundled backend not found at', resourcesBackend); return }

  const ffmpegDir = path.join(process.resourcesPath, 'ffmpeg')
  if (existsSync(ffmpegDir)) {
    process.env.CE_FFMPEG_DIR = ffmpegDir
    process.env.PATH = ffmpegDir + path.delimiter + (process.env.PATH ?? '')
  }
  log.info('[CE] Starting backend:', cmd, args.join(' '))
  backendProcess = spawn(cmd, args, { cwd, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], env: process.env })

  // Backend output is the single most useful thing when a job fails; keep it.
  const backendLog = createWriteStream(path.join(app.getPath('userData'), 'logs', 'backend.log'), { flags: 'a' })
  backendProcess.stdout?.pipe(backendLog)
  backendProcess.stderr?.pipe(backendLog)

  backendProcess.on('error', (err) => log.error('[CE] Backend failed:', err))
  backendProcess.on('exit', (code) => { log.warn('[CE] Backend exited:', code); backendProcess = null })
}

function showFatal(win: BrowserWindow, message: string) {
  const html = `<!doctype html><html><head><meta charset="utf-8"><style>
    body{background:#0F172A;color:#F8FAFC;font-family:Segoe UI,system-ui,sans-serif;
         display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
    .box{max-width:640px;padding:32px;background:#1E293B;border-radius:12px;border:1px solid #334155}
    h1{font-size:18px;margin:0 0 12px;color:#818CF8}
    pre{white-space:pre-wrap;word-break:break-word;font-size:13px;color:#CBD5E1;margin:0}
    p{font-size:13px;color:#94A3B8;margin:16px 0 0}
  </style></head><body><div class="box"><h1>Cutting Edge could not start the interface</h1>
  <pre>${message.replace(/[<>&]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' }[c] as string))}</pre>
  <p>Restart the app with CE_DEBUG=1 to open developer tools.</p></div></body></html>`
  win.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(html))
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1440, height: 900, minWidth: 1024, minHeight: 768,
    title: 'Cutting Edge', backgroundColor: '#0F172A',
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false },
  })
  if (process.env.VITE_DEV_SERVER_URL) mainWindow.loadURL(process.env.VITE_DEV_SERVER_URL)
  else {
    const indexPath = path.join(__dirname, '../dist/index.html')
    if (!existsSync(indexPath)) {
      showFatal(mainWindow, `UI bundle not found at ${indexPath}`)
    } else {
      mainWindow.loadFile(indexPath)
    }
  }

  if (process.env.CE_DEBUG === '1') mainWindow.webContents.openDevTools({ mode: 'detach' })

  // Never leave the user staring at an empty dark window: surface load failures.
  mainWindow.webContents.on('did-fail-load', (_e, errorCode, errorDescription, validatedURL) => {
    log.error('[CE] Renderer failed to load:', errorCode, errorDescription, validatedURL)
    if (mainWindow) showFatal(mainWindow, `${errorDescription} (${errorCode})\n${validatedURL}`)
  })
  mainWindow.webContents.on('render-process-gone', (_e, details) => {
    log.error('[CE] Renderer process gone:', details.reason)
  })

  // Uncaught renderer errors are forwarded by preload and land in the same file.
  ipcMain.on('log:renderer', (_e, level: string, message: string) => {
    ;(log as unknown as Record<string, (m: string) => void>)[level === 'error' ? 'error' : 'info'](
      `[renderer] ${message}`
    )
  })
  // Native file picker for the editor — the renderer only ever sees paths.
  ipcMain.handle('media:pick', async () => {
    const result = await dialog.showOpenDialog(mainWindow!, {
      title: 'Import media',
      properties: ['openFile', 'multiSelections'],
      filters: [
        { name: 'Media', extensions: ['mp4', 'mov', 'mkv', 'webm', 'avi', 'mp3', 'wav', 'm4a', 'aac', 'flac'] },
        { name: 'All files', extensions: ['*'] },
      ],
    })
    return result.canceled ? [] : result.filePaths
  })

  ipcMain.handle('log:path', () => log.transports.file.getFile().path)
  ipcMain.on('log:open', () => shell.showItemInFolder(log.transports.file.getFile().path))

  mainWindow.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' } })
}

app.whenReady().then(() => {
  log.info(`[CE] Cutting Edge ${app.getVersion()} starting — logs at ${log.transports.file.getFile().path}`)
  startBackend()
  createWindow()
  // Initialize auto-updater (lazy import to avoid issues in dev)
  try {
    const { initUpdater } = require('./updater')
    initUpdater(mainWindow!)
  } catch (e) { console.log('[CE] updater not available in dev mode:', e) }
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow() })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    if (backendProcess) { backendProcess.kill(); backendProcess = null }
    app.quit()
  }
})