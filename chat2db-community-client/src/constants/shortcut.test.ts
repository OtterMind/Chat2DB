import assert from 'node:assert/strict';

const globalObj = globalThis as unknown as Record<string, unknown>;
globalObj.__RUNTIME_ENV__ = 'community';
globalObj.__ENV__ = 'test';
globalObj.window = {};

if (typeof globalThis.navigator === 'undefined') {
  Object.defineProperty(globalThis, 'navigator', {
    value: { userAgent: 'Mac' },
    configurable: true,
  });
}

async function runShortcutTests() {
  const {
    ShortcutAction,
    DEFAULT_SHORTCUT_CONFIG,
    getEffectiveShortcutConfig,
    getEventShortcutBinding,
    isShortcutCaptureAllowed,
    shortcutBindingToMonacoKeybinding,
  } = await import('./shortcut');

  // 1. Verify Default Shortcuts for Line Comment and Block Comment
  type DefaultConfigKey = keyof typeof DEFAULT_SHORTCUT_CONFIG;
  const lineKey = ShortcutAction.SqlToggleLineComment as DefaultConfigKey;
  const defaultLineComment = DEFAULT_SHORTCUT_CONFIG[lineKey];
  assert.equal(defaultLineComment.action, ShortcutAction.SqlToggleLineComment);
  assert.equal(defaultLineComment.canModify, true);

  const blockKey = ShortcutAction.SqlToggleBlockComment as DefaultConfigKey;
  const defaultBlockComment = DEFAULT_SHORTCUT_CONFIG[blockKey];
  assert.equal(defaultBlockComment.action, ShortcutAction.SqlToggleBlockComment);
  assert.equal(defaultBlockComment.canModify, true);

  // 2. Test getEventShortcutBinding with physical key (event.code) on shifted punctuation key
  const fakeSlashShiftEvent = {
    code: 'Slash',
    key: '?',
    ctrlKey: true,
    shiftKey: true,
    altKey: false,
    metaKey: false,
  } as unknown as KeyboardEvent;

  const bindingFromEvent = getEventShortcutBinding(fakeSlashShiftEvent);
  assert.equal(bindingFromEvent, 'Ctrl + Shift + /');

  // Parsing and capture policy are separate: printable keys need a modifier to be saved from Settings.
  assert.equal(isShortcutCaptureAllowed(['a']), false);
  assert.equal(isShortcutCaptureAllowed(['/']), false);
  assert.equal(isShortcutCaptureAllowed(['control']), false);
  assert.equal(isShortcutCaptureAllowed(['f5']), true);
  assert.equal(isShortcutCaptureAllowed(['control', 'shift', '?']), true);

  // 3. Test shortcutBindingToMonacoKeybinding with mock Monaco instance
  const mockMonaco = {
    KeyMod: {
      CtrlCmd: 2048,
      Shift: 1024,
      Alt: 512,
    },
    KeyCode: {
      Slash: 85,
      KeyC: 33,
      Semicolon: 80,
    },
  };

  const lineBinding = defaultLineComment.defaultBinding;
  const defaultLineKeybinding = shortcutBindingToMonacoKeybinding(lineBinding, mockMonaco);
  assert.notEqual(defaultLineKeybinding, null);

  const blockBinding = defaultBlockComment.defaultBinding;
  const defaultBlockKeybinding = shortcutBindingToMonacoKeybinding(blockBinding, mockMonaco);
  assert.notEqual(defaultBlockKeybinding, null);
  const expectedBlock = mockMonaco.KeyMod.CtrlCmd | mockMonaco.KeyMod.Shift | mockMonaco.KeyCode.Slash;
  assert.equal(defaultBlockKeybinding, expectedBlock);

  // 4. Test Remapping / Override Config
  const overrides = {
    [ShortcutAction.SqlToggleLineComment]: {
      binding: 'Ctrl + Shift + C',
    },
  };

  const remappedConfig = getEffectiveShortcutConfig(ShortcutAction.SqlToggleLineComment, overrides);
  assert.equal(remappedConfig.binding, 'Ctrl + Shift + C');
  assert.equal(remappedConfig.isDefault, false);

  const remappedKeybinding = shortcutBindingToMonacoKeybinding(remappedConfig.binding, mockMonaco);
  const expectedRemapped = mockMonaco.KeyMod.CtrlCmd | mockMonaco.KeyMod.Shift | mockMonaco.KeyCode.KeyC;
  assert.equal(remappedKeybinding, expectedRemapped);

  console.log('All shortcut tests passed successfully!');
}

runShortcutTests().catch((err) => {
  console.error(err);
  process.exit(1);
});
