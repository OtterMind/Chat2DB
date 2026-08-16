import assert from 'node:assert/strict';

const globalObj = globalThis as unknown as {
  __RUNTIME_ENV__?: string;
  __ENV__?: string;
  window?: {
    javaQuery?: (query: {
      request: string;
      onSuccess: (response: string) => void;
      onFailure: (errorCode: number, errorMessage: string) => void;
    }) => number;
  };
};

const originalRuntimeEnv = globalObj.__RUNTIME_ENV__;
const originalEnv = globalObj.__ENV__;
const originalWindow = globalObj.window;
const originalNavigatorDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
const originalConsoleWarn = console.warn;

globalObj.__RUNTIME_ENV__ = 'community';
globalObj.__ENV__ = 'test';
globalObj.window = {};

if (typeof globalThis.navigator === 'undefined') {
  Object.defineProperty(globalThis, 'navigator', {
    value: { userAgent: 'Mac' },
    configurable: true,
  });
}

function createShortcutEvent() {
  let defaultPrevented = false;
  return {
    event: {
      preventDefault: () => {
        defaultPrevented = true;
      },
    },
    isDefaultPrevented: () => defaultPrevented,
  };
}

async function run() {
  const [{ ShortcutAction }, { canHandleWebFrameZoom, handleWebFrameZoom }, { prepareGlobalShortcutHandling }] =
    await Promise.all([import('@/constants/shortcut'), import('./jcefZoom'), import('./shortcutDispatch')]);
  const zoomCases = [
    [ShortcutAction.ZoomIn, 'in', 'zoomIn'],
    [ShortcutAction.ZoomOut, 'out', 'zoomOut'],
    [ShortcutAction.ZoomReset, 'reset', 'zoomReset'],
  ] as const;
  const warnings: unknown[][] = [];
  console.warn = (...args: unknown[]) => {
    warnings.push(args);
  };

  try {
    globalObj.window = {};
    assert.equal(canHandleWebFrameZoom(), false);

    for (const [shortcutAction, zoomType] of zoomCases) {
      const shortcutEvent = createShortcutEvent();
      assert.equal(prepareGlobalShortcutHandling(shortcutEvent.event, shortcutAction), false);
      assert.equal(shortcutEvent.isDefaultPrevented(), false);
      await handleWebFrameZoom(zoomType);
    }
    assert.equal(warnings.length, 0);

    const requestPayloads: string[] = [];
    globalObj.window.javaQuery = (query) => {
      requestPayloads.push(query.request);
      query.onSuccess(JSON.stringify({ data: true }));
      return 1;
    };

    assert.equal(canHandleWebFrameZoom(), true);
    for (const [shortcutAction, zoomType] of zoomCases) {
      const shortcutEvent = createShortcutEvent();
      assert.equal(prepareGlobalShortcutHandling(shortcutEvent.event, shortcutAction), true);
      assert.equal(shortcutEvent.isDefaultPrevented(), true);
      await handleWebFrameZoom(zoomType);
    }

    assert.deepEqual(
      requestPayloads.map((requestPayload) => JSON.parse(requestPayload)),
      zoomCases.map(([, , zoomAction]) => ({
        requestUrl: 'web-frame-set-zoom',
        method: 'client-command',
        message: JSON.stringify({ action: zoomAction }),
      })),
    );

    globalObj.window.javaQuery = (query) => {
      query.onFailure(500, 'zoom failed');
      return 1;
    };

    await handleWebFrameZoom('in');
    assert.deepEqual(warnings, [['Failed to set web frame zoom:', 'zoom failed']]);

    console.log('All JCEF zoom tests passed successfully!');
  } finally {
    console.warn = originalConsoleWarn;
    if (originalRuntimeEnv === undefined) {
      delete globalObj.__RUNTIME_ENV__;
    } else {
      globalObj.__RUNTIME_ENV__ = originalRuntimeEnv;
    }
    if (originalEnv === undefined) {
      delete globalObj.__ENV__;
    } else {
      globalObj.__ENV__ = originalEnv;
    }
    if (originalWindow === undefined) {
      delete globalObj.window;
    } else {
      globalObj.window = originalWindow;
    }
    if (originalNavigatorDescriptor === undefined) {
      delete (globalThis as { navigator?: Navigator }).navigator;
    } else {
      Object.defineProperty(globalThis, 'navigator', originalNavigatorDescriptor);
    }
  }
}

run().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
