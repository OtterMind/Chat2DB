import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ConsoleStatus } from '@/constants/common';
import { WorkspaceTabType } from '@/constants/workspace';
import type { IBoundInfo, IConsole } from '@/typings';
import { createSavedConsoleLoadSQL, type SavedConsoleReader } from './savedConsoleRestore';

function releaseConsoleData(overrides: IBoundInfo = {}): IBoundInfo {
  return {
    consoleId: 42,
    status: ConsoleStatus.RELEASE,
    ddl: 'select * from snapshot',
    ...overrides,
  };
}

function createReader(handler: (id: number) => Promise<IConsole | null>) {
  const requestedIds: number[] = [];
  const reader: SavedConsoleReader = ({ id }) => {
    requestedIds.push(id);
    return handler(id);
  };
  return { reader, requestedIds };
}

test('a saved console tab restores sql from the backend', async () => {
  const { reader, requestedIds } = createReader(async () => {
    return { id: 42, ddl: 'select * from test_export_table' } as IConsole;
  });
  const loadSQL = createSavedConsoleLoadSQL({
    tabId: 42,
    tabType: WorkspaceTabType.CONSOLE,
    uniqueData: releaseConsoleData(),
    readSavedConsole: reader,
  });

  assert.ok(loadSQL, 'a RELEASE console tab must restore sql from the saved console');
  assert.equal(await loadSQL(), 'select * from test_export_table');
  assert.deepEqual(requestedIds, [42]);
});

test('a missing saved console falls back to the snapshot', async () => {
  const { reader } = createReader(async () => null);
  const loadSQL = createSavedConsoleLoadSQL({
    tabId: 42,
    tabType: WorkspaceTabType.CONSOLE,
    uniqueData: releaseConsoleData(),
    readSavedConsole: reader,
  });

  assert.ok(loadSQL);
  assert.equal(await loadSQL(), 'select * from snapshot');
});

test('a failed saved console read falls back to the snapshot without throwing', async () => {
  const { reader } = createReader(async () => {
    throw new Error('common.dataNotFound');
  });
  const loadSQL = createSavedConsoleLoadSQL({
    tabId: 42,
    tabType: WorkspaceTabType.CONSOLE,
    uniqueData: releaseConsoleData({ ddl: undefined }),
    readSavedConsole: reader,
  });

  assert.ok(loadSQL);
  assert.equal(await loadSQL(), '');
});

test('a draft console keeps reading from the local draft store', () => {
  const { reader } = createReader(async () => ({ id: 42, ddl: 'select 1' } as IConsole));
  const loadSQL = createSavedConsoleLoadSQL({
    tabId: 42,
    tabType: WorkspaceTabType.CONSOLE,
    uniqueData: releaseConsoleData({ status: ConsoleStatus.DRAFT }),
    readSavedConsole: reader,
  });

  assert.equal(loadSQL, undefined);
});

test('a non-console tab is not restored from saved consoles', () => {
  const { reader } = createReader(async () => ({ id: 42, ddl: 'select 1' } as IConsole));
  const loadSQL = createSavedConsoleLoadSQL({
    tabId: 42,
    tabType: WorkspaceTabType.VIEW,
    uniqueData: releaseConsoleData(),
    readSavedConsole: reader,
  });

  assert.equal(loadSQL, undefined);
});

test('a non-numeric tab without console id is not restored from saved consoles', () => {
  const { reader } = createReader(async () => ({ id: 42, ddl: 'select 1' } as IConsole));
  const loadSQL = createSavedConsoleLoadSQL({
    tabId: 'chat2db_temporary_42',
    tabType: WorkspaceTabType.CONSOLE,
    uniqueData: releaseConsoleData({ consoleId: undefined }),
    readSavedConsole: reader,
  });

  assert.equal(loadSQL, undefined);
});

test('a numeric tab id is used when the console id is missing', async () => {
  const { reader, requestedIds } = createReader(async () => {
    return { id: 42, ddl: 'select * from test_export_table' } as IConsole;
  });
  const loadSQL = createSavedConsoleLoadSQL({
    tabId: 42,
    tabType: WorkspaceTabType.CONSOLE,
    uniqueData: releaseConsoleData({ consoleId: undefined }),
    readSavedConsole: reader,
  });

  assert.ok(loadSQL);
  assert.equal(await loadSQL(), 'select * from test_export_table');
  assert.deepEqual(requestedIds, [42]);
});
