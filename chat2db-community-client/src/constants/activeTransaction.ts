export enum ActiveTransactionQueryState {
  VISIBLE = 'VISIBLE',
  UNAVAILABLE = 'UNAVAILABLE',
}

export enum ActiveTransactionSessionState {
  LIVE = 'LIVE',
  DISAPPEARED_OR_HIDDEN = 'DISAPPEARED_OR_HIDDEN',
}

export enum ActiveTransactionLockMetadataState {
  AVAILABLE = 'AVAILABLE',
  UNAVAILABLE = 'UNAVAILABLE',
}

export enum ActiveTransactionLockMetadataSource {
  MYSQL_80_PERFORMANCE_SCHEMA = 'MYSQL_80_PERFORMANCE_SCHEMA',
  MYSQL_57_INFORMATION_SCHEMA = 'MYSQL_57_INFORMATION_SCHEMA',
}

export const ACTIVE_TRANSACTION_PROCESS_PRIVILEGE_ERROR_CODE =
  'mysql.activeTransaction.processPrivilegeRequired';

export const MYSQL_ACTIVE_TRANSACTION_STATE = {
  RUNNING: 'RUNNING',
  LOCK_WAIT: 'LOCK WAIT',
} as const;
