"use strict";
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __copyProps = (to, from, except, desc) => {
  if (from && typeof from === "object" || typeof from === "function") {
    for (let key of __getOwnPropNames(from))
      if (!__hasOwnProp.call(to, key) && key !== except)
        __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
  }
  return to;
};
var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);

// electron/preload.ts
var preload_exports = {};
module.exports = __toCommonJS(preload_exports);
var import_electron = require("electron");
window.addEventListener(
  "error",
  (e) => import_electron.ipcRenderer.send("log:renderer", "error", `${e.message} @ ${e.filename}:${e.lineno}`)
);
window.addEventListener(
  "unhandledrejection",
  (e) => import_electron.ipcRenderer.send("log:renderer", "error", `unhandled rejection: ${String(e.reason)}`)
);
import_electron.contextBridge.exposeInMainWorld("cuttingEdge", {
  platform: process.platform,
  versions: {
    electron: process.versions.electron,
    chrome: process.versions.chrome,
    node: process.versions.node
  },
  /** Check + download in one shot. */
  runUpdate: () => import_electron.ipcRenderer.send("update:run"),
  /** Restart into the freshly downloaded version. */
  installUpdate: () => import_electron.ipcRenderer.send("update:install"),
  /**
   * Subscribe to update progress. Returns an unsubscribe function.
   * Previously the renderer listened for window 'message' events, which the main
   * process never emits — so the UI stayed silent no matter what happened.
   */
  onUpdateEvent: (callback) => {
    const listener = (_event, payload) => callback(payload);
    import_electron.ipcRenderer.on("update:event", listener);
    return () => import_electron.ipcRenderer.removeListener("update:event", listener);
  }
});
