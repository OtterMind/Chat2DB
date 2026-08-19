interface DataSourceAuthorizationSource {
  storageType?: string;
  hasPermission?: boolean;
  isAdmin?: boolean;
}

export interface DataSourceAuthorization {
  hasPermission: boolean;
  isAdmin: boolean;
}

export function resolveDataSourceAuthorization(
  source: DataSourceAuthorizationSource | null | undefined,
  usesFixedIdentity: boolean,
): DataSourceAuthorization {
  const isLocalStorage = source?.storageType?.trim().toUpperCase() === 'LOCAL';
  const fallback = isLocalStorage || usesFixedIdentity;

  return {
    hasPermission: source?.hasPermission ?? fallback,
    isAdmin: source?.isAdmin ?? fallback,
  };
}
