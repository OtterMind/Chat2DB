import i18n from '@/i18n';
import useSelectDatabase from '@/hooks/useSelectDatabase';
import { Input, Select } from 'antd';
import classnames from 'classnames';
import { memo } from 'react';
import { OperationLogFilterValues, updateOperationLogFilters } from './model';
import { useStyles } from './style';

interface IProps {
  className?: string;
  value: OperationLogFilterValues;
  onChange: (value: OperationLogFilterValues) => void;
  size?: 'small' | 'middle' | 'large';
}

function OperationLogFilters({ className, value, onChange, size = 'middle' }: IProps) {
  const { styles } = useStyles();
  const { dataSourceList, databaseList, schemaList, selectDatabase, onChangeSelectDatabase } = useSelectDatabase({});
  const hasDataSource = value.dataSourceId !== undefined;
  const databaseEnabled = hasDataSource && selectDatabase?.supportDatabase !== false;
  const schemaEnabled =
    hasDataSource &&
    selectDatabase?.supportSchema !== false &&
    (selectDatabase?.supportDatabase === false || !!value.databaseName);

  const handleDataSourceChange = (dataSourceId?: number) => {
    onChangeSelectDatabase({ dataSourceId });
    onChange(updateOperationLogFilters(value, { field: 'dataSourceId', value: dataSourceId }));
  };

  const handleDatabaseChange = (databaseName?: string) => {
    onChangeSelectDatabase({ databaseName });
    onChange(updateOperationLogFilters(value, { field: 'databaseName', value: databaseName }));
  };

  const handleSchemaChange = (schemaName?: string) => {
    onChangeSelectDatabase({ schemaName });
    onChange(updateOperationLogFilters(value, { field: 'schemaName', value: schemaName }));
  };

  return (
    <div className={classnames(styles.filters, className)}>
      <Select
        allowClear
        showSearch
        className={styles.scopeFilter}
        loading={dataSourceList === null}
        optionFilterProp="label"
        options={dataSourceList || []}
        placeholder={i18n('common.dataSource.title')}
        size={size}
        value={value.dataSourceId}
        onChange={handleDataSourceChange}
      />
      <Select
        allowClear
        showSearch
        className={styles.scopeFilter}
        disabled={!databaseEnabled}
        loading={databaseList === null}
        optionFilterProp="label"
        options={databaseList || []}
        placeholder={i18n('common.database.title')}
        size={size}
        value={value.databaseName}
        onChange={handleDatabaseChange}
      />
      <Select
        allowClear
        showSearch
        className={styles.scopeFilter}
        disabled={!schemaEnabled}
        loading={schemaList === null}
        optionFilterProp="label"
        options={schemaList || []}
        placeholder={i18n('common.schema.title')}
        size={size}
        value={value.schemaName}
        onChange={handleSchemaChange}
      />
      <Input
        allowClear
        className={styles.searchFilter}
        placeholder={i18n('common.text.searchPlaceholder')}
        size={size}
        value={value.searchKey || ''}
        onChange={(event) =>
          onChange(updateOperationLogFilters(value, { field: 'searchKey', value: event.target.value }))
        }
      />
    </div>
  );
}

export default memo(OperationLogFilters);
export type { OperationLogFilterValues } from './model';
export { useDebouncedOperationLogFilters } from './useDebouncedFilters';
