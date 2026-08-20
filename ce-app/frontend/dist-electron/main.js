"use strict";
var __create = Object.create;
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __getProtoOf = Object.getPrototypeOf;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __esm = (fn, res) => function __init() {
  return fn && (res = (0, fn[__getOwnPropNames(fn)[0]])(fn = 0)), res;
};
var __export = (target, all) => {
  for (var name in all)
    __defProp(target, name, { get: all[name], enumerable: true });
};
var __copyProps = (to, from, except, desc) => {
  if (from && typeof from === "object" || typeof from === "function") {
    for (let key of __getOwnPropNames(from))
      if (!__hasOwnProp.call(to, key) && key !== except)
        __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
  }
  return to;
};
var __toESM = (mod, isNodeMode, target) => (target = mod != null ? __create(__getProtoOf(mod)) : {}, __copyProps(
  // If the importer is in node compatibility mode or this is not an ESM
  // file that has been converted to a CommonJS file using a Babel-
  // compatible transform (i.e. "__esModule" has not been set), then set
  // "default" to the CommonJS "module.exports" for node compatibility.
  isNodeMode || !mod || !mod.__esModule ? __defProp(target, "default", { value: mod, enumerable: true }) : target,
  mod
));
var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);

// electron/updater.ts
var updater_exports = {};
__export(updater_exports, {
  initUpdater: () => initUpdater
});
function initUpdater(mainWindow2) {
  import_electron_updater.autoUpdater.autoDownload = false;
  import_electron_updater.autoUpdater.autoInstallOnAppQuit = true;
  import_electron_updater.autoUpdater.on("checking-for-update", () => {
    mainWindow2.webContents.send("update:checking");
  });
  import_electron_updater.autoUpdater.on("update-available", (info) => {
    mainWindow2.webContents.send("update:available", {
      version: info.version,
      releaseDate: info.releaseDate,
      releaseNotes: info.releaseNotes
    });
  });
  import_electron_updater.autoUpdater.on("update-not-available", () => {
    mainWindow2.webContents.send("update:not-available");
  });
  import_electron_updater.autoUpdater.on("download-progress", (p) => {
    mainWindow2.webContents.send("update:progress", {
      percent: p.percent,
      bytesPerSecond: p.bytesPerSecond,
      downloaded: p.transferred,
      total: p.total
    });
  });
  import_electron_updater.autoUpdater.on("update-downloaded", () => {
    mainWindow2.webContents.send("update:downloaded");
  });
  import_electron_updater.autoUpdater.on("error", (err) => {
    mainWindow2.webContents.send("update:error", { error: err.message });
  });
  import_electron.ipcMain.on("update:check", () => {
    try {
      import_electron_updater.autoUpdater.checkForUpdates();
    } catch (e) {
      mainWindow2.webContents.send("update:error", { error: e.message });
    }
  });
  import_electron.ipcMain.on("update:download", () => {
    import_electron_updater.autoUpdater.downloadUpdate();
  });
  import_electron.ipcMain.on("update:install", () => {
    import_electron_updater.autoUpdater.quitAndInstall(true, true);
  });
}
var import_electron_updater, import_electron;
var init_updater = __esm({
  "electron/updater.ts"() {
    "use strict";
    import_electron_updater = require("electron-updater");
    import_electron = require("electron");
  }
});

// electron/main.ts
var import_electron2 = require("electron");
var import_path = __toESM(require("path"));
var import_child_process = require("child_process");
var import_fs = require("fs");
var backendProcess = null;
var mainWindow = null;
function startBackend() {
  if (process.env.CE_MANUAL_BACKEND === "1") return;
  if (backendProcess) return;
  const resourcesBackend = import_path.default.join(process.resourcesPath, "backend");
  const exePath = import_path.default.join(resourcesBackend, "cutting-edge-backend.exe");
  const pythonPath = import_path.default.join(resourcesBackend, "python", "python.exe");
  let cmd;
  let args;
  let cwd;
  if ((0, import_fs.existsSync)(exePath)) {
    cmd = exePath;
    args = [];
    cwd = resourcesBackend;
  } else if ((0, import_fs.existsSync)(pythonPath)) {
    cmd = pythonPath;
    args = ["run_backend.py"];
    cwd = resourcesBackend;
  } else {
    console.warn("[CE] Bundled backend not found at", resourcesBackend);
    return;
  }
  const ffmpegDir = import_path.default.join(process.resourcesPath, "ffmpeg");
  if ((0, import_fs.existsSync)(ffmpegDir)) {
    process.env.CE_FFMPEG_DIR = ffmpegDir;
    process.env.PATH = ffmpegDir + import_path.default.delimiter + (process.env.PATH ?? "");
  }
  console.log("[CE] Starting backend:", cmd, args.join(" "));
  backendProcess = (0, import_child_process.spawn)(cmd, args, { cwd, windowsHide: true, stdio: "ignore", env: process.env });
  backendProcess.on("error", (err) => console.error("[CE] Backend failed:", err));
  backendProcess.on("exit", (code) => {
    console.log("[CE] Backend exited:", code);
    backendProcess = null;
  });
}
function createWindow() {
  mainWindow = new import_electron2.BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1024,
    minHeight: 768,
    title: "Cutting Edge",
    backgroundColor: "#0F172A",
    webPreferences: { preload: import_path.default.join(__dirname, "preload.js"), contextIsolation: true, nodeIntegration: false }
  });
  if (process.env.VITE_DEV_SERVER_URL) mainWindow.loadURL(process.env.VITE_DEV_SERVER_URL);
  else mainWindow.loadFile(import_path.default.join(__dirname, "../dist/index.html"));
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    import_electron2.shell.openExternal(url);
    return { action: "deny" };
  });
}
import_electron2.app.whenReady().then(() => {
  startBackend();
  createWindow();
  try {
    const { initUpdater: initUpdater2 } = (init_updater(), __toCommonJS(updater_exports));
    initUpdater2(mainWindow);
  } catch (e) {
    console.log("[CE] updater not available in dev mode:", e);
  }
  import_electron2.app.on("activate", () => {
    if (import_electron2.BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});
import_electron2.app.on("window-all-closed", () => {
  if (process.platform !== "darwin") {
    if (backendProcess) {
      backendProcess.kill();
      backendProcess = null;
    }
    import_electron2.app.quit();
  }
});
