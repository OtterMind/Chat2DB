import { useTreeStore } from '@/store/tree';
import { resolveDataSourceIdentityColor } from '@/utils/dataSourceIdentity';

export function useDataSourceIdentityColor(dataSourceId?: number): string | null {
  const identitySource = useTreeStore((state) =>
    state.dataSourceList?.find((item) => item.extraParams.dataSourceId === dataSourceId),
  )?.extraParams;

  return resolveDataSourceIdentityColor(identitySource);
}
