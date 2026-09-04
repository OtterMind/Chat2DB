import assert from 'node:assert/strict';
import {
  applySavedConsoleBoundInfoSwitch,
  resolveSavedConsoleBoundInfoSwitch,
} from './savedConsoleBoundInfoSwitch';

async function run() {
  const current = {
    consoleId: 42,
    dataSourceId: 7,
    dataSourceName: 'main',
    databaseName: 'shop',
    schemaName: 'public',
    databaseType: 'MYSQL',
    nameCustomized: false,
  };

  assert.deepEqual(
    resolveSavedConsoleBoundInfoSwitch(current as any, {
      ...current,
      databaseName: 'analytics',
      schemaName: 'mart',
    } as any),
    {
      persistBeforeUiSwitch: true,
      nextDBInfo: {
        ...current,
        databaseName: 'analytics',
        schemaName: 'mart',
        nameCustomized: false,
      },
    },
  );

  const crossDataSource = resolveSavedConsoleBoundInfoSwitch(current as any, {
    ...current,
    dataSourceId: 8,
    databaseName: 'analytics',
  } as any);
  assert.equal(
    crossDataSource.persistBeforeUiSwitch,
    true,
    'cross-datasource switches must persist before manual execution can begin',
  );

  assert.equal(
    resolveSavedConsoleBoundInfoSwitch(current as any, current as any).persistBeforeUiSwitch,
    false,
    'unchanged connection targets do not need a blocking persistence request',
  );

  let resolvePersistence!: () => void;
  const persistence = new Promise<void>((resolve) => {
    resolvePersistence = resolve;
  });
  const applied: any[] = [];
  const switching = applySavedConsoleBoundInfoSwitch(
    crossDataSource,
    () => persistence,
    (next) => applied.push(next),
  );
  await Promise.resolve();
  assert.equal(applied.length, 0, 'UI must not switch while saved-console persistence is pending');
  resolvePersistence();
  await switching;
  assert.deepEqual(applied, [crossDataSource.nextDBInfo]);

  const failedApplied: any[] = [];
  await assert.rejects(
    applySavedConsoleBoundInfoSwitch(
      crossDataSource,
      async () => Promise.reject(new Error('save failed')),
      (next) => failedApplied.push(next),
    ),
    /save failed/,
  );
  assert.equal(failedApplied.length, 0, 'failed persistence must keep the original UI connection');

  console.log('Saved console bound info switch tests passed');
}

run().catch((error) => {
  console.error(error);
  process.exit(1);
});
