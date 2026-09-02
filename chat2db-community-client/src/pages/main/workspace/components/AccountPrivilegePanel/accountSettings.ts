import {
  AccountActionType,
  AccountPasswordExpirePolicy,
  type Account,
  type AccountCapability,
  type AccountCommand,
} from '@/service/accountTypes';

export interface AccountSettingsValues {
  user?: string;
  host?: string;
  password?: string;
  passwordExpirePolicy?: AccountPasswordExpirePolicy;
  passwordExpireDays?: number;
  maxQueriesPerHour?: number;
  maxUpdatesPerHour?: number;
  maxConnectionsPerHour?: number;
  maxUserConnections?: number;
}

interface BuildAccountSettingsCommandParams {
  dataSourceId: number;
  account: Pick<Account, 'user' | 'host'>;
  actionType: AccountActionType;
  values: AccountSettingsValues;
}

export function getAccountSettingsInitialValues(account: Account): AccountSettingsValues {
  return {
    user: account.user,
    host: account.host,
    password: '',
    passwordExpirePolicy: normalizePasswordExpirePolicy(account),
    passwordExpireDays: account.passwordLifetime,
    maxQueriesPerHour: account.maxQueriesPerHour,
    maxUpdatesPerHour: account.maxUpdatesPerHour,
    maxConnectionsPerHour: account.maxConnectionsPerHour,
    maxUserConnections: account.maxUserConnections,
  };
}

export function buildAccountSettingsCommand({
  dataSourceId,
  account,
  actionType,
  values,
}: BuildAccountSettingsCommandParams): AccountCommand {
  const command: AccountCommand = {
    dataSourceId,
    user: account.user,
    host: account.host,
    actionType,
  };

  if (actionType === AccountActionType.ALTER_PASSWORD) {
    command.password = values.password;
  }

  if (actionType === AccountActionType.ALTER_PASSWORD_POLICY) {
    command.passwordExpirePolicy = values.passwordExpirePolicy;
    command.passwordExpireDays =
      values.passwordExpirePolicy === AccountPasswordExpirePolicy.INTERVAL ? values.passwordExpireDays : undefined;
  }

  if (actionType === AccountActionType.ALTER_RESOURCE_LIMITS) {
    copyNumber(values, command, 'maxQueriesPerHour');
    copyNumber(values, command, 'maxUpdatesPerHour');
    copyNumber(values, command, 'maxConnectionsPerHour');
    copyNumber(values, command, 'maxUserConnections');
  }

  return command;
}

export function isAccountSettingsActionSupported(actionType: AccountActionType, capability: AccountCapability | null) {
  if (actionType === AccountActionType.ALTER_PASSWORD_POLICY) {
    return capability?.passwordExpirationSupported !== false;
  }
  if (actionType === AccountActionType.ALTER_RESOURCE_LIMITS) {
    return capability?.resourceLimitsSupported !== false;
  }
  return true;
}

function copyNumber(values: AccountSettingsValues, command: AccountCommand, field: keyof AccountSettingsValues) {
  const value = values[field];
  if (typeof value === 'number') {
    (command as Record<string, unknown>)[field] = value;
  }
}

function normalizePasswordExpirePolicy(account: Account) {
  if (account.passwordExpirePolicy) {
    return account.passwordExpirePolicy;
  }
  if (account.passwordExpired) {
    return AccountPasswordExpirePolicy.IMMEDIATE;
  }
  if (account.passwordLifetime === 0) {
    return AccountPasswordExpirePolicy.NEVER;
  }
  if (typeof account.passwordLifetime === 'number') {
    return AccountPasswordExpirePolicy.INTERVAL;
  }
  return AccountPasswordExpirePolicy.DEFAULT;
}
