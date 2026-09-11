import assert from 'node:assert/strict';
import { hasSelectedImportFile } from './selection';

assert.equal(hasSelectedImportFile([]), false);
assert.equal(hasSelectedImportFile([{ filePath: '/tmp/users.sql' }]), true);
assert.equal(hasSelectedImportFile([{ file: {} as File }]), true);
