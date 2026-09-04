import assert from 'node:assert/strict';
import type { AccountGrant, AccountGrantSummary } from '@/service/accountAdmin';
import {
  canRevokeRoutinePrivileges,
  grantSourceLabelKey,
  routineGrantEvidence,
} from './accountGrantSummary';

const AccountGrantSource = {
  DIRECT_ROUTINE: 'DIRECT_ROUTINE',
  INHERITED_DATABASE: 'INHERITED_DATABASE',
  INHERITED_GLOBAL: 'INHERITED_GLOBAL',
  INHERITED_ROLE: 'INHERITED_ROLE',
} as const;

const AccountPrivilegeScope = {
  FUNCTION: 'FUNCTION',
  PROCEDURE: 'PROCEDURE',
  DATABASE: 'DATABASE',
} as const;

const directFunctionGrant: AccountGrant = {
  source: AccountGrantSource.DIRECT_ROUTINE,
  scope: AccountPrivilegeScope.FUNCTION,
  databaseName: 'app',
  objectName: 'calculate_total',
  privileges: ['EXECUTE'],
  direct: true,
  revocable: true,
  rawStatement: "GRANT EXECUTE ON FUNCTION `app`.`calculate_total` TO 'runner'@'%'",
};

const inheritedDatabaseGrant: AccountGrant = {
  source: AccountGrantSource.INHERITED_DATABASE,
  scope: AccountPrivilegeScope.DATABASE,
  databaseName: 'app',
  privileges: ['EXECUTE'],
  direct: false,
  revocable: false,
  rawStatement: "GRANT EXECUTE ON `app`.* TO 'runner'@'%'",
};

const roleGrant: AccountGrant = {
  source: AccountGrantSource.INHERITED_ROLE,
  scope: 'ROLE',
  roleName: '`routine_role`@`%`',
  privileges: [],
  direct: false,
  revocable: false,
  rawStatement: "GRANT `routine_role`@`%` TO 'runner'@'%'",
};

const readableSummary: AccountGrantSummary = {
  readable: true,
  rawStatements: [],
  grants: [directFunctionGrant, inheritedDatabaseGrant, roleGrant],
};

assert.equal(
  canRevokeRoutinePrivileges(
    readableSummary,
    AccountPrivilegeScope.FUNCTION,
    'app',
    'calculate_total',
    ['EXECUTE'],
  ),
  true,
  'matching direct routine grants are revocable',
);

assert.equal(
  canRevokeRoutinePrivileges(
    readableSummary,
    AccountPrivilegeScope.FUNCTION,
    'app',
    'calculate_total',
    ['ALTER_ROUTINE'],
  ),
  false,
  'different direct routine privileges are not treated as revocable',
);

assert.equal(
  canRevokeRoutinePrivileges(
    {
      readable: true,
      rawStatements: [],
      grants: [inheritedDatabaseGrant, roleGrant],
    },
    AccountPrivilegeScope.FUNCTION,
    'app',
    'calculate_total',
    ['EXECUTE'],
  ),
  false,
  'inherited database and role access cannot be revoked from a routine object',
);

assert.equal(
  canRevokeRoutinePrivileges(
    {
      readable: false,
      message: 'Access denied for SHOW GRANTS',
      rawStatements: [],
      grants: [],
    },
    AccountPrivilegeScope.PROCEDURE,
    'app',
    'sync_orders',
    ['EXECUTE'],
  ),
  false,
  'unreadable SHOW GRANTS blocks routine revoke classification',
);

assert.deepEqual(
  routineGrantEvidence(readableSummary, AccountPrivilegeScope.FUNCTION, 'app', 'calculate_total').map(
    (grant) => grant.source,
  ),
  [
    AccountGrantSource.DIRECT_ROUTINE,
    AccountGrantSource.INHERITED_DATABASE,
  ],
  'routine evidence keeps direct grants separate from proven inherited sources',
);

assert.equal(
  grantSourceLabelKey(AccountGrantSource.INHERITED_GLOBAL),
  'workspace.databaseAccount.grantSourceInheritedGlobal',
  'source labels use i18n keys',
);
