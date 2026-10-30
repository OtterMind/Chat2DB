import assert from 'node:assert/strict';
import { ImportExportTaskStatus as Status, ImportExportTaskType as Type } from '@/constants/importExport';
import type { ImportExportTaskDetails } from '@/typings/importExport';
import { groupTaskAttempts, isLatestTaskAttempt } from './taskAttempts';

const task = (
  id: number, spec: object = {}, patch: Partial<ImportExportTaskDetails> = {},
): ImportExportTaskDetails => ({
  id, name: 'Import rows', type: Type.DATA_FILE_IMPORT, status: Status.CANCELLED, progress: 50,
  createdAt: id, specJson: JSON.stringify(spec), checkpointJson: '{"processedRows":100}', ...patch,
});
const original = task(1);
const second = task(2, { resumeRootTaskId: 1, resumeParentTaskId: 1, resumeAttempt: 1,
  resumeRootTaskName: 'Import rows' });
const third = task(3, { resumeRootTaskId: 1, resumeParentTaskId: 2, resumeAttempt: 2,
  resumeRootTaskName: 'Import rows' }, { status: Status.SUCCESS });
const group = groupTaskAttempts([third, original, second]);
assert.equal(group.length, 1);
assert.equal(group[0].id, 1);
assert.equal(group[0].latest.id, 3);
assert.equal(group[0].latest.status, Status.SUCCESS);
assert.equal(group[0].resumeCount, 2);
assert.deepEqual(group[0].attempts.map(t => t.id), [3, 2, 1]);
assert.equal(original.status, Status.CANCELLED, 'grouping must not rewrite the original outcome');
assert.equal(groupTaskAttempts([third])[0].id, 1, 'pagination keeps the same group key before the root loads');
assert.equal(groupTaskAttempts([third])[0].resumeCount, 2);
assert.equal(isLatestTaskAttempt(original, [third]), false, 'older logs cannot resume again');
assert.equal(isLatestTaskAttempt(second, [third, second]), false);
assert.equal(isLatestTaskAttempt(third, [third, second]), true);
assert.equal(isLatestTaskAttempt(original, []), true, 'a standalone unfinished task can still resume');
assert.equal(groupTaskAttempts([task(4), third, original, second]).length, 2,
  'independent imports with the same name must remain distinct');

const target = { dataSourceId: 9, databaseName: 'test', tableName: 'rows' };
const legacySpec = { sourceFile: '/tmp/unique-upload.csv', target };
const legacyRoot = task(11, legacySpec);
const legacyResume = task(12, { ...legacySpec, resumeFromRow: 100 }, { name: 'Import rows (resumed)' });
assert.equal(groupTaskAttempts([legacyResume, legacyRoot]).length, 1);
assert.equal(groupTaskAttempts([legacyResume, legacyRoot])[0].name, 'Import rows');
assert.equal(groupTaskAttempts([legacyResume, task(11, { ...legacySpec,
  target: { tableName: 'rows', databaseName: 'test', dataSourceId: 9 } })]).length, 1,
'target key ordering must not break legacy lineage');
assert.equal(groupTaskAttempts([legacyResume, legacyRoot, task(10, legacySpec)]).length, 3,
  'ambiguous legacy predecessors must never be guessed');
assert.equal(groupTaskAttempts([legacyResume, task(11, { ...legacySpec, sourceFile: '/tmp/other.csv' })]).length, 2);
assert.equal(groupTaskAttempts([legacyResume, task(11, { ...legacySpec, target: { ...target, tableName: 'other' } })])
  .length, 2);
assert.equal(groupTaskAttempts([task(11, legacySpec, { specJson: '{broken' }), legacyResume]).length, 2);
assert.equal(groupTaskAttempts([task(20, { resumeRootTaskId: 21 }), task(21, { resumeRootTaskId: 20 })]).length, 2);
console.log('Task attempt grouping, legacy matching, pagination and audit preservation passed');
