import assert from 'node:assert/strict';
import test from 'node:test';
import { ImportExportTaskStatus, ImportExportTaskType } from '@/constants/importExport';
import {
  buildTaskArtifactUrl,
  buildTaskErrorArtifactUrl,
  buildTaskProgressSummary,
  canDownloadErrorArtifact,
  canResumeTask,
  formatEta,
  formatRowsPerSecond,
  isImportTaskType,
  parseProcessedRows,
} from './taskArtifacts';

test('offers resume only for a stopped import with spec and checkpoint', () => {
  const resumable = {
    type: ImportExportTaskType.DATA_FILE_IMPORT,
    status: ImportExportTaskStatus.CANCELLED,
    specJson: '{"taskType":"DATA_FILE_IMPORT"}',
    checkpointJson: '{"processedRows":33500}',
  };
  assert.equal(canResumeTask(resumable), true);
  assert.equal(canResumeTask({ ...resumable, status: ImportExportTaskStatus.FAILED }), true);
  assert.equal(canResumeTask({ ...resumable, status: ImportExportTaskStatus.RUNNING }), false);
  assert.equal(canResumeTask({ ...resumable, status: ImportExportTaskStatus.SUCCESS }), false);
  assert.equal(canResumeTask({ ...resumable, checkpointJson: null }), false);
  assert.equal(canResumeTask({ ...resumable, specJson: '' }), false);
  assert.equal(canResumeTask({ ...resumable, type: ImportExportTaskType.TABLE_DATA_EXPORT }), false);
});

test('exposes the error file only when the task published one', () => {
  assert.equal(canDownloadErrorArtifact({ errorArtifactId: '/tmp/tasks/7/error-rows.csv' }), true);
  assert.equal(canDownloadErrorArtifact({ errorArtifactId: null }), false);
  assert.equal(canDownloadErrorArtifact({}), false);
});

test('reads the structured row counter out of the checkpoint', () => {
  assert.equal(parseProcessedRows('{"processedRows":33500,"targetTable":"t"}'), 33500);
  assert.equal(parseProcessedRows('{"processedRows":0}'), 0);
  assert.equal(parseProcessedRows('{"targetTable":"t"}'), null);
  assert.equal(parseProcessedRows('not json'), null);
  assert.equal(parseProcessedRows(''), null);
  assert.equal(parseProcessedRows(undefined), null);
});

test('derives the rate from the checkpoint and the persisted timestamps', () => {
  const started = Date.parse('2026-10-09T20:57:47Z');
  assert.deepEqual(
    buildTaskProgressSummary({
      checkpointJson: '{"processedRows":33500}',
      startedAt: started,
      status: ImportExportTaskStatus.RUNNING,
      now: started + 10_000,
    }),
    { processedRows: 33500, totalRows: null, rowsPerSecond: 3350, etaSeconds: null },
  );
  // A finished task keeps the rate it ended with, even when the clock moved on.
  assert.deepEqual(
    buildTaskProgressSummary({
      checkpointJson: '{"processedRows":1000}',
      startedAt: started,
      finishedAt: started + 2_000,
      status: ImportExportTaskStatus.CANCELLED,
      now: started + 60_000,
    }),
    { processedRows: 1000, totalRows: null, rowsPerSecond: 500, etaSeconds: null },
  );
  assert.equal(
    buildTaskProgressSummary({ checkpointJson: null, startedAt: started, status: 'RUNNING', now: started + 1000 }),
    null,
  );
  assert.deepEqual(
    buildTaskProgressSummary({ checkpointJson: '{"processedRows":10}', startedAt: null, now: started }),
    { processedRows: 10, totalRows: null, rowsPerSecond: null, etaSeconds: null },
  );
});

test('uses the structured backend counters instead of parsing a checkpoint', () => {
  const started = Date.parse('2026-10-09T20:57:47Z');
  assert.deepEqual(
    buildTaskProgressSummary({
      processedRows: 500,
      totalRows: 1000,
      rowsPerSecond: 50,
      etaSeconds: 10,
      checkpointJson: '{"processedRows":1}',
      startedAt: started,
      status: ImportExportTaskStatus.RUNNING,
      now: started + 60_000,
    }),
    { processedRows: 500, totalRows: 1000, rowsPerSecond: 50, etaSeconds: 10 },
  );
});

test('formats a compact ETA', () => {
  assert.equal(formatEta(42), '42s');
  assert.equal(formatEta(185), '3m 5s');
  assert.equal(formatEta(7800), '2h 10m');
});

test('keeps the rate readable and builds the artifact urls', () => {
  assert.equal(formatRowsPerSecond(3.21), '3.2');
  assert.equal(formatRowsPerSecond(120.4), '120');
  assert.equal(buildTaskArtifactUrl(7), '/api/tasks/artifact?taskId=7');
  assert.equal(buildTaskErrorArtifactUrl(7), '/api/tasks/error-artifact?taskId=7');
  assert.equal(isImportTaskType(ImportExportTaskType.SQL_FILE_IMPORT), true);
  assert.equal(isImportTaskType(ImportExportTaskType.SQL_EXPORT), false);
  assert.equal(isImportTaskType(undefined), false);
});
