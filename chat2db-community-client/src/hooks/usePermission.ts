import { useCallback, useMemo } from 'react';

import { useOrgStore } from '@/store/workspaceContext';

export type PermissionPath = readonly [business: string, resource: string, action: string];

export const buildPermissionCode = ([business, resource, action]: PermissionPath) =>
  `${business}:${resource}:${action}`;

export const hasPermission = (permissions: readonly string[] | undefined, path: PermissionPath) =>
  Boolean(permissions?.includes(buildPermissionCode(path)));

export const hasAnyPermission = (
  permissions: readonly string[] | undefined,
  paths: readonly PermissionPath[],
) => paths.some((path) => hasPermission(permissions, path));

export const usePermission = () => {
  const permissions = useOrgStore((state) => state.curOrg?.permissions || []);
  const permissionSet = useMemo(() => new Set(permissions), [permissions]);
  const can = useCallback(
    (...path: PermissionPath) => permissionSet.has(buildPermissionCode(path)),
    [permissionSet],
  );
  const canAny = useCallback(
    (...paths: PermissionPath[]) => paths.some((path) => permissionSet.has(buildPermissionCode(path))),
    [permissionSet],
  );

  return { can, canAny, permissions };
};
