import assert from 'node:assert/strict';
import {
  MentionSuggestionResolution,
  MentionTableRequestCoordinator,
  type MentionTableRequestOwner,
} from './mentionTableRequestCoordinator';

type TestPage = 'workspace' | 'stream';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((next, fail) => {
    resolve = next;
    reject = fail;
  });
  return { promise, resolve, reject };
}

async function commitWhenCurrent(
  coordinator: MentionTableRequestCoordinator<TestPage>,
  owner: MentionTableRequestOwner<TestPage>,
  response: Promise<string>,
  commits: string[],
) {
  const value = await response;
  if (coordinator.isCurrent(owner)) {
    commits.push(value);
  }
}

async function clearOwnedPageOnError(
  coordinator: MentionTableRequestCoordinator<TestPage>,
  owner: MentionTableRequestOwner<TestPage>,
  response: Promise<never>,
  clearedPages: TestPage[],
) {
  try {
    await response;
  } catch {
    const page = coordinator.getOwnedErrorPage(owner);
    if (page) {
      clearedPages.push(page);
    }
  }
}

async function run() {
  const tableOnlyResolution = new MentionSuggestionResolution(true);
  assert.equal(tableOnlyResolution.resolveKnowledge(false), 'wait');
  assert.equal(
    tableOnlyResolution.resolveTable(true),
    'open',
    'table candidates must open the mention menu when knowledge has no candidates',
  );

  const knowledgeOnlyResolution = new MentionSuggestionResolution(false);
  assert.equal(knowledgeOnlyResolution.resolveKnowledge(true), 'open');

  const emptyResolution = new MentionSuggestionResolution(true);
  assert.equal(emptyResolution.resolveTable(false), 'wait');
  assert.equal(emptyResolution.resolveKnowledge(false), 'close');

  const reverseEmptyResolution = new MentionSuggestionResolution(true);
  assert.equal(reverseEmptyResolution.resolveKnowledge(false), 'wait');
  assert.equal(reverseEmptyResolution.resolveTable(false), 'close');

  const delayedKnowledgeResolution = new MentionSuggestionResolution(true);
  assert.equal(delayedKnowledgeResolution.resolveTable(false), 'wait');
  assert.equal(delayedKnowledgeResolution.resolveKnowledge(true), 'open');

  const alreadyOpenResolution = new MentionSuggestionResolution(true);
  assert.equal(alreadyOpenResolution.resolveTable(true), 'open');
  assert.equal(alreadyOpenResolution.resolveKnowledge(true), 'ignore');

  const contextCoordinator = new MentionTableRequestCoordinator<TestPage>();
  const contextA = deferred<string>();
  const contextB = deferred<string>();
  const contextCommits: string[] = [];
  const contextAOwner = contextCoordinator.beginRequest(
    'workspace',
    { dataSourceId: 1, databaseName: 'db-a', schemaName: 'schema-a' },
    '',
  );
  const contextARequest = commitWhenCurrent(
    contextCoordinator,
    contextAOwner,
    contextA.promise,
    contextCommits,
  );
  const contextBOwner = contextCoordinator.beginRequest(
    'workspace',
    { dataSourceId: 2, databaseName: 'db-b', schemaName: 'schema-b' },
    '',
  );
  const contextBRequest = commitWhenCurrent(
    contextCoordinator,
    contextBOwner,
    contextB.promise,
    contextCommits,
  );
  contextB.resolve('context-b');
  await contextBRequest;
  contextA.resolve('context-a');
  await contextARequest;

  const searchCoordinator = new MentionTableRequestCoordinator<TestPage>();
  const oldSearch = deferred<string>();
  const newSearch = deferred<string>();
  const searchCommits: string[] = [];
  const oldSearchOwner = searchCoordinator.beginRequest('stream', { dataSourceId: 7 }, 'old');
  const oldSearchRequest = commitWhenCurrent(
    searchCoordinator,
    oldSearchOwner,
    oldSearch.promise,
    searchCommits,
  );
  const newSearchOwner = searchCoordinator.beginRequest('stream', { dataSourceId: 7 }, 'new');
  const newSearchRequest = commitWhenCurrent(
    searchCoordinator,
    newSearchOwner,
    newSearch.promise,
    searchCommits,
  );
  newSearch.resolve('search-new');
  await newSearchRequest;
  oldSearch.resolve('search-old');
  await oldSearchRequest;

  const errorCoordinator = new MentionTableRequestCoordinator<TestPage>();
  const staleFailure = deferred<never>();
  const currentFailure = deferred<never>();
  const clearedPages: TestPage[] = [];
  const staleErrorOwner = errorCoordinator.beginRequest('workspace', { dataSourceId: 11 }, 'first');
  const staleErrorRequest = clearOwnedPageOnError(
    errorCoordinator,
    staleErrorOwner,
    staleFailure.promise,
    clearedPages,
  );
  const currentErrorOwner = errorCoordinator.beginRequest('stream', { dataSourceId: 12 }, 'second');
  const currentErrorRequest = clearOwnedPageOnError(
    errorCoordinator,
    currentErrorOwner,
    currentFailure.promise,
    clearedPages,
  );
  staleFailure.reject(new Error('stale failure'));
  await staleErrorRequest;
  currentFailure.reject(new Error('current failure'));
  await currentErrorRequest;

  const invalidatedOwner = errorCoordinator.beginRequest('stream', { dataSourceId: 13 }, 'pending');
  errorCoordinator.invalidate();

  assert.deepEqual(
    {
      contextCommits,
      searchCommits,
      clearedPages,
      invalidatedOwnerIsCurrent: errorCoordinator.isCurrent(invalidatedOwner),
    },
    {
      contextCommits: ['context-b'],
      searchCommits: ['search-new'],
      clearedPages: ['stream'],
      invalidatedOwnerIsCurrent: false,
    },
  );

  console.log('AI mention table request coordinator tests passed.');
}

run().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
