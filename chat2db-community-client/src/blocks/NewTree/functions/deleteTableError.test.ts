import { getDeleteTableErrorMessage } from './deleteTableError';

function assertEqual(actual: string, expected: string, message: string) {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

assertEqual(
  getDeleteTableErrorMessage({ errorMessage: 'Table is referenced by a foreign key', message: 'generic' }, 'Failed'),
  'Table is referenced by a foreign key',
  'server errorMessage takes precedence',
);
assertEqual(getDeleteTableErrorMessage(new Error('Network unavailable'), 'Failed'), 'Network unavailable', 'Error.message is used');
assertEqual(getDeleteTableErrorMessage('Request cancelled', 'Failed'), 'Request cancelled', 'string errors are used');
assertEqual(getDeleteTableErrorMessage({ errorCode: 'SYSTEM_ERROR' }, 'Failed'), 'Failed', 'fallback is used');

console.log('delete table error message tests passed');
