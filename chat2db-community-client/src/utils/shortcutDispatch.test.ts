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

async function run() {
  const [{ getEffectiveShortcutConfigMap, ShortcutAction, ShortcutScope }, { resolveShortcutDispatch }] =
    await Promise.all([import('@/constants/shortcut'), import('./shortcutDispatch')]);
  const event = {
    key: 's',
    code: 'KeyS',
    metaKey: false,
    ctrlKey: true,
    altKey: false,
    shiftKey: false,
  } as KeyboardEvent;
  const shortcutConfig = getEffectiveShortcutConfigMap({
    [ShortcutAction.SqlSave]: { binding: 'Ctrl + S' },
  });

  assert.deepEqual(
    resolveShortcutDispatch(event, shortcutConfig, { editableTarget: false, workspaceSaveAllowed: true }),
    { kind: 'workspace-save' },
    'non-editor workspace focus routes the save shortcut to the active editor',
  );
  assert.equal(
    resolveShortcutDispatch(event, shortcutConfig, { editableTarget: true, workspaceSaveAllowed: true }),
    undefined,
    'editor-owned shortcuts remain inside the editor',
  );
  assert.equal(
    resolveShortcutDispatch(event, shortcutConfig, { editableTarget: false, workspaceSaveAllowed: false }),
    undefined,
    'workspace save must not affect a hidden editor from another page',
  );

  const conflictingConfig = getEffectiveShortcutConfigMap({
    [ShortcutAction.SqlSave]: { binding: 'Ctrl + S' },
    [ShortcutAction.SwitchToChat]: { binding: 'Ctrl + S' },
  });
  assert.deepEqual(
    resolveShortcutDispatch(event, conflictingConfig, { editableTarget: false, workspaceSaveAllowed: true }),
    { kind: 'global', action: ShortcutAction.SwitchToChat },
    'global shortcuts retain precedence over a SQL-editor shortcut with the same binding',
  );

  const fileTreeConfig = getEffectiveShortcutConfigMap({
    [ShortcutAction.SqlSave]: { binding: 'Ctrl + S' },
    [ShortcutAction.LocalSqlFileTreeDelete]: { binding: 'Ctrl + S' },
  });
  assert.equal(
    resolveShortcutDispatch(event, fileTreeConfig, {
      activeScope: ShortcutScope.LocalSqlFileTree,
      editableTarget: false,
      workspaceSaveAllowed: true,
    }),
    undefined,
    'a focused scope owns its binding before the workspace save fallback',
  );

  const resultSetConfig = getEffectiveShortcutConfigMap({
    [ShortcutAction.SqlSave]: { binding: 'Ctrl + S' },
    [ShortcutAction.ResultSubmit]: { binding: 'Ctrl + S' },
  });
  assert.equal(
    resolveShortcutDispatch(event, resultSetConfig, {
      activeScope: ShortcutScope.ResultSet,
      editableTarget: false,
      workspaceSaveAllowed: true,
    }),
    undefined,
    'the result set submit shortcut must not fall through to workspace console save',
  );

  console.log('shortcut dispatch tests passed');
}

void run();
