import { useEffect, useState } from 'react';
import { Input, Select, type CollapseProps } from 'antd';
import type { IExcelOptions } from '@/typings/importExport';
import i18n from '@/i18n';
import OptionsSections from './OptionsSections';
import SourceRowsFields from './SourceRowsFields';
import ValueFormatFields from './ValueFormatFields';
import { useStyles } from './style';

interface Props {
  value: IExcelOptions;
  sheets: string[];
  disabled: boolean;
  activeKeys: string[];
  onActiveKeysChange: (keys: string[]) => void;
  dataItems: CollapseProps['items'];
  onChange: (value: IExcelOptions) => void;
}

export default function ExcelOptionsSections({
  value,
  sheets,
  disabled,
  activeKeys,
  onActiveKeysChange,
  dataItems,
  onChange,
}: Props) {
  const { styles } = useStyles();
  const update = (patch: Partial<IExcelOptions>) => onChange({ ...value, ...patch });
  const [columnRange, setColumnRange] = useState(value.columnRange);
  useEffect(() => {
    setColumnRange(value.columnRange);
  }, [value.columnRange]);
  const commitColumnRange = () => {
    const next = columnRange.trim();
    if (next !== value.columnRange) {
      update({ columnRange: next });
    }
  };
  return (
    <OptionsSections
      activeKeys={activeKeys}
      onActiveKeysChange={onActiveKeysChange}
      items={[
        {
          key: 'sourceRows',
          label: i18n('workspace.importExport.sourceRows'),
          children: (
            <>
              <div className={styles.csvFormatOptions}>
                <label className={styles.csvOptionField}>
                  <span>{i18n('workspace.importExport.sheet')}</span>
                  <Select
                    aria-label={i18n('workspace.importExport.sheet')}
                    disabled={disabled}
                    value={value.sheetIndex}
                    options={sheets.map((label, index) => ({ label, value: index }))}
                    onChange={(sheetIndex) => update({ sheetIndex })}
                  />
                </label>
                <label className={styles.csvOptionField}>
                  <span>{i18n('workspace.importExport.columnRange')}</span>
                  <Input
                    disabled={disabled}
                    placeholder="A:H"
                    value={columnRange}
                    onChange={(event) => setColumnRange(event.target.value)}
                    onBlur={commitColumnRange}
                    onPressEnter={commitColumnRange}
                  />
                </label>
              </div>
              <SourceRowsFields value={value} disabled={disabled} onChange={update} />
            </>
          ),
        },
        {
          key: 'formats',
          label: i18n('workspace.importExport.dateTimeFormats'),
          children: <ValueFormatFields value={value} disabled={disabled} onChange={update} />,
        },
        ...(dataItems || []),
      ]}
    />
  );
}
