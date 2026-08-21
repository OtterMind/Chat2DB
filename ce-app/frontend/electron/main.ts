import { app, BrowserWindow, shell, ipcMain, dialog } from 'electron'
import path from 'path'
import { spawn } from 'child_process'
import { existsSync, createWriteStream, mkdirSync, readFileSync, statSync } from 'fs'
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
/** Why the backend is not available, surfaced to the UI instead of a silent failure. */
let backendFailure: string | null = null

function backendLogPath() {
  return path.join(app.getPath('userData'), 'logs', 'backend.log')
}
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
  else {
    log.error('[CE] Bundled backend not found at', resourcesBackend)
    backendFailure = `Bundled backend not found at ${resourcesBackend}`
    return
  }

  const ffmpegDir = path.join(process.resourcesPath, 'ffmpeg')
  if (existsSync(ffmpegDir)) {
    process.env.CE_FFMPEG_DIR = ffmpegDir
    process.env.PATH = ffmpegDir + path.delimiter + (process.env.PATH ?? '')
  }
  log.info('[CE] Starting backend:', cmd, args.join(' '))
  try {
    backendProcess = spawn(cmd, args, { cwd, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], env: process.env })
  } catch (error) {
    backendFailure = `spawn failed: ${String(error)}`
    log.error('[CE] Backend spawn threw:', error)
    return
  }

  // Backend output is the single most useful thing when anything fails; keep it.
  // The directory may not exist on a first run, and an unhandled stream error
  // would take the whole main process down with it.
  try {
    mkdirSync(path.dirname(backendLogPath()), { recursive: true })
    const backendLog = createWriteStream(backendLogPath(), { flags: 'a' })
    backendLog.on('error', (err) => log.error('[CE] backend.log write failed:', err))
    backendProcess.stdout?.pipe(backendLog)
    backendProcess.stderr?.pipe(backendLog)
  } catch (error) {
    log.error('[CE] Could not open backend.log:', error)
  }

  backendProcess.on('error', (err) => {
    backendFailure = String(err)
    log.error('[CE] Backend failed:', err)
  })
  backendProcess.on('exit', (code, signal) => {
    backendFailure = `backend exited with code ${code}${signal ? ` (${signal})` : ''}`
    log.warn('[CE] Backend exited:', code, signal)
    backendProcess = null
  })
}

/**
 * IPC is registered once, at module scope.
 *
 * Registering inside createWindow() meant a second window (or a reload) would
 * throw "second handler for ..." and, worse, hid the fact that a channel was
 * missing entirely: the renderer's invoke() simply rejected and the UI showed
 * nothing at all.
 */
function registerIpc() {
  ipcMain.on('log:renderer', (_e, level: string, message: string) => {
    ;(log as unknown as Record<string, (m: string) => void>)[level === 'error' ? 'error' : 'info'](
      `[renderer] ${message}`
    )
  })

  ipcMain.handle('media:pick', async () => {
    try {
      const result = await dialog.showOpenDialog({
        title: 'Import media',
        properties: ['openFile', 'multiSelections'],
        filters: [
          { name: 'Media', extensions: ['mp4', 'mov', 'mkv', 'webm', 'avi', 'mp3', 'wav', 'm4a', 'aac', 'flac'] },
          { name: 'All files', extensions: ['*'] },
        ],
      })
      log.info('[CE] media:pick ->', result.canceled ? 'cancelled' : result.filePaths.join(', '))
      return result.canceled ? [] : result.filePaths
    } catch (error) {
      log.error('[CE] media:pick failed:', error)
      throw error
    }
  })

  ipcMain.handle('log:path', () => log.transports.file.getFile().path)

  /** Everything the diagnostics screen needs to explain a dead backend. */
  ipcMain.handle('backend:status', () => {
    let tail: string[] = []
    try {
      const file = backendLogPath()
      if (existsSync(file)) {
        const size = statSync(file).size
        const text = readFileSync(file, 'utf8').slice(Math.max(0, size - 8000))
        tail = text.split(/\r?\n/).filter(Boolean).slice(-40)
      }
    } catch (error) {
      tail = [`could not read backend.log: ${String(error)}`]
    }
    return {
      running: backendProcess !== null,
      pid: backendProcess?.pid ?? null,
      failure: backendFailure,
      logPath: backendLogPath(),
      tail,
    }
  })

  /** Manual restart from the UI when the backend died. */
  ipcMain.handle('backend:restart', () => {
    if (backendProcess) {
      try { backendProcess.kill() } catch { /* already gone */ }
      backendProcess = null
    }
    backendFailure = null
    startBackend()
    return { running: backendProcess !== null, failure: backendFailure }
  })
  ipcMain.on('log:open', () => shell.showItemInFolder(log.transports.file.getFile().path))
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


  mainWindow.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' } })
}

app.whenReady().then(() => {
  registerIpc()
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