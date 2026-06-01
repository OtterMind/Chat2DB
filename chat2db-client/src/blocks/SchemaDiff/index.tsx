import React, { memo, useCallback, useEffect, useState } from 'react';
import { Button, Select, Spin, message, Modal, Checkbox } from 'antd';
import classnames from 'classnames';

import { i18n, i18nElement } from '@/i18n';
import connectionService from '@/service/connection';
import sqlService from '@/service/sql';
import { useConnectionStore, getConnectionList } from '@/pages/main/store/connection';
import { IConnectionListItem } from '@/typings/connection';
import { IDatabaseItem, ISchemaItem } from '@/typings';

import { useSchemaDiffStore, setSourceDataSource, setTargetDataSource, setSourceDatabase, setTargetDatabase, setSourceSchema, setTargetSchema, setCompareOption, setCompareResult, setComparing, setSelectedTableDiffs, setDetailViewTableName } from './store';
import ResultPanel from './ResultPanel';
import MigrationPanel from './MigrationPanel';
import styles from './index.less';

/**
 * SchemaDiffPanel - Database schema comparison component.
 * 
 * Allows users to:
 * 1. Select source and target data sources, databases, and schemas
 * 2. Configure comparison options (columns, indexes, foreign keys, case sensitivity)
 * 3. View comparison results with diff details and DDL statements
 * 4. Execute migration to apply changes to the target database
 */
const SchemaDiffPanel: React.FC = memo(() => {
  // Store state
  const {
    sourceDataSource, targetDataSource,
    sourceDatabase, targetDatabase,
    sourceSchema, targetSchema,
    compareOption, compareResult,
    comparing, selectedTableDiffs,
  } = useSchemaDiffStore();

  const connectionList = useConnectionStore((s) => s.connectionList);
  
  // Local state for database/schema lists and loading indicators
  const [sourceDatabases, setSourceDatabases] = useState<IDatabaseItem[]>([]);
  const [targetDatabases, setTargetDatabases] = useState<IDatabaseItem[]>([]);
  const [sourceSchemas, setSourceSchemas] = useState<ISchemaItem[]>([]);
  const [targetSchemas, setTargetSchemas] = useState<ISchemaItem[]>([]);
  const [sourceDbLoading, setSourceDbLoading] = useState(false);
  const [targetDbLoading, setTargetDbLoading] = useState(false);
  const [sourceSchemaLoading, setSourceSchemaLoading] = useState(false);
  const [targetSchemaLoading, setTargetSchemaLoading] = useState(false);

  // Load connection list on mount
  useEffect(() => {
    if (!connectionList) {
      getConnectionList();
    }
  }, [connectionList]);

  // Load databases when source data source changes
  useEffect(() => {
    if (sourceDataSource?.id) {
      setSourceDbLoading(true);
      setSourceDatabases([]);
      connectionService.getDatabaseList({ dataSourceId: sourceDataSource.id })
        .then((res) => {
          setSourceDatabases(Array.isArray(res) ? res : []);
        })
        .catch(() => {
          message.error(i18n('schemaDiff.loadDatabaseFail'));
        })
        .finally(() => {
          setSourceDbLoading(false);
        });
    } else {
      setSourceDatabases([]);
    }
  }, [sourceDataSource?.id]);

  useEffect(() => {
    if (targetDataSource?.id) {
      setTargetDbLoading(true);
      setTargetDatabases([]);
      connectionService.getDatabaseList({ dataSourceId: targetDataSource.id })
        .then((res) => {
          setTargetDatabases(Array.isArray(res) ? res : []);
        })
        .catch(() => {
          message.error(i18n('schemaDiff.loadDatabaseFail'));
        })
        .finally(() => {
          setTargetDbLoading(false);
        });
    } else {
      setTargetDatabases([]);
    }
  }, [targetDataSource?.id]);

  useEffect(() => {
    if (sourceDataSource?.id && sourceDatabase) {
      setSourceSchemaLoading(true);
      setSourceSchemas([]);
      connectionService.getSchemaList({ dataSourceId: sourceDataSource.id, databaseName: sourceDatabase })
        .then((res) => {
          setSourceSchemas(Array.isArray(res) ? res : []);
        })
        .catch(() => {
          message.error(i18n('schemaDiff.loadSchemaFail'));
        })
        .finally(() => {
          setSourceSchemaLoading(false);
        });
    } else {
      setSourceSchemas([]);
    }
  }, [sourceDataSource?.id, sourceDatabase]);

  useEffect(() => {
    if (targetDataSource?.id && targetDatabase) {
      setTargetSchemaLoading(true);
      setTargetSchemas([]);
      connectionService.getSchemaList({ dataSourceId: targetDataSource.id, databaseName: targetDatabase })
        .then((res) => {
          setTargetSchemas(Array.isArray(res) ? res : []);
        })
        .catch(() => {
          message.error(i18n('schemaDiff.loadSchemaFail'));
        })
        .finally(() => {
          setTargetSchemaLoading(false);
        });
    } else {
      setTargetSchemas([]);
    }
  }, [targetDataSource?.id, targetDatabase]);

  // Execute schema comparison
  const handleCompare = useCallback(async () => {
    if (!sourceDataSource || !targetDataSource || !sourceDatabase || !targetDatabase) {
      message.warning(i18n('schemaDiff.selectSource'));
      return;
    }
    setComparing(true);
    try {
      const result = await sqlService.compareSchema({
        sourceDataSourceId: sourceDataSource.id,
        sourceDatabaseName: sourceDatabase,
        sourceSchemaName: sourceSchema || undefined,
        targetDataSourceId: targetDataSource.id,
        targetDatabaseName: targetDatabase,
        targetSchemaName: targetSchema || undefined,
        compareOption,
      });
      setCompareResult(result);
      if (result?.tableDiffs) {
        const selected: Record<string, boolean> = {};
        result.tableDiffs.forEach((td) => {
          if (td.diffType !== 'UNCHANGED') {
            selected[td.tableName] = true;
          }
        });
        setSelectedTableDiffs(selected);
      }
    } catch (e: any) {
      message.error(e?.message || 'Compare failed');
    } finally {
      setComparing(false);
    }
  }, [sourceDataSource, targetDataSource, sourceDatabase, targetDatabase, sourceSchema, targetSchema, compareOption]);

  // Calculate total number of changes for display
  const totalChanges = compareResult
    ? (compareResult.summary?.tablesOnlyInSource || 0)
    + (compareResult.summary?.tablesOnlyInTarget || 0)
    + (compareResult.summary?.modifiedTables || 0)
    : 0;

  // Render: header with selectors, options, loading spinner, and results
  return (
    <div className={styles.schemaDiffPanel}>
      <div className={styles.header}>
        <div className={styles.sourceSelector}>
          <div className={styles.selectorLabel}>{i18n('schemaDiff.source')}</div>
          <Select
            showSearch
            placeholder={i18n('schemaDiff.selectSource')}
            value={sourceDataSource?.id}
            onChange={(id) => {
              const ds = connectionList?.find((c: IConnectionListItem) => c.id === id) || null;
              setSourceDataSource(ds ? { id: ds.id!, alias: ds.alias || '', dbType: ds.type || '' } : null);
            }}
            style={{ width: 200 }}
            optionFilterProp="label"
            options={(connectionList || []).map((c: IConnectionListItem) => ({
              label: c.alias,
              value: c.id,
            }))}
          />
          <Select
            placeholder={i18n('schemaDiff.selectDatabase')}
            value={sourceDatabase || undefined}
            onChange={(v) => setSourceDatabase(v || '')}
            style={{ width: 160, marginLeft: 8 }}
            loading={sourceDbLoading}
            options={(sourceDatabases || []).map((db: IDatabaseItem) => ({ label: db.name, value: db.name }))}
          />
          {sourceSchemas.length > 0 && (
            <Select
              placeholder={i18n('schemaDiff.selectSchema')}
              value={sourceSchema || undefined}
              onChange={(v) => setSourceSchema(v || '')}
              style={{ width: 140, marginLeft: 8 }}
              loading={sourceSchemaLoading}
              options={(sourceSchemas || []).map((s: ISchemaItem) => ({ label: s.name, value: s.name }))}
              allowClear
            />
          )}
        </div>
        <div className={styles.selectorArrow}>→</div>
        <div className={styles.targetSelector}>
          <div className={styles.selectorLabel}>{i18n('schemaDiff.target')}</div>
          <Select
            showSearch
            placeholder={i18n('schemaDiff.selectTarget')}
            value={targetDataSource?.id}
            onChange={(id) => {
              const ds = connectionList?.find((c: IConnectionListItem) => c.id === id) || null;
              setTargetDataSource(ds ? { id: ds.id!, alias: ds.alias || '', dbType: ds.type || '' } : null);
            }}
            style={{ width: 200 }}
            optionFilterProp="label"
            options={(connectionList || []).map((c: IConnectionListItem) => ({
              label: c.alias,
              value: c.id,
            }))}
          />
          <Select
            placeholder={i18n('schemaDiff.selectDatabase')}
            value={targetDatabase || undefined}
            onChange={(v) => setTargetDatabase(v || '')}
            style={{ width: 160, marginLeft: 8 }}
            loading={targetDbLoading}
            options={(targetDatabases || []).map((db: IDatabaseItem) => ({ label: db.name, value: db.name }))}
          />
          {targetSchemas.length > 0 && (
            <Select
              placeholder={i18n('schemaDiff.selectSchema')}
              value={targetSchema || undefined}
              onChange={(v) => setTargetSchema(v || '')}
              style={{ width: 140, marginLeft: 8 }}
              loading={targetSchemaLoading}
              options={(targetSchemas || []).map((s: ISchemaItem) => ({ label: s.name, value: s.name }))}
              allowClear
            />
          )}
        </div>
        <div className={styles.compareActions}>
          <Button type="primary" onClick={handleCompare} loading={comparing}>
            {comparing ? i18n('schemaDiff.comparing') : i18n('schemaDiff.compare')}
          </Button>
        </div>
      </div>

      <div className={styles.options}>
        <Checkbox checked={compareOption.compareColumn} onChange={(e) => setCompareOption({ ...compareOption, compareColumn: e.target.checked })}>
          {i18n('schemaDiff.compareColumn')}
        </Checkbox>
        <Checkbox checked={compareOption.compareIndex} onChange={(e) => setCompareOption({ ...compareOption, compareIndex: e.target.checked })}>
          {i18n('schemaDiff.compareIndex')}
        </Checkbox>
        <Checkbox checked={compareOption.compareForeignKey} onChange={(e) => setCompareOption({ ...compareOption, compareForeignKey: e.target.checked })}>
          {i18n('schemaDiff.compareForeignKey')}
        </Checkbox>
        <Checkbox checked={compareOption.compareTableOption} onChange={(e) => setCompareOption({ ...compareOption, compareTableOption: e.target.checked })}>
          {i18n('schemaDiff.compareTableOption')}
        </Checkbox>
        <Checkbox checked={compareOption.excludeDeprecated} onChange={(e) => setCompareOption({ ...compareOption, excludeDeprecated: e.target.checked })}>
          {i18n('schemaDiff.excludeDeprecated')}
        </Checkbox>
        <Checkbox checked={compareOption.caseSensitive} onChange={(e) => setCompareOption({ ...compareOption, caseSensitive: e.target.checked })}>
          {i18n('schemaDiff.caseSensitive')}
        </Checkbox>
        <Checkbox checked={compareOption.ignoreCharsetAlias} onChange={(e) => setCompareOption({ ...compareOption, ignoreCharsetAlias: e.target.checked })}>
          {i18n('schemaDiff.ignoreCharsetAlias')}
        </Checkbox>
        <Checkbox checked={compareOption.ignoreIntegerDisplayWidth} onChange={(e) => setCompareOption({ ...compareOption, ignoreIntegerDisplayWidth: e.target.checked })}>
          {i18n('schemaDiff.ignoreIntegerDisplayWidth')}
        </Checkbox>
        <Checkbox checked={compareOption.ignoreAutoIncrement} onChange={(e) => setCompareOption({ ...compareOption, ignoreAutoIncrement: e.target.checked })}>
          {i18n('schemaDiff.ignoreAutoIncrement')}
        </Checkbox>
      </div>

      {comparing && (
        <div className={styles.loadingContainer}>
          <Spin size="large" />
        </div>
      )}

      {compareResult && (
        <>
          <div className={styles.summaryBar}>
            <span className={styles.summaryItem}>
              {i18n('schemaDiff.totalTables')}: <strong>{compareResult.summary?.totalTables || 0}</strong>
            </span>
            <span className={classnames(styles.summaryItem, styles.added)}>
              +{i18n('schemaDiff.tablesAdded')}: <strong>{compareResult.summary?.tablesOnlyInSource || 0}</strong>
            </span>
            <span className={classnames(styles.summaryItem, styles.removed)}>
              -{i18n('schemaDiff.tablesRemoved')}: <strong>{compareResult.summary?.tablesOnlyInTarget || 0}</strong>
            </span>
            <span className={classnames(styles.summaryItem, styles.modified)}>
              ~{i18n('schemaDiff.tablesModified')}: <strong>{compareResult.summary?.modifiedTables || 0}</strong>
            </span>
            <span className={classnames(styles.summaryItem, styles.unchanged)}>
              {i18n('schemaDiff.tablesUnchanged')}: <strong>{compareResult.summary?.unchangedTables || 0}</strong>
            </span>
            {compareResult.summary?.excludedDeprecatedTables > 0 && (
              <span className={classnames(styles.summaryItem, styles.excluded)}>
                {i18n('schemaDiff.excluded')}: <strong>{compareResult.summary.excludedDeprecatedTables}</strong>
              </span>
            )}
          </div>

          <ResultPanel />
          <MigrationPanel />
        </>
      )}
    </div>
  );
});

export default SchemaDiffPanel;
