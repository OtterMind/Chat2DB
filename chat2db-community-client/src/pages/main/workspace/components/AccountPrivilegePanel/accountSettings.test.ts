import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  AccountActionType,
  AccountPasswordExpirePolicy,
  type Account,
  type AccountCapability,
} from '@/service/accountTypes';

import {
  buildAccountSettingsCommand,
  getAccountSettingsInitialValues,
  isAccountSettingsActionSupported,
} from './accountSettings';

const account: Account = {
  user: 'app',
  host: '%',
  displayName: 'app@%',
  passwordExpired: false,
  passwordLifetime: 90,
  maxQueriesPerHour: 100,
  maxUpdatesPerHour: 20,
  maxConnectionsPerHour: 10,
  maxUserConnections: 3,
};

const capability: AccountCapability = {
  accountListReadable: true,
  accountLockSupported: true,
  passwordExpirationSupported: true,
  resourceLimitsSupported: true,
  editablePrivileges: [],
};

assert.deepEqual(
  buildAccountSettingsCommand({
    dataSourceId: 42,
    account,
    actionType: AccountActionType.ALTER_PASSWORD_POLICY,
    values: {
      passwordExpirePolicy: AccountPasswordExpirePolicy.INTERVAL,
      passwordExpireDays: 30,
    },
  }),
  {
    dataSourceId: 42,
    user: 'app',
    host: '%',
    actionType: AccountActionType.ALTER_PASSWORD_POLICY,
    passwordExpirePolicy: AccountPasswordExpirePolicy.INTERVAL,
    passwordExpireDays: 30,
  },
);

assert.deepEqual(
  buildAccountSettingsCommand({
    dataSourceId: 42,
    account,
    actionType: AccountActionType.ALTER_RESOURCE_LIMITS,
    values: {
      maxQueriesPerHour: 0,
      maxUpdatesPerHour: 20,
      maxConnectionsPerHour: undefined,
      maxUserConnections: 3,
    },
  }),
  {
    dataSourceId: 42,
    user: 'app',
    host: '%',
    actionType: AccountActionType.ALTER_RESOURCE_LIMITS,
    maxQueriesPerHour: 0,
    maxUpdatesPerHour: 20,
    maxUserConnections: 3,
  },
);

assert.deepEqual(getAccountSettingsInitialValues(account), {
  user: 'app',
  host: '%',
  password: '',
  passwordExpirePolicy: AccountPasswordExpirePolicy.INTERVAL,
  passwordExpireDays: 90,
  maxQueriesPerHour: 100,
  maxUpdatesPerHour: 20,
  maxConnectionsPerHour: 10,
  maxUserConnections: 3,
});

assert.equal(
  getAccountSettingsInitialValues({
    ...account,
    passwordExpirePolicy: AccountPasswordExpirePolicy.NEVER,
    passwordExpired: false,
    passwordLifetime: 0,
  }).passwordExpirePolicy,
  AccountPasswordExpirePolicy.NEVER,
);

assert.equal(
  getAccountSettingsInitialValues({
    ...account,
    passwordExpirePolicy: AccountPasswordExpirePolicy.INTERVAL,
    passwordExpired: false,
    passwordLifetime: 30,
  }).passwordExpirePolicy,
  AccountPasswordExpirePolicy.INTERVAL,
);

assert.equal(
  getAccountSettingsInitialValues({
    ...account,
    passwordExpirePolicy: AccountPasswordExpirePolicy.IMMEDIATE,
    passwordExpired: true,
    passwordLifetime: undefined,
  }).passwordExpirePolicy,
  AccountPasswordExpirePolicy.IMMEDIATE,
);

assert.equal(
  getAccountSettingsInitialValues({
    ...account,
    passwordExpirePolicy: undefined,
    passwordExpired: false,
    passwordLifetime: undefined,
  }).passwordExpirePolicy,
  AccountPasswordExpirePolicy.DEFAULT,
);

assert.deepEqual(
  buildAccountSettingsCommand({
    dataSourceId: 42,
    account,
    actionType: AccountActionType.ALTER_RESOURCE_LIMITS,
    values: getAccountSettingsInitialValues(account),
  }),
  {
    dataSourceId: 42,
    user: 'app',
    host: '%',
    actionType: AccountActionType.ALTER_RESOURCE_LIMITS,
    maxQueriesPerHour: 100,
    maxUpdatesPerHour: 20,
    maxConnectionsPerHour: 10,
    maxUserConnections: 3,
  },
);

assert.equal(isAccountSettingsActionSupported(AccountActionType.ALTER_PASSWORD, capability), true);
assert.equal(isAccountSettingsActionSupported(AccountActionType.ALTER_PASSWORD_POLICY, capability), true);
assert.equal(isAccountSettingsActionSupported(AccountActionType.ALTER_RESOURCE_LIMITS, capability), true);
assert.equal(
  isAccountSettingsActionSupported(AccountActionType.ALTER_PASSWORD_POLICY, {
    ...capability,
    passwordExpirationSupported: false,
  }),
  false,
);
assert.equal(
  isAccountSettingsActionSupported(AccountActionType.ALTER_RESOURCE_LIMITS, {
    ...capability,
    resourceLimitsSupported: false,
  }),
  false,
);

const panelSource = readFileSync('src/pages/main/workspace/components/AccountPrivilegePanel/index.tsx', 'utf8');
assert.match(panelSource, /AccountActionType\.ALTER_PASSWORD_POLICY/);
assert.match(panelSource, /AccountActionType\.ALTER_RESOURCE_LIMITS/);
assert.match(panelSource, /isAccountSettingsActionSupported/);

console.log('Account settings contract tests passed');
