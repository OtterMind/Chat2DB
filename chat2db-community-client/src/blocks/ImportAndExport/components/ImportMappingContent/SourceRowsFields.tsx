import { Checkbox, InputNumber } from 'antd';
import type { ISourceRowOptions } from '@/typings/importExport';
import i18n from '@/i18n';
import { useStyles } from './style';

interface Props {
  value: ISourceRowOptions;
  disabled?: boolean;
  onChange: (patch: Partial<ISourceRowOptions>) => void;
}

export default function SourceRowsFields({ value, disabled, onChange }: Props) {
  const { styles } = useStyles();
  return (
    <div className={styles.sourceRowOptions}>
      <Checkbox
        className={styles.sourceRowHasHeader}
        checked={value.hasHeader}
        disabled={disabled}
        onChange={(event) => {
          const hasHeader = event.target.checked;
          const dataStartRow = hasHeader ? Math.max(value.dataStartRow, value.headerRow + 1) : 1;
          onChange({
            hasHeader,
            dataStartRow,
            dataEndRow:
              hasHeader && value.dataEndRow && value.dataEndRow < dataStartRow
                ? dataStartRow
                : value.dataEndRow,
          });
        }}
      >
        {i18n('workspace.importExport.hasHeader')}
      </Checkbox>
      <div className={styles.csvOptionField}>
        <span>{i18n('workspace.importExport.headerRow')}</span>
        <InputNumber
          aria-label={i18n('workspace.importExport.headerRow')}
          min={1}
          precision={0}
          disabled={disabled || !value.hasHeader}
          value={value.headerRow}
          onChange={(headerRow) => {
            if (headerRow === null) return;
            const dataStartRow = Math.max(value.dataStartRow, headerRow + 1);
            onChange({
              headerRow,
              dataStartRow,
              dataEndRow:
                value.dataEndRow && value.dataEndRow < dataStartRow ? dataStartRow : value.dataEndRow,
            });
          }}
        />
      </div>
      <label className={styles.csvOptionField}>
        <span>{i18n('workspace.importExport.dataStartRow')}</span>
        <InputNumber
          min={value.hasHeader ? value.headerRow + 1 : 1}
          precision={0}
          disabled={disabled}
          value={value.dataStartRow}
          onChange={(dataStartRow) => {
            if (dataStartRow === null) return;
            onChange({
              dataStartRow,
              dataEndRow:
                value.dataEndRow && value.dataEndRow < dataStartRow ? dataStartRow : value.dataEndRow,
            });
          }}
        />
      </label>
      <label className={styles.csvOptionField}>
        <span>{i18n('workspace.importExport.dataEndRow')}</span>
        <InputNumber
          min={value.dataStartRow}
          precision={0}
          disabled={disabled}
          placeholder={i18n('workspace.importExport.endOfFile')}
          value={value.dataEndRow}
          onChange={(dataEndRow) => onChange({ dataEndRow: dataEndRow === null ? undefined : dataEndRow })}
        />
      </label>
    </div>
  );
}
