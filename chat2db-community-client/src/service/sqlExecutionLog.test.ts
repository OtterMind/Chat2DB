import assert from 'node:assert/strict';
import { TableDataType } from '@/constants/table';
import type { IManageResultData } from '@/typings';
import { SqlTypeEnum } from '@/typings/sqlParser';
import {
  beginWebSqlExecution,
  clearSqlExecutionLog,
  completeWebSqlExecution,
  createSqlExecutionLogState,
  failWebSqlExecution,
  isSqlExecutionCancellationError,
  reduceDesktopSqlExecutionEvent,
  rethrowNonCancellationSqlExecutionError,
} from './sqlExecutionLog';

const context = {
  dataSourceId: 1,
  dataSourceName: '@localhost',
  databaseName: 'app',
};

function result(overrides: Partial<IManageResultData> = {}): IManageResultData {
  return {
    dataList: [],
    headerList: [],
    description: '',
    sql: 'select 1',
    originalSql: 'select 1',
    success: true,
    duration: 10,
    sqlType: SqlTypeEnum.SELECT,
    refreshTargets: [],
    pageNo: 1,
    pageSize: 1000,
    fuzzyTotal: '0',
    hasNextPage: false,
    ...overrides,
  };
}

function rawExecutionContext(value: Record<string, unknown>) {
  return value as unknown as NonNullable<IManageResultData['executionContext']>;
}

function assertTruncatedMessage(message: string | undefined) {
  const value = message || '';
  assert.equal(Array.from(value).length, 8_192);
  assert.ok(value.startsWith('prefix:'));
  assert.ok(value.endsWith(':suffix'));
  assert.doesNotThrow(() => encodeURIComponent(value));
}

function webExecution() {
  return beginWebSqlExecution(createSqlExecutionLogState(), {
    executionId: 'web-1',
    sql: 'select 1',
    context,
    occurredAtEpochMs: 100,
  });
}

{
  const running = webExecution();
  assert.equal(clearSqlExecutionLog(running).records.length, 1);
  const completed = completeWebSqlExecution(running, {
    executionId: 'web-1',
    sql: 'select 1',
    context,
    occurredAtEpochMs: 110,
    results: [],
  });
  assert.equal(clearSqlExecutionLog(completed).records.length, 0);
}

{
  const state = completeWebSqlExecution(webExecution(), {
    executionId: 'web-1',
    sql: 'select 1',
    context,
    occurredAtEpochMs: 140,
    results: [
      result({
        statementSequence: 1,
        dataList: [[], []],
        headerList: [
          { name: '#', dataType: TableDataType.CHAT2DB_ROW_NUMBER },
          { name: 'value', dataType: TableDataType.NUMERIC },
        ],
        extra: {
          resultKey: 'web-1:1:1',
          messages: [{ level: 'INFO', message: 'notice' }],
        },
        executionMetrics: {
          startedAtEpochMs: 100,
          finishedAtEpochMs: 140,
          totalDurationMs: 20,
          executeDurationMs: 12,
          fetchDurationMs: 8,
          fetchedRowCount: 2,
        },
        executionContext: {
          databaseName: 'catalog_after_use',
          schemaName: 'schema_after_use',
        },
      }),
    ],
  });
  assert.equal(state.records.length, 1);
  assert.equal(state.records[0].status, 'success');
  assert.deepEqual(
    state.records[0].outputs.map((output) => output.kind),
    ['message', 'result'],
  );
  const output = state.records[0].outputs[1];
  assert.equal(output.kind === 'result' ? output.rowCount : undefined, 2);
  assert.equal(output.kind === 'result' ? output.resultKey : undefined, 'web-1:1:1');
  assert.equal('dataList' in output, false);
  assert.equal(state.records[0].context.databaseName, 'catalog_after_use');
  assert.equal(state.records[0].context.schemaName, 'schema_after_use');
}

{
  const state = completeWebSqlExecution(webExecution(), {
    executionId: 'web-1',
    sql: 'SET SCHEMA target_schema; SELECT 1',
    context,
    occurredAtEpochMs: 150,
    results: [
      result({
        statementSequence: 1,
        originalSql: 'SET SCHEMA target_schema',
        executionContext: { schemaName: 'PUBLIC' },
      }),
      result({
        statementSequence: 2,
        originalSql: 'SELECT 1',
        executionContext: { schemaName: 'TARGET_SCHEMA' },
      }),
    ],
  });
  assert.deepEqual(
    state.records.map((record) => record.context.schemaName),
    ['PUBLIC', 'TARGET_SCHEMA'],
  );
}

{
  const state = completeWebSqlExecution(webExecution(), {
    executionId: 'web-1',
    sql: 'update t set c = 1',
    context,
    occurredAtEpochMs: 130,
    results: [result({ originalSql: 'update t set c = 1', sqlType: SqlTypeEnum.UPDATE, updateCount: 3 })],
  });
  const output = state.records[0].outputs[0];
  assert.equal(output.kind === 'result' ? output.updateCount : undefined, 3);
  assert.equal(output.kind === 'result' ? output.resultKey : undefined, undefined);
}

{
  assert.equal(isSqlExecutionCancellationError({ name: 'AbortError' }), true);
  assert.equal(isSqlExecutionCancellationError({ name: 'CanceledError' }), true);
  assert.equal(isSqlExecutionCancellationError({ code: 'ERR_CANCELED' }), true);
  const databaseError = { message: 'current transaction is aborted' };
  assert.equal(isSqlExecutionCancellationError(databaseError), false);
  assert.doesNotThrow(() => rethrowNonCancellationSqlExecutionError({ name: 'AbortError' }));
  assert.throws(() => rethrowNonCancellationSqlExecutionError(databaseError), (error) => error === databaseError);
}

{
  const state = failWebSqlExecution(webExecution(), {
    executionId: 'web-1',
    sql: 'select 1',
    context,
    occurredAtEpochMs: 120,
    error: { message: 'connection failed' },
  });
  assert.equal(state.records[0].status, 'failed');
  assert.equal(state.records[0].outputs[0].kind, 'message');
}

{
  const state = failWebSqlExecution(webExecution(), {
    executionId: 'web-1',
    sql: 'select 1',
    context,
    occurredAtEpochMs: 120,
    error: { name: 'AbortError', message: 'The request was aborted' },
  });
  assert.equal(state.records[0].status, 'cancelled');
  assert.equal(state.records[0].outputs.length, 0);
}

{
  let state = createSqlExecutionLogState();
  state = reduceDesktopSqlExecutionEvent(
    state,
    {
      executionId: 'desktop-1',
      eventSequence: 1,
      occurredAtEpochMs: 200,
      eventType: 'statementStarted',
      statementSequence: 1,
      message: { originalSql: 'select * from t' },
    },
    context,
  );
  for (const eventSequence of [2, 3]) {
    state = reduceDesktopSqlExecutionEvent(
      state,
      {
        executionId: 'desktop-1',
        eventSequence,
        occurredAtEpochMs: 205 + eventSequence,
        eventType: 'rows',
        statementSequence: 1,
        resultSequence: 1,
        message: result({ dataList: eventSequence === 2 ? [[], []] : [[]] }),
      },
      context,
    );
  }
  const messageEvent = {
    executionId: 'desktop-1',
    eventSequence: 4,
    occurredAtEpochMs: 210,
    eventType: 'message' as const,
    statementSequence: 1,
    message: { level: 'WARN' as const, message: 'slow' },
  };
  state = reduceDesktopSqlExecutionEvent(state, messageEvent, context);
  state = reduceDesktopSqlExecutionEvent(state, messageEvent, context);
  state = reduceDesktopSqlExecutionEvent(state, { ...messageEvent, eventSequence: 5 }, context);
  state = reduceDesktopSqlExecutionEvent(
    state,
    {
      executionId: 'desktop-1',
      eventSequence: 6,
      occurredAtEpochMs: 220,
      eventType: 'resultFinished',
      statementSequence: 1,
      resultSequence: 1,
      resultKey: 'desktop-1:1:1',
      message: result({
        dataList: [],
        executionContext: { databaseName: 'desktop_catalog', schemaName: 'desktop_schema' },
        extra: {
          messages: [
            { level: 'WARN', message: 'slow' },
            { level: 'INFO', message: 'finished notice' },
          ],
        },
      }),
    },
    context,
  );
  state = reduceDesktopSqlExecutionEvent(
    state,
    {
      executionId: 'desktop-1',
      eventSequence: 7,
      occurredAtEpochMs: 230,
      eventType: 'statementFinished',
      statementSequence: 1,
      message: { duration: 30 },
    },
    context,
  );
  assert.equal(state.records[0].status, 'success');
  assert.equal(state.records[0].outputs.filter((output) => output.kind === 'message').length, 4);
  assert.equal(
    state.records[0].outputs.filter((output) => output.kind === 'message' && output.message === 'slow').length,
    3,
  );
  assert.ok(state.records[0].outputs.some((output) => output.kind === 'message' && output.message === 'finished notice'));
  const output = state.records[0].outputs.find((item) => item.kind === 'result');
  assert.equal(output?.kind === 'result' ? output.rowCount : undefined, 3);
  assert.equal(state.records[0].context.databaseName, 'desktop_catalog');
  assert.equal(state.records[0].context.schemaName, 'desktop_schema');
}

{
  let state = createSqlExecutionLogState();
  state = reduceDesktopSqlExecutionEvent(
    state,
    {
      executionId: 'desktop-context',
      eventType: 'statementStarted',
      statementSequence: 1,
      message: { originalSql: 'SET SCHEMA target_schema' },
    },
    context,
  );
  state = reduceDesktopSqlExecutionEvent(
    state,
    {
      executionId: 'desktop-context',
      eventType: 'resultStarted',
      statementSequence: 1,
      message: result({ executionContext: { schemaName: 'PUBLIC' } }),
    },
    context,
  );
  state = reduceDesktopSqlExecutionEvent(
    state,
    {
      executionId: 'desktop-context',
      eventType: 'statementStarted',
      statementSequence: 2,
      message: { originalSql: 'SELECT 1' },
    },
    context,
  );
  state = reduceDesktopSqlExecutionEvent(
    state,
    {
      executionId: 'desktop-context',
      eventType: 'resultStarted',
      statementSequence: 2,
      message: result({ executionContext: { schemaName: 'TARGET_SCHEMA' } }),
    },
    context,
  );
  assert.deepEqual(
    state.records.map((record) => record.context.schemaName),
    ['PUBLIC', 'TARGET_SCHEMA'],
  );
}

{
  let state = createSqlExecutionLogState();
  for (const statementSequence of [1, 2]) {
    state = reduceDesktopSqlExecutionEvent(
      state,
      {
        executionId: 'desktop-cancel',
        occurredAtEpochMs: 300 + statementSequence,
        eventType: 'statementStarted',
        statementSequence,
        message: { originalSql: `select ${statementSequence}` },
      },
      context,
    );
  }
  state = reduceDesktopSqlExecutionEvent(
    state,
    {
      executionId: 'desktop-cancel',
      occurredAtEpochMs: 310,
      eventType: 'resultFinished',
      statementSequence: 2,
      resultSequence: 2,
      message: result({ success: false, message: 'SQL execution canceled' }),
    },
    context,
  );
  state = reduceDesktopSqlExecutionEvent(
    state,
    {
      executionId: 'desktop-cancel',
      occurredAtEpochMs: 311,
      eventType: 'cancelled',
      message: { message: 'SQL execution canceled' },
    },
    context,
  );
  assert.equal(state.records[0].status, 'running');
  assert.equal(state.records[1].status, 'cancelled');
  assert.equal(state.records[1].outputs.length, 0);
}

{
  const state = completeWebSqlExecution(
    beginWebSqlExecution(createSqlExecutionLogState(), {
      executionId: 'web-context-presence',
      sql: 'select 1; select 2; select 3',
      context: { ...context, schemaName: 'PUBLIC' },
      occurredAtEpochMs: 100,
    }),
    {
      executionId: 'web-context-presence',
      sql: 'select 1; select 2; select 3',
      context: { ...context, schemaName: 'PUBLIC' },
      occurredAtEpochMs: 110,
      results: [
        result({ statementSequence: 1, executionContext: { databaseName: 'next_database' } }),
        result({
          statementSequence: 2,
          executionContext: rawExecutionContext({ databaseName: null, schemaName: null }),
        }),
        result({ statementSequence: 3, executionContext: { schemaName: '' } }),
      ],
    },
  );
  assert.deepEqual(
    state.records.map((record) => record.context.schemaName),
    ['PUBLIC', undefined, undefined],
  );
  assert.deepEqual(
    state.records.map((record) => record.context.databaseName),
    ['next_database', undefined, 'app'],
  );
}

{
  let state = createSqlExecutionLogState();
  state = reduceDesktopSqlExecutionEvent(
    state,
    {
      executionId: 'desktop-context-presence',
      eventType: 'statementStarted',
      statementSequence: 1,
      message: { originalSql: 'select 1' },
    },
    { ...context, schemaName: 'PUBLIC' },
  );
  state = reduceDesktopSqlExecutionEvent(
    state,
    {
      executionId: 'desktop-context-presence',
      eventType: 'resultStarted',
      statementSequence: 1,
      message: result({ executionContext: { databaseName: 'next_database' } }),
    },
    context,
  );
  assert.equal(state.records[0].context.schemaName, 'PUBLIC');
  assert.equal(state.records[0].context.databaseName, 'next_database');
  state = reduceDesktopSqlExecutionEvent(
    state,
    {
      executionId: 'desktop-context-presence',
      eventType: 'resultStarted',
      statementSequence: 1,
      message: result({ executionContext: rawExecutionContext({ databaseName: null, schemaName: null }) }),
    },
    context,
  );
  assert.equal(state.records[0].context.schemaName, undefined);
  assert.equal(state.records[0].context.databaseName, undefined);
  state = reduceDesktopSqlExecutionEvent(
    state,
    {
      executionId: 'desktop-context-presence',
      eventType: 'resultStarted',
      statementSequence: 1,
      message: result({ executionContext: { schemaName: 'TARGET_SCHEMA' } }),
    },
    context,
  );
  state = reduceDesktopSqlExecutionEvent(
    state,
    {
      executionId: 'desktop-context-presence',
      eventType: 'resultStarted',
      statementSequence: 1,
      message: result({ executionContext: { schemaName: '' } }),
    },
    context,
  );
  assert.equal(state.records[0].context.schemaName, undefined);
}

{
  let state = createSqlExecutionLogState();
  state = reduceDesktopSqlExecutionEvent(
    state,
    {
      executionId: 'desktop-message-id',
      eventSequence: 1,
      eventType: 'statementStarted',
      statementSequence: 1,
      message: { originalSql: 'select 1' },
    },
    context,
  );
  state = reduceDesktopSqlExecutionEvent(
    state,
    {
      executionId: 'desktop-message-id',
      eventSequence: 6,
      eventType: 'resultFinished',
      statementSequence: 1,
      resultSequence: 1,
      message: result({ extra: { messages: [{ level: 'ERROR', message: 'result-attached' }] } }),
    },
    context,
  );
  state = reduceDesktopSqlExecutionEvent(
    state,
    {
      executionId: 'desktop-message-id',
      eventSequence: 6000,
      eventType: 'message',
      statementSequence: 1,
      message: { level: 'ERROR', message: 'direct-event' },
    },
    context,
  );
  const messages = state.records[0].outputs.filter((output) => output.kind === 'message');
  assert.deepEqual(
    messages.map((output) => output.message),
    ['result-attached', 'direct-event'],
  );
  assert.equal(new Set(messages.map((output) => output.id)).size, 2);
}

{
  let state = reduceDesktopSqlExecutionEvent(
    createSqlExecutionLogState(),
    {
      executionId: 'desktop-bounded-replay',
      eventSequence: 1,
      eventType: 'statementStarted',
      statementSequence: 1,
      message: { originalSql: 'select 1' },
    },
    context,
  );
  const resultEvent = {
    executionId: 'desktop-bounded-replay',
    eventSequence: 2,
    eventType: 'resultFinished' as const,
    statementSequence: 1,
    message: result({
      success: false,
      message: 'failed result',
      extra: {
        messages: [
          { level: 'ERROR', message: 'pinned desktop error' },
          ...Array.from({ length: 220 }, (_, index) => ({
            level: 'INFO' as const,
            message: `desktop-notice-${index}`,
          })),
        ],
      },
    }),
  };
  state = reduceDesktopSqlExecutionEvent(state, resultEvent, context);
  const retainedOutputs = state.records[0].outputs;
  state = reduceDesktopSqlExecutionEvent(state, resultEvent, context);
  const outputs = state.records[0].outputs;
  assert.equal(state.records[0].status, 'failed');
  assert.equal(outputs.length, 200);
  assert.deepEqual(outputs, retainedOutputs);
  assert.equal(outputs.filter((output) => output.kind === 'result').length, 1);
  assert.ok(outputs.some((output) => output.kind === 'message' && output.message === 'pinned desktop error'));
  assert.ok(outputs.some((output) => output.kind === 'result' && !output.success));
  assert.ok(outputs.some((output) => output.kind === 'message' && output.message === 'desktop-notice-219'));
  assert.ok(!outputs.some((output) => output.kind === 'message' && output.message === 'desktop-notice-0'));
}

{
  const state = completeWebSqlExecution(webExecution(), {
    executionId: 'web-1',
    sql: 'select 1',
    context,
    occurredAtEpochMs: 140,
    results: [
      result({
        success: false,
        message: 'failed result',
        extra: {
          messages: [
            { level: 'ERROR', message: 'pinned error' },
            ...Array.from({ length: 220 }, (_, index) => ({
              level: 'INFO' as const,
              message: `notice-${index}`,
            })),
          ],
        },
      }),
    ],
  });
  const outputs = state.records[0].outputs;
  assert.equal(state.records[0].status, 'failed');
  assert.equal(outputs.length, 200);
  assert.ok(outputs.some((output) => output.kind === 'message' && output.message === 'pinned error'));
  assert.ok(outputs.some((output) => output.kind === 'result' && !output.success));
  assert.ok(outputs.some((output) => output.kind === 'message' && output.message === 'notice-219'));
  assert.ok(!outputs.some((output) => output.kind === 'message' && output.message === 'notice-0'));
}

{
  const longMessage = `prefix:${'\u{1F642}'.repeat(9_000)}:suffix`;
  const state = failWebSqlExecution(webExecution(), {
    executionId: 'web-1',
    sql: 'select 1',
    context,
    occurredAtEpochMs: 120,
    error: { message: longMessage },
  });
  const output = state.records[0].outputs[0];
  assert.equal(output.kind, 'message');
  if (output.kind === 'message') {
    assertTruncatedMessage(output.message);
  }
}

{
  const longMessage = `prefix:${'\u{1F642}'.repeat(9_000)}:suffix`;
  const state = completeWebSqlExecution(webExecution(), {
    executionId: 'web-1',
    sql: 'select 1',
    context,
    occurredAtEpochMs: 140,
    results: [result({ success: false, message: longMessage })],
  });
  const output = state.records[0].outputs.find((item) => item.kind === 'result');
  assert.equal(output?.kind, 'result');
  if (output?.kind === 'result') {
    assertTruncatedMessage(output.message);
  }
}

{
  let state = beginWebSqlExecution(createSqlExecutionLogState(), {
    executionId: 'still-running',
    sql: 'select sleep(10)',
    context,
  });
  for (let index = 0; index < 205; index += 1) {
    const executionId = `history-${index}`;
    state = beginWebSqlExecution(state, { executionId, sql: 'select 1', context, occurredAtEpochMs: index });
    state = completeWebSqlExecution(state, {
      executionId,
      sql: 'select 1',
      context,
      occurredAtEpochMs: index + 1,
      results: [result()],
    });
  }
  assert.ok(state.records.length <= 200);
  assert.equal(state.records[0].executionId, 'still-running');
  assert.equal(state.records.at(-1)?.executionId, 'history-204');
}

console.log('SQL execution log tests passed');
