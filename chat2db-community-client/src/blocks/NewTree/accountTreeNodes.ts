import type { Account } from '@/service/accountAdmin';

type AccountTreeExtraParams = Record<string, any>;

export function createDatabaseAccountTreeNodeExtraParams(extraParams: AccountTreeExtraParams, account: Account) {
  return {
    ...extraParams,
    user: account.user,
    host: account.host,
    authenticationPlugin: account.authenticationPlugin,
    locked: account.locked,
    tlsRequirement: account.tlsRequirement,
    tlsCipher: account.tlsCipher,
    tlsIssuer: account.tlsIssuer,
    tlsSubject: account.tlsSubject,
    popoverContent: account.displayName,
  };
}
