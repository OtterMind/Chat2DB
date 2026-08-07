import type { IDataSourceIdentityColorResponse, IDataSourceIdentityColorUpdateRequest } from '@/typings';
import { normalizeIdentityColor } from '@/utils/dataSourceIdentity';

interface ConnectionIdentityColorTarget {
  id: number;
  identityColor?: string | null;
}

export async function applyConnectionIdentityColorUpdate<T extends ConnectionIdentityColorTarget>(
  connection: T,
  previousIdentityColor: string | null | undefined,
  requestedIdentityColor: string | null | undefined,
  updateIdentityColor: (
    request: IDataSourceIdentityColorUpdateRequest,
  ) => Promise<IDataSourceIdentityColorResponse>,
): Promise<T> {
  const previousColor = normalizeIdentityColor(previousIdentityColor);
  const nextColor = normalizeIdentityColor(requestedIdentityColor);
  if (previousColor === nextColor) {
    return connection;
  }

  const identity = await updateIdentityColor({
    id: connection.id,
    identityColor: nextColor,
  });
  return {
    ...connection,
    ...identity,
  };
}
