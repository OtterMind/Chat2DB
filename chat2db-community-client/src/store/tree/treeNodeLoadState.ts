import type { DataSourceRuntimeAvailability } from '@/utils/editorDataSourceLifecycle';

interface ReuseTreeNodeChildrenOptions {
  children: unknown[] | undefined;
  refresh?: boolean;
  isDataSourceRoot: boolean;
  runtimeAvailability?: DataSourceRuntimeAvailability;
}

export function shouldReuseTreeNodeChildren({
  children,
  refresh,
  isDataSourceRoot,
  runtimeAvailability,
}: ReuseTreeNodeChildrenOptions) {
  if (refresh || children === undefined) {
    return false;
  }
  if (!isDataSourceRoot) {
    return true;
  }
  return runtimeAvailability === 'available';
}
