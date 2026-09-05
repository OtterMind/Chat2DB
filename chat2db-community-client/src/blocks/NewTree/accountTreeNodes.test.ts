import assert from 'node:assert/strict';
import { createDatabaseAccountTreeNodeExtraParams } from './accountTreeNodes';

const extraParams = {
  dataSourceId: 1,
  databaseType: 'MYSQL',
  databaseName: 'app',
};

const account = {
  user: 'sec002_ssl',
  host: '%',
  displayName: 'sec002_ssl@%',
  authenticationPlugin: 'caching_sha2_password',
  locked: false,
  tlsRequirement: 'SSL',
  tlsCipher: '',
  tlsIssuer: '',
  tlsSubject: '',
};

assert.deepEqual(createDatabaseAccountTreeNodeExtraParams(extraParams, account), {
  dataSourceId: 1,
  databaseType: 'MYSQL',
  databaseName: 'app',
  user: 'sec002_ssl',
  host: '%',
  authenticationPlugin: 'caching_sha2_password',
  locked: false,
  tlsRequirement: 'SSL',
  tlsCipher: '',
  tlsIssuer: '',
  tlsSubject: '',
  popoverContent: 'sec002_ssl@%',
});

console.log('database account tree node helpers passed');
