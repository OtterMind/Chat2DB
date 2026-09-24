import assert from 'node:assert/strict';
import {
  datasourceStateColor,
  datasourceStateHintKey,
  datasourceStateLabelKey,
  isDatasourceSelectable,
} from './sqlxDatasourceStates';

assert.equal(datasourceStateLabelKey('imported'), 'setting.sqlx.datasourceState.imported');
assert.equal(datasourceStateLabelKey('ready'), 'setting.sqlx.datasourceState.ready');
assert.equal(datasourceStateLabelKey('unsupported'), 'setting.sqlx.datasourceState.unsupported');
assert.equal(datasourceStateLabelKey('incomplete'), 'setting.sqlx.datasourceState.incomplete');
// An unknown state must never be presented as importable.
assert.equal(datasourceStateLabelKey(undefined), 'setting.sqlx.datasourceState.incomplete');

assert.equal(datasourceStateColor('imported'), 'success');
assert.equal(datasourceStateColor('ready'), 'default');
assert.equal(datasourceStateColor('unsupported'), 'warning');
assert.equal(datasourceStateColor('incomplete'), 'warning');

assert.equal(isDatasourceSelectable('ready'), true);
// An imported row has nothing left to do, so it must not be offered for selection again.
assert.equal(isDatasourceSelectable('imported'), false);
assert.equal(isDatasourceSelectable('unsupported'), false);
assert.equal(isDatasourceSelectable('incomplete'), false);
// Until the state is known the checkbox stays disabled rather than offering a row SQLX may already hold.
assert.equal(isDatasourceSelectable(undefined), false);

assert.equal(datasourceStateHintKey({ id: 1, state: 'imported' }), 'setting.sqlx.datasourceState.importedHint');
assert.equal(
  datasourceStateHintKey({ id: 1, state: 'unsupported', reason: 'engine_not_supported' }),
  'setting.sqlx.datasourceState.unsupportedHint',
);
// An incomplete row explains itself with the skip reason the import report uses.
assert.equal(datasourceStateHintKey({ id: 1, state: 'incomplete', reason: 'missing_host' }), null);
assert.equal(datasourceStateHintKey({ id: 1, state: 'ready' }), null);
assert.equal(datasourceStateHintKey(undefined), null);

console.log('SQLX datasource state tests passed');
