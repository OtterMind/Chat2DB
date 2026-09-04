import type { AccountGrant, AccountGrantSummary, AccountPrivilegeScope } from '@/service/accountAdmin';

const ROUTINE_PRIVILEGE_ALL = 'ALL_PRIVILEGES';
const SCOPE_FUNCTION = 'FUNCTION';
const SCOPE_PROCEDURE = 'PROCEDURE';
const SOURCE_DIRECT_ROUTINE = 'DIRECT_ROUTINE';
const SOURCE_INHERITED_DATABASE = 'INHERITED_DATABASE';
const SOURCE_INHERITED_GLOBAL = 'INHERITED_GLOBAL';
const SOURCE_INHERITED_ROLE = 'INHERITED_ROLE';

export function isRoutineScope(scope?: AccountPrivilegeScope | string) {
  return scope === SCOPE_FUNCTION || scope === SCOPE_PROCEDURE;
}

export function routineGrantEvidence(
  summary: AccountGrantSummary | null,
  scope?: AccountPrivilegeScope | string,
  databaseName?: string,
  objectName?: string,
) {
  if (!summary?.grants?.length || !isRoutineScope(scope) || !databaseName || !objectName) {
    return [];
  }
  return summary.grants.filter((grant) => {
    if (grant.source === SOURCE_DIRECT_ROUTINE) {
      return grant.scope === scope && grant.databaseName === databaseName && grant.objectName === objectName;
    }
    if (grant.source === SOURCE_INHERITED_DATABASE) {
      return grant.databaseName === databaseName && grantMayAffectRoutine(grant);
    }
    return grant.source === SOURCE_INHERITED_GLOBAL;
  });
}

export function canRevokeRoutinePrivileges(
  summary: AccountGrantSummary | null,
  scope: AccountPrivilegeScope | string | undefined,
  databaseName: string | undefined,
  objectName: string | undefined,
  privileges: string[] | undefined,
) {
  if (!isRoutineScope(scope)) {
    return true;
  }
  if (!summary?.readable || !privileges?.length) {
    return false;
  }
  const evidence = routineGrantEvidence(summary, scope, databaseName, objectName);
  return privileges.every((privilege) =>
    evidence.some(
      (grant) =>
        grant.source === SOURCE_DIRECT_ROUTINE &&
        grant.direct &&
        grant.revocable &&
        grantContainsPrivilege(grant, privilege),
    ),
  );
}

export function grantSourceLabelKey(source?: string) {
  switch (source) {
    case SOURCE_DIRECT_ROUTINE:
      return 'workspace.databaseAccount.grantSourceDirectRoutine';
    case SOURCE_INHERITED_DATABASE:
      return 'workspace.databaseAccount.grantSourceInheritedDatabase';
    case SOURCE_INHERITED_GLOBAL:
      return 'workspace.databaseAccount.grantSourceInheritedGlobal';
    case SOURCE_INHERITED_ROLE:
      return 'workspace.databaseAccount.grantSourceInheritedRole';
    default:
      return 'workspace.databaseAccount.grantSourceUnparsed';
  }
}

function grantMayAffectRoutine(grant: AccountGrant) {
  return grantContainsPrivilege(grant, 'EXECUTE') || grantContainsPrivilege(grant, 'ALTER_ROUTINE');
}

function grantContainsPrivilege(grant: AccountGrant, privilege: string) {
  const normalizedPrivilege = normalizePrivilege(privilege);
  return (grant.privileges || []).some((value) => {
    const normalizedGrantPrivilege = normalizePrivilege(value);
    return normalizedGrantPrivilege === ROUTINE_PRIVILEGE_ALL || normalizedGrantPrivilege === normalizedPrivilege;
  });
}

function normalizePrivilege(value: string) {
  return value
    .trim()
    .toUpperCase()
    .replace(/\s+/g, '_');
}
