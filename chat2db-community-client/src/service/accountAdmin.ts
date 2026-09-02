import createRequest from './base';
import i18n from '@/i18n';
import type {
  Account,
  AccountBaseParams,
  AccountCapability,
  AccountCommand,
  AccountExecute,
  AccountPreview,
} from './accountTypes';

export {
  AccountActionType,
  AccountPasswordExpirePolicy,
  AccountPrivilege,
  AccountPrivilegeScope,
} from './accountTypes';
export type { Account, AccountBaseParams, AccountCapability, AccountCommand, AccountExecute, AccountPreview };

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

export default {
  capability,
  list,
  preview,
  execute,
  grants,
};
