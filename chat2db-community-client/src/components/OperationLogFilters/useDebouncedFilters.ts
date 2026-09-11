import { useEffect, useMemo, useState } from 'react';
import { normalizeOperationLogFilters, OperationLogFilterValues } from './model';

export function useDebouncedOperationLogFilters(filters: OperationLogFilterValues, delay = 300) {
  const { dataSourceId, databaseName, schemaName, searchKey } = filters;
  const [debouncedSearchKey, setDebouncedSearchKey] = useState(normalizeOperationLogFilters({ searchKey }).searchKey);

  useEffect(() => {
    const timeoutId = window.setTimeout(() => {
      setDebouncedSearchKey(normalizeOperationLogFilters({ searchKey }).searchKey);
    }, delay);

    return () => window.clearTimeout(timeoutId);
  }, [delay, searchKey]);

  return useMemo(
    () =>
      normalizeOperationLogFilters({
        dataSourceId,
        databaseName,
        schemaName,
        searchKey: debouncedSearchKey,
      }),
    [dataSourceId, databaseName, debouncedSearchKey, schemaName],
  );
}
