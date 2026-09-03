import assert from 'node:assert/strict';
import type { IInnodbStatusResponse } from '@/service/sql';
import {
  applyInnodbStatusFailure,
  applyInnodbStatusSuccess,
  beginInnodbStatusRefresh,
  getInnodbStatusCopyText,
  initialInnodbStatusViewState,
} from './state';

const successfulResult: IInnodbStatusResponse = {
  rawText: "UPDATE orders SET status='paid', password=<redacted> WHERE id=1",
  capturedAt: '2026-08-31T10:00:00Z',
  sections: [],
  latestDeadlock: {
    found: false,
    message: 'The server did not provide a latest deadlock.',
    transactions: [],
  },
  messages: [],
};

const loaded = applyInnodbStatusSuccess(initialInnodbStatusViewState, successfulResult, 'fallback');
assert.equal(loaded.result, successfulResult, 'successful refresh stores the latest structured result');
assert.equal(loaded.lastSuccessAt, '2026-08-31T10:00:00Z', 'successful refresh records the server timestamp');
assert.equal(loaded.error, null, 'successful refresh clears previous errors');

const refreshing = beginInnodbStatusRefresh(loaded);
assert.equal(refreshing.loading, true, 'refresh starts without clearing the visible result');
assert.equal(refreshing.result, successfulResult, 'refresh keeps the previous successful result visible');

const failed = applyInnodbStatusFailure(refreshing, { errorMessage: 'PROCESS privilege required' });
assert.equal(failed.loading, false, 'failed refresh clears loading');
assert.equal(failed.result, successfulResult, 'failed refresh retains the previous successful result');
assert.equal(failed.lastSuccessAt, '2026-08-31T10:00:00Z', 'failed refresh retains the previous success timestamp');
assert.equal(failed.error, 'PROCESS privilege required', 'failed refresh exposes diagnostic messaging');

assert.equal(
  getInnodbStatusCopyText(failed.result),
  "UPDATE orders SET status='paid', password=<redacted> WHERE id=1",
  'copy uses the redacted raw monitor text supplied by the diagnostics API',
);
assert.equal(
  getInnodbStatusCopyText(failed.result).includes('secret-value'),
  false,
  'copy does not reintroduce redacted secrets',
);
assert.equal(getInnodbStatusCopyText(null), '', 'copy is empty when no successful result exists');

console.log('InnoDB status panel state tests passed.');
