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
  import_electron_updater.autoUpdater.allowDowngrade = false;
  import_electron_updater.autoUpdater.logger = null;
  const send = (event) => {
    if (!mainWindow.isDestroyed()) mainWindow.webContents.send("update:event", event);
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
  import_electron.ipcMain.on("update:install", () => {
    try {
      ;
      globalThis.__ceStopBackend?.();
    } catch (error) {
      console.warn("[CE] could not stop the backend before installing:", error);
    }
    setTimeout(() => import_electron_updater.autoUpdater.quitAndInstall(true, true), 1200);
  });
  import_electron_updater.autoUpdater.on("before-quit-for-update", () => {
    ;
    globalThis.__ceStopBackend?.();
  });
  if (import_electron.app.isPackaged) {
    setTimeout(() => {
      import_electron_updater.autoUpdater.checkForUpdates().catch(() => void 0);
    }, 8e3);
  }
}
// Annotate the CommonJS export names for ESM import in node:
0 && (module.exports = {
  initUpdater
});
