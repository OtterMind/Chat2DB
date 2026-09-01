let monacoEnvironmentInitialized = false;
let monacoCancellationRejectionHandlerInitialized = false;

const workerProxyUrls = new Map<string, string>();

const getWorkerFile = (label: string) => {
  if (label === 'json') {
    return 'json.worker.js';
  }

  return 'editor.worker.js';
};

const createWorkerProxyUrl = (workerUrl: string) => {
  const cached = workerProxyUrls.get(workerUrl);
  if (cached) {
    return cached;
  }

  const blob = new Blob(
    [
      `self.MonacoEnvironment = { baseUrl: ${JSON.stringify(new URL('./', window.location.href).href)} };`,
      `importScripts(${JSON.stringify(workerUrl)});`,
    ],
    { type: 'text/javascript' },
  );
  const proxyUrl = URL.createObjectURL(blob);
  workerProxyUrls.set(workerUrl, proxyUrl);
  return proxyUrl;
};

export const isMonacoCancellationError = (reason: unknown) => {
  if (typeof reason === 'string') {
    return reason === 'Canceled' || reason === 'CanceledError';
  }

  if (!reason || typeof reason !== 'object') {
    return false;
  }

  const error = reason as { name?: unknown; message?: unknown; code?: unknown };
  return (
    error.name === 'Canceled' ||
    error.name === 'CanceledError' ||
    error.message === 'Canceled' ||
    error.message === 'CanceledError' ||
    error.code === 'ERR_CANCELED'
  );
};

export const runMonacoDisposalSafely = (task: () => unknown) => {
  try {
    const result = task();
    if (result && typeof (result as PromiseLike<unknown>).then === 'function') {
      Promise.resolve(result).catch((error) => {
        if (!isMonacoCancellationError(error)) {
          setTimeout(() => {
            throw error;
          }, 0);
        }
      });
    }
  } catch (error) {
    if (!isMonacoCancellationError(error)) {
      throw error;
    }
  }
};

const setupMonacoCancellationRejectionHandler = () => {
  if (monacoCancellationRejectionHandlerInitialized || typeof window === 'undefined') {
    return;
  }
  monacoCancellationRejectionHandlerInitialized = true;

  window.addEventListener(
    'unhandledrejection',
    (event) => {
      if (isMonacoCancellationError(event.reason)) {
        event.preventDefault();
        event.stopImmediatePropagation();
      }
    },
    true,
  );
};

export const setupMonacoEnvironment = () => {
  setupMonacoCancellationRejectionHandler();

  if (monacoEnvironmentInitialized) {
    return;
  }
  monacoEnvironmentInitialized = true;

  if (!window.location.href.startsWith('file://')) {
    return;
  }

  const globalWindow = window as Window & {
    MonacoEnvironment?: {
      getWorker?: (_moduleId: string, label: string) => Worker;
    };
  };

  globalWindow.MonacoEnvironment = {
    ...globalWindow.MonacoEnvironment,
    getWorker: (_moduleId: string, label: string) => {
      const workerUrl = new URL(`./${getWorkerFile(label)}`, window.location.href).href;
      const proxyUrl = createWorkerProxyUrl(workerUrl);
      return new Worker(proxyUrl);
    },
  };
};
