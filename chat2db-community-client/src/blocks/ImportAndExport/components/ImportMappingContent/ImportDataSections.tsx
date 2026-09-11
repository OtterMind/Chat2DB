import { Checkbox, Select, Table, Tooltip, type CollapseProps } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { TriangleAlert } from 'lucide-react';
import { ImportUnmappedTarget, SKIP_IMPORT_SOURCE_FIELD } from '@/constants/importExport';
import i18n from '@/i18n';
import type { IImportPreview } from '@/service/sql';
import type { ICsvOptions } from '@/typings/importExport';
import { buildImportMappingRows, type IDuplicateImportMapping, type ImportMappingRow } from './mapping';
import { useStyles } from './style';

interface Props {
  preview: IImportPreview | null;
  mapping: Record<string, string>;
  duplicateMappings: Record<string, IDuplicateImportMapping>;
  blockedColumnNames: Set<string>;
  unmappedTarget: ImportUnmappedTarget;
  csvOptions: ICsvOptions;
  isCsv: boolean;
  loading: boolean;
  onMappingChange: (sourceColumn: string, targetColumn: string) => void;
  onUnmappedTargetChange: (value: ImportUnmappedTarget) => void;
  onCsvOptionsChange: (value: ICsvOptions) => void;
}

const useImportDataSections = (props: Props): CollapseProps['items'] => {
  const { styles, cx } = useStyles();
  const {
    preview,
    mapping,
    duplicateMappings,
    blockedColumnNames,
    unmappedTarget,
    csvOptions,
    isCsv,
    loading,
    onMappingChange,
    onUnmappedTargetChange,
    onCsvOptionsChange,
  } = props;
  if (!preview) {
    return [];
  }
  const targetOptions = [
    { value: SKIP_IMPORT_SOURCE_FIELD, label: i18n('workspace.importExport.skipSourceField') },
    ...preview.targetColumns.map((column) => ({
      value: column.name,
      label: `${column.name} (${column.dataType}${column.nullable ? '' : ', NOT NULL'})${
        column.comment ? ` - ${column.comment}` : ''
      }`,
    })),
  ];
  const mappingRows = buildImportMappingRows(preview.sourceColumns, preview.targetColumns, mapping);
  const columns: ColumnsType<ImportMappingRow<IImportPreview['targetColumns'][number]>> = [
    {
      title: i18n('workspace.importExport.sourceField'),
      width: '28%',
      render: (_, record) =>
        record.kind === 'source' ? (
          record.sourceColumn
        ) : (
          <span className={styles.unmappedSource}>{i18n('workspace.importExport.unmapped')}</span>
        ),
    },
    {
      title: i18n('workspace.importExport.targetColumn'),
      width: '52%',
      render: (_, record) => {
        if (record.kind === 'target') {
          return targetOptions.find(({ value }) => value === record.targetColumn.name)?.label;
        }
        const duplicate = duplicateMappings[record.sourceColumn];
        const warning = duplicate
          ? i18n('workspace.importExport.duplicateMappingContent', duplicate.targetColumn, duplicate.mappedSource)
          : undefined;
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
              className={cx(styles.targetColumnSelect, warning && styles.targetColumnSelectWarning)}
              value={mapping[record.sourceColumn]}
              options={targetOptions}
              onChange={(value) => onMappingChange(record.sourceColumn, value)}
            />
          </div>
        );
      },
    },
    {
      title: i18n('workspace.importExport.mappingStatus'),
      width: '20%',
      render: (_, record) => {
        if (record.kind === 'source') {
          return mapping[record.sourceColumn] === SKIP_IMPORT_SOURCE_FIELD
            ? i18n('workspace.importExport.skipped')
            : i18n('workspace.importExport.mapped');
        }
        if (blockedColumnNames.has(record.targetColumn.name)) {
          return <span className={styles.requiredStatus}>{i18n('workspace.importExport.unmappedRequired')}</span>;
        }
        if (record.targetColumn.autoIncrement) {
          return i18n('workspace.importExport.unmappedAutoIncrement');
        }
        return unmappedTarget === ImportUnmappedTarget.NULL
          ? i18n('workspace.importExport.unmappedNullValue')
          : i18n('workspace.importExport.unmappedDefaultValue');
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
  const previewData = preview.previewData.map((values, index) => ({ key: index, values }));

  return [
    {
      key: 'mapping',
      label: i18n('workspace.importExport.fieldMapping'),
      children: (
        <>
          <div className={styles.mappingControls}>
            {isCsv && (
              <Checkbox
                checked={csvOptions.emptyAsNull}
                onChange={(event) => onCsvOptionsChange({ ...csvOptions, emptyAsNull: event.target.checked })}
              >
                {i18n('workspace.importExport.emptyAsNull')}
              </Checkbox>
            )}
            <Select
              className={styles.unmappedTargetSelect}
              value={unmappedTarget}
              onChange={onUnmappedTargetChange}
              options={[
                { value: ImportUnmappedTarget.DEFAULT, label: i18n('workspace.importExport.unmappedDefault') },
                { value: ImportUnmappedTarget.NULL, label: i18n('workspace.importExport.unmappedNull') },
              ]}
            />
          </div>
          <Table
            className={cx(styles.mappingTable, styles.scrollableTable)}
            size="small"
            rowKey="key"
            columns={columns}
            dataSource={mappingRows}
            loading={loading}
            pagination={false}
            tableLayout="fixed"
            scroll={{ y: 220 }}
          />
        </>
      ),
    },
    {
      key: 'preview',
      label: i18n('workspace.importExport.dataPreview', preview.previewLimit),
      children: (
        <Table
          className={styles.scrollableTable}
          size="small"
          rowKey="key"
          columns={previewColumns}
          dataSource={previewData}
          pagination={false}
          scroll={{ x: 'max-content', y: 190 }}
        />
      ),
    },
  ] satisfies CollapseProps['items'];
};

export default useImportDataSections;
