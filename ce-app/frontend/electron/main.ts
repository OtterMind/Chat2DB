import { app, BrowserWindow, shell } from 'electron'
import path from 'path'
import { spawn } from 'child_process'
import { existsSync } from 'fs'

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
  console.log('[CE] Starting backend:', cmd, args.join(' '))
  backendProcess = spawn(cmd, args, { cwd, windowsHide: true, stdio: 'ignore', env: process.env })
  backendProcess.on('error', (err) => console.error('[CE] Backend failed:', err))
  backendProcess.on('exit', (code) => { console.log('[CE] Backend exited:', code); backendProcess = null })
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1440, height: 900, minWidth: 1024, minHeight: 768,
    title: 'Cutting Edge', backgroundColor: '#0F172A',
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false },
  })
  if (process.env.VITE_DEV_SERVER_URL) mainWindow.loadURL(process.env.VITE_DEV_SERVER_URL)
  else mainWindow.loadFile(path.join(__dirname, '../dist/index.html'))
  mainWindow.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' } })
}

app.whenReady().then(() => {
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