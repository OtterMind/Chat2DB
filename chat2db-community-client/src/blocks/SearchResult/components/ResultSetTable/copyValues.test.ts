import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildResultCopyOperations,
  completeResultCopyValues,
  createCopyAbort,
  getResultCopyFields,
  readCompleteCopyValue,
  resultCopySelectionToRows,
  snapshotResultCopySelection,
  type ReadCopyChunk,
  type ResultCopyRecord,
  type ResultCopySelection,
} from './copyValues';
import type { IResultCell, ITableHeaderItem } from '@/typings/database';
import zh from '@/i18n/zh-CN/common';
import en from '@/i18n/en-US/common';
import es from '@/i18n/es-ES/common';
import ja from '@/i18n/ja-JP/common';
import ko from '@/i18n/ko-KR/common';

const largeMeta = (id = 'json-1'): IResultCell => ({
  value: '{"preview":',
  largeValue: true,
  truncated: true,
  largeValueId: id,
  valueType: 'JSON',
});

function reader(bytes: Uint8Array, chunkSize = 7) {
  const offsets: number[] = [];
  const read: ReadCopyChunk = async ({ offset = 0 }) => {
    offsets.push(offset);
    const nextOffset = Math.min(offset + chunkSize, bytes.length);
    return {
      value: Buffer.from(bytes.subarray(offset, nextOffset)).toString('base64'),
      offset,
      nextOffset,
      eof: nextOffset === bytes.length,
      displayMode: 'JSON',
    };
  };
  return { read, offsets };
}

function selected(record: ResultCopyRecord, fields = ['2']): ResultCopySelection {
  return [
    fields.map((field, index) => ({
      row: 1,
      col: index + 1,
      field,
      value: record[field],
      originData: record,
    })),
  ];
}

test('loads the whole JSON across UTF-8 boundaries into a detached selection', async () => {
  const json = JSON.stringify({ text: '中文😀\n\t quote\'" \\ '.repeat(100), tail: 'END_JSON' });
  const record: ResultCopyRecord = {
    CHAT2DB_ROW_NUMBER: 1,
    '1': '1',
    '2': '{"preview":',
    __CHAT2DB_CELL_META__: [{ value: '1' }, { value: '1' }, largeMeta()],
  };
  const copy = snapshotResultCopySelection(selected(record));
  const original = structuredClone(record);
  const { read, offsets } = reader(new TextEncoder().encode(json));
  await completeResultCopyValues(copy, [['2']], read, createCopyAbort());
  assert.deepEqual(record, original, 'copying never writes to the editable table record');
  assert.equal(copy[0][0].value, json);
  assert.deepEqual(JSON.parse(String(copy[0][0].value)), JSON.parse(json));
  assert.ok(offsets.length > 1);
  const operation = buildResultCopyOperations(copy, 'IN_VALUES')[0];
  assert.equal(operation.selectedCell?.largeValue, false);
  assert.equal(operation.selectedCell?.truncated, false);
  assert.equal(operation.selectedCell?.largeValueId, undefined);
});

test('copy requests include only used fields and UPDATE locator fields', () => {
  const headers = [
    { name: '#' },
    { name: 'id', primaryKey: true },
    { name: 'json' },
    { name: 'other' },
  ] as ITableHeaderItem[];
  const row = selected({ '2': 'preview' })[0];
  for (const format of ['cells', 'CREATE', 'WHERE', 'IN_VALUES', 'markdown'] as const) {
    assert.deepEqual(getResultCopyFields(row, format, ['1', '2', '3'], headers), ['2']);
  }
  assert.deepEqual(getResultCopyFields(row, 'UPDATE_COPY', ['1', '2', '3'], headers), ['2', '1']);
  assert.deepEqual(getResultCopyFields(row, 'tsv', ['3', '2'], headers), ['3', '2']);
  assert.deepEqual(getResultCopyFields(row, 'tsvWithHeaders', ['3', '2'], headers), ['3', '2']);
  assert.deepEqual(getResultCopyFields(row, 'headers', ['3', '2'], headers), []);
  assert.deepEqual(
    getResultCopyFields(
      row,
      'UPDATE_COPY',
      [],
      headers.map((header) => ({ ...header, primaryKey: false })),
    ),
    ['2', '1', '3'],
  );
});

test('the snapshot preserves the chosen row and unsaved cell values after table changes', async () => {
  const record: ResultCopyRecord = {
    CHAT2DB_ROW_NUMBER: 'row-1',
    '1': 'unsaved value',
    '2': 'preview',
    __CHAT2DB_CELL_META__: [],
  };
  const selection = selected(record, ['1', '2']);
  const copy = snapshotResultCopySelection(selection);
  record['1'] = 'later edit';
  selection[0].reverse();
  await completeResultCopyValues(
    copy,
    [['1', '2']],
    async () => {
      throw new Error('unexpected read');
    },
    createCopyAbort(),
  );
  assert.deepEqual(resultCopySelectionToRows(copy), [['unsaved value', 'preview']]);
  assert.equal(buildResultCopyOperations(copy, 'CREATE')[0].rowId, 'row-1');
});

test('reuses a full value only within the same copy and avoids reading unrelated fields', async () => {
  const record: ResultCopyRecord = {
    '1': 'a',
    '2': 'preview',
    '3': 'unavailable',
    __CHAT2DB_CELL_META__: [{ value: '' }, { value: 'a' }, largeMeta(), { value: 'unavailable', largeValue: true }],
  };
  const copy = snapshotResultCopySelection([...selected(record), ...selected({ ...record }, ['2'])]);
  const { read, offsets } = reader(new TextEncoder().encode('complete'), 100);
  await completeResultCopyValues(copy, [['2'], ['2']], read, createCopyAbort());
  assert.deepEqual(offsets, [0]);
  assert.equal(copy[1][0].value, 'complete');
  await completeResultCopyValues(
    snapshotResultCopySelection(selected(record)),
    [['2']],
    read,
    createCopyAbort(),
  );
  assert.deepEqual(offsets, [0, 0], 'another copy reads current data again');
});

test('missing locators, read failures, stalled chunks and cancellation reject completion', async () => {
  const abort = createCopyAbort();
  await assert.rejects(
    readCompleteCopyValue(
      { value: 'preview', largeValue: true },
      async () => {
        throw new Error('unexpected read');
      },
      abort,
    ),
    /fullValueUnsupported/,
  );
  await assert.rejects(
    readCompleteCopyValue(
      largeMeta(),
      async () => {
        throw new Error('expired token');
      },
      abort,
    ),
    /expired token/,
  );
  await assert.rejects(
    readCompleteCopyValue(largeMeta(), async () => ({ value: '', offset: 0, nextOffset: 0, eof: false }), abort),
    /readFailed/,
  );
  await assert.rejects(
    readCompleteCopyValue(largeMeta(), async () => ({ value: 'eA==', offset: 4, nextOffset: 5, eof: true }), abort),
    /readFailed/,
  );
  const cancelled = createCopyAbort();
  const { read } = reader(new TextEncoder().encode('value'));
  await assert.rejects(
    readCompleteCopyValue(
      largeMeta(),
      async (params, requestSignal) => {
        cancelled.abort();
        return read(params, requestSignal);
      },
      cancelled,
    ),
    { name: 'AbortError' },
  );
});

test('complete binary values retain every byte as a hex literal', async () => {
  const bytes = Uint8Array.from({ length: 256 }, (_, index) => index);
  const { read } = reader(bytes, 17);
  const full = await readCompleteCopyValue(
    { ...largeMeta('binary-1'), valueType: 'BINARY' },
    async (params, signal) => ({
      ...(await read(params, signal)),
      displayMode: 'BINARY',
    }),
    createCopyAbort(),
  );
  assert.equal(full.value, `0x${Buffer.from(bytes).toString('hex')
.toUpperCase()}`);
  assert.equal(full.sizeBytes, bytes.length);
});

test('full copies exceed the editor display limit without truncation', async () => {
  const bytes = new TextEncoder().encode('x'.repeat(11 * 1024 * 1024) + 'END_LARGE_TEXT');
  const { read } = reader(bytes, 256 * 1024);
  const full = await readCompleteCopyValue(largeMeta(), read, createCopyAbort());
  assert.equal(full.value.length, bytes.length);
  assert.ok(full.value.endsWith('END_LARGE_TEXT'));
});

test('SQL IN validates one selected column and grid copies preserve sparse selections', () => {
  assert.throws(
    () => buildResultCopyOperations(selected({ '1': 'a', '2': 'b' }, ['1', '2']), 'IN_VALUES'),
    /singleColumnRequired/,
  );
  assert.deepEqual(
    resultCopySelectionToRows([
      [
        { row: 2, col: 3, value: null },
        { row: 2, col: 5, value: '' },
      ],
      [{ row: 3, col: 3, value: 'a\n\tb' }],
    ]),
    [
      ['', '', "''"],
      ['a\n\tb', '', ''],
    ],
  );
});

test('copy progress and incomplete-value messages are localized', () => {
  const locales = [zh, en, es, ja, ko];
  for (const locale of locales) {
    assert.ok(locale['common.largeCellValue.status.copyLoading']);
    assert.ok(locale['common.sqlInValues.largeValueRejected']);
  }
  assert.equal(
    new Set(locales.map((locale) => locale['common.largeCellValue.status.copyLoading'])).size,
    locales.length,
  );
});
