import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';

const dom = new JSDOM('<!doctype html><html><body></body></html>', {
  pretendToBeVisual: true,
  url: 'http://localhost',
});
const domWindow = dom.window;

Object.defineProperties(globalThis, {
  window: { configurable: true, value: domWindow },
  document: { configurable: true, value: domWindow.document },
  navigator: { configurable: true, value: domWindow.navigator },
  Node: { configurable: true, value: domWindow.Node },
  Element: { configurable: true, value: domWindow.Element },
  HTMLElement: { configurable: true, value: domWindow.HTMLElement },
  HTMLIFrameElement: { configurable: true, value: domWindow.HTMLIFrameElement },
  Event: { configurable: true, value: domWindow.Event },
  FocusEvent: { configurable: true, value: domWindow.FocusEvent },
  MouseEvent: { configurable: true, value: domWindow.MouseEvent },
  PointerEvent: { configurable: true, value: domWindow.MouseEvent },
  IS_REACT_ACT_ENVIRONMENT: { configurable: true, value: true },
});

async function main() {
  const [{ default: React, act }, { createRoot }, { createStore }, { WorkspaceTabContentInteraction }] =
    await Promise.all([
      import('react'),
      import('react-dom/client'),
      import('zustand/vanilla'),
      import('./workspaceTabContentInteraction'),
    ]);

  const activeTabStore = createStore(() => ({ activePane: 'main', activeTabId: 'tab-a' }));
  let activationCalls = 0;
  const activateSplitTab = () => {
    activationCalls += 1;
    activeTabStore.setState({ activePane: 'pane-right', activeTabId: 'tab-b' });
  };
  const resetActiveTab = () => activeTabStore.setState({ activePane: 'main', activeTabId: 'tab-a' });
  let pointerTargetPane: string | undefined;
  let shortcutPane: string | undefined;

  const outsideFocusTarget = document.createElement('button');
  const container = document.createElement('div');
  document.body.append(outsideFocusTarget, container);
  const root = createRoot(container);

  await act(async () => {
    root.render(
      React.createElement(
        WorkspaceTabContentInteraction,
        { isActive: false, isVisible: true, onActivate: activateSplitTab },
        React.createElement('button', {
          'data-testid': 'editor-control',
          onKeyDown: () => {
            shortcutPane = activeTabStore.getState().activePane;
          },
          onPointerDown: () => {
            pointerTargetPane = activeTabStore.getState().activePane;
          },
        }),
        React.createElement('iframe', { title: 'embedded editor', tabIndex: 0 }),
      ),
    );
  });

  const editorControl = container.querySelector<HTMLButtonElement>('[data-testid="editor-control"]');
  const embeddedEditor = container.querySelector<HTMLIFrameElement>('iframe');
  assert.ok(editorControl);
  assert.ok(embeddedEditor);

  resetActiveTab();
  activationCalls = 0;
  outsideFocusTarget.focus();
  await act(async () => {
    editorControl.dispatchEvent(new domWindow.MouseEvent('pointerdown', { bubbles: true, cancelable: true }));
    editorControl.focus();
  });
  assert.equal(pointerTargetPane, 'pane-right', 'capture activation must precede the target pointer handler');
  assert.equal(activationCalls, 1, 'the focus following a pointer activation must not activate the tab twice');

  await act(async () => {
    root.render(
      React.createElement(
        WorkspaceTabContentInteraction,
        { isActive: false, isVisible: true, onActivate: activateSplitTab },
        React.createElement('button', {
          'data-testid': 'editor-control',
          onKeyDown: () => {
            shortcutPane = activeTabStore.getState().activePane;
          },
        }),
        React.createElement('iframe', { title: 'embedded editor', tabIndex: 0 }),
      ),
    );
  });

  const rerenderedEditorControl = container.querySelector<HTMLButtonElement>('[data-testid="editor-control"]');
  const rerenderedEmbeddedEditor = container.querySelector<HTMLIFrameElement>('iframe');
  assert.ok(rerenderedEditorControl);
  assert.ok(rerenderedEmbeddedEditor);

  resetActiveTab();
  outsideFocusTarget.focus();
  await act(async () => rerenderedEditorControl.focus());
  assert.deepEqual(
    activeTabStore.getState(),
    { activePane: 'pane-right', activeTabId: 'tab-b' },
    'keyboard or programmatic focus should activate the owning pane and tab',
  );
  await act(async () => {
    rerenderedEditorControl.dispatchEvent(new domWindow.KeyboardEvent('keydown', { bubbles: true, key: 'w' }));
  });
  assert.equal(shortcutPane, 'pane-right', 'shortcut handlers must observe the focused pane as active');

  await act(async () => {
    root.render(
      React.createElement(
        WorkspaceTabContentInteraction,
        { isActive: false, isVisible: true, onActivate: activateSplitTab },
        React.createElement('iframe', { title: 'embedded editor', tabIndex: 0 }),
      ),
    );
  });
  const innerFocusedFrame = container.querySelector<HTMLIFrameElement>('iframe');
  assert.ok(innerFocusedFrame?.contentDocument);
  const embeddedControl = innerFocusedFrame.contentDocument.createElement('button');
  innerFocusedFrame.contentDocument.body.append(embeddedControl);

  resetActiveTab();
  activationCalls = 0;
  outsideFocusTarget.focus();
  await act(async () => embeddedControl.focus());
  assert.equal(innerFocusedFrame.contentDocument.activeElement, embeddedControl);
  assert.equal(document.activeElement, innerFocusedFrame);
  assert.equal(
    activeTabStore.getState().activePane,
    'pane-right',
    'focus inside an embedded document should activate its owning pane',
  );
  assert.equal(activationCalls, 1, 'an embedded focus transition should activate its owner once');

  await act(async () => {
    root.render(
      React.createElement(
        WorkspaceTabContentInteraction,
        { isActive: false, isVisible: true, onActivate: activateSplitTab },
        React.createElement('iframe', { title: 'embedded editor', tabIndex: 0 }),
      ),
    );
  });
  resetActiveTab();
  activationCalls = 0;
  await act(async () => {
    domWindow.dispatchEvent(new domWindow.Event('blur'));
    await new Promise((resolve) => domWindow.setTimeout(resolve, 0));
  });
  assert.equal(
    activeTabStore.getState().activePane,
    'pane-right',
    'parent window focus transitions should recover activation for embedded documents',
  );
  assert.equal(activationCalls, 1, 'the parent focus bridge should activate the embedded owner once');

  outsideFocusTarget.focus();
  const callsBeforeUnmount = activationCalls;
  await act(async () => root.unmount());
  embeddedControl.focus();
  assert.equal(activationCalls, callsBeforeUnmount, 'unmount must detach embedded document listeners');
  outsideFocusTarget.remove();
  container.remove();
  domWindow.close();
  console.log('Workspace tab content interaction tests passed');
}

main().catch((error) => {
  console.error(error);
  domWindow.close();
  process.exit(1);
});
