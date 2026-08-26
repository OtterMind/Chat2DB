import assert from 'node:assert/strict';
import { isMonacoCancellationError, runMonacoDisposalSafely } from './monaco';
import {
  createSingleFileShortcutController,
  isSuggestWidgetVisible,
  type SuggestEditor,
} from '@/components/SingleFileMonacoEditor/shortcut';

const run = async () => {
  assert.equal(isMonacoCancellationError('Canceled'), true);
  assert.equal(isMonacoCancellationError({ name: 'Canceled', message: 'Canceled' }), true);
  assert.equal(isMonacoCancellationError(new Error('Canceled')), true);
  assert.equal(isMonacoCancellationError(new Error('boom')), false);
  assert.equal(isSuggestWidgetVisible(null), false);
  assert.equal(isSuggestWidgetVisible({ getContribution: () => null }), false);
  assert.equal(
    isSuggestWidgetVisible({
      getContribution: () => ({ _widget: { suggestWidgetVisible: { get: () => true } } }),
    }),
    true,
  );

  const keydownListeners = new Set<(event: KeyboardEvent) => void>();
  const target = {
    addEventListener: (_type: 'keydown', listener: (event: KeyboardEvent) => void) => {
      keydownListeners.add(listener);
    },
    removeEventListener: (_type: 'keydown', listener: (event: KeyboardEvent) => void) => {
      keydownListeners.delete(listener);
    },
  };
  let enterCount = 0;
  let preventDefaultCount = 0;
  const shortcutController = createSingleFileShortcutController(target, () => {
    enterCount += 1;
  });
  const dispatchEnter = () => {
    const event = {
      key: 'Enter',
      preventDefault: () => {
        preventDefaultCount += 1;
      },
    } as KeyboardEvent;
    keydownListeners.forEach((listener) => listener(event));
  };

  shortcutController.activate({ getContribution: () => null });
  shortcutController.activate({ getContribution: () => null });
  assert.equal(keydownListeners.size, 1, 'repeated focus does not register duplicate keydown listeners');
  dispatchEnter();
  assert.equal(enterCount, 1, 'Enter executes even when Monaco has no suggest controller');
  assert.equal(preventDefaultCount, 1);

  shortcutController.deactivate();
  let latestCallbackCount = 0;
  let latestCallback = () => undefined;
  const latestCallbackController = createSingleFileShortcutController(target, () => latestCallback());
  latestCallbackController.activate({ getContribution: () => null });
  latestCallback = () => {
    latestCallbackCount += 1;
  };
  dispatchEnter();
  assert.equal(latestCallbackCount, 1, 'an active editor uses the latest Enter callback without refocusing');
  latestCallbackController.dispose();
  shortcutController.activate({ getContribution: () => null });

  const visibleSuggestEditor: SuggestEditor = {
    getContribution: () => ({ _widget: { suggestWidgetVisible: { get: () => true } } }),
  };
  shortcutController.activate(visibleSuggestEditor);
  dispatchEnter();
  assert.equal(enterCount, 1, 'Enter is reserved for an open suggestion widget');

  shortcutController.deactivate();
  assert.equal(keydownListeners.size, 0, 'blur removes the keydown listener');
  dispatchEnter();
  assert.equal(enterCount, 1);

  shortcutController.activate({ getContribution: () => null });
  shortcutController.dispose();
  shortcutController.dispose();
  assert.equal(keydownListeners.size, 0, 'unmount cleanup is idempotent');

  let synchronousCleanupRan = false;
  runMonacoDisposalSafely(() => {
    synchronousCleanupRan = true;
    throw Object.assign(new Error('Canceled'), { name: 'Canceled' });
  });
  assert.equal(synchronousCleanupRan, true);

  const unhandledRejections: unknown[] = [];
  const rejectionListener = (reason: unknown) => unhandledRejections.push(reason);
  process.on('unhandledRejection', rejectionListener);
  runMonacoDisposalSafely(() => Promise.reject(Object.assign(new Error('Canceled'), { name: 'Canceled' })));
  await new Promise((resolve) => setTimeout(resolve, 0));
  process.off('unhandledRejection', rejectionListener);
  assert.deepEqual(unhandledRejections, []);

  assert.throws(
    () =>
      runMonacoDisposalSafely(() => {
        throw new Error('unexpected disposal failure');
      }),
    /unexpected disposal failure/,
  );
};

run().then(() => {
  console.log('monaco lifecycle tests passed');
});
