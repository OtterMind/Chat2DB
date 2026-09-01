import assert from 'node:assert/strict';
import { getLocalFileSaveKey, LocalFileSaveCoordinator } from './localFileSaveCoordinator';

function deferred() {
  let resolve!: () => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<void>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
}

async function testSavesAreSerializedAndCoalesced() {
  const coordinator = new LocalFileSaveCoordinator();
  const firstGate = deferred();
  const calls: string[] = [];
  const mutation = async ({ fileContent }: { fileContent: string }) => {
    calls.push(`start:${fileContent}`);
    if (fileContent === 'first') {
      await firstGate.promise;
    }
    calls.push(`end:${fileContent}`);
  };
  const latestMutation = async ({ fileContent }: { fileContent: string }) => {
    calls.push(`latest-start:${fileContent}`);
    calls.push(`latest-end:${fileContent}`);
  };

  const first = coordinator.save({ filePath: '/tmp/query.sql', fileContent: 'first' }, mutation);
  const second = coordinator.save({ filePath: '/tmp/query.sql', fileContent: 'second' }, latestMutation);
  await Promise.resolve();
  assert.deepEqual(calls, ['start:first']);

  firstGate.resolve();
  assert.deepEqual(await first, { filePath: '/tmp/query.sql', fileContent: 'second' });
  assert.deepEqual(await second, { filePath: '/tmp/query.sql', fileContent: 'second' });
  assert.deepEqual(calls, ['start:first', 'end:first', 'latest-start:second', 'latest-end:second']);
}

async function testDifferentPathsCanRunIndependently() {
  const coordinator = new LocalFileSaveCoordinator();
  const firstGate = deferred();
  const calls: string[] = [];
  const mutation = async ({ filePath }: { filePath: string }) => {
    calls.push(`start:${filePath}`);
    if (filePath.endsWith('first.sql')) {
      await firstGate.promise;
    }
    calls.push(`end:${filePath}`);
  };

  const first = coordinator.save({ filePath: '/tmp/first.sql', fileContent: '1' }, mutation);
  const second = coordinator.save({ filePath: '/tmp/second.sql', fileContent: '2' }, mutation);
  await second;
  assert.deepEqual(calls, ['start:/tmp/first.sql', 'start:/tmp/second.sql', 'end:/tmp/second.sql']);
  firstGate.resolve();
  await first;
}

async function testEquivalentPathsShareSaveOrder() {
  const coordinator = new LocalFileSaveCoordinator(false);
  const firstGate = deferred();
  const calls: string[] = [];
  const mutation = async ({ fileContent }: { fileContent: string }) => {
    calls.push(`start:${fileContent}`);
    if (fileContent === 'first') {
      await firstGate.promise;
    }
    calls.push(`end:${fileContent}`);
  };

  const first = coordinator.save({ filePath: '/work/dir/../query.sql', fileContent: 'first' }, mutation);
  const second = coordinator.save({ filePath: '/work/query.sql', fileContent: 'second' }, mutation);
  await Promise.resolve();
  assert.deepEqual(calls, ['start:first']);

  firstGate.resolve();
  assert.equal((await first).fileContent, 'second');
  assert.equal((await second).fileContent, 'second');
  assert.deepEqual(calls, ['start:first', 'end:first', 'start:second', 'end:second']);
}

async function testFailureRejectsCurrentBatchAndAllowsRetry() {
  const coordinator = new LocalFileSaveCoordinator();
  let attempts = 0;
  const mutation = async () => {
    attempts += 1;
    if (attempts === 1) {
      throw new Error('expected failure');
    }
  };

  await assert.rejects(
    coordinator.save({ filePath: '/tmp/failure.sql', fileContent: 'bad' }, mutation),
    /expected failure/,
  );
  const result = await coordinator.save({ filePath: '/tmp/failure.sql', fileContent: 'retry' }, mutation);
  assert.equal(result.fileContent, 'retry');
  assert.equal(attempts, 2);
}

async function testWaitForIdleIncludesQueuedSaves() {
  const coordinator = new LocalFileSaveCoordinator();
  const firstGate = deferred();
  let idle = false;
  const mutation = async () => firstGate.promise;

  const save = coordinator.save({ filePath: '/tmp/pending.sql', fileContent: 'pending' }, mutation);
  void coordinator.waitForIdle('/tmp/pending.sql').then(() => {
    idle = true;
  });
  await Promise.resolve();
  assert.equal(idle, false, 'pending writes keep the file busy');

  firstGate.resolve();
  await save;
  await coordinator.waitForIdle('/tmp/pending.sql');
  assert.equal(idle, true, 'the idle barrier resolves after the save queue drains');
}

async function run() {
  assert.equal(getLocalFileSaveKey('/work/dir/../query.sql'), '/work/query.sql');
  assert.equal(getLocalFileSaveKey('/tmp//query.sql'), '/tmp/query.sql');
  assert.equal(getLocalFileSaveKey('/tmp/query.sql/'), '/tmp/query.sql');
  assert.equal(getLocalFileSaveKey('C:\\SQL\\..\\Query.sql', true), 'c:/query.sql');
  assert.equal(getLocalFileSaveKey('\\\\SERVER\\Share\\dir\\..\\Query.sql', true), '//server/share/query.sql');
  assert.notEqual(
    getLocalFileSaveKey('/tmp/a\\b.sql'),
    getLocalFileSaveKey('/tmp/a/b.sql'),
    'POSIX backslashes must remain valid file-name characters',
  );
  assert.notEqual(
    getLocalFileSaveKey('/tmp/query.sql'),
    getLocalFileSaveKey('/tmp/query.sql '),
    'valid trailing spaces must not merge distinct files',
  );
  assert.notEqual(
    getLocalFileSaveKey('/tmp/query.sql'),
    getLocalFileSaveKey('/tmp/query.sql.'),
    'POSIX trailing dots remain part of the file name',
  );
  await testSavesAreSerializedAndCoalesced();
  await testDifferentPathsCanRunIndependently();
  await testEquivalentPathsShareSaveOrder();
  await testFailureRejectsCurrentBatchAndAllowsRetry();
  await testWaitForIdleIncludesQueuedSaves();
  console.log('local file save coordinator tests passed');
}

void run();
