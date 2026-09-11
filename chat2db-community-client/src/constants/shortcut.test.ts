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
    getEffectiveShortcutConfigMap,
    ShortcutScope,
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

  const platformModifier = DEFAULT_SHORTCUT_CONFIG[ShortcutAction.ZoomReset].defaultBinding.split(' + ')[0];
  assert.deepEqual(
    [
      ShortcutAction.SwitchToChat,
      ShortcutAction.SwitchToWorkspace,
      ShortcutAction.SwitchToDashboard,
      ShortcutAction.OpenSetting,
    ].map((action) => DEFAULT_SHORTCUT_CONFIG[action].defaultBinding),
    [1, 2, 3, 4].map((index) => `${platformModifier} + ${index}`),
    'top title-bar shortcuts should follow the visible Community action order',
  );

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

  // 5. Scoped DDL search shortcut (Issue #2748, plan B1)
  const { resolveShortcutDispatch } = await import('@/utils/shortcutDispatch');
  const ddlSearchDefault = DEFAULT_SHORTCUT_CONFIG[ShortcutAction.DdlSearch];
  assert.equal(ddlSearchDefault.scope, 'viewDdl');
  assert.equal(ddlSearchDefault.allowInEditable, true, 'must toggle while the search input is focused');
  assert.equal(ddlSearchDefault.canModify, true);
  assert.match(ddlSearchDefault.defaultBinding, / \+ F$/, 'defaults to modifier + F');

  const findEvent = {
    key: 'f',
    code: 'KeyF',
    metaKey: true,
    ctrlKey: false,
    altKey: false,
    shiftKey: false,
  } as KeyboardEvent;

  // Default binding: inside the viewDdl scope the global dispatcher defers to
  // the scoped (local) handler instead of firing a global action.
  const defaultConfigMap = getEffectiveShortcutConfigMap();
  assert.equal(
    resolveShortcutDispatch(findEvent, defaultConfigMap, {
      activeScope: ShortcutScope.ViewDdl,
      editableTarget: false,
      workspaceSaveAllowed: false,
    }),
    undefined,
    'viewDdl scope consumes its own search shortcut',
  );
  // Outside any scope, modifier+F is not a global action either.
  assert.equal(
    resolveShortcutDispatch(findEvent, defaultConfigMap, {
      editableTarget: false,
      workspaceSaveAllowed: false,
    }),
    undefined,
    'no global action owns modifier + F',
  );

  // Rebinding is honoured: the new binding is what the scoped listener sees.
  const reboundMap = getEffectiveShortcutConfigMap({
    [ShortcutAction.DdlSearch]: { binding: 'Ctrl + G' },
  });
  assert.equal(reboundMap[ShortcutAction.DdlSearch].binding, 'Ctrl + G');
  assert.equal(reboundMap[ShortcutAction.DdlSearch].disabled, false);
  assert.equal(reboundMap[ShortcutAction.DdlSearch].isDefault, false);

  // Disabling: binding null marks the config disabled so the local listener
  // must not take over, and the dispatcher does not treat it as scoped either.
  const disabledMap = getEffectiveShortcutConfigMap({
    [ShortcutAction.DdlSearch]: { binding: null },
  });
  assert.equal(disabledMap[ShortcutAction.DdlSearch].disabled, true);
  assert.equal(
    resolveShortcutDispatch(findEvent, disabledMap, {
      activeScope: ShortcutScope.ViewDdl,
      editableTarget: false,
      workspaceSaveAllowed: false,
    }),
    undefined,
    'a disabled scoped shortcut falls through to the browser default',
  );

  // Restoring defaults re-enables the takeover.
  const restoredMap = getEffectiveShortcutConfigMap({});
  assert.equal(restoredMap[ShortcutAction.DdlSearch].disabled, false);
  assert.equal(restoredMap[ShortcutAction.DdlSearch].isDefault, true);

  // Same key in a different scope (ResultSet) must not leak into viewDdl:
  // only configs of the active scope count as scoped matches.
  const sameKeyMap = getEffectiveShortcutConfigMap();
  assert.equal(sameKeyMap[ShortcutAction.ResultSearch].binding, sameKeyMap[ShortcutAction.DdlSearch].binding);
  assert.equal(
    resolveShortcutDispatch(findEvent, sameKeyMap, {
      activeScope: ShortcutScope.ResultSet,
      editableTarget: false,
      workspaceSaveAllowed: false,
    }),
    undefined,
    'ResultSet keeps handling its own search shortcut',
  );

  console.log('All shortcut tests passed successfully!');
}

runShortcutTests().catch((err) => {
  console.error(err);
  process.exit(1);
});
