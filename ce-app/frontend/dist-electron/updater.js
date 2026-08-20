"use strict";
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __hasOwnProp = Object.prototype.hasOwnProperty;
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
var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);

// electron/updater.ts
var updater_exports = {};
__export(updater_exports, {
  initUpdater: () => initUpdater
});
module.exports = __toCommonJS(updater_exports);
var import_electron_updater = require("electron-updater");
var import_electron = require("electron");
function initUpdater(mainWindow) {
  import_electron_updater.autoUpdater.autoDownload = false;
  import_electron_updater.autoUpdater.autoInstallOnAppQuit = true;
  import_electron_updater.autoUpdater.on("checking-for-update", () => {
    mainWindow.webContents.send("update:checking");
  });
  import_electron_updater.autoUpdater.on("update-available", (info) => {
    mainWindow.webContents.send("update:available", {
      version: info.version,
      releaseDate: info.releaseDate,
      releaseNotes: info.releaseNotes
    });
  });
  import_electron_updater.autoUpdater.on("update-not-available", () => {
    mainWindow.webContents.send("update:not-available");
  });
  import_electron_updater.autoUpdater.on("download-progress", (p) => {
    mainWindow.webContents.send("update:progress", {
      percent: p.percent,
      bytesPerSecond: p.bytesPerSecond,
      downloaded: p.transferred,
      total: p.total
    });
  });
  import_electron_updater.autoUpdater.on("update-downloaded", () => {
    mainWindow.webContents.send("update:downloaded");
  });
  import_electron_updater.autoUpdater.on("error", (err) => {
    mainWindow.webContents.send("update:error", { error: err.message });
  });
  import_electron.ipcMain.on("update:check", () => {
    try {
      import_electron_updater.autoUpdater.checkForUpdates();
    } catch (e) {
      mainWindow.webContents.send("update:error", { error: e.message });
    }
  });
  import_electron.ipcMain.on("update:download", () => {
    import_electron_updater.autoUpdater.downloadUpdate();
  });
  import_electron.ipcMain.on("update:install", () => {
    import_electron_updater.autoUpdater.quitAndInstall(true, true);
  });
}
// Annotate the CommonJS export names for ESM import in node:
0 && (module.exports = {
  initUpdater
});
