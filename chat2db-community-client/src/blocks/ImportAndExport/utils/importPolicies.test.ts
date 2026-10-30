import assert from 'node:assert/strict';
import {
  DEFAULT_IMPORT_POLICIES,
  resolveImportPolicies,
  resolveImportPoliciesForTarget,
  supportsDuplicateIgnore,
} from './importPolicies';

function defaultsAreInsertAndAbort() {
  assert.deepEqual(resolveImportPolicies(null), DEFAULT_IMPORT_POLICIES);
  assert.deepEqual(resolveImportPolicies(undefined), { duplicatePolicy: 'INSERT', failurePolicy: 'ABORT' });
}

function restoresStoredPolicies() {
  assert.deepEqual(
    resolveImportPolicies({ duplicatePolicy: 'IGNORE', failurePolicy: 'SKIP_ERROR_ROWS' }),
    { duplicatePolicy: 'IGNORE', failurePolicy: 'SKIP_ERROR_ROWS' },
  );
  assert.deepEqual(resolveImportPolicies({ failurePolicy: 'SKIP_ERROR_ROWS' }), {
    duplicatePolicy: 'INSERT',
    failurePolicy: 'SKIP_ERROR_ROWS',
  });
}

function gatesIgnoreByDialect() {
  assert.equal(supportsDuplicateIgnore('MYSQL'), true);
  assert.equal(supportsDuplicateIgnore('mariadb'), true);
  assert.equal(supportsDuplicateIgnore('POSTGRESQL'), true);
  assert.equal(supportsDuplicateIgnore('ORACLE'), false);
  assert.equal(supportsDuplicateIgnore('SQLSERVER'), false);
  assert.equal(supportsDuplicateIgnore(null), true, 'an unresolved type lets the backend decide');
}

function dropsRestoredIgnoreOnUnsupportedTargets() {
  assert.deepEqual(
    resolveImportPoliciesForTarget({ duplicatePolicy: 'IGNORE', failurePolicy: 'ABORT' }, 'ORACLE'),
    { duplicatePolicy: 'INSERT', failurePolicy: 'ABORT' },
  );
  assert.deepEqual(
    resolveImportPoliciesForTarget({ duplicatePolicy: 'IGNORE', failurePolicy: 'ABORT' }, 'MYSQL'),
    { duplicatePolicy: 'IGNORE', failurePolicy: 'ABORT' },
  );
}

defaultsAreInsertAndAbort();
restoresStoredPolicies();
gatesIgnoreByDialect();
dropsRestoredIgnoreOnUnsupportedTargets();
console.log('import policy tests passed');
