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
var __commonJS = (cb, mod) => function __require() {
  return mod || (0, cb[__getOwnPropNames(cb)[0]])((mod = { exports: {} }).exports, mod), mod.exports;
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

// node_modules/electron-log/src/node/packageJson.js
var require_packageJson = __commonJS({
  "node_modules/electron-log/src/node/packageJson.js"(exports2, module2) {
    "use strict";
    var fs = require("fs");
    var path2 = require("path");
    module2.exports = {
      findAndReadPackageJson,
      tryReadJsonAt
    };
    function findAndReadPackageJson() {
      return tryReadJsonAt(getMainModulePath()) || tryReadJsonAt(extractPathFromArgs()) || tryReadJsonAt(process.resourcesPath, "app.asar") || tryReadJsonAt(process.resourcesPath, "app") || tryReadJsonAt(process.cwd()) || { name: void 0, version: void 0 };
    }
    function tryReadJsonAt(...searchPaths) {
      if (!searchPaths[0]) {
        return void 0;
      }
      try {
        const searchPath = path2.join(...searchPaths);
        const fileName = findUp("package.json", searchPath);
        if (!fileName) {
          return void 0;
        }
        const json = JSON.parse(fs.readFileSync(fileName, "utf8"));
        const name = json?.productName || json?.name;
        if (!name || name.toLowerCase() === "electron") {
          return void 0;
        }
        if (name) {
          return { name, version: json?.version };
        }
        return void 0;
      } catch (e) {
        return void 0;
      }
    }
    function findUp(fileName, cwd) {
      let currentPath = cwd;
      while (true) {
        const parsedPath = path2.parse(currentPath);
        const root = parsedPath.root;
        const dir = parsedPath.dir;
        if (fs.existsSync(path2.join(currentPath, fileName))) {
          return path2.resolve(path2.join(currentPath, fileName));
        }
        if (currentPath === root) {
          return null;
        }
        currentPath = dir;
      }
    }
    function extractPathFromArgs() {
      const matchedArgs = process.argv.filter((arg) => {
        return arg.indexOf("--user-data-dir=") === 0;
      });
      if (matchedArgs.length === 0 || typeof matchedArgs[0] !== "string") {
        return null;
      }
      const userDataDir = matchedArgs[0];
      return userDataDir.replace("--user-data-dir=", "");
    }
    function getMainModulePath() {
      try {
        return require.main?.filename;
      } catch {
        return void 0;
      }
    }
  }
});

// node_modules/electron-log/src/node/NodeExternalApi.js
var require_NodeExternalApi = __commonJS({
  "node_modules/electron-log/src/node/NodeExternalApi.js"(exports2, module2) {
    "use strict";
    var childProcess = require("child_process");
    var os = require("os");
    var path2 = require("path");
    var packageJson = require_packageJson();
    var NodeExternalApi = class {
      appName = void 0;
      appPackageJson = void 0;
      platform = process.platform;
      getAppLogPath(appName = this.getAppName()) {
        if (this.platform === "darwin") {
          return path2.join(this.getSystemPathHome(), "Library/Logs", appName);
        }
        return path2.join(this.getAppUserDataPath(appName), "logs");
      }
      getAppName() {
        const appName = this.appName || this.getAppPackageJson()?.name;
        if (!appName) {
          throw new Error(
            "electron-log can't determine the app name. It tried these methods:\n1. Use `electron.app.name`\n2. Use productName or name from the nearest package.json`\nYou can also set it through log.transports.file.setAppName()"
          );
        }
        return appName;
      }
      /**
       * @private
       * @returns {undefined}
       */
      getAppPackageJson() {
        if (typeof this.appPackageJson !== "object") {
          this.appPackageJson = packageJson.findAndReadPackageJson();
        }
        return this.appPackageJson;
      }
      getAppUserDataPath(appName = this.getAppName()) {
        return appName ? path2.join(this.getSystemPathAppData(), appName) : void 0;
      }
      getAppVersion() {
        return this.getAppPackageJson()?.version;
      }
      getElectronLogPath() {
        return this.getAppLogPath();
      }
      getMacOsVersion() {
        const release = Number(os.release().split(".")[0]);
        if (release <= 19) {
          return `10.${release - 4}`;
        }
        return release - 9;
      }
      /**
       * @protected
       * @returns {string}
       */
      getOsVersion() {
        let osName = os.type().replace("_", " ");
        let osVersion = os.release();
        if (osName === "Darwin") {
          osName = "macOS";
          osVersion = this.getMacOsVersion();
        }
        return `${osName} ${osVersion}`;
      }
      /**
       * @return {PathVariables}
       */
      getPathVariables() {
        const appName = this.getAppName();
        const appVersion = this.getAppVersion();
        const self = this;
        return {
          appData: this.getSystemPathAppData(),
          appName,
          appVersion,
          get electronDefaultDir() {
            return self.getElectronLogPath();
          },
          home: this.getSystemPathHome(),
          libraryDefaultDir: this.getAppLogPath(appName),
          libraryTemplate: this.getAppLogPath("{appName}"),
          temp: this.getSystemPathTemp(),
          userData: this.getAppUserDataPath(appName)
        };
      }
      getSystemPathAppData() {
        const home = this.getSystemPathHome();
        switch (this.platform) {
          case "darwin": {
            return path2.join(home, "Library/Application Support");
          }
          case "win32": {
            return process.env.APPDATA || path2.join(home, "AppData/Roaming");
          }
          default: {
            return process.env.XDG_CONFIG_HOME || path2.join(home, ".config");
          }
        }
      }
      getSystemPathHome() {
        return os.homedir?.() || process.env.HOME;
      }
      getSystemPathTemp() {
        return os.tmpdir();
      }
      getVersions() {
        return {
          app: `${this.getAppName()} ${this.getAppVersion()}`,
          electron: void 0,
          os: this.getOsVersion()
        };
      }
      isDev() {
        return process.env.NODE_ENV === "development" || process.env.ELECTRON_IS_DEV === "1";
      }
      isElectron() {
        return Boolean(process.versions.electron);
      }
      onAppEvent(_eventName, _handler) {
      }
      onAppReady(handler) {
        handler();
      }
      onEveryWebContentsEvent(eventName, handler) {
      }
      /**
       * Listen to async messages sent from opposite process
       * @param {string} channel
       * @param {function} listener
       */
      onIpc(channel, listener) {
      }
      onIpcInvoke(channel, listener) {
      }
      /**
       * @param {string} url
       * @param {Function} [logFunction]
       */
      openUrl(url, logFunction = console.error) {
        const startMap = { darwin: "open", win32: "start", linux: "xdg-open" };
        const start = startMap[process.platform] || "xdg-open";
        childProcess.exec(`${start} ${url}`, {}, (err) => {
          if (err) {
            logFunction(err);
          }
        });
      }
      setAppName(appName) {
        this.appName = appName;
      }
      setPlatform(platform) {
        this.platform = platform;
      }
      setPreloadFileForSessions({
        filePath,
        // eslint-disable-line no-unused-vars
        includeFutureSession = true,
        // eslint-disable-line no-unused-vars
        getSessions = () => []
        // eslint-disable-line no-unused-vars
      }) {
      }
      /**
       * Sent a message to opposite process
       * @param {string} channel
       * @param {any} message
       */
      sendIpc(channel, message) {
      }
      showErrorBox(title, message) {
      }
    };
    module2.exports = NodeExternalApi;
  }
});

// node_modules/electron-log/src/main/ElectronExternalApi.js
var require_ElectronExternalApi = __commonJS({
  "node_modules/electron-log/src/main/ElectronExternalApi.js"(exports2, module2) {
    "use strict";
    var path2 = require("path");
    var NodeExternalApi = require_NodeExternalApi();
    var ElectronExternalApi = class extends NodeExternalApi {
      /**
       * @type {typeof Electron}
       */
      electron = void 0;
      /**
       * @param {object} options
       * @param {typeof Electron} [options.electron]
       */
      constructor({ electron } = {}) {
        super();
        this.electron = electron;
      }
      getAppName() {
        let appName;
        try {
          appName = this.appName || this.electron.app?.name || this.electron.app?.getName();
        } catch {
        }
        return appName || super.getAppName();
      }
      getAppUserDataPath(appName) {
        return this.getPath("userData") || super.getAppUserDataPath(appName);
      }
      getAppVersion() {
        let appVersion;
        try {
          appVersion = this.electron.app?.getVersion();
        } catch {
        }
        return appVersion || super.getAppVersion();
      }
      getElectronLogPath() {
        return this.getPath("logs") || super.getElectronLogPath();
      }
      /**
       * @private
       * @param {any} name
       * @returns {string|undefined}
       */
      getPath(name) {
        try {
          return this.electron.app?.getPath(name);
        } catch {
          return void 0;
        }
      }
      getVersions() {
        return {
          app: `${this.getAppName()} ${this.getAppVersion()}`,
          electron: `Electron ${process.versions.electron}`,
          os: this.getOsVersion()
        };
      }
      getSystemPathAppData() {
        return this.getPath("appData") || super.getSystemPathAppData();
      }
      isDev() {
        if (this.electron.app?.isPackaged !== void 0) {
          return !this.electron.app.isPackaged;
        }
        if (typeof process.execPath === "string") {
          const execFileName = path2.basename(process.execPath).toLowerCase();
          return execFileName.startsWith("electron");
        }
        return super.isDev();
      }
      onAppEvent(eventName, handler) {
        this.electron.app?.on(eventName, handler);
        return () => {
          this.electron.app?.off(eventName, handler);
        };
      }
      onAppReady(handler) {
        if (this.electron.app?.isReady()) {
          handler();
        } else if (this.electron.app?.once) {
          this.electron.app?.once("ready", handler);
        } else {
          handler();
        }
      }
      onEveryWebContentsEvent(eventName, handler) {
        this.electron.webContents?.getAllWebContents()?.forEach((webContents) => {
          webContents.on(eventName, handler);
        });
        this.electron.app?.on("web-contents-created", onWebContentsCreated);
        return () => {
          this.electron.webContents?.getAllWebContents().forEach((webContents) => {
            webContents.off(eventName, handler);
          });
          this.electron.app?.off("web-contents-created", onWebContentsCreated);
        };
        function onWebContentsCreated(_, webContents) {
          webContents.on(eventName, handler);
        }
      }
      /**
       * Listen to async messages sent from opposite process
       * @param {string} channel
       * @param {function} listener
       */
      onIpc(channel, listener) {
        this.electron.ipcMain?.on(channel, listener);
      }
      onIpcInvoke(channel, listener) {
        this.electron.ipcMain?.handle?.(channel, listener);
      }
      /**
       * @param {string} url
       * @param {Function} [logFunction]
       */
      openUrl(url, logFunction = console.error) {
        this.electron.shell?.openExternal(url).catch(logFunction);
      }
      setPreloadFileForSessions({
        filePath,
        includeFutureSession = true,
        getSessions = () => [this.electron.session?.defaultSession]
      }) {
        for (const session of getSessions().filter(Boolean)) {
          setPreload(session);
        }
        if (includeFutureSession) {
          this.onAppEvent("session-created", (session) => {
            setPreload(session);
          });
        }
        function setPreload(session) {
          if (typeof session.registerPreloadScript === "function") {
            session.registerPreloadScript({
              filePath,
              id: "electron-log-preload",
              type: "frame"
            });
          } else {
            session.setPreloads([...session.getPreloads(), filePath]);
          }
        }
      }
      /**
       * Sent a message to opposite process
       * @param {string} channel
       * @param {any} message
       */
      sendIpc(channel, message) {
        this.electron.BrowserWindow?.getAllWindows()?.forEach((wnd) => {
          if (wnd.webContents?.isDestroyed() === false && wnd.webContents?.isCrashed() === false) {
            wnd.webContents.send(channel, message);
          }
        });
      }
      showErrorBox(title, message) {
        this.electron.dialog?.showErrorBox(title, message);
      }
    };
    module2.exports = ElectronExternalApi;
  }
});

// node_modules/electron-log/src/renderer/electron-log-preload.js
var require_electron_log_preload = __commonJS({
  "node_modules/electron-log/src/renderer/electron-log-preload.js"(exports2, module2) {
    "use strict";
    var electron = {};
    try {
      electron = require("electron");
    } catch (e) {
    }
    if (electron.ipcRenderer) {
      initialize(electron);
    }
    if (typeof module2 === "object") {
      module2.exports = initialize;
    }
    function initialize({ contextBridge, ipcRenderer }) {
      if (!ipcRenderer) {
        return;
      }
      ipcRenderer.on("__ELECTRON_LOG_IPC__", (_, message) => {
        window.postMessage({ cmd: "message", ...message });
      });
      ipcRenderer.invoke("__ELECTRON_LOG__", { cmd: "getOptions" }).catch((e) => console.error(new Error(
        `electron-log isn't initialized in the main process. Please call log.initialize() before. ${e.message}`
      )));
      const electronLog = {
        sendToMain(message) {
          try {
            ipcRenderer.send("__ELECTRON_LOG__", message);
          } catch (e) {
            console.error("electronLog.sendToMain ", e, "data:", message);
            ipcRenderer.send("__ELECTRON_LOG__", {
              cmd: "errorHandler",
              error: { message: e?.message, stack: e?.stack },
              errorName: "sendToMain"
            });
          }
        },
        log(...data) {
          electronLog.sendToMain({ data, level: "info" });
        }
      };
      for (const level of ["error", "warn", "info", "verbose", "debug", "silly"]) {
        electronLog[level] = (...data) => electronLog.sendToMain({
          data,
          level
        });
      }
      if (contextBridge && process.contextIsolated) {
        try {
          contextBridge.exposeInMainWorld("__electronLog", electronLog);
        } catch {
        }
      }
      if (typeof window === "object") {
        window.__electronLog = electronLog;
      } else {
        __electronLog = electronLog;
      }
    }
  }
});

// node_modules/electron-log/src/main/initialize.js
var require_initialize = __commonJS({
  "node_modules/electron-log/src/main/initialize.js"(exports2, module2) {
    "use strict";
    var fs = require("fs");
    var os = require("os");
    var path2 = require("path");
    var preloadInitializeFn = require_electron_log_preload();
    var preloadInitialized = false;
    var spyConsoleInitialized = false;
    module2.exports = {
      initialize({
        externalApi,
        getSessions,
        includeFutureSession,
        logger,
        preload = true,
        spyRendererConsole = false
      }) {
        externalApi.onAppReady(() => {
          try {
            if (preload) {
              initializePreload({
                externalApi,
                getSessions,
                includeFutureSession,
                logger,
                preloadOption: preload
              });
            }
            if (spyRendererConsole) {
              initializeSpyRendererConsole({ externalApi, logger });
            }
          } catch (err) {
            logger.warn(err);
          }
        });
      }
    };
    function initializePreload({
      externalApi,
      getSessions,
      includeFutureSession,
      logger,
      preloadOption
    }) {
      let preloadPath = typeof preloadOption === "string" ? preloadOption : void 0;
      if (preloadInitialized) {
        logger.warn(new Error("log.initialize({ preload }) already called").stack);
        return;
      }
      preloadInitialized = true;
      try {
        preloadPath = path2.resolve(
          __dirname,
          "../renderer/electron-log-preload.js"
        );
      } catch {
      }
      if (!preloadPath || !fs.existsSync(preloadPath)) {
        preloadPath = path2.join(
          externalApi.getAppUserDataPath() || os.tmpdir(),
          "electron-log-preload.js"
        );
        const preloadCode = `
      try {
        (${preloadInitializeFn.toString()})(require('electron'));
      } catch(e) {
        console.error(e);
      }
    `;
        fs.writeFileSync(preloadPath, preloadCode, "utf8");
      }
      externalApi.setPreloadFileForSessions({
        filePath: preloadPath,
        includeFutureSession,
        getSessions
      });
    }
    function initializeSpyRendererConsole({ externalApi, logger }) {
      if (spyConsoleInitialized) {
        logger.warn(
          new Error("log.initialize({ spyRendererConsole }) already called").stack
        );
        return;
      }
      spyConsoleInitialized = true;
      const levels = ["debug", "info", "warn", "error"];
      externalApi.onEveryWebContentsEvent(
        "console-message",
        (event, level, message) => {
          logger.processMessage({
            data: [message],
            level: levels[level],
            variables: { processType: "renderer" }
          });
        }
      );
    }
  }
});

// node_modules/electron-log/src/core/scope.js
var require_scope = __commonJS({
  "node_modules/electron-log/src/core/scope.js"(exports2, module2) {
    "use strict";
    module2.exports = scopeFactory;
    function scopeFactory(logger) {
      return Object.defineProperties(scope, {
        defaultLabel: { value: "", writable: true },
        labelPadding: { value: true, writable: true },
        maxLabelLength: { value: 0, writable: true },
        labelLength: {
          get() {
            switch (typeof scope.labelPadding) {
              case "boolean":
                return scope.labelPadding ? scope.maxLabelLength : 0;
              case "number":
                return scope.labelPadding;
              default:
                return 0;
            }
          }
        }
      });
      function scope(label) {
        scope.maxLabelLength = Math.max(scope.maxLabelLength, label.length);
        const newScope = {};
        for (const level of logger.levels) {
          newScope[level] = (...d) => logger.logData(d, { level, scope: label });
        }
        newScope.log = newScope.info;
        return newScope;
      }
    }
  }
});

// node_modules/electron-log/src/core/Buffering.js
var require_Buffering = __commonJS({
  "node_modules/electron-log/src/core/Buffering.js"(exports2, module2) {
    "use strict";
    var Buffering = class {
      constructor({ processMessage }) {
        this.processMessage = processMessage;
        this.buffer = [];
        this.enabled = false;
        this.begin = this.begin.bind(this);
        this.commit = this.commit.bind(this);
        this.reject = this.reject.bind(this);
      }
      addMessage(message) {
        this.buffer.push(message);
      }
      begin() {
        this.enabled = [];
      }
      commit() {
        this.enabled = false;
        this.buffer.forEach((item) => this.processMessage(item));
        this.buffer = [];
      }
      reject() {
        this.enabled = false;
        this.buffer = [];
      }
    };
    module2.exports = Buffering;
  }
});

// node_modules/electron-log/src/core/Logger.js
var require_Logger = __commonJS({
  "node_modules/electron-log/src/core/Logger.js"(exports2, module2) {
    "use strict";
    var scopeFactory = require_scope();
    var Buffering = require_Buffering();
    var Logger = class _Logger {
      static instances = {};
      dependencies = {};
      errorHandler = null;
      eventLogger = null;
      functions = {};
      hooks = [];
      isDev = false;
      levels = null;
      logId = null;
      scope = null;
      transports = {};
      variables = {};
      constructor({
        allowUnknownLevel = false,
        dependencies = {},
        errorHandler,
        eventLogger,
        initializeFn,
        isDev = false,
        levels = ["error", "warn", "info", "verbose", "debug", "silly"],
        logId,
        transportFactories = {},
        variables
      } = {}) {
        this.addLevel = this.addLevel.bind(this);
        this.create = this.create.bind(this);
        this.initialize = this.initialize.bind(this);
        this.logData = this.logData.bind(this);
        this.processMessage = this.processMessage.bind(this);
        this.allowUnknownLevel = allowUnknownLevel;
        this.buffering = new Buffering(this);
        this.dependencies = dependencies;
        this.initializeFn = initializeFn;
        this.isDev = isDev;
        this.levels = levels;
        this.logId = logId;
        this.scope = scopeFactory(this);
        this.transportFactories = transportFactories;
        this.variables = variables || {};
        for (const name of this.levels) {
          this.addLevel(name, false);
        }
        this.log = this.info;
        this.functions.log = this.log;
        this.errorHandler = errorHandler;
        errorHandler?.setOptions({ ...dependencies, logFn: this.error });
        this.eventLogger = eventLogger;
        eventLogger?.setOptions({ ...dependencies, logger: this });
        for (const [name, factory] of Object.entries(transportFactories)) {
          this.transports[name] = factory(this, dependencies);
        }
        _Logger.instances[logId] = this;
      }
      static getInstance({ logId }) {
        return this.instances[logId] || this.instances.default;
      }
      addLevel(level, index = this.levels.length) {
        if (index !== false) {
          this.levels.splice(index, 0, level);
        }
        this[level] = (...args) => this.logData(args, { level });
        this.functions[level] = this[level];
      }
      catchErrors(options) {
        this.processMessage(
          {
            data: ["log.catchErrors is deprecated. Use log.errorHandler instead"],
            level: "warn"
          },
          { transports: ["console"] }
        );
        return this.errorHandler.startCatching(options);
      }
      create(options) {
        if (typeof options === "string") {
          options = { logId: options };
        }
        return new _Logger({
          dependencies: this.dependencies,
          errorHandler: this.errorHandler,
          initializeFn: this.initializeFn,
          isDev: this.isDev,
          transportFactories: this.transportFactories,
          variables: { ...this.variables },
          ...options
        });
      }
      compareLevels(passLevel, checkLevel, levels = this.levels) {
        const pass = levels.indexOf(passLevel);
        const check = levels.indexOf(checkLevel);
        if (check === -1 || pass === -1) {
          return true;
        }
        return check <= pass;
      }
      initialize(options = {}) {
        this.initializeFn({ logger: this, ...this.dependencies, ...options });
      }
      logData(data, options = {}) {
        if (this.buffering.enabled) {
          this.buffering.addMessage({ data, date: /* @__PURE__ */ new Date(), ...options });
        } else {
          this.processMessage({ data, ...options });
        }
      }
      processMessage(message, { transports = this.transports } = {}) {
        if (message.cmd === "errorHandler") {
          this.errorHandler.handle(message.error, {
            errorName: message.errorName,
            processType: "renderer",
            showDialog: Boolean(message.showDialog)
          });
          return;
        }
        let level = message.level;
        if (!this.allowUnknownLevel) {
          level = this.levels.includes(message.level) ? message.level : "info";
        }
        const normalizedMessage = {
          date: /* @__PURE__ */ new Date(),
          logId: this.logId,
          ...message,
          level,
          variables: {
            ...this.variables,
            ...message.variables
          }
        };
        for (const [transName, transFn] of this.transportEntries(transports)) {
          if (typeof transFn !== "function" || transFn.level === false) {
            continue;
          }
          if (!this.compareLevels(transFn.level, message.level)) {
            continue;
          }
          try {
            const transformedMsg = this.hooks.reduce((msg, hook) => {
              return msg ? hook(msg, transFn, transName) : msg;
            }, normalizedMessage);
            if (transformedMsg) {
              transFn({ ...transformedMsg, data: [...transformedMsg.data] });
            }
          } catch (e) {
            this.processInternalErrorFn(e);
          }
        }
      }
      processInternalErrorFn(_e) {
      }
      transportEntries(transports = this.transports) {
        const transportArray = Array.isArray(transports) ? transports : Object.entries(transports);
        return transportArray.map((item) => {
          switch (typeof item) {
            case "string":
              return this.transports[item] ? [item, this.transports[item]] : null;
            case "function":
              return [item.name, item];
            default:
              return Array.isArray(item) ? item : null;
          }
        }).filter(Boolean);
      }
    };
    module2.exports = Logger;
  }
});

// node_modules/electron-log/src/node/ErrorHandler.js
var require_ErrorHandler = __commonJS({
  "node_modules/electron-log/src/node/ErrorHandler.js"(exports2, module2) {
    "use strict";
    var ErrorHandler = class {
      externalApi = void 0;
      isActive = false;
      logFn = void 0;
      onError = void 0;
      showDialog = true;
      constructor({
        externalApi,
        logFn = void 0,
        onError = void 0,
        showDialog = void 0
      } = {}) {
        this.createIssue = this.createIssue.bind(this);
        this.handleError = this.handleError.bind(this);
        this.handleRejection = this.handleRejection.bind(this);
        this.setOptions({ externalApi, logFn, onError, showDialog });
        this.startCatching = this.startCatching.bind(this);
        this.stopCatching = this.stopCatching.bind(this);
      }
      handle(error, {
        logFn = this.logFn,
        onError = this.onError,
        processType = "browser",
        showDialog = this.showDialog,
        errorName = ""
      } = {}) {
        error = normalizeError(error);
        try {
          if (typeof onError === "function") {
            const versions = this.externalApi?.getVersions() || {};
            const createIssue = this.createIssue;
            const result = onError({
              createIssue,
              error,
              errorName,
              processType,
              versions
            });
            if (result === false) {
              return;
            }
          }
          errorName ? logFn(errorName, error) : logFn(error);
          if (showDialog && !errorName.includes("rejection") && this.externalApi) {
            this.externalApi.showErrorBox(
              `A JavaScript error occurred in the ${processType} process`,
              error.stack
            );
          }
        } catch {
          console.error(error);
        }
      }
      setOptions({ externalApi, logFn, onError, showDialog }) {
        if (typeof externalApi === "object") {
          this.externalApi = externalApi;
        }
        if (typeof logFn === "function") {
          this.logFn = logFn;
        }
        if (typeof onError === "function") {
          this.onError = onError;
        }
        if (typeof showDialog === "boolean") {
          this.showDialog = showDialog;
        }
      }
      startCatching({ onError, showDialog } = {}) {
        if (this.isActive) {
          return;
        }
        this.isActive = true;
        this.setOptions({ onError, showDialog });
        process.on("uncaughtException", this.handleError);
        process.on("unhandledRejection", this.handleRejection);
      }
      stopCatching() {
        this.isActive = false;
        process.removeListener("uncaughtException", this.handleError);
        process.removeListener("unhandledRejection", this.handleRejection);
      }
      createIssue(pageUrl, queryParams) {
        this.externalApi?.openUrl(
          `${pageUrl}?${new URLSearchParams(queryParams).toString()}`
        );
      }
      handleError(error) {
        this.handle(error, { errorName: "Unhandled" });
      }
      handleRejection(reason) {
        const error = reason instanceof Error ? reason : new Error(JSON.stringify(reason));
        this.handle(error, { errorName: "Unhandled rejection" });
      }
    };
    function normalizeError(e) {
      if (e instanceof Error) {
        return e;
      }
      if (e && typeof e === "object") {
        if (e.message) {
          return Object.assign(new Error(e.message), e);
        }
        try {
          return new Error(JSON.stringify(e));
        } catch (serErr) {
          return new Error(`Couldn't normalize error ${String(e)}: ${serErr}`);
        }
      }
      return new Error(`Can't normalize error ${String(e)}`);
    }
    module2.exports = ErrorHandler;
  }
});

// node_modules/electron-log/src/node/EventLogger.js
var require_EventLogger = __commonJS({
  "node_modules/electron-log/src/node/EventLogger.js"(exports2, module2) {
    "use strict";
    var EventLogger = class {
      disposers = [];
      format = "{eventSource}#{eventName}:";
      formatters = {
        app: {
          "certificate-error": ({ args }) => {
            return this.arrayToObject(args.slice(1, 4), [
              "url",
              "error",
              "certificate"
            ]);
          },
          "child-process-gone": ({ args }) => {
            return args.length === 1 ? args[0] : args;
          },
          "render-process-gone": ({ args: [webContents, details] }) => {
            return details && typeof details === "object" ? { ...details, ...this.getWebContentsDetails(webContents) } : [];
          }
        },
        webContents: {
          "console-message": ({ args: [level, message, line, sourceId] }) => {
            if (level < 3) {
              return void 0;
            }
            return { message, source: `${sourceId}:${line}` };
          },
          "did-fail-load": ({ args }) => {
            return this.arrayToObject(args, [
              "errorCode",
              "errorDescription",
              "validatedURL",
              "isMainFrame",
              "frameProcessId",
              "frameRoutingId"
            ]);
          },
          "did-fail-provisional-load": ({ args }) => {
            return this.arrayToObject(args, [
              "errorCode",
              "errorDescription",
              "validatedURL",
              "isMainFrame",
              "frameProcessId",
              "frameRoutingId"
            ]);
          },
          "plugin-crashed": ({ args }) => {
            return this.arrayToObject(args, ["name", "version"]);
          },
          "preload-error": ({ args }) => {
            return this.arrayToObject(args, ["preloadPath", "error"]);
          }
        }
      };
      events = {
        app: {
          "certificate-error": true,
          "child-process-gone": true,
          "render-process-gone": true
        },
        webContents: {
          // 'console-message': true,
          "did-fail-load": true,
          "did-fail-provisional-load": true,
          "plugin-crashed": true,
          "preload-error": true,
          "unresponsive": true
        }
      };
      externalApi = void 0;
      level = "error";
      scope = "";
      constructor(options = {}) {
        this.setOptions(options);
      }
      setOptions({
        events,
        externalApi,
        level,
        logger,
        format,
        formatters,
        scope
      }) {
        if (typeof events === "object") {
          this.events = events;
        }
        if (typeof externalApi === "object") {
          this.externalApi = externalApi;
        }
        if (typeof level === "string") {
          this.level = level;
        }
        if (typeof logger === "object") {
          this.logger = logger;
        }
        if (typeof format === "string" || typeof format === "function") {
          this.format = format;
        }
        if (typeof formatters === "object") {
          this.formatters = formatters;
        }
        if (typeof scope === "string") {
          this.scope = scope;
        }
      }
      startLogging(options = {}) {
        this.setOptions(options);
        this.disposeListeners();
        for (const eventName of this.getEventNames(this.events.app)) {
          this.disposers.push(
            this.externalApi.onAppEvent(eventName, (...handlerArgs) => {
              this.handleEvent({ eventSource: "app", eventName, handlerArgs });
            })
          );
        }
        for (const eventName of this.getEventNames(this.events.webContents)) {
          this.disposers.push(
            this.externalApi.onEveryWebContentsEvent(
              eventName,
              (...handlerArgs) => {
                this.handleEvent(
                  { eventSource: "webContents", eventName, handlerArgs }
                );
              }
            )
          );
        }
      }
      stopLogging() {
        this.disposeListeners();
      }
      arrayToObject(array, fieldNames) {
        const obj = {};
        fieldNames.forEach((fieldName, index) => {
          obj[fieldName] = array[index];
        });
        if (array.length > fieldNames.length) {
          obj.unknownArgs = array.slice(fieldNames.length);
        }
        return obj;
      }
      disposeListeners() {
        this.disposers.forEach((disposer) => disposer());
        this.disposers = [];
      }
      formatEventLog({ eventName, eventSource, handlerArgs }) {
        const [event, ...args] = handlerArgs;
        if (typeof this.format === "function") {
          return this.format({ args, event, eventName, eventSource });
        }
        const formatter = this.formatters[eventSource]?.[eventName];
        let formattedArgs = args;
        if (typeof formatter === "function") {
          formattedArgs = formatter({ args, event, eventName, eventSource });
        }
        if (!formattedArgs) {
          return void 0;
        }
        const eventData = {};
        if (Array.isArray(formattedArgs)) {
          eventData.args = formattedArgs;
        } else if (typeof formattedArgs === "object") {
          Object.assign(eventData, formattedArgs);
        }
        if (eventSource === "webContents") {
          Object.assign(eventData, this.getWebContentsDetails(event?.sender));
        }
        const title = this.format.replace("{eventSource}", eventSource === "app" ? "App" : "WebContents").replace("{eventName}", eventName);
        return [title, eventData];
      }
      getEventNames(eventMap) {
        if (!eventMap || typeof eventMap !== "object") {
          return [];
        }
        return Object.entries(eventMap).filter(([_, listen]) => listen).map(([eventName]) => eventName);
      }
      getWebContentsDetails(webContents) {
        if (!webContents?.loadURL) {
          return {};
        }
        try {
          return {
            webContents: {
              id: webContents.id,
              url: webContents.getURL()
            }
          };
        } catch {
          return {};
        }
      }
      handleEvent({ eventName, eventSource, handlerArgs }) {
        const log2 = this.formatEventLog({ eventName, eventSource, handlerArgs });
        if (log2) {
          const logFns = this.scope ? this.logger.scope(this.scope) : this.logger;
          logFns?.[this.level]?.(...log2);
        }
      }
    };
    module2.exports = EventLogger;
  }
});

// node_modules/electron-log/src/core/transforms/transform.js
var require_transform = __commonJS({
  "node_modules/electron-log/src/core/transforms/transform.js"(exports2, module2) {
    "use strict";
    module2.exports = { transform };
    function transform({
      logger,
      message,
      transport,
      initialData = message?.data || [],
      transforms = transport?.transforms
    }) {
      return transforms.reduce((data, trans) => {
        if (typeof trans === "function") {
          return trans({ data, logger, message, transport });
        }
        return data;
      }, initialData);
    }
  }
});

// node_modules/electron-log/src/core/transforms/format.js
var require_format = __commonJS({
  "node_modules/electron-log/src/core/transforms/format.js"(exports2, module2) {
    "use strict";
    var { transform } = require_transform();
    module2.exports = {
      concatFirstStringElements,
      formatScope,
      formatText,
      formatVariables,
      timeZoneFromOffset,
      format({ message, logger, transport, data = message?.data }) {
        switch (typeof transport.format) {
          case "string": {
            return transform({
              message,
              logger,
              transforms: [formatVariables, formatScope, formatText],
              transport,
              initialData: [transport.format, ...data]
            });
          }
          case "function": {
            return transport.format({
              data,
              level: message?.level || "info",
              logger,
              message,
              transport
            });
          }
          default: {
            return data;
          }
        }
      }
    };
    function concatFirstStringElements({ data }) {
      if (typeof data[0] !== "string" || typeof data[1] !== "string") {
        return data;
      }
      if (data[0].match(/%[1cdfiOos]/)) {
        return data;
      }
      return [`${data[0]} ${data[1]}`, ...data.slice(2)];
    }
    function timeZoneFromOffset(minutesOffset) {
      const minutesPositive = Math.abs(minutesOffset);
      const sign = minutesOffset > 0 ? "-" : "+";
      const hours = Math.floor(minutesPositive / 60).toString().padStart(2, "0");
      const minutes = (minutesPositive % 60).toString().padStart(2, "0");
      return `${sign}${hours}:${minutes}`;
    }
    function formatScope({ data, logger, message }) {
      const { defaultLabel, labelLength } = logger?.scope || {};
      const template = data[0];
      let label = message.scope;
      if (!label) {
        label = defaultLabel;
      }
      let scopeText;
      if (label === "") {
        scopeText = labelLength > 0 ? "".padEnd(labelLength + 3) : "";
      } else if (typeof label === "string") {
        scopeText = ` (${label})`.padEnd(labelLength + 3);
      } else {
        scopeText = "";
      }
      data[0] = template.replace("{scope}", scopeText);
      return data;
    }
    function formatVariables({ data, message }) {
      let template = data[0];
      if (typeof template !== "string") {
        return data;
      }
      template = template.replace("{level}]", `${message.level}]`.padEnd(6, " "));
      const date = message.date || /* @__PURE__ */ new Date();
      data[0] = template.replace(/\{(\w+)}/g, (substring, name) => {
        switch (name) {
          case "level":
            return message.level || "info";
          case "logId":
            return message.logId;
          case "y":
            return date.getFullYear().toString(10);
          case "m":
            return (date.getMonth() + 1).toString(10).padStart(2, "0");
          case "d":
            return date.getDate().toString(10).padStart(2, "0");
          case "h":
            return date.getHours().toString(10).padStart(2, "0");
          case "i":
            return date.getMinutes().toString(10).padStart(2, "0");
          case "s":
            return date.getSeconds().toString(10).padStart(2, "0");
          case "ms":
            return date.getMilliseconds().toString(10).padStart(3, "0");
          case "z":
            return timeZoneFromOffset(date.getTimezoneOffset());
          case "iso":
            return date.toISOString();
          default: {
            return message.variables?.[name] || substring;
          }
        }
      }).trim();
      return data;
    }
    function formatText({ data }) {
      const template = data[0];
      if (typeof template !== "string") {
        return data;
      }
      const textTplPosition = template.lastIndexOf("{text}");
      if (textTplPosition === template.length - 6) {
        data[0] = template.replace(/\s?{text}/, "");
        if (data[0] === "") {
          data.shift();
        }
        return data;
      }
      const templatePieces = template.split("{text}");
      let result = [];
      if (templatePieces[0] !== "") {
        result.push(templatePieces[0]);
      }
      result = result.concat(data.slice(1));
      if (templatePieces[1] !== "") {
        result.push(templatePieces[1]);
      }
      return result;
    }
  }
});

// node_modules/electron-log/src/node/transforms/object.js
var require_object = __commonJS({
  "node_modules/electron-log/src/node/transforms/object.js"(exports2, module2) {
    "use strict";
    var util = require("util");
    module2.exports = {
      serialize,
      maxDepth({ data, transport, depth = transport?.depth ?? 6 }) {
        if (!data) {
          return data;
        }
        if (depth < 1) {
          if (Array.isArray(data)) return "[array]";
          if (typeof data === "object" && data) return "[object]";
          return data;
        }
        if (Array.isArray(data)) {
          return data.map((child) => module2.exports.maxDepth({
            data: child,
            depth: depth - 1
          }));
        }
        if (typeof data !== "object") {
          return data;
        }
        if (data && typeof data.toISOString === "function") {
          return data;
        }
        if (data === null) {
          return null;
        }
        if (data instanceof Error) {
          return data;
        }
        const newJson = {};
        for (const i in data) {
          if (!Object.prototype.hasOwnProperty.call(data, i)) continue;
          newJson[i] = module2.exports.maxDepth({
            data: data[i],
            depth: depth - 1
          });
        }
        return newJson;
      },
      toJSON({ data }) {
        return JSON.parse(JSON.stringify(data, createSerializer()));
      },
      toString({ data, transport }) {
        const inspectOptions = transport?.inspectOptions || {};
        const simplifiedData = data.map((item) => {
          if (item === void 0) {
            return void 0;
          }
          try {
            const str = JSON.stringify(item, createSerializer(), "  ");
            return str === void 0 ? void 0 : JSON.parse(str);
          } catch (e) {
            return item;
          }
        });
        return util.formatWithOptions(inspectOptions, ...simplifiedData);
      }
    };
    function createSerializer(options = {}) {
      const seen = /* @__PURE__ */ new WeakSet();
      return function(key, value) {
        if (typeof value === "object" && value !== null) {
          if (seen.has(value)) {
            return void 0;
          }
          seen.add(value);
        }
        return serialize(key, value, options);
      };
    }
    function serialize(key, value, options = {}) {
      const serializeMapAndSet = options?.serializeMapAndSet !== false;
      if (value instanceof Error) {
        return value.stack;
      }
      if (!value) {
        return value;
      }
      if (typeof value === "function") {
        return `[function] ${value.toString()}`;
      }
      if (value instanceof Date) {
        return value.toISOString();
      }
      if (serializeMapAndSet && value instanceof Map && Object.fromEntries) {
        return Object.fromEntries(value);
      }
      if (serializeMapAndSet && value instanceof Set && Array.from) {
        return Array.from(value);
      }
      return value;
    }
  }
});

// node_modules/electron-log/src/core/transforms/style.js
var require_style = __commonJS({
  "node_modules/electron-log/src/core/transforms/style.js"(exports2, module2) {
    "use strict";
    module2.exports = {
      transformStyles,
      applyAnsiStyles({ data }) {
        return transformStyles(data, styleToAnsi, resetAnsiStyle);
      },
      removeStyles({ data }) {
        return transformStyles(data, () => "");
      }
    };
    var ANSI_COLORS = {
      unset: "\x1B[0m",
      black: "\x1B[30m",
      red: "\x1B[31m",
      green: "\x1B[32m",
      yellow: "\x1B[33m",
      blue: "\x1B[34m",
      magenta: "\x1B[35m",
      cyan: "\x1B[36m",
      white: "\x1B[37m",
      gray: "\x1B[90m"
    };
    function styleToAnsi(style) {
      const color = style.replace(/color:\s*(\w+).*/, "$1").toLowerCase();
      return ANSI_COLORS[color] || "";
    }
    function resetAnsiStyle(string) {
      return string + ANSI_COLORS.unset;
    }
    function transformStyles(data, onStyleFound, onStyleApplied) {
      const foundStyles = {};
      return data.reduce((result, item, index, array) => {
        if (foundStyles[index]) {
          return result;
        }
        if (typeof item === "string") {
          let valueIndex = index;
          let styleApplied = false;
          item = item.replace(/%[1cdfiOos]/g, (match) => {
            valueIndex += 1;
            if (match !== "%c") {
              return match;
            }
            const style = array[valueIndex];
            if (typeof style === "string") {
              foundStyles[valueIndex] = true;
              styleApplied = true;
              return onStyleFound(style, item);
            }
            return match;
          });
          if (styleApplied && onStyleApplied) {
            item = onStyleApplied(item);
          }
        }
        result.push(item);
        return result;
      }, []);
    }
  }
});

// node_modules/electron-log/src/node/transports/console.js
var require_console = __commonJS({
  "node_modules/electron-log/src/node/transports/console.js"(exports2, module2) {
    "use strict";
    var {
      concatFirstStringElements,
      format
    } = require_format();
    var { maxDepth, toJSON } = require_object();
    var {
      applyAnsiStyles,
      removeStyles
    } = require_style();
    var { transform } = require_transform();
    var consoleMethods = {
      error: console.error,
      warn: console.warn,
      info: console.info,
      verbose: console.info,
      debug: console.debug,
      silly: console.debug,
      log: console.log
    };
    module2.exports = consoleTransportFactory;
    var separator = process.platform === "win32" ? ">" : "\u203A";
    var DEFAULT_FORMAT = `%c{h}:{i}:{s}.{ms}{scope}%c ${separator} {text}`;
    Object.assign(consoleTransportFactory, {
      DEFAULT_FORMAT
    });
    function consoleTransportFactory(logger) {
      return Object.assign(transport, {
        colorMap: {
          error: "red",
          warn: "yellow",
          info: "cyan",
          verbose: "unset",
          debug: "gray",
          silly: "gray",
          default: "unset"
        },
        format: DEFAULT_FORMAT,
        level: "silly",
        transforms: [
          addTemplateColors,
          format,
          formatStyles,
          concatFirstStringElements,
          maxDepth,
          toJSON
        ],
        useStyles: process.env.FORCE_STYLES,
        writeFn({ message }) {
          const consoleLogFn = consoleMethods[message.level] || consoleMethods.info;
          consoleLogFn(...message.data);
        }
      });
      function transport(message) {
        const data = transform({ logger, message, transport });
        transport.writeFn({
          message: { ...message, data }
        });
      }
    }
    function addTemplateColors({ data, message, transport }) {
      if (typeof transport.format !== "string" || !transport.format.includes("%c")) {
        return data;
      }
      return [
        `color:${levelToStyle(message.level, transport)}`,
        "color:unset",
        ...data
      ];
    }
    function canUseStyles(useStyleValue, level) {
      if (typeof useStyleValue === "boolean") {
        return useStyleValue;
      }
      const useStderr = level === "error" || level === "warn";
      const stream = useStderr ? process.stderr : process.stdout;
      return stream && stream.isTTY;
    }
    function formatStyles(args) {
      const { message, transport } = args;
      const useStyles = canUseStyles(transport.useStyles, message.level);
      const nextTransform = useStyles ? applyAnsiStyles : removeStyles;
      return nextTransform(args);
    }
    function levelToStyle(level, transport) {
      return transport.colorMap[level] || transport.colorMap.default;
    }
  }
});

// node_modules/electron-log/src/node/transports/file/File.js
var require_File = __commonJS({
  "node_modules/electron-log/src/node/transports/file/File.js"(exports2, module2) {
    "use strict";
    var EventEmitter = require("events");
    var fs = require("fs");
    var os = require("os");
    var File = class extends EventEmitter {
      asyncWriteQueue = [];
      bytesWritten = 0;
      hasActiveAsyncWriting = false;
      path = null;
      initialSize = void 0;
      writeOptions = null;
      writeAsync = false;
      constructor({
        path: path2,
        writeOptions = { encoding: "utf8", flag: "a", mode: 438 },
        writeAsync = false
      }) {
        super();
        this.path = path2;
        this.writeOptions = writeOptions;
        this.writeAsync = writeAsync;
      }
      get size() {
        return this.getSize();
      }
      clear() {
        try {
          fs.writeFileSync(this.path, "", {
            mode: this.writeOptions.mode,
            flag: "w"
          });
          this.reset();
          return true;
        } catch (e) {
          if (e.code === "ENOENT") {
            return true;
          }
          this.emit("error", e, this);
          return false;
        }
      }
      crop(bytesAfter) {
        try {
          const content = readFileSyncFromEnd(this.path, bytesAfter || 4096);
          this.clear();
          this.writeLine(`[log cropped]${os.EOL}${content}`);
        } catch (e) {
          this.emit(
            "error",
            new Error(`Couldn't crop file ${this.path}. ${e.message}`),
            this
          );
        }
      }
      getSize() {
        if (this.initialSize === void 0) {
          try {
            const stats = fs.statSync(this.path);
            this.initialSize = stats.size;
          } catch (e) {
            this.initialSize = 0;
          }
        }
        return this.initialSize + this.bytesWritten;
      }
      increaseBytesWrittenCounter(text) {
        this.bytesWritten += Buffer.byteLength(text, this.writeOptions.encoding);
      }
      isNull() {
        return false;
      }
      nextAsyncWrite() {
        const file = this;
        if (this.hasActiveAsyncWriting || this.asyncWriteQueue.length === 0) {
          return;
        }
        const text = this.asyncWriteQueue.join("");
        this.asyncWriteQueue = [];
        this.hasActiveAsyncWriting = true;
        fs.writeFile(this.path, text, this.writeOptions, (e) => {
          file.hasActiveAsyncWriting = false;
          if (e) {
            file.emit(
              "error",
              new Error(`Couldn't write to ${file.path}. ${e.message}`),
              this
            );
          } else {
            file.increaseBytesWrittenCounter(text);
          }
          file.nextAsyncWrite();
        });
      }
      reset() {
        this.initialSize = void 0;
        this.bytesWritten = 0;
      }
      toString() {
        return this.path;
      }
      writeLine(text) {
        text += os.EOL;
        if (this.writeAsync) {
          this.asyncWriteQueue.push(text);
          this.nextAsyncWrite();
          return;
        }
        try {
          fs.writeFileSync(this.path, text, this.writeOptions);
          this.increaseBytesWrittenCounter(text);
        } catch (e) {
          this.emit(
            "error",
            new Error(`Couldn't write to ${this.path}. ${e.message}`),
            this
          );
        }
      }
    };
    module2.exports = File;
    function readFileSyncFromEnd(filePath, bytesCount) {
      const buffer = Buffer.alloc(bytesCount);
      const stats = fs.statSync(filePath);
      const readLength = Math.min(stats.size, bytesCount);
      const offset = Math.max(0, stats.size - bytesCount);
      const fd = fs.openSync(filePath, "r");
      const totalBytes = fs.readSync(fd, buffer, 0, readLength, offset);
      fs.closeSync(fd);
      return buffer.toString("utf8", 0, totalBytes);
    }
  }
});

// node_modules/electron-log/src/node/transports/file/NullFile.js
var require_NullFile = __commonJS({
  "node_modules/electron-log/src/node/transports/file/NullFile.js"(exports2, module2) {
    "use strict";
    var File = require_File();
    var NullFile = class extends File {
      clear() {
      }
      crop() {
      }
      getSize() {
        return 0;
      }
      isNull() {
        return true;
      }
      writeLine() {
      }
    };
    module2.exports = NullFile;
  }
});

// node_modules/electron-log/src/node/transports/file/FileRegistry.js
var require_FileRegistry = __commonJS({
  "node_modules/electron-log/src/node/transports/file/FileRegistry.js"(exports2, module2) {
    "use strict";
    var EventEmitter = require("events");
    var fs = require("fs");
    var path2 = require("path");
    var File = require_File();
    var NullFile = require_NullFile();
    var FileRegistry = class extends EventEmitter {
      store = {};
      constructor() {
        super();
        this.emitError = this.emitError.bind(this);
      }
      /**
       * Provide a File object corresponding to the filePath
       * @param {string} filePath
       * @param {WriteOptions} [writeOptions]
       * @param {boolean} [writeAsync]
       * @return {File}
       */
      provide({ filePath, writeOptions = {}, writeAsync = false }) {
        let file;
        try {
          filePath = path2.resolve(filePath);
          if (this.store[filePath]) {
            return this.store[filePath];
          }
          file = this.createFile({ filePath, writeOptions, writeAsync });
        } catch (e) {
          file = new NullFile({ path: filePath });
          this.emitError(e, file);
        }
        file.on("error", this.emitError);
        this.store[filePath] = file;
        return file;
      }
      /**
       * @param {string} filePath
       * @param {WriteOptions} writeOptions
       * @param {boolean} async
       * @return {File}
       * @private
       */
      createFile({ filePath, writeOptions, writeAsync }) {
        this.testFileWriting({ filePath, writeOptions });
        return new File({ path: filePath, writeOptions, writeAsync });
      }
      /**
       * @param {Error} error
       * @param {File} file
       * @private
       */
      emitError(error, file) {
        this.emit("error", error, file);
      }
      /**
       * @param {string} filePath
       * @param {WriteOptions} writeOptions
       * @private
       */
      testFileWriting({ filePath, writeOptions }) {
        fs.mkdirSync(path2.dirname(filePath), { recursive: true });
        fs.writeFileSync(filePath, "", { flag: "a", mode: writeOptions.mode });
      }
    };
    module2.exports = FileRegistry;
  }
});

// node_modules/electron-log/src/node/transports/file/index.js
var require_file = __commonJS({
  "node_modules/electron-log/src/node/transports/file/index.js"(exports2, module2) {
    "use strict";
    var fs = require("fs");
    var os = require("os");
    var path2 = require("path");
    var FileRegistry = require_FileRegistry();
    var { transform } = require_transform();
    var { removeStyles } = require_style();
    var {
      format,
      concatFirstStringElements
    } = require_format();
    var { toString } = require_object();
    module2.exports = fileTransportFactory;
    var globalRegistry = new FileRegistry();
    function fileTransportFactory(logger, { registry = globalRegistry, externalApi } = {}) {
      let pathVariables;
      if (registry.listenerCount("error") < 1) {
        registry.on("error", (e, file) => {
          logConsole(`Can't write to ${file}`, e);
        });
      }
      return Object.assign(transport, {
        fileName: getDefaultFileName(logger.variables.processType),
        format: "[{y}-{m}-{d} {h}:{i}:{s}.{ms}] [{level}]{scope} {text}",
        getFile,
        inspectOptions: { depth: 5 },
        level: "silly",
        maxSize: 1024 ** 2,
        readAllLogs,
        sync: true,
        transforms: [removeStyles, format, concatFirstStringElements, toString],
        writeOptions: { flag: "a", mode: 438, encoding: "utf8" },
        archiveLogFn(file) {
          const oldPath = file.toString();
          const inf = path2.parse(oldPath);
          try {
            fs.renameSync(oldPath, path2.join(inf.dir, `${inf.name}.old${inf.ext}`));
          } catch (e) {
            logConsole("Could not rotate log", e);
            const quarterOfMaxSize = Math.round(transport.maxSize / 4);
            file.crop(Math.min(quarterOfMaxSize, 256 * 1024));
          }
        },
        resolvePathFn(vars) {
          return path2.join(vars.libraryDefaultDir, vars.fileName);
        },
        setAppName(name) {
          logger.dependencies.externalApi.setAppName(name);
        }
      });
      function transport(message) {
        const file = getFile(message);
        const needLogRotation = transport.maxSize > 0 && file.size > transport.maxSize;
        if (needLogRotation) {
          transport.archiveLogFn(file);
          file.reset();
        }
        const content = transform({ logger, message, transport });
        file.writeLine(content);
      }
      function initializeOnFirstAccess() {
        if (pathVariables) {
          return;
        }
        pathVariables = Object.create(
          Object.prototype,
          {
            ...Object.getOwnPropertyDescriptors(
              externalApi.getPathVariables()
            ),
            fileName: {
              get() {
                return transport.fileName;
              },
              enumerable: true
            }
          }
        );
        if (typeof transport.archiveLog === "function") {
          transport.archiveLogFn = transport.archiveLog;
          logConsole("archiveLog is deprecated. Use archiveLogFn instead");
        }
        if (typeof transport.resolvePath === "function") {
          transport.resolvePathFn = transport.resolvePath;
          logConsole("resolvePath is deprecated. Use resolvePathFn instead");
        }
      }
      function logConsole(message, error = null, level = "error") {
        const data = [`electron-log.transports.file: ${message}`];
        if (error) {
          data.push(error);
        }
        logger.transports.console({ data, date: /* @__PURE__ */ new Date(), level });
      }
      function getFile(msg) {
        initializeOnFirstAccess();
        const filePath = transport.resolvePathFn(pathVariables, msg);
        return registry.provide({
          filePath,
          writeAsync: !transport.sync,
          writeOptions: transport.writeOptions
        });
      }
      function readAllLogs({ fileFilter = (f) => f.endsWith(".log") } = {}) {
        initializeOnFirstAccess();
        const logsPath = path2.dirname(transport.resolvePathFn(pathVariables));
        if (!fs.existsSync(logsPath)) {
          return [];
        }
        return fs.readdirSync(logsPath).map((fileName) => path2.join(logsPath, fileName)).filter(fileFilter).map((logPath) => {
          try {
            return {
              path: logPath,
              lines: fs.readFileSync(logPath, "utf8").split(os.EOL)
            };
          } catch {
            return null;
          }
        }).filter(Boolean);
      }
    }
    function getDefaultFileName(processType = process.type) {
      switch (processType) {
        case "renderer":
          return "renderer.log";
        case "worker":
          return "worker.log";
        default:
          return "main.log";
      }
    }
  }
});

// node_modules/electron-log/src/node/transports/ipc.js
var require_ipc = __commonJS({
  "node_modules/electron-log/src/node/transports/ipc.js"(exports2, module2) {
    "use strict";
    var { maxDepth, toJSON } = require_object();
    var { transform } = require_transform();
    module2.exports = ipcTransportFactory;
    function ipcTransportFactory(logger, { externalApi }) {
      Object.assign(transport, {
        depth: 3,
        eventId: "__ELECTRON_LOG_IPC__",
        level: logger.isDev ? "silly" : false,
        transforms: [toJSON, maxDepth]
      });
      return externalApi?.isElectron() ? transport : void 0;
      function transport(message) {
        if (message?.variables?.processType === "renderer") {
          return;
        }
        externalApi?.sendIpc(transport.eventId, {
          ...message,
          data: transform({ logger, message, transport })
        });
      }
    }
  }
});

// node_modules/electron-log/src/node/transports/remote.js
var require_remote = __commonJS({
  "node_modules/electron-log/src/node/transports/remote.js"(exports2, module2) {
    "use strict";
    var http = require("http");
    var https = require("https");
    var { transform } = require_transform();
    var { removeStyles } = require_style();
    var { toJSON, maxDepth } = require_object();
    module2.exports = remoteTransportFactory;
    function remoteTransportFactory(logger) {
      return Object.assign(transport, {
        client: { name: "electron-application" },
        depth: 6,
        level: false,
        requestOptions: {},
        transforms: [removeStyles, toJSON, maxDepth],
        makeBodyFn({ message }) {
          return JSON.stringify({
            client: transport.client,
            data: message.data,
            date: message.date.getTime(),
            level: message.level,
            scope: message.scope,
            variables: message.variables
          });
        },
        processErrorFn({ error }) {
          logger.processMessage(
            {
              data: [`electron-log: can't POST ${transport.url}`, error],
              level: "warn"
            },
            { transports: ["console", "file"] }
          );
        },
        sendRequestFn({ serverUrl, requestOptions, body }) {
          const httpTransport = serverUrl.startsWith("https:") ? https : http;
          const request = httpTransport.request(serverUrl, {
            method: "POST",
            ...requestOptions,
            headers: {
              "Content-Type": "application/json",
              "Content-Length": body.length,
              ...requestOptions.headers
            }
          });
          request.write(body);
          request.end();
          return request;
        }
      });
      function transport(message) {
        if (!transport.url) {
          return;
        }
        const body = transport.makeBodyFn({
          logger,
          message: { ...message, data: transform({ logger, message, transport }) },
          transport
        });
        const request = transport.sendRequestFn({
          serverUrl: transport.url,
          requestOptions: transport.requestOptions,
          body: Buffer.from(body, "utf8")
        });
        request.on("error", (error) => transport.processErrorFn({
          error,
          logger,
          message,
          request,
          transport
        }));
      }
    }
  }
});

// node_modules/electron-log/src/node/createDefaultLogger.js
var require_createDefaultLogger = __commonJS({
  "node_modules/electron-log/src/node/createDefaultLogger.js"(exports2, module2) {
    "use strict";
    var Logger = require_Logger();
    var ErrorHandler = require_ErrorHandler();
    var EventLogger = require_EventLogger();
    var transportConsole = require_console();
    var transportFile = require_file();
    var transportIpc = require_ipc();
    var transportRemote = require_remote();
    module2.exports = createDefaultLogger;
    function createDefaultLogger({ dependencies, initializeFn }) {
      const defaultLogger = new Logger({
        dependencies,
        errorHandler: new ErrorHandler(),
        eventLogger: new EventLogger(),
        initializeFn,
        isDev: dependencies.externalApi?.isDev(),
        logId: "default",
        transportFactories: {
          console: transportConsole,
          file: transportFile,
          ipc: transportIpc,
          remote: transportRemote
        },
        variables: {
          processType: "main"
        }
      });
      defaultLogger.default = defaultLogger;
      defaultLogger.Logger = Logger;
      defaultLogger.processInternalErrorFn = (e) => {
        defaultLogger.transports.console.writeFn({
          message: {
            data: ["Unhandled electron-log error", e],
            level: "error"
          }
        });
      };
      return defaultLogger;
    }
  }
});

// node_modules/electron-log/src/main/index.js
var require_main = __commonJS({
  "node_modules/electron-log/src/main/index.js"(exports2, module2) {
    "use strict";
    var electron = require("electron");
    var ElectronExternalApi = require_ElectronExternalApi();
    var { initialize } = require_initialize();
    var createDefaultLogger = require_createDefaultLogger();
    var externalApi = new ElectronExternalApi({ electron });
    var defaultLogger = createDefaultLogger({
      dependencies: { externalApi },
      initializeFn: initialize
    });
    module2.exports = defaultLogger;
    externalApi.onIpc("__ELECTRON_LOG__", (_, message) => {
      if (message.scope) {
        defaultLogger.Logger.getInstance(message).scope(message.scope);
      }
      const date = new Date(message.date);
      processMessage({
        ...message,
        date: date.getTime() ? date : /* @__PURE__ */ new Date()
      });
    });
    externalApi.onIpcInvoke("__ELECTRON_LOG__", (_, { cmd = "", logId }) => {
      switch (cmd) {
        case "getOptions": {
          const logger = defaultLogger.Logger.getInstance({ logId });
          return {
            levels: logger.levels,
            logId
          };
        }
        default: {
          processMessage({ data: [`Unknown cmd '${cmd}'`], level: "error" });
          return {};
        }
      }
    });
    function processMessage(message) {
      defaultLogger.Logger.getInstance(message)?.processMessage(message);
    }
  }
});

// node_modules/electron-log/main.js
var require_main2 = __commonJS({
  "node_modules/electron-log/main.js"(exports2, module2) {
    "use strict";
    var main = require_main();
    module2.exports = main;
  }
});

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
var import_electron_updater, import_electron;
var init_updater = __esm({
  "electron/updater.ts"() {
    "use strict";
    import_electron_updater = require("electron-updater");
    import_electron = require("electron");
  }
});

// electron/main.ts
var main_exports = {};
__export(main_exports, {
  stopBackend: () => stopBackend
});
module.exports = __toCommonJS(main_exports);
var import_electron2 = require("electron");
var import_path = __toESM(require("path"));
var import_child_process = require("child_process");
var import_fs = require("fs");
var import_net = require("net");
var import_crypto = require("crypto");
var import_main = __toESM(require_main2());
import_main.default.initialize();
import_main.default.transports.file.level = "info";
import_main.default.transports.file.maxSize = 5 * 1024 * 1024;
import_main.default.errorHandler.startCatching({ showDialog: false });
Object.assign(console, import_main.default.functions);
var backendProcess = null;
function stopBackend() {
  const child = backendProcess;
  backendProcess = null;
  if (!child?.pid) return;
  try {
    if (process.platform === "win32") {
      (0, import_child_process.execFileSync)("taskkill", ["/pid", String(child.pid), "/T", "/F"], { timeout: 1e4, windowsHide: true });
    } else {
      process.kill(-child.pid, "SIGKILL");
    }
  } catch {
    try {
      child.kill();
    } catch {
    }
  }
}
var backendFailure = null;
function backendLogPath() {
  return import_path.default.join(import_electron2.app.getPath("userData"), "logs", "backend.log");
}
var mainWindow = null;
var backendPort = 8742;
function freePort(start, span = 20) {
  return new Promise((resolve) => {
    const tryPort = (p) => {
      if (p >= start + span) return resolve(start);
      const srv = (0, import_net.createServer)();
      srv.once("error", () => tryPort(p + 1));
      srv.listen(p, "127.0.0.1", () => srv.close(() => resolve(p)));
    };
    tryPort(start);
  });
}
function writeCrashReport(where, detail) {
  const id = (0, import_crypto.randomUUID)().slice(0, 8);
  try {
    const dir = import_path.default.dirname(import_main.default.transports.file.getFile().path);
    const file = import_path.default.join(dir, `crash-${id}.json`);
    writeFileSync(file, JSON.stringify({
      id,
      at: (/* @__PURE__ */ new Date()).toISOString(),
      where,
      detail,
      version: import_electron2.app.getVersion(),
      platform: process.platform
    }, null, 2));
    import_main.default.error(`[CE] crash report ${id} written to ${file}`);
  } catch {
  }
  return id;
}
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
    import_main.default.error("[CE] Bundled backend not found at", resourcesBackend);
    backendFailure = `Bundled backend not found at ${resourcesBackend}`;
    return;
  }
  const ffmpegDir = import_path.default.join(process.resourcesPath, "ffmpeg");
  if ((0, import_fs.existsSync)(ffmpegDir)) {
    process.env.CE_FFMPEG_DIR = ffmpegDir;
    process.env.PATH = ffmpegDir + import_path.default.delimiter + (process.env.PATH ?? "");
  }
  import_main.default.info("[CE] Starting backend:", cmd, args.join(" "));
  try {
    backendProcess = (0, import_child_process.spawn)(cmd, args, {
      cwd,
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"],
      // The backend sets Windows' per-application GPU preference, and the
      // preference is per *executable* — so it has to know which .exe the user
      // actually launched, not just its own python.exe.
      // CE_VERSION is the release number the installer was built from. The
      // backend cannot read the frontend's package.json in a packaged install —
      // it is inside an asar — so without this it would fall back to a constant
      // and `/api/health` would report the previous build forever.
      env: { ...process.env, CE_APP_EXE: import_electron2.app.getPath("exe"), CE_VERSION: import_electron2.app.getVersion(), CE_PORT: String(backendPort) }
    });
  } catch (error) {
    backendFailure = `spawn failed: ${String(error)}`;
    import_main.default.error("[CE] Backend spawn threw:", error);
    return;
  }
  try {
    (0, import_fs.mkdirSync)(import_path.default.dirname(backendLogPath()), { recursive: true });
    const backendLog = (0, import_fs.createWriteStream)(backendLogPath(), { flags: "a" });
    backendLog.on("error", (err) => import_main.default.error("[CE] backend.log write failed:", err));
    backendProcess.stdout?.pipe(backendLog);
    backendProcess.stderr?.pipe(backendLog);
  } catch (error) {
    import_main.default.error("[CE] Could not open backend.log:", error);
  }
  backendProcess.on("error", (err) => {
    backendFailure = String(err);
    import_main.default.error("[CE] Backend failed:", err);
  });
  backendProcess.on("exit", (code, signal) => {
    backendFailure = `backend exited with code ${code}${signal ? ` (${signal})` : ""}`;
    import_main.default.warn("[CE] Backend exited:", code, signal);
    backendProcess = null;
  });
}
function registerIpc() {
  import_electron2.ipcMain.on("log:renderer", (_e, level, message) => {
    ;
    import_main.default[level === "error" ? "error" : "info"](
      `[renderer] ${message}`
    );
  });
  import_electron2.ipcMain.handle("media:pick", async () => {
    try {
      const result = await import_electron2.dialog.showOpenDialog({
        title: "Import media",
        properties: ["openFile", "multiSelections"],
        filters: [
          { name: "Media", extensions: ["mp4", "mov", "mkv", "webm", "avi", "mp3", "wav", "m4a", "aac", "flac"] },
          { name: "All files", extensions: ["*"] }
        ]
      });
      import_main.default.info("[CE] media:pick ->", result.canceled ? "cancelled" : result.filePaths.join(", "));
      return result.canceled ? [] : result.filePaths;
    } catch (error) {
      import_main.default.error("[CE] media:pick failed:", error);
      throw error;
    }
  });
  import_electron2.ipcMain.handle("media:save-dialog", async (_event, suggestedName) => {
    const result = await import_electron2.dialog.showSaveDialog({
      title: "Export video",
      defaultPath: import_path.default.join(import_electron2.app.getPath("videos"), suggestedName || "timeline.mp4"),
      filters: [{ name: "MP4 video", extensions: ["mp4"] }]
    });
    import_main.default.info("[CE] media:save-dialog ->", result.canceled ? "cancelled" : result.filePath);
    return result.canceled ? null : result.filePath;
  });
  import_electron2.ipcMain.handle("window:fullscreen:toggle", () => setFullscreen(!(mainWindow?.isFullScreen() ?? false)));
  import_electron2.ipcMain.handle("window:fullscreen:get", () => mainWindow?.isFullScreen() ?? false);
  import_electron2.ipcMain.handle("log:path", () => import_main.default.transports.file.getFile().path);
  import_electron2.ipcMain.handle("backend:status", () => {
    let tail = [];
    try {
      const file = backendLogPath();
      if ((0, import_fs.existsSync)(file)) {
        const size = (0, import_fs.statSync)(file).size;
        const text = (0, import_fs.readFileSync)(file, "utf8").slice(Math.max(0, size - 8e3));
        tail = text.split(/\r?\n/).filter(Boolean).slice(-40);
      }
    } catch (error) {
      tail = [`could not read backend.log: ${String(error)}`];
    }
    return {
      running: backendProcess !== null,
      pid: backendProcess?.pid ?? null,
      failure: backendFailure,
      logPath: backendLogPath(),
      tail
    };
  });
  import_electron2.ipcMain.handle("backend:restart", () => {
    stopBackend();
    backendFailure = null;
    startBackend();
    return { running: backendProcess !== null, failure: backendFailure };
  });
  import_electron2.ipcMain.on("log:open", () => import_electron2.shell.showItemInFolder(import_main.default.transports.file.getFile().path));
  import_electron2.ipcMain.on("shell:open", (_e, url) => {
    if (typeof url !== "string") return;
    try {
      const parsed = new URL(url);
      const allowed = ["nvidia.com", "www.nvidia.com", "us.download.nvidia.com"];
      if (parsed.protocol === "https:" && allowed.some((h) => parsed.hostname.endsWith(h))) {
        void import_electron2.shell.openExternal(url);
      }
    } catch {
    }
  });
}
function setFullscreen(value) {
  if (!mainWindow || mainWindow.isDestroyed()) return false;
  mainWindow.setFullScreen(value);
  if (!value && !mainWindow.isMaximized()) mainWindow.unmaximize();
  return mainWindow.isFullScreen();
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
  import_electron2.Menu.setApplicationMenu(null);
  mainWindow = new import_electron2.BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1024,
    minHeight: 768,
    title: "Cutting Edge",
    backgroundColor: "#0F172A",
    // The default menu bar (File/Edit/View/Window/Help) is a white strip that
    // survived even in fullscreen. The app has no use for it: every action lives
    // in the interface, so the whole bar goes.
    autoHideMenuBar: true,
    webPreferences: { preload: import_path.default.join(__dirname, "preload.js"), contextIsolation: true, nodeIntegration: false, additionalArguments: [`--ce-backend-port=${backendPort}`] }
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
  mainWindow.webContents.on("before-input-event", (event, input) => {
    if (input.type !== "keyDown") return;
    if (input.key === "F11") {
      event.preventDefault();
      setFullscreen(!mainWindow.isFullScreen());
    } else if (input.key === "Escape" && mainWindow.isFullScreen()) {
      event.preventDefault();
      setFullscreen(false);
    }
  });
  const broadcastFullscreen = () => {
    if (!mainWindow || mainWindow.isDestroyed()) return;
    mainWindow.webContents.send("window:fullscreen", mainWindow.isFullScreen());
  };
  mainWindow.on("enter-full-screen", broadcastFullscreen);
  mainWindow.on("leave-full-screen", broadcastFullscreen);
  mainWindow.webContents.on("did-fail-load", (_e, errorCode, errorDescription, validatedURL) => {
    import_main.default.error("[CE] Renderer failed to load:", errorCode, errorDescription, validatedURL);
    if (mainWindow) showFatal(mainWindow, `${errorDescription} (${errorCode})
${validatedURL}`);
  });
  mainWindow.webContents.on("render-process-gone", (_e, details) => {
    import_main.default.error("[CE] Renderer process gone:", details.reason);
    writeCrashReport("renderer", String(details.reason));
  });
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    import_electron2.shell.openExternal(url);
    return { action: "deny" };
  });
}
process.on("uncaughtException", (error) => {
  import_main.default.error("[CE] uncaughtException:", error);
  writeCrashReport("main", String(error?.stack ?? error));
});
process.on("unhandledRejection", (reason) => {
  import_main.default.error("[CE] unhandledRejection:", reason);
  writeCrashReport("main", String(reason));
});
import_electron2.app.whenReady().then(async () => {
  registerIpc();
  backendPort = import_electron2.app.isPackaged ? await freePort(8742) : 8742;
  import_main.default.info(`[CE] Cutting Edge ${import_electron2.app.getVersion()} starting \u2014 logs at ${import_main.default.transports.file.getFile().path}`);
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
    stopBackend();
    import_electron2.app.quit();
  }
});
import_electron2.app.on("before-quit", () => stopBackend());
import_electron2.app.on("will-quit", () => stopBackend());
globalThis.__ceStopBackend = stopBackend;
// Annotate the CommonJS export names for ESM import in node:
0 && (module.exports = {
  stopBackend
});
