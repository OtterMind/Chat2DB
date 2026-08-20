"use strict";

// electron/preload.ts
var import_electron = require("electron");
import_electron.contextBridge.exposeInMainWorld("cuttingEdge", {
  platform: process.platform,
  versions: { electron: process.versions.electron, chrome: process.versions.chrome, node: process.versions.node },
  // Auto-update IPC
  checkUpdate: () => import_electron.ipcRenderer.send("update:check"),
  downloadUpdate: () => import_electron.ipcRenderer.send("update:download"),
  installUpdate: () => import_electron.ipcRenderer.send("update:install")
});
