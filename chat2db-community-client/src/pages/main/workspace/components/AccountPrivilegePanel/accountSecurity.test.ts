import assert from 'node:assert/strict';
import type { AccountActionType } from '@/service/accountAdmin';
import {
  buildAccountSecurityCommand,
  createAccountSecurityInitialValues,
} from './accountSecurity';

const selectedAccount = {
  user: 'sec002_ssl',
  host: '%',
  authenticationPlugin: 'caching_sha2_password',
  tlsRequirement: 'SPECIFIED',
  tlsCipher: 'AES256',
  tlsIssuer: 'CN=issuer',
  tlsSubject: 'CN=subject',
};
const alterAuthPlugin = 'ALTER_AUTH_PLUGIN' as AccountActionType;

assert.deepEqual(createAccountSecurityInitialValues(selectedAccount), {
  user: 'sec002_ssl',
  host: '%',
  password: '',
  authPlugin: 'caching_sha2_password',
  tlsRequirement: 'SPECIFIED',
  tlsCipher: 'AES256',
  tlsIssuer: 'CN=issuer',
  tlsSubject: 'CN=subject',
});

const command = buildAccountSecurityCommand({
  dataSourceId: 1,
  actionType: alterAuthPlugin,
  values: {
    user: 'sec002_ssl',
    host: '%',
    password: 'secret',
    authPlugin: 'mysql_native_password',
    tlsRequirement: 'NONE',
    tlsCipher: 'stale-cipher',
    tlsIssuer: 'stale-issuer',
    tlsSubject: 'stale-subject',
  },
  currentAccount: selectedAccount,
});

assert.deepEqual(command, {
  dataSourceId: 1,
  user: 'sec002_ssl',
  host: '%',
  password: 'secret',
  authPlugin: 'mysql_native_password',
  tlsRequirement: 'NONE',
  actionType: alterAuthPlugin,
});

assert.deepEqual(
  buildAccountSecurityCommand({
    dataSourceId: 1,
    actionType: alterAuthPlugin,
    values: {
      user: 'sec002_ssl',
      host: '%',
      authPlugin: 'caching_sha2_password',
      tlsRequirement: 'NONE',
      actionType: alterAuthPlugin,
    },
    currentAccount: selectedAccount,
  }),
  {
    dataSourceId: 1,
    user: 'sec002_ssl',
    host: '%',
    tlsRequirement: 'NONE',
    actionType: alterAuthPlugin,
  },
  'TLS-only changes must not resend IDENTIFIED WITH without a password',
);

console.log('account security helpers passed');
