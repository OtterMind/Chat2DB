import type { IConnectionDetails } from '@/typings';

export function dataSourceDisplayName(
  dataSourceId: number,
  dataSources: Array<Pick<IConnectionDetails, 'id' | 'alias'>>,
  fallback: string,
) {
  const alias = dataSources.find((source) => String(source.id) === String(dataSourceId))?.alias?.trim();
  return alias || fallback;
}
