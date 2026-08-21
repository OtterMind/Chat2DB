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
  import_electron_updater.autoUpdater.allowDowngrade = false;
  import_electron_updater.autoUpdater.logger = null;
  const send = (event) => {
    if (!mainWindow2.isDestroyed()) mainWindow2.webContents.send("update:event", event);
  };
  let downloading = false;
  import_electron_updater.autoUpdater.on("checking-for-update", () => send({ type: "checking" }));
  import_electron_updater.autoUpdater.on("update-available", (info) => {
    send({
      type: "available",
      version: info.version,
      releaseDate: info.releaseDate,
      notes: typeof info.releaseNotes === "string" ? info.releaseNotes : null
    });
    if (!downloading) {
      downloading = true;
      import_electron_updater.autoUpdater.downloadUpdate().catch((e) => {
        downloading = false;
        send({ type: "error", error: e.message });
      });
    }
  });
  import_electron_updater.autoUpdater.on(
    "update-not-available",
    (info) => send({ type: "not-available", version: info?.version ?? import_electron.app.getVersion() })
  );
  import_electron_updater.autoUpdater.on(
    "download-progress",
    (p) => send({
      type: "progress",
      percent: p.percent,
      transferred: p.transferred,
      total: p.total,
      bytesPerSecond: p.bytesPerSecond
    })
  );
  import_electron_updater.autoUpdater.on("update-downloaded", (info) => {
    downloading = false;
    send({ type: "downloaded", version: info.version });
  });
  import_electron_updater.autoUpdater.on("error", (err) => {
    downloading = false;
    send({ type: "error", error: err?.message ?? String(err) });
  });
  import_electron.ipcMain.on("update:run", async () => {
    if (!import_electron.app.isPackaged) {
      send({ type: "error", error: "\u0628\u0647\u200C\u0631\u0648\u0632\u0631\u0633\u0627\u0646\u06CC \u0641\u0642\u0637 \u062F\u0631 \u0646\u0633\u062E\u0647\u200C\u06CC \u0646\u0635\u0628\u200C\u0634\u062F\u0647 \u06A9\u0627\u0631 \u0645\u06CC\u200C\u06A9\u0646\u062F" });
      return;
    }
    try {
      await import_electron_updater.autoUpdater.checkForUpdates();
    } catch (e) {
      send({ type: "error", error: e.message });
    }
  });
  import_electron.ipcMain.on("update:install", () => import_electron_updater.autoUpdater.quitAndInstall(true, true));
  if (import_electron.app.isPackaged) {
    setTimeout(() => {
      import_electron_updater.autoUpdater.checkForUpdates().catch(() => void 0);
    }, 8e3);
  }
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
function showFatal(win, message) {
  const html = `<!doctype html><html><head><meta charset="utf-8"><style>
    body{background:#0F172A;color:#F8FAFC;font-family:Segoe UI,system-ui,sans-serif;
         display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
    .box{max-width:640px;padding:32px;background:#1E293B;border-radius:12px;border:1px solid #334155}
    h1{font-size:18px;margin:0 0 12px;color:#818CF8}
    pre{white-space:pre-wrap;word-break:break-word;font-size:13px;color:#CBD5E1;margin:0}
    p{font-size:13px;color:#94A3B8;margin:16px 0 0}
  </style></head><body><div class="box"><h1>Cutting Edge could not start the interface</h1>
  <pre>${message.replace(/[<>&]/g, (c) => ({ "<": "&lt;", ">": "&gt;", "&": "&amp;" })[c])}</pre>
  <p>Restart the app with CE_DEBUG=1 to open developer tools.</p></div></body></html>`;
  win.loadURL("data:text/html;charset=utf-8," + encodeURIComponent(html));
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
  else {
    const indexPath = import_path.default.join(__dirname, "../dist/index.html");
    if (!(0, import_fs.existsSync)(indexPath)) {
      showFatal(mainWindow, `UI bundle not found at ${indexPath}`);
    } else {
      mainWindow.loadFile(indexPath);
    }
  }
  if (process.env.CE_DEBUG === "1") mainWindow.webContents.openDevTools({ mode: "detach" });
  mainWindow.webContents.on("did-fail-load", (_e, errorCode, errorDescription, validatedURL) => {
    console.error("[CE] Renderer failed to load:", errorCode, errorDescription, validatedURL);
    if (mainWindow) showFatal(mainWindow, `${errorDescription} (${errorCode})
${validatedURL}`);
  });
  mainWindow.webContents.on("render-process-gone", (_e, details) => {
    console.error("[CE] Renderer process gone:", details.reason);
  });
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
