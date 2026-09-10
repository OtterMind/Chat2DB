import { Checkbox, Collapse, InputNumber, Select, type CollapseProps } from 'antd';
import type { ICsvOptions } from '@/typings/importExport';
import i18n from '@/i18n';
import LocalFileEncodingSelect from '@/components/LocalFileEncodingSelect';
import SingleCharacterSelect from '@/components/SingleCharacterSelect';
import { getCsvDateTimeExamples } from '../../utils/csvOptions';
import { useStyles } from './style';

interface Props {
  value: ICsvOptions;
  activeKeys: string[];
  disabled?: boolean;
  dataItems: CollapseProps['items'];
  onChange: (value: ICsvOptions) => void;
  onActiveKeysChange: (keys: string[]) => void;
}

const CsvOptionsSections = ({ value, activeKeys, disabled, dataItems, onChange, onActiveKeysChange }: Props) => {
  const { styles } = useStyles();
  const update = (patch: Partial<ICsvOptions>) => onChange({ ...value, ...patch });
  const characterProps = {
    className: styles.csvOptionField,
    customInputClassName: styles.customCharacterInput,
    customOptionLabel: (character: string) => i18n('workspace.importExport.customCharacterValue', character),
    customInputLabel: i18n('workspace.importExport.customCharacter'),
    disabled,
  };

  return (
    <Collapse
      className={styles.sections}
      ghost
      size="small"
      activeKey={activeKeys}
      onChange={(keys) => onActiveKeysChange(Array.isArray(keys) ? keys : [keys])}
      items={[
        {
          key: 'csvFormat',
          label: i18n('workspace.importExport.csvFormat'),
          children: (
            <div className={styles.csvFormatOptions}>
              <div className={styles.csvOptionField}>
                <span>{i18n('workspace.importExport.encoding')}</span>
                <LocalFileEncodingSelect
                  className={styles.fullWidthControl}
                  charset={value.encoding === 'AUTO' ? undefined : value.encoding}
                  disabled={disabled}
                  size="middle"
                  variant="outlined"
                  onEncodingChange={async (encoding) => update({ encoding: encoding || 'AUTO' })}
                />
              </div>
              <SingleCharacterSelect
                {...characterProps}
                label={i18n('workspace.importExport.delimiter')}
                value={value.delimiter}
                options={[
                  { value: ',', label: i18n('workspace.importExport.delimiterComma') },
                  { value: ';', label: i18n('workspace.importExport.delimiterSemicolon') },
                  { value: '\t', label: i18n('workspace.importExport.delimiterTab') },
                  { value: '|', label: i18n('workspace.importExport.delimiterPipe') },
                ]}
                onChange={(delimiter) => update({ delimiter })}
              />
              <SingleCharacterSelect
                {...characterProps}
                label={i18n('workspace.importExport.textQualifier')}
                value={value.quote}
                options={[
                  { value: '"', label: i18n('workspace.importExport.quoteDouble') },
                  { value: "'", label: i18n('workspace.importExport.quoteSingle') },
                  { value: '`', label: i18n('workspace.importExport.quoteBacktick') },
                  { value: '~', label: i18n('workspace.importExport.quoteTilde') },
                ]}
                onChange={(quote) => update({ quote, escape: value.escape === value.quote ? quote : value.escape })}
              />
              <SingleCharacterSelect
                {...characterProps}
                label={i18n('workspace.importExport.escapeMethod')}
                value={value.escape}
                options={[
                  { value: value.quote, label: i18n('workspace.importExport.escapeRepeatedQualifier') },
                  { value: '\\', label: i18n('workspace.importExport.escapeBackslash') },
                ]}
                onChange={(escape) => update({ escape })}
              />
            </div>
          ),
        },
        {
          key: 'sourceRows',
          label: i18n('workspace.importExport.sourceRows'),
          children: (
            <div className={styles.sourceRowOptions}>
              <Checkbox
                className={styles.sourceRowHasHeader}
                checked={value.hasHeader}
                disabled={disabled}
                onChange={(event) => {
                  const hasHeader = event.target.checked;
                  const dataStartRow = hasHeader ? Math.max(value.dataStartRow, value.headerRow + 1) : 1;
                  update({
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
                  min={1}
                  precision={0}
                  disabled={disabled || !value.hasHeader}
                  value={value.headerRow}
                  onChange={(headerRow) => {
                    if (headerRow === null) return;
                    const dataStartRow = Math.max(value.dataStartRow, headerRow + 1);
                    update({
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
                    update({
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
                  onChange={(dataEndRow) => update({ dataEndRow: dataEndRow === null ? undefined : dataEndRow })}
                />
              </label>
            </div>
          ),
        },
        {
          key: 'formats',
          label: i18n('workspace.importExport.dateTimeFormats'),
          children: (
            <div className={styles.formatOptions}>
              <label className={styles.csvOptionField}>
                <span>{i18n('workspace.importExport.dateOrder')}</span>
                <Select
                  disabled={disabled}
                  value={value.dateOrder}
                  options={['MDY', 'DMY', 'YMD', 'YDM', 'DYM', 'MYD'].map((order) => ({ value: order, label: order }))}
                  onChange={(dateOrder) => update({ dateOrder })}
                />
              </label>
              <label className={styles.csvOptionField}>
                <span>{i18n('workspace.importExport.dateTimeOrder')}</span>
                <Select
                  disabled={disabled}
                  value={value.dateTimeOrder}
                  options={[
                    { value: 'DATE_TIME', label: i18n('workspace.importExport.dateFirst') },
                    { value: 'TIME_DATE', label: i18n('workspace.importExport.timeFirst') },
                    { value: 'DATE_TIME_TIMEZONE', label: i18n('workspace.importExport.dateTimeTimezone') },
                    { value: 'TIME_DATE_TIMEZONE', label: i18n('workspace.importExport.timeDateTimezone') },
                    { value: 'TIME_TIMEZONE_DATE', label: i18n('workspace.importExport.timeTimezoneDate') },
                  ]}
                  onChange={(dateTimeOrder) => update({ dateTimeOrder })}
                />
              </label>
              <SingleCharacterSelect
                {...characterProps}
                label={i18n('workspace.importExport.dateDelimiter')}
                value={value.dateDelimiter}
                options={dateDelimiterOptions()}
                onChange={(dateDelimiter) => update({ dateDelimiter })}
              />
              <SingleCharacterSelect
                {...characterProps}
                label={i18n('workspace.importExport.yearDelimiter')}
                value={value.yearDelimiter}
                options={dateDelimiterOptions()}
                onChange={(yearDelimiter) => update({ yearDelimiter })}
              />
              <SingleCharacterSelect
                {...characterProps}
                label={i18n('workspace.importExport.timeDelimiter')}
                value={value.timeDelimiter}
                options={[
                  { value: ':', label: i18n('workspace.importExport.delimiterColon') },
                  { value: '.', label: i18n('workspace.importExport.delimiterDot') },
                ]}
                onChange={(timeDelimiter) => update({ timeDelimiter })}
              />
              <SingleCharacterSelect
                {...characterProps}
                allowCustom={false}
                label={i18n('workspace.importExport.decimalSymbol')}
                value={value.decimalSymbol}
                options={[
                  { value: '.', label: i18n('workspace.importExport.delimiterDot') },
                  { value: ',', label: i18n('workspace.importExport.delimiterComma') },
                ]}
                onChange={(decimalSymbol) => update({ decimalSymbol })}
              />
              <div className={styles.dateExamples}>
                <span>{i18n('workspace.importExport.dateTimeExample')}</span>
                {getCsvDateTimeExamples(value).map((example) => (
                  <code key={example}>{example}</code>
                ))}
              </div>
            </div>
          ),
        },
        ...(dataItems || []),
      ]}
    />
  );
};

const dateDelimiterOptions = () => [
  { value: '-', label: i18n('workspace.importExport.delimiterDash') },
  { value: '/', label: i18n('workspace.importExport.delimiterSlash') },
  { value: '.', label: i18n('workspace.importExport.delimiterDot') },
];

export default CsvOptionsSections;
