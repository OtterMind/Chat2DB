import React, { memo, useMemo } from 'react';
import classnames from 'classnames';

import { i18n } from '@/i18n';


import { useSchemaDiffStore, setSelectedTableDiffs, setDetailViewTableName } from '../store';
import DetailView from './DetailView';
import styles from './index.less';

const diffTypeLabel: Record<string, string> = {
  ADDED: 'schemaDiff.added',
  REMOVED: 'schemaDiff.removed',
  MODIFIED: 'schemaDiff.modified',
  UNCHANGED: 'schemaDiff.unchanged',
};

const diffTypeClass: Record<string, string> = {
  ADDED: styles.added,
  REMOVED: styles.removed,
  MODIFIED: styles.modified,
  UNCHANGED: styles.unchanged,
};

const ResultPanel: React.FC = memo(() => {
  const { compareResult, selectedTableDiffs, detailViewTableName } = useSchemaDiffStore();

  const tableDiffs = useMemo(() => compareResult?.tableDiffs || [], [compareResult]);

  const visibleDiffs = useMemo(
    () => tableDiffs.filter((td) => td.diffType !== 'UNCHANGED'),
    [tableDiffs],
  );

  const selectedTable = useMemo(
    () => visibleDiffs.find((td) => td.tableName === detailViewTableName) || null,
    [visibleDiffs, detailViewTableName],
  );

  const handleToggleSelect = (tableName: string) => {
    setSelectedTableDiffs({
      ...selectedTableDiffs,
      [tableName]: !selectedTableDiffs[tableName],
    });
  };

  return (
    <div className={styles.resultPanel}>
      <div className={styles.tableList}>
        <div className={styles.listHeader}>
          {i18n('schemaDiff.table')}
          <span className={styles.count}>({visibleDiffs.length})</span>
        </div>
        {visibleDiffs.map((td) => {
          const ddlLength = td.ddlStatements?.length || (td.ddlStatement ? 1 : 0);
          return (
            <div
              key={td.tableName}
              className={classnames(styles.tableItem, {
                [styles.active]: detailViewTableName === td.tableName,
              })}
              onClick={() => setDetailViewTableName(td.tableName)}
            >
              <div className={styles.tableItemHeader}>
                <input
                  type="checkbox"
                  checked={!!selectedTableDiffs[td.tableName]}
                  onClick={(e) => e.stopPropagation()}
                  onChange={() => handleToggleSelect(td.tableName)}
                />
                <span className={classnames(styles.diffBadge, diffTypeClass[td.diffType])}>
                  {i18n(diffTypeLabel[td.diffType])}
                </span>
                <span className={styles.tableItemName}>{td.tableName}</span>
              </div>
              <div className={styles.tableItemMeta}>
                {td.columnDiffs?.length ? <span>Col: {td.columnDiffs.length}</span> : null}
                {td.indexDiffs?.length ? <span>Idx: {td.indexDiffs.length}</span> : null}
                {td.foreignKeyDiffs?.length ? <span>FK: {td.foreignKeyDiffs.length}</span> : null}
                {ddlLength > 0 ? <span>DDL: {ddlLength} stmts</span> : null}
              </div>
            </div>
          );
        })}
        {visibleDiffs.length === 0 && (
          <div className={styles.noData}>{i18n('schemaDiff.noChanges')}</div>
        )}
      </div>
      <div className={styles.detailArea}>
        {selectedTable ? <DetailView tableDiff={selectedTable} /> : (
          <div className={styles.noSelection}>
            {i18n('schemaDiff.table')}
          </div>
        )}
      </div>
    </div>
  );
});

export default ResultPanel;
