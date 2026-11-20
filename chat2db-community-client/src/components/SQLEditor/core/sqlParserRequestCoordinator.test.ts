import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  SqlParserRequestCoordinator,
  runAfterCommittedSqlParser,
  runAfterCommittedSqlParserWithSnapshot,
  type SqlParserRequestContext,
  type SqlParserRequestScope,
} from './sqlParserRequestCoordinator';
import {
  createSqlExecutionTargetSnapshot,
  resolveSqlExecutionTarget,
} from './sqlExecutionTargetSnapshot';

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
}

function deferred<T>(): Deferred<T> {
  let resolvePromise: ((value: T) => void) | undefined;
  const promise = new Promise<T>((resolve) => {
    resolvePromise = resolve;
  });
  return { promise, resolve: (value) => resolvePromise?.(value) };
}

function context(
  scope: SqlParserRequestScope,
  overrides: Partial<Omit<SqlParserRequestContext, 'scope'>> = {},
): SqlParserRequestContext {
  return {
    scope,
    databaseKey: 'db-a',
    sql: 'select 1',
    model: modelA,
    modelVersion: 1,
    ...overrides,
  };
}

const modelA = { id: 'model-a' };
const modelB = { id: 'model-b' };

async function testLatestRequestOwnsAllParserSideEffects() {
  const coordinator = new SqlParserRequestCoordinator();
  const scope = coordinator.createScope();
  const first = deferred<string>();
  const latest = deferred<string>();
  let currentContext = context(scope, { sql: 'select 1', modelVersion: 1 });
  const sideEffects = {
    statements: [] as string[],
    markers: [] as string[],
    completion: [] as string[],
    decorations: [] as string[],
    hints: [] as string[],
  };
  const commit = (value: string) => {
    sideEffects.statements.push(value);
    sideEffects.markers.push(value);
    sideEffects.completion.push(value);
    sideEffects.decorations.push(value);
    sideEffects.hints.push(value);
  };

  const firstStatus = coordinator.run(
    currentContext,
    () => currentContext,
    () => first.promise,
    commit,
  );
  currentContext = context(scope, { sql: 'select 2', modelVersion: 2 });
  const latestStatus = coordinator.run(
    currentContext,
    () => currentContext,
    () => latest.promise,
    commit,
  );
  latest.resolve('S2');
  assert.equal(await latestStatus, 'committed');
  first.resolve('S1');
  assert.equal(await firstStatus, 'stale');
  Object.values(sideEffects).forEach((values) => assert.deepEqual(values, ['S2']));
}

async function testEmptySqlInvalidatesPendingRequest() {
  const coordinator = new SqlParserRequestCoordinator();
  const scope = coordinator.createScope();
  const request = deferred<string>();
  const requestContext = context(scope);
  const commits: string[] = [];
  const status = coordinator.run(
    requestContext,
    () => requestContext,
    () => request.promise,
    (value) => {
      commits.push(value);
    },
  );
  coordinator.invalidate();
  request.resolve('stale');
  assert.equal(await status, 'stale');
  assert.deepEqual(commits, []);
}

async function testUnmountInvalidatesInflightAndLateStarts() {
  const coordinator = new SqlParserRequestCoordinator();
  const scope = coordinator.createScope();
  const request = deferred<string>();
  const requestContext = context(scope);
  const commits: string[] = [];
  const status = coordinator.run(
    requestContext,
    () => requestContext,
    () => request.promise,
    (value) => {
      commits.push(value);
    },
  );
  coordinator.dispose();
  request.resolve('stale');
  assert.equal(await status, 'stale');
  let lateStarts = 0;
  assert.equal(
    await coordinator.run(
      requestContext,
      () => requestContext,
      async () => {
        lateStarts += 1;
        return 'late';
      },
      (value) => commits.push(value),
    ),
    'stale',
  );
  assert.equal(lateStarts, 0);
  assert.deepEqual(commits, []);
}

async function testDatabaseAndModelChangesInvalidatePendingRequest() {
  const coordinator = new SqlParserRequestCoordinator();
  const firstScope = coordinator.createScope();
  const databaseRequest = deferred<string>();
  let currentContext = context(firstScope);
  const databaseStatus = coordinator.run(
    currentContext,
    () => currentContext,
    () => databaseRequest.promise,
    () => {
      assert.fail('database-stale request committed');
    },
  );
  currentContext = context(coordinator.createScope(), { databaseKey: 'db-b' });
  databaseRequest.resolve('db-a');
  assert.equal(await databaseStatus, 'stale');

  const modelRequest = deferred<string>();
  currentContext = context(firstScope, { model: modelA, modelVersion: 1 });
  const modelStatus = coordinator.run(
    currentContext,
    () => currentContext,
    () => modelRequest.promise,
    () => {
      assert.fail('model-stale request committed');
    },
  );
  currentContext = context(firstScope, { model: modelB, modelVersion: 1 });
  modelRequest.resolve('model-a');
  assert.equal(await modelStatus, 'stale');
}

async function testAbaContextUsesUniqueScope() {
  const coordinator = new SqlParserRequestCoordinator();
  const firstScope = coordinator.createScope();
  const request = deferred<string>();
  let currentContext = context(firstScope, { databaseKey: 'db-a' });
  const status = coordinator.run(
    currentContext,
    () => currentContext,
    () => request.promise,
    () => {
      assert.fail('ABA-stale request committed');
    },
  );
  currentContext = context(coordinator.createScope(), { databaseKey: 'db-b' });
  const secondAScope = coordinator.createScope();
  assert.notEqual(firstScope, secondAScope);
  currentContext = context(secondAScope, { databaseKey: 'db-a' });
  request.resolve('first-a');
  assert.equal(await status, 'stale');
}

async function testStaleQuickParseCannotExecuteSharedStatement() {
  const coordinator = new SqlParserRequestCoordinator();
  const scope = coordinator.createScope();
  const request = deferred<string>();
  let currentContext = context(scope, { sql: 'select new', modelVersion: 2 });
  let sharedStatement = 'select old';
  const statusPromise = coordinator.run(currentContext, () => currentContext, () => request.promise, (statement) => {
    sharedStatement = statement;
  });
  currentContext = context(scope, { sql: 'select newer', modelVersion: 3 });
  request.resolve('select new');
  const executions: string[] = [];
  const executed = await runAfterCommittedSqlParser(
    () => statusPromise,
    () => {
      executions.push(sharedStatement);
    },
  );
  assert.equal(executed, false);
  assert.deepEqual(executions, [], 'quick execution must not read shared parser refs after a stale result');
}

async function testToolbarCannotExecuteStatementAfterEditorInvalidation() {
  const coordinator = new SqlParserRequestCoordinator();
  const scope = coordinator.createScope();
  const request = deferred<string>();
  let currentContext = context(scope, { sql: 'select old', modelVersion: 1 });
  let sharedStatement = 'select previously parsed';
  const parseStatus = coordinator.run(currentContext, () => currentContext, () => request.promise, (statement) => {
    sharedStatement = statement;
  });

  currentContext = context(scope, { sql: 'select edited', modelVersion: 2 });
  coordinator.invalidate();
  sharedStatement = '';
  request.resolve('select old');
  const executions: string[] = [];
  const executed = await runAfterCommittedSqlParser(
    () => parseStatus,
    () => {
      executions.push(sharedStatement);
    },
  );

  assert.equal(executed, false);
  assert.deepEqual(executions, [], 'the toolbar must not execute a statement parsed before the latest edit');
}

async function testDeferredExecutionUsesTriggerTimeSelectionAndCursor() {
  const request = deferred<void>();
  const liveCursor = { lineNumber: 1, column: 1 };
  const cursorExecutions: Array<{ sql: string; single: boolean }> = [];
  const pendingCursorExecution = runAfterCommittedSqlParserWithSnapshot(
    () => createSqlExecutionTargetSnapshot('', liveCursor),
    async () => {
      await request.promise;
      return 'committed';
    },
    (cursorSnapshot) => {
      cursorExecutions.push(
        resolveSqlExecutionTarget(cursorSnapshot, (position) =>
          position.lineNumber === 1 ? 'select safe' : 'delete from important_table',
        ),
      );
    },
  );

  liveCursor.lineNumber = 3;
  liveCursor.column = 8;
  assert.deepEqual(cursorExecutions, [], 'execution must wait for the parser to commit');
  request.resolve();
  assert.equal(await pendingCursorExecution, true);
  assert.deepEqual(cursorExecutions, [{ sql: 'select safe', single: true }]);

  const selectionRequest = deferred<void>();
  let liveSelection = 'select selected';
  const selectionExecutions: Array<{ sql: string; single: boolean }> = [];
  const pendingSelectionExecution = runAfterCommittedSqlParserWithSnapshot(
    () => createSqlExecutionTargetSnapshot(liveSelection, liveCursor),
    async () => {
      await selectionRequest.promise;
      return 'committed';
    },
    (selectionSnapshot) => {
      selectionExecutions.push(resolveSqlExecutionTarget(selectionSnapshot, () => 'delete from important_table'));
    },
  );

  liveSelection = 'delete from important_table';
  assert.deepEqual(selectionExecutions, [], 'selection execution must wait for the parser to commit');
  selectionRequest.resolve();
  assert.equal(await pendingSelectionExecution, true);
  assert.deepEqual(selectionExecutions, [{ sql: 'select selected', single: false }]);

  let staleExecutions = 0;
  assert.equal(
    await runAfterCommittedSqlParserWithSnapshot(
      () => createSqlExecutionTargetSnapshot('', null),
      async () => 'stale',
      () => {
        staleExecutions += 1;
      },
    ),
    false,
  );
  assert.equal(staleExecutions, 0);
  assert.deepEqual(resolveSqlExecutionTarget(createSqlExecutionTargetSnapshot('', null), () => 'unexpected'), {
    sql: '',
    single: true,
  });
}

function testProductionExecutionPathsUseCommittedParserState() {
  const editorSource = readFileSync('src/components/SQLEditor/editor/SQLEditor/index.tsx', 'utf8');
  const operationSource = readFileSync(
    'src/components/SQLEditor/editor/SQLEditorWithOperation/index.tsx',
    'utf8',
  );
  const completionSource = readFileSync(
    'src/components/SQLEditor/core/completionProviderManager.ts',
    'utf8',
  );
  const parserChangeSource = completionSource.slice(
    completionSource.indexOf('public onParserChange'),
    completionSource.indexOf('public bindModelDBInfo'),
  );
  const toolbarExecutionSource = operationSource.slice(
    operationSource.indexOf('const handleExecuteSingleSQL'),
    operationSource.indexOf('const handleShortCutExecuteSQL'),
  );
  const shortcutExecutionSource = operationSource.slice(
    operationSource.indexOf('const handleShortCutExecuteSQL'),
    operationSource.indexOf('/** Save current editor data. */'),
  );

  assert.match(
    editorSource,
    /const handleImmediateContentChange[\s\S]*?invalidateSqlParserRequestWork\(\)[\s\S]*?onContentChange\?\.\(sql\)/,
    'the immediate Monaco content event must invalidate stale parser state',
  );
  assert.match(editorSource, /onContentChange=\{handleImmediateContentChange\}/);
  assert.match(
    operationSource,
    /case SQLOptType\.EXECUTE_SINGLE_SQL:[\s\S]{0,160}handleExecuteSingleSQL\(typeof params === 'string'/,
    'gutter execution must preserve the SQL from its committed quick parse',
  );
  [toolbarExecutionSource, shortcutExecutionSource].forEach((executionSource) => {
    assert.match(
      executionSource,
      /await runAfterCommittedSqlParserWithSnapshot\([\s\S]*?createSqlExecutionTargetSnapshot\([\s\S]*?editor\.getSelectedContent\(\)[\s\S]*?editor\.getInstance\(\)\?\.getPosition\(\)[\s\S]*?editor\.handleQuickSQLParser\(sqlSnapshot, executionBoundInfo\)/,
      'execution must capture selection and cursor state before awaiting its current quick parse',
    );
    assert.match(
      executionSource,
      /resolveSqlExecutionTarget\(targetSnapshot,[\s\S]*?editor\.getCursorCurLineNearestSQL\(position\)/,
      'execution must resolve the committed statement at its trigger-time cursor position',
    );
    assert.doesNotMatch(
      executionSource,
      /getCursorCurLineNearestSQL\(\)/,
      'execution must not read the live cursor after awaiting the parser',
    );
    const postParseSource = executionSource.slice(executionSource.indexOf('editor.handleQuickSQLParser'));
    assert.doesNotMatch(
      postParseSource,
      /getSelectedContent\(\)|getPosition\(\)/,
      'execution must not read live selection or cursor state after starting the parser',
    );
  });
  assert.match(
    toolbarExecutionSource,
    /if \(committedSql !== undefined\) \{[\s\S]*?await execute\(committedSql\);[\s\S]*?return;[\s\S]*?runAfterCommittedSqlParserWithSnapshot/,
    'gutter execution must use its already committed SQL without starting another parser request',
  );
  assert.match(
    toolbarExecutionSource,
    /single: true,[\s\S]*?resolveSqlExecutionTarget\(targetSnapshot,[\s\S]*?execute\(target\.sql\)/,
    'toolbar execution must stay single-statement and use SQL resolved from its trigger-time snapshot',
  );
  assert.match(
    shortcutExecutionSource,
    /resolveSqlExecutionTarget\([\s\S]*?sql: target\.sql,[\s\S]*?single: target\.single/,
    'shortcut execution must use both SQL and selection mode from its trigger-time snapshot',
  );
  assert.match(parserChangeSource, /this\.originColumnList = \[\]/);
  assert.equal(
    parserChangeSource.includes('setTimeout'),
    false,
    'clearing parser completion state must not allow an old delayed column update to write back',
  );
}

void Promise.all([
  testLatestRequestOwnsAllParserSideEffects(),
  testEmptySqlInvalidatesPendingRequest(),
  testUnmountInvalidatesInflightAndLateStarts(),
  testDatabaseAndModelChangesInvalidatePendingRequest(),
  testAbaContextUsesUniqueScope(),
  testStaleQuickParseCannotExecuteSharedStatement(),
  testToolbarCannotExecuteStatementAfterEditorInvalidation(),
  testDeferredExecutionUsesTriggerTimeSelectionAndCursor(),
  testProductionExecutionPathsUseCommittedParserState(),
])
  .then(() => console.log('SQL parser request coordinator tests passed'))
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
