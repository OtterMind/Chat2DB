import createRequest from './base';
import i18n from '@/i18n';

export enum AccountActionType {
  CREATE_USER = 'CREATE_USER',
  ALTER_PASSWORD = 'ALTER_PASSWORD',
  LOCK_ACCOUNT = 'LOCK_ACCOUNT',
  UNLOCK_ACCOUNT = 'UNLOCK_ACCOUNT',
  DROP_USER = 'DROP_USER',
  GRANT_PRIVILEGE = 'GRANT_PRIVILEGE',
  REVOKE_PRIVILEGE = 'REVOKE_PRIVILEGE',
}

export enum AccountPrivilegeScope {
  GLOBAL = 'GLOBAL',
  DATABASE = 'DATABASE',
  TABLE = 'TABLE',
  FUNCTION = 'FUNCTION',
  PROCEDURE = 'PROCEDURE',
}

export enum AccountPrivilege {
  SELECT = 'SELECT',
  INSERT = 'INSERT',
  UPDATE = 'UPDATE',
  DELETE = 'DELETE',
  CREATE = 'CREATE',
  DROP = 'DROP',
  ALTER = 'ALTER',
  INDEX = 'INDEX',
  REFERENCES = 'REFERENCES',
  EXECUTE = 'EXECUTE',
  ALTER_ROUTINE = 'ALTER_ROUTINE',
  SHOW_VIEW = 'SHOW_VIEW',
  TRIGGER = 'TRIGGER',
  EVENT = 'EVENT',
  CREATE_TEMPORARY_TABLES = 'CREATE_TEMPORARY_TABLES',
}

export enum AccountGrantSource {
  DIRECT_ROUTINE = 'DIRECT_ROUTINE',
  INHERITED_DATABASE = 'INHERITED_DATABASE',
  INHERITED_GLOBAL = 'INHERITED_GLOBAL',
  INHERITED_ROLE = 'INHERITED_ROLE',
  UNPARSED = 'UNPARSED',
}

export interface AccountBaseParams {
  dataSourceId: number;
  user?: string;
  host?: string;
}

export interface Account {
  user: string;
  host: string;
  displayName: string;
  authenticationPlugin?: string;
  locked?: boolean;
}

export interface AccountCapability {
  dbType?: string;
  productName?: string;
  productVersion?: string;
  currentUser?: string;
  connectionUser?: string;
  accountListReadable: boolean;
  accountLockSupported: boolean;
  editablePrivileges: AccountPrivilege[];
  message?: string;
}

export interface AccountCommand extends AccountBaseParams {
  actionType: AccountActionType;
  scope?: AccountPrivilegeScope;
  databaseName?: string;
  tableName?: string;
  objectName?: string;
  privileges?: AccountPrivilege[];
  grantOption?: boolean;
  password?: string;
  previewToken?: string;
}

export interface AccountPreview {
  actionType: AccountActionType;
  sql: string;
  previewToken: string;
}

export interface AccountExecute extends AccountPreview {
  success: boolean;
  message?: string;
  failureCode?: string;
  errorCode?: number;
  sqlState?: string;
}

export interface AccountGrant {
  source: AccountGrantSource | string;
  scope?: AccountPrivilegeScope | string;
  databaseName?: string;
  objectName?: string;
  roleName?: string;
  privileges: string[];
  grantOption?: boolean;
  direct?: boolean;
  revocable?: boolean;
  rawStatement: string;
}

export interface AccountGrantSummary {
  readable: boolean;
  message?: string;
  rawStatements: string[];
  grants: AccountGrant[];
}

export function formatAccountExecuteMessage(result: AccountExecute) {
  if (result.success) {
    return i18n('workspace.databaseAccount.executeSuccess');
  }
  const detail = [
    localizeAccountMessage(result.message || result.failureCode),
    result.errorCode ? `${i18n('workspace.databaseAccount.errorCode')} ${result.errorCode}` : '',
    result.sqlState,
  ]
    .filter(Boolean)
    .join(' / ');
  return detail || i18n('workspace.databaseAccount.executeFailed');
}

function localizeAccountMessage(rawMessage?: string) {
  if (!rawMessage) {
    return '';
  }
  if (rawMessage.startsWith('account.') || rawMessage.startsWith('mysql.account.')) {
    return i18n(rawMessage as any);
  }
  return rawMessage;
}

const capability = createRequest<AccountBaseParams, AccountCapability>('/api/rdb/account/capability', {
  method: 'get',
  errorLevel: false,
});

const list = createRequest<AccountBaseParams, Account[]>('/api/rdb/account/list', {
  method: 'get',
  errorLevel: 'toast',
});

const preview = createRequest<AccountCommand, AccountPreview>('/api/rdb/account/preview', {
  method: 'post',
  errorLevel: 'toast',
});

const execute = createRequest<AccountCommand, AccountExecute>('/api/rdb/account/execute', {
  method: 'post',
  errorLevel: 'toast',
});

const grants = createRequest<AccountBaseParams, string[]>('/api/rdb/account/grants', {
  method: 'get',
  errorLevel: 'toast',
});

const grantSummary = createRequest<AccountBaseParams, AccountGrantSummary>('/api/rdb/account/grant-summary', {
  method: 'get',
  errorLevel: false,
});

export default {
  capability,
  list,
  preview,
  execute,
  grants,
  grantSummary,
};
