export enum AccountActionType {
  CREATE_USER = 'CREATE_USER',
  ALTER_PASSWORD = 'ALTER_PASSWORD',
  LOCK_ACCOUNT = 'LOCK_ACCOUNT',
  UNLOCK_ACCOUNT = 'UNLOCK_ACCOUNT',
  DROP_USER = 'DROP_USER',
  GRANT_PRIVILEGE = 'GRANT_PRIVILEGE',
  REVOKE_PRIVILEGE = 'REVOKE_PRIVILEGE',
  ALTER_PASSWORD_POLICY = 'ALTER_PASSWORD_POLICY',
  ALTER_RESOURCE_LIMITS = 'ALTER_RESOURCE_LIMITS',
}

export enum AccountPasswordExpirePolicy {
  DEFAULT = 'DEFAULT',
  NEVER = 'NEVER',
  IMMEDIATE = 'IMMEDIATE',
  INTERVAL = 'INTERVAL',
}

export enum AccountPrivilegeScope {
  GLOBAL = 'GLOBAL',
  DATABASE = 'DATABASE',
  TABLE = 'TABLE',
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
  SHOW_VIEW = 'SHOW_VIEW',
  TRIGGER = 'TRIGGER',
  EVENT = 'EVENT',
  CREATE_TEMPORARY_TABLES = 'CREATE_TEMPORARY_TABLES',
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
  passwordExpired?: boolean;
  passwordExpirePolicy?: AccountPasswordExpirePolicy;
  passwordLastChanged?: string;
  passwordLifetime?: number;
  maxQueriesPerHour?: number;
  maxUpdatesPerHour?: number;
  maxConnectionsPerHour?: number;
  maxUserConnections?: number;
}

export interface AccountCapability {
  dbType?: string;
  productName?: string;
  productVersion?: string;
  currentUser?: string;
  connectionUser?: string;
  accountListReadable: boolean;
  accountLockSupported: boolean;
  passwordExpirationSupported?: boolean;
  resourceLimitsSupported?: boolean;
  roleManagementSupported?: boolean;
  editablePrivileges: AccountPrivilege[];
  message?: string;
}

export interface AccountCommand extends AccountBaseParams {
  actionType: AccountActionType;
  scope?: AccountPrivilegeScope;
  databaseName?: string;
  tableName?: string;
  privileges?: AccountPrivilege[];
  grantOption?: boolean;
  password?: string;
  previewToken?: string;
  passwordExpirePolicy?: AccountPasswordExpirePolicy;
  passwordExpireDays?: number;
  maxQueriesPerHour?: number;
  maxUpdatesPerHour?: number;
  maxConnectionsPerHour?: number;
  maxUserConnections?: number;
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
