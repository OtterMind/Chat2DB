import { Checkbox, Select, Table, Tooltip } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { TriangleAlert } from 'lucide-react';
import ResizableColumns from '@/components/ResizableColumns';
import { ImportUnmappedTarget, SKIP_IMPORT_SOURCE_FIELD } from '@/constants/importExport';
import i18n from '@/i18n';
import type { IImportPreview } from '@/service/sql';
import { buildImportMappingRows, type IDuplicateImportMapping, type ImportMappingRow } from './mapping';
import { useStyles } from './style';

interface Props {
  preview: IImportPreview;
  mapping: Record<string, string>;
  duplicateMappings: Record<string, IDuplicateImportMapping>;
  blockedColumnNames: Set<string>;
  unmappedTarget: ImportUnmappedTarget;
  emptyAsNull: boolean;
  emptyAsNullLabel: string;
  loading: boolean;
  disabled: boolean;
  onMappingChange: (sourceColumn: string, targetColumn: string) => void;
  onUnmappedTargetChange: (value: ImportUnmappedTarget) => void;
  onEmptyAsNullChange: (value: boolean) => void;
}

export default function ImportDataPanels({
  preview, mapping, duplicateMappings, blockedColumnNames, unmappedTarget, emptyAsNull,
  emptyAsNullLabel, loading, disabled, onMappingChange, onUnmappedTargetChange, onEmptyAsNullChange,
}: Props) {
  const { styles, cx } = useStyles();
  const targetOptions = [
    { value: SKIP_IMPORT_SOURCE_FIELD, label: i18n('workspace.importExport.skipSourceField'), description: '' },
    ...preview.targetColumns.map((column) => ({
      value: column.name,
      label: column.name,
      description: `${column.dataType}${column.nullable ? '' : ', NOT NULL'}${
        column.comment ? ` · ${column.comment}` : ''
      }`,
    })),
  ];
  const mappingRows = buildImportMappingRows(preview.sourceColumns, preview.targetColumns, mapping);
  const columns: ColumnsType<ImportMappingRow<IImportPreview['targetColumns'][number]>> = [
    {
      title: i18n('workspace.importExport.sourceField'),
      width: 150,
      ellipsis: true,
      render: (_, record) => record.kind === 'source'
        ? record.sourceColumn
        : <span className={styles.unmappedSource}>{i18n('workspace.importExport.unmapped')}</span>,
    },
    {
      title: i18n('workspace.importExport.targetColumn'),
      width: 230,
      render: (_, record) => {
        if (record.kind === 'target') return record.targetColumn.name;
        const duplicate = duplicateMappings[record.sourceColumn];
        const warning = duplicate && i18n(
          'workspace.importExport.duplicateMappingContent', duplicate.targetColumn, duplicate.mappedSource,
        );
        return (
          <div className={styles.targetColumnCell}>
            {warning && (
              <span className={styles.mappingWarningSlot}>
                <Tooltip title={warning}>
                  <span className={styles.mappingWarningIcon} role="img" aria-label={warning}>
                    <TriangleAlert size={16} />
                  </span>
                </Tooltip>
              </span>
            )}
            <Select
              aria-label={`${record.sourceColumn} · ${i18n('workspace.importExport.targetColumn')}`}
              disabled={disabled || loading}
              className={cx(styles.targetColumnSelect, warning && styles.targetColumnSelectWarning)}
              value={mapping[record.sourceColumn]}
              options={targetOptions}
              optionRender={({ data }) => (
                <div>{data.label}<div className={styles.columnType}>{data.description}</div></div>
              )}
              onChange={(value) => onMappingChange(record.sourceColumn, value)}
            />
          </div>
        );
      },
    },
    {
      title: i18n('workspace.importExport.wizard.targetType'),
      width: 150,
      render: (_, record) => {
        if (record.kind === 'source' && mapping[record.sourceColumn] === SKIP_IMPORT_SOURCE_FIELD) {
          return <span className={styles.columnType}>{i18n('workspace.importExport.skipped')}</span>;
        }
        const column = record.kind === 'target' ? record.targetColumn
          : preview.targetColumns.find(({ name }) => name === mapping[record.sourceColumn]);
        return (
          <div>
            <span className={styles.columnType}>{column?.dataType ?? '—'}</span>
            {record.kind === 'target' && (
              <div
                className={blockedColumnNames.has(record.targetColumn.name)
                  ? styles.requiredStatus : styles.unmappedStatus}
              >
                {i18n(blockedColumnNames.has(record.targetColumn.name)
                  ? 'workspace.importExport.unmappedRequired' : 'workspace.importExport.unmapped')}
              </div>
            )}
          </div>
        );
      },
    },
  ];
  const previewColumns: ColumnsType<{ key: number; values: string[] }> = preview.sourceColumns.map(
    (column, index) => ({
      title: column,
      width: 180,
      ellipsis: true,
      render: (_, record) => record.values[index],
    }),
  );

  return (
    <div className={styles.dataPanels}>
      <ResizableColumns
        label={i18n('workspace.importExport.wizard.resizeColumns')}
        left={
          <section className={styles.dataPane} aria-label={i18n('workspace.importExport.fieldMapping')}>
            <div className={styles.paneTitle}>{i18n('workspace.importExport.fieldMapping')}</div>
            <Table
              className={styles.dataTable}
              size="small"
              rowKey="key"
              rowClassName={(record) => record.kind === 'target' ? styles.mappingRowUnmapped : ''}
              columns={columns}
              dataSource={mappingRows}
              loading={loading}
              pagination={false}
              tableLayout="fixed"
              scroll={{ x: 530, y: '100%' }}
            />
          </section>
        }
        right={
          <section className={styles.dataPane} aria-label={i18n('workspace.importExport.dataPreview', preview.previewLimit)}>
            <div className={styles.paneTitle}>
              {i18n('workspace.importExport.dataPreview', preview.previewLimit)}
            </div>
            <Table
              className={styles.dataTable}
              size="small"
              rowKey="key"
              columns={previewColumns}
              dataSource={preview.previewData.map((values, index) => ({ key: index, values }))}
              pagination={false}
              tableLayout="fixed"
              scroll={{ x: Math.max(540, preview.sourceColumns.length * 180), y: '100%' }}
            />
          </section>
        }
      />
      <div className={styles.mappingControls}>
        <Checkbox
          disabled={disabled || loading}
          checked={emptyAsNull}
          onChange={(event) => onEmptyAsNullChange(event.target.checked)}
        >
          {emptyAsNullLabel}
        </Checkbox>
        <label className={styles.mappingTargetPolicy}>
          <span className={styles.mappingControlLabel}>{i18n('workspace.importExport.wizard.summaryUnmapped')}</span>
          <Select
            aria-label={i18n('workspace.importExport.wizard.summaryUnmapped')}
            disabled={disabled || loading}
            className={styles.unmappedTargetSelect}
            value={unmappedTarget}
            onChange={onUnmappedTargetChange}
            options={[
              { value: ImportUnmappedTarget.DEFAULT, label: i18n('workspace.importExport.unmappedDefault') },
              { value: ImportUnmappedTarget.NULL, label: i18n('workspace.importExport.unmappedNull') },
            ]}
          />
        </label>
      </div>
    </div>
  );
}
