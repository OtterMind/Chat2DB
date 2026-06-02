import React, { memo, useMemo, useState, useCallback } from 'react';
import { Button, Modal, message, Tag } from 'antd';

import { i18n } from '@/i18n';
import sqlService from '@/service/sql';

import { useSchemaDiffStore, setMigrationExecuting, setMigrationResult, setSelectedTableDiffs } from '../store';
import styles from './index.less';

const MigrationPanel: React.FC = memo(() => {
  const {
    compareResult, selectedTableDiffs, migrationExecuting, migrationResult,
  } = useSchemaDiffStore();
  const [confirmVisible, setConfirmVisible] = useState(false);

  const pendingStatements = useMemo(() => {
    if (!compareResult?.tableDiffs) return [];
    const stmts: { tableName: string; sql: string }[] = [];
    for (const td of compareResult.tableDiffs) {
      if (selectedTableDiffs[td.tableName] && td.ddlStatement) {
        const ddlStatements = td.ddlStatements?.length ? td.ddlStatements : [td.ddlStatement];
        for (const ddlStatement of ddlStatements) {
          if (ddlStatement.trim().length > 0) {
            stmts.push({ tableName: td.tableName, sql: ddlStatement.trim() });
          }
        }
      }
    }
    return stmts;
  }, [compareResult, selectedTableDiffs]);

  const selectedCount = useMemo(
    () => Object.values(selectedTableDiffs).filter(Boolean).length,
    [selectedTableDiffs],
  );

  const allSelected = useMemo(
    () => {
      const changed = (compareResult?.tableDiffs || []).filter(td => td.diffType !== 'UNCHANGED');
      return changed.length > 0 && changed.every(td => selectedTableDiffs[td.tableName]);
    },
    [compareResult, selectedTableDiffs],
  );

  const handleSelectAll = useCallback(() => {
    const changed = (compareResult?.tableDiffs || []).filter(td => td.diffType !== 'UNCHANGED');
    const newSelected: Record<string, boolean> = {};
      changed.forEach(td => { newSelected[td.tableName] = !allSelected; });
      setSelectedTableDiffs(newSelected);
  }, [compareResult, allSelected]);

  const handleMigrate = useCallback(async () => {
    if (!compareResult) return;
    const stmts = pendingStatements.map(s => s.sql);
    if (stmts.length === 0) {
      message.warning('No statements to execute');
      return;
    }

    setMigrationExecuting(true);
    setMigrationResult(null);
    try {
      const targetDataSourceId = compareResult.targetKey?.split('.')[0]
        ? parseInt(compareResult.targetKey.split('.')[0]) : 0;
      const targetDatabaseName = compareResult.targetKey?.split('.')[1] || '';
      const result = await sqlService.migrateSchema({
        targetDataSourceId,
        targetDatabaseName,
        ddlStatements: stmts,
        continueOnError: true,
      });
      setMigrationResult(result);
      if (result.success) {
        message.success(i18n('schemaDiff.migrateSuccess'));
      } else {
        message.error(i18n('schemaDiff.migrateFail'));
      }
    } catch (e: any) {
      message.error(e?.message || 'Migration failed');
    } finally {
      setMigrationExecuting(false);
      setConfirmVisible(false);
    }
  }, [compareResult, pendingStatements]);

  const handleOpenConfirm = useCallback(() => {
    setConfirmVisible(true);
  }, []);

  return (
    <div className={styles.migrationPanel}>
      <div className={styles.migrationHeader}>
        <div className={styles.migrationTitle}>
          {i18n('schemaDiff.ddlPreview')}
          <span className={styles.selectedInfo}>
            {selectedCount} tables selected, {pendingStatements.length} statements
          </span>
        </div>
        <div className={styles.migrationActions}>
          <Button size="small" onClick={handleSelectAll}>
            {allSelected ? i18n('schemaDiff.deselectAll') : i18n('schemaDiff.selectAll')}
          </Button>
          <Button
            type="primary"
            size="small"
            onClick={handleOpenConfirm}
            disabled={pendingStatements.length === 0 || migrationExecuting}
            loading={migrationExecuting}
          >
            {migrationExecuting ? i18n('schemaDiff.migrating') : i18n('schemaDiff.migrate')}
          </Button>
        </div>
      </div>

      {pendingStatements.length > 0 && (
        <div className={styles.ddlList}>
          {pendingStatements.map((stmt, i) => (
            <div key={i} className={styles.ddlItem}>
              <span className={styles.ddlSeq}>{i + 1}</span>
              <span className={styles.ddlTable}>{stmt.tableName}</span>
              <code className={styles.ddlSql}>{stmt.sql}</code>
              {(() => {
                const r = migrationResult?.statementResults?.find(sr => sr.sequence === i + 1);
                return r ? (
                  <Tag color={r.success ? 'success' : 'error'}>
                    {r.success ? 'OK' : 'FAIL'}
                  </Tag>
                ) : null;
              })()}
            </div>
          ))}
        </div>
      )}

      {migrationResult && (
        <div className={styles.migrationResult}>
          <div className={styles.migrationResultHeader}>
            {i18n('schemaDiff.migrateResult')}:
            {i18n('schemaDiff.successCount')}: {migrationResult.successCount}
            {' | '}
            {i18n('schemaDiff.failCount')}: {migrationResult.failCount}
          </div>
          {migrationResult.statementResults.filter(r => !r.success).length > 0 && (
            <div className={styles.errorList}>
              {migrationResult.statementResults.filter(r => !r.success).map(r => (
                <div key={r.sequence} className={styles.errorItem}>
                  <strong>#{r.sequence}</strong> {r.sql?.substring(0, 100)}...
                  <div className={styles.errorMsg}>{r.errorMessage}</div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      <Modal
        title={i18n('schemaDiff.migrateConfirm').replace('{count}', String(pendingStatements.length))}
        open={confirmVisible}
        onOk={handleMigrate}
        onCancel={() => setConfirmVisible(false)}
        confirmLoading={migrationExecuting}
      >
        <p>{i18n('schemaDiff.migrateConfirmMessage').replace('{count}', String(pendingStatements.length))}</p>
      </Modal>
    </div>
  );
});

export default MigrationPanel;
