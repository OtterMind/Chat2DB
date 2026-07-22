import assert from 'node:assert/strict';
import {
  getInsertValueAutoFill,
  materializeInsertValueAutoFillHints,
  rematerializeInsertValueHints,
} from './sqlInsertValueDefaults';

const emptyRange = { startLineNumber: 1, startColumn: 61, endLineNumber: 1, endColumn: 61 };
const hints = [{
  type: 'INSERT_VALUE',
  valueRange: emptyRange,
  items: [
    { rowIndex: 0, columnIndex: 2, fieldName: 'name', fieldType: 'VARCHAR', defaultValue: "''", active: false },
    { rowIndex: 0, columnIndex: 0, fieldName: 'id', fieldType: 'BIGINT', defaultValue: '0', active: true },
    { rowIndex: 0, columnIndex: 1, fieldName: 'enabled', fieldType: 'BOOLEAN', defaultValue: 'FALSE', active: false },
  ],
}];
const insertWithoutClosingParenthesis = 'INSERT INTO demo (id, enabled, name) VALUES (';
const insertWithEditorClosingParenthesis = 'INSERT INTO demo (id, enabled, name) VALUES ()';

assert.deepEqual(
  getInsertValueAutoFill(insertWithoutClosingParenthesis, insertWithoutClosingParenthesis.length, hints),
  { text: "0, FALSE, '')", firstValueLength: 1, valuesTextLength: 12 },
  'VALUES opening parenthesis expands defaults in column order and closes the row',
);

const autoFill = getInsertValueAutoFill(
  insertWithoutClosingParenthesis,
  insertWithoutClosingParenthesis.length,
  hints,
)!;
const materializedHints = materializeInsertValueAutoFillHints(
  insertWithoutClosingParenthesis + autoFill.text,
  insertWithoutClosingParenthesis.length,
  hints,
);
const materializedItems = [...(materializedHints[0].items || [])]
  .sort((left, right) => left.columnIndex - right.columnIndex);
assert.deepEqual(
  materializedItems.map((item) => [item.range?.startColumn, item.range?.endColumn]),
  [[46, 47], [49, 54], [56, 58]],
  'auto-filled labels receive their final value ranges immediately',
);

const formattedSql = `INSERT INTO demo (id, enabled, name)
VALUES
  (
    0,
    false,
    ''
  );`;
const formattedHints = rematerializeInsertValueHints(formattedSql, materializedHints);
const formattedItems = [...(formattedHints[0]?.items || [])]
  .sort((left, right) => left.columnIndex - right.columnIndex);
assert.deepEqual(
  formattedItems.map((item) => item.range?.endLineNumber),
  [4, 5, 6],
  'formatting remaps each label to its value on the new line',
);
assert.deepEqual(
  [...(rematerializeInsertValueHints(formattedSql.replace('false', 'true'), materializedHints)[0]?.items || [])]
    .sort((left, right) => left.columnIndex - right.columnIndex)
    .map((item) => item.range?.endLineNumber),
  [4, 5, 6],
  'changing a value preserves labels by column position',
);
const emptyMiddleValueHints = rematerializeInsertValueHints(formattedSql.replace('false', ''), materializedHints);
const emptyMiddleValueItems = [...(emptyMiddleValueHints[0]?.items || [])]
  .sort((left, right) => left.columnIndex - right.columnIndex);
assert.equal(emptyMiddleValueItems[1].range, undefined, 'an empty value slot temporarily hides only its own label');
assert.equal(emptyMiddleValueItems[2].range?.endLineNumber, 6, 'later value labels keep their positional mapping');
assert.equal(
  rematerializeInsertValueHints(insertWithoutClosingParenthesis, materializedHints).length,
  1,
  'returning to an empty VALUES row preserves the handled-row state and prevents a second auto-fill',
);

assert.deepEqual(
  getInsertValueAutoFill(
    insertWithEditorClosingParenthesis,
    insertWithEditorClosingParenthesis.lastIndexOf(')'),
    hints,
  ),
  { text: "0, FALSE, ''", firstValueLength: 1, valuesTextLength: 12 },
  'an editor-provided closing parenthesis is not duplicated',
);

assert.equal(
  getInsertValueAutoFill('INSERT INTO demo (id, enabled, name) VALUES (1', 51, hints),
  null,
  'an existing value is never overwritten',
);

assert.equal(
  getInsertValueAutoFill('SELECT (', 8, hints),
  null,
  'non-INSERT parentheses never expand',
);

assert.equal(
  getInsertValueAutoFill('INSERT INTO demo (id) VALUES (', 35, [{
    type: 'INSERT_VALUE',
    valueRange: emptyRange,
    items: [{ rowIndex: 0, columnIndex: 0, fieldName: 'id', active: true }],
  }]),
  null,
  'missing backend defaults fail closed',
);

console.log('sqlInsertValueDefaults tests passed');
