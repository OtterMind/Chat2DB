import { DatabaseTypeCode } from '@/constants/common';
import type { ImportDuplicatePolicy, ImportFailurePolicy } from '@/typings/importExport';

export interface ImportPolicies {
  duplicatePolicy: ImportDuplicatePolicy;
  failurePolicy: ImportFailurePolicy;
}

export const DEFAULT_IMPORT_POLICIES: ImportPolicies = {
  duplicatePolicy: 'INSERT',
  failurePolicy: 'ABORT',
};

/** Mirrors the backend's INSERT IGNORE / ON CONFLICT DO NOTHING families. */
const DUPLICATE_IGNORE_DATABASES = new Set<string>([
  DatabaseTypeCode.MYSQL,
  DatabaseTypeCode.MARIADB,
  DatabaseTypeCode.TIDB,
  DatabaseTypeCode.STARROCKS,
  DatabaseTypeCode.DORIS,
  DatabaseTypeCode.OCEANBASE,
  DatabaseTypeCode.POSTGRESQL,
]);

/**
 * Unknown database types stay enabled: the wizard may open without a resolved type, and the backend
 * rejects a dialect that cannot express the policy instead of silently changing the semantics.
 */
export const supportsDuplicateIgnore = (databaseType?: string | null): boolean =>
  !databaseType || DUPLICATE_IGNORE_DATABASES.has(databaseType.toUpperCase());

/** Resolves a restored task's policies, falling back to the documented defaults. */
export const resolveImportPolicies = (restored?: Partial<ImportPolicies> | null): ImportPolicies => ({
  duplicatePolicy: restored?.duplicatePolicy ?? DEFAULT_IMPORT_POLICIES.duplicatePolicy,
  failurePolicy: restored?.failurePolicy ?? DEFAULT_IMPORT_POLICIES.failurePolicy,
});

/** A restored IGNORE policy falls back to INSERT when the current dialect cannot express it. */
export const resolveImportPoliciesForTarget = (
  restored: Partial<ImportPolicies> | null | undefined,
  databaseType?: string | null,
): ImportPolicies => {
  const resolved = resolveImportPolicies(restored);
  return resolved.duplicatePolicy === 'IGNORE' && !supportsDuplicateIgnore(databaseType)
    ? { ...resolved, duplicatePolicy: 'INSERT' }
    : resolved;
};
