import { useCallback, useEffect, useRef, useState, type UIEventHandler } from 'react';
import { Select, Spin } from 'antd';
import i18n from '@/i18n';
import sqlService from '@/service/sql';
import {
  appendImportTargetTableOptions,
  IMPORT_TARGET_TABLE_PAGE_SIZE,
  shouldLoadMoreImportTargetTables,
  toImportTargetTableOptions,
  type ImportTargetTableOption,
} from '@/blocks/ImportAndExport/utils/importTargetTables';
import { useStyles } from './style';

interface IProps {
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string;
  /** Currently selected table; the wizard owns the value so a switch can reset the mapping. */
  value: string;
  disabled?: boolean;
  onChange: (tableName: string) => void;
}

/** Typing a table name is debounced so one keystroke does not become one request. */
const SEARCH_DEBOUNCE_MS = 300;

/**
 * Searchable picker for the import target table.
 *
 * The list comes from the same paged endpoint the object tree uses (`/api/rdb/table/list`), one
 * page at a time, so a database with hundreds of tables stays usable: the dropdown is virtualized,
 * scrolling loads the next page, and searching is done by the server instead of filtering an
 * incomplete client-side list.
 */
export default function TargetTableSelect({
  dataSourceId,
  databaseName,
  schemaName,
  value,
  disabled,
  onChange,
}: IProps) {
  const { styles } = useStyles();
  const [options, setOptions] = useState<ImportTargetTableOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  const [hasNextPage, setHasNextPage] = useState(false);
  const searchKeyRef = useRef('');
  const pageNoRef = useRef(1);
  const requestGenerationRef = useRef(0);
  const searchTimerRef = useRef<ReturnType<typeof setTimeout>>();

  const requestPage = useCallback(
    async (pageNo: number, searchKey: string) => {
      if (dataSourceId == null || !databaseName) {
        return;
      }
      const generation = ++requestGenerationRef.current;
      pageNoRef.current = pageNo;
      setLoading(true);
      try {
        const response = await sqlService.getTableList({
          dataSourceId,
          databaseName,
          schemaName,
          searchKey: searchKey || undefined,
          pageNo,
          pageSize: IMPORT_TARGET_TABLE_PAGE_SIZE,
        });
        if (generation !== requestGenerationRef.current) {
          return;
        }
        const incoming = toImportTargetTableOptions(response?.data);
        setOptions((current) => (pageNo === 1 ? incoming : appendImportTargetTableOptions(current, incoming)));
        setHasNextPage(Boolean(response?.hasNextPage));
        setFailed(false);
      } catch {
        if (generation !== requestGenerationRef.current) {
          return;
        }
        if (pageNo === 1) {
          setOptions([]);
        }
        setHasNextPage(false);
        setFailed(true);
      } finally {
        if (generation === requestGenerationRef.current) {
          setLoading(false);
        }
      }
    },
    [dataSourceId, databaseName, schemaName],
  );

  useEffect(() => {
    searchKeyRef.current = '';
    void requestPage(1, '');
    return () => {
      // A response of a picker that is already gone must not set state.
      requestGenerationRef.current += 1;
      if (searchTimerRef.current) {
        clearTimeout(searchTimerRef.current);
      }
    };
  }, [requestPage]);

  const handleSearch = (nextSearchKey: string) => {
    searchKeyRef.current = nextSearchKey;
    if (searchTimerRef.current) {
      clearTimeout(searchTimerRef.current);
    }
    searchTimerRef.current = setTimeout(() => void requestPage(1, nextSearchKey), SEARCH_DEBOUNCE_MS);
  };

  const handlePopupScroll: UIEventHandler<HTMLDivElement> = (event) => {
    const list = event.currentTarget;
    if (!shouldLoadMoreImportTargetTables({ hasNextPage, loading })) {
      return;
    }
    if (list.scrollTop + list.clientHeight < list.scrollHeight - 16) {
      return;
    }
    void requestPage(pageNoRef.current + 1, searchKeyRef.current);
  };

  // Closing the dropdown drops the search text, so reopening it has to show the full first page
  // again instead of the filtered list of the previous search.
  const handleDropdownVisibleChange = (open: boolean) => {
    if (!open || !searchKeyRef.current) {
      return;
    }
    searchKeyRef.current = '';
    if (searchTimerRef.current) {
      clearTimeout(searchTimerRef.current);
    }
    void requestPage(1, '');
  };

  return (
    <Select
      className={styles.targetTableSelect}
      showSearch
      value={value || undefined}
      disabled={disabled}
      loading={loading}
      placeholder={i18n('workspace.importExport.wizard.targetTablePlaceholder')}
      aria-label={i18n('workspace.importExport.targetTable')}
      options={options}
      filterOption={false}
      onSearch={handleSearch}
      onPopupScroll={handlePopupScroll}
      onDropdownVisibleChange={handleDropdownVisibleChange}
      notFoundContent={
        loading
          ? <Spin size="small" />
          : failed
          ? i18n('workspace.importExport.wizard.targetTableLoadFailed')
          : i18n('workspace.importExport.wizard.targetTableEmpty')
      }
      onChange={onChange}
    />
  );
}
