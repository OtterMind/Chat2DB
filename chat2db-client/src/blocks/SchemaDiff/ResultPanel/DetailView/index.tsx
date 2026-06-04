import React, { memo, useMemo, useState } from 'react';
import { Button, Modal, Space, Table, Tabs, Tag } from 'antd';

import { i18n } from '@/i18n';
import { IColumnDiff, IFieldDiff, IForeignKeyDiff, IIndexDiff, ITableDiff } from '@/typings/schemaDiff';
import styles from './index.less';

interface DetailViewProps {
  tableDiff: ITableDiff;
}

const changeTypeColor: Record<string, string> = {
  ADD: 'success',
  MODIFY: 'warning',
  DELETE: 'error',
};

const changeTypeLabel: Record<string, string> = {
  ADD: i18n('schemaDiff.added'),
  MODIFY: i18n('schemaDiff.modified'),
  DELETE: i18n('schemaDiff.removed'),
};

interface IDiffRow {
  key: string;
  changeType: string;
  name?: string;
  changedFields?: IFieldDiff[];
  [key: string]: any;
}

const formatDiffValue = (value?: string) => (value === undefined || value === null ? '-' : value);

const diffColumns = [
  { title: i18n('schemaDiff.diffField'), dataIndex: 'fieldName', key: 'fieldName', width: 160 },
  {
    title: i18n('schemaDiff.sourceValue'),
    dataIndex: 'sourceValue',
    key: 'sourceValue',
    ellipsis: true,
    render: formatDiffValue,
  },
  {
    title: i18n('schemaDiff.targetValue'),
    dataIndex: 'targetValue',
    key: 'targetValue',
    ellipsis: true,
    render: formatDiffValue,
  },
];

const buildOperationColumn = (onViewDiff: (row: IDiffRow) => void) => ({
  title: i18n('schemaDiff.operation'),
  dataIndex: 'changeType',
  key: 'changeType',
  width: 118,
  render: (v: string, row: IDiffRow) => (
    <Space size={4} className={styles.operationCell}>
      <Tag color={changeTypeColor[v]}>{changeTypeLabel[v]}</Tag>
      {row.changedFields?.length ? (
        <Button type="link" size="small" onClick={() => onViewDiff(row)}>
          {i18n('schemaDiff.viewDiff')}
        </Button>
      ) : null}
    </Space>
  ),
});

const buildColumnColumns = (onViewDiff: (row: IDiffRow) => void) => [
  buildOperationColumn(onViewDiff),
  { title: i18n('schemaDiff.columnName'), dataIndex: 'name', key: 'name', width: 140 },
  { title: i18n('schemaDiff.columnType'), dataIndex: 'columnType', key: 'columnType', width: 120 },
  { title: 'Size', dataIndex: 'size', key: 'size', width: 60 },
  {
    title: i18n('schemaDiff.nullable'),
    dataIndex: 'nullable',
    key: 'nullable',
    width: 60,
    render: (v: any) => (v ? 'YES' : 'NO'),
  },
  { title: i18n('schemaDiff.defaultValue'), dataIndex: 'defaultValue', key: 'defaultValue', width: 100 },
  { title: i18n('schemaDiff.comment'), dataIndex: 'comment', key: 'comment', ellipsis: true },
];

const buildIndexColumns = (onViewDiff: (row: IDiffRow) => void) => [
  buildOperationColumn(onViewDiff),
  { title: i18n('schemaDiff.indexName'), dataIndex: 'name', key: 'name', width: 140 },
  { title: i18n('schemaDiff.indexType'), dataIndex: 'indexType', key: 'indexType', width: 100 },
  {
    title: i18n('schemaDiff.unique'),
    dataIndex: 'unique',
    key: 'unique',
    width: 60,
    render: (v: any) => (v ? 'YES' : 'NO'),
  },
];

const buildFkColumns = (onViewDiff: (row: IDiffRow) => void) => [
  buildOperationColumn(onViewDiff),
  { title: i18n('schemaDiff.foreignKeyName'), dataIndex: 'name', key: 'name', width: 140 },
  { title: i18n('schemaDiff.referencedTable'), dataIndex: 'refTable', key: 'refTable', width: 120 },
  { title: i18n('schemaDiff.referencedColumn'), dataIndex: 'refColumn', key: 'refColumn', width: 120 },
];

function buildColumnRows(diffs: IColumnDiff[]): IDiffRow[] {
  return (diffs || []).map((d) => {
    const col = d.targetColumn || d.sourceColumn || {};
    return {
      key: `${d.changeType}-${col.name}`,
      changeType: d.changeType,
      name: col.name,
      columnType: col.dataType || col.columnType || '-',
      size: col.columnSize,
      nullable: col.nullable,
      defaultValue: col.defaultValue,
      comment: col.comment,
      changedFields: d.changedFields,
    };
  });
}

function buildIndexRows(diffs: IIndexDiff[]): IDiffRow[] {
  return (diffs || []).map((d) => {
    const idx = d.targetIndex || d.sourceIndex || {};
    return {
      key: `${d.changeType}-${idx.name}`,
      changeType: d.changeType,
      name: idx.name,
      indexType: idx.type,
      unique: idx.unique,
      changedFields: d.changedFields,
    };
  });
}

function buildFkRows(diffs: IForeignKeyDiff[]): IDiffRow[] {
  return (diffs || []).map((d) => {
    const fk = d.targetForeignKey || d.sourceForeignKey || {};
    return {
      key: `${d.changeType}-${fk.name}`,
      changeType: d.changeType,
      name: fk.name,
      refTable: fk.referencedTable,
      refColumn: fk.referencedColumn,
      changedFields: d.changedFields,
    };
  });
}

const DetailView: React.FC<DetailViewProps> = memo(({ tableDiff }) => {
  const [currentDiff, setCurrentDiff] = useState<IDiffRow | null>(null);
  const hasColumns = tableDiff.columnDiffs && tableDiff.columnDiffs.length > 0;
  const hasIndexes = tableDiff.indexDiffs && tableDiff.indexDiffs.length > 0;
  const hasFKs = tableDiff.foreignKeyDiffs && tableDiff.foreignKeyDiffs.length > 0;
  const hasTableOptions = !!tableDiff.tableOptionDiffs?.length;
  const hasDdl = tableDiff.ddlStatement;
  const columnColumns = useMemo(() => buildColumnColumns(setCurrentDiff), []);
  const indexColumns = useMemo(() => buildIndexColumns(setCurrentDiff), []);
  const fkColumns = useMemo(() => buildFkColumns(setCurrentDiff), []);

  if (!hasColumns && !hasIndexes && !hasFKs && !hasTableOptions && !hasDdl) {
    return <div className={styles.empty}>{i18n('schemaDiff.noChanges')}</div>;
  }

  const tabItems = [];
  if (hasColumns) {
    tabItems.push({
      key: 'columns',
      label: `${i18n('schemaDiff.columns')} (${tableDiff.columnDiffs!.length})`,
      children: (
        <Table
          dataSource={buildColumnRows(tableDiff.columnDiffs!)}
          columns={columnColumns}
          size="small"
          pagination={false}
          bordered
        />
      ),
    });
  }
  if (hasIndexes) {
    tabItems.push({
      key: 'indexes',
      label: `${i18n('schemaDiff.indexes')} (${tableDiff.indexDiffs!.length})`,
      children: (
        <Table
          dataSource={buildIndexRows(tableDiff.indexDiffs!)}
          columns={indexColumns}
          size="small"
          pagination={false}
          bordered
        />
      ),
    });
  }
  if (hasFKs) {
    tabItems.push({
      key: 'foreignKeys',
      label: `${i18n('schemaDiff.foreignKeys')} (${tableDiff.foreignKeyDiffs!.length})`,
      children: (
        <Table
          dataSource={buildFkRows(tableDiff.foreignKeyDiffs!)}
          columns={fkColumns}
          size="small"
          pagination={false}
          bordered
        />
      ),
    });
  }
  if (hasTableOptions) {
    tabItems.push({
      key: 'tableOptions',
      label: `${i18n('schemaDiff.tableOptions')} (${tableDiff.tableOptionDiffs!.length})`,
      children: (
        <Table
          dataSource={tableDiff.tableOptionDiffs}
          columns={diffColumns}
          rowKey="fieldName"
          size="small"
          pagination={false}
          bordered
        />
      ),
    });
  }
  if (hasDdl) {
    tabItems.push({
      key: 'ddl',
      label: i18n('schemaDiff.ddlPreview'),
      children: (
        <pre className={styles.ddlBlock}>{tableDiff.ddlStatement}</pre>
      ),
    });
  }

  return (
    <div className={styles.detailView}>
      <div className={styles.detailHeader}>
        <span className={styles.tableName}>{tableDiff.tableName}</span>
        <Tag color={tableDiff.diffType === 'MODIFIED' ? 'warning' : tableDiff.diffType === 'ADDED' ? 'success' : 'error'}>
          {i18n(tableDiff.diffType === 'MODIFIED' ? 'schemaDiff.modified' : tableDiff.diffType === 'ADDED' ? 'schemaDiff.added' : 'schemaDiff.removed')}
        </Tag>
      </div>
      <Tabs items={tabItems} size="small" />
      <Modal
        title={`${currentDiff?.name || ''} ${i18n('schemaDiff.diffProperties')}`}
        open={!!currentDiff}
        footer={null}
        width={720}
        onCancel={() => setCurrentDiff(null)}
      >
        <Table
          dataSource={currentDiff?.changedFields || []}
          columns={diffColumns}
          rowKey="fieldName"
          size="small"
          pagination={false}
          bordered
        />
      </Modal>
    </div>
  );
});

export default DetailView;
