import { Select } from 'antd';
import type { IImportValueOptions } from '@/typings/importExport';
import i18n from '@/i18n';
import SingleCharacterSelect from '@/components/SingleCharacterSelect';
import { getCsvDateTimeExamples } from '../../utils/csvOptions';
import { useStyles } from './style';

interface Props {
  value: IImportValueOptions;
  disabled?: boolean;
  withDecimal?: boolean;
  withTimeZone?: boolean;
  onChange: (patch: Partial<IImportValueOptions>) => void;
}

export default function ValueFormatFields({
  value,
  disabled,
  withDecimal = true,
  withTimeZone = false,
  onChange,
}: Props) {
  const { styles } = useStyles();
  const characterProps = {
    className: styles.csvOptionField,
    customInputClassName: styles.customCharacterInput,
    customOptionLabel: (character: string) => i18n('workspace.importExport.customCharacterValue', character),
    customInputLabel: i18n('workspace.importExport.customCharacter'),
    disabled,
  };
  return (
    <div className={styles.formatOptions}>
      <label className={styles.csvOptionField}>
        <span>{i18n('workspace.importExport.dateOrder')}</span>
        <Select
          disabled={disabled}
          value={value.dateOrder}
          options={['MDY', 'DMY', 'YMD', 'YDM', 'DYM', 'MYD'].map((order) => ({
            value: order,
            label: order,
          }))}
          onChange={(dateOrder) => onChange({ dateOrder })}
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
            ...(withTimeZone
              ? [
                  { value: 'DATE_TIME_TIMEZONE', label: i18n('workspace.importExport.dateTimeTimezone') },
                  { value: 'TIME_DATE_TIMEZONE', label: i18n('workspace.importExport.timeDateTimezone') },
                  { value: 'TIME_TIMEZONE_DATE', label: i18n('workspace.importExport.timeTimezoneDate') },
                ]
              : []),
          ]}
          onChange={(dateTimeOrder) => onChange({ dateTimeOrder })}
        />
      </label>
      <SingleCharacterSelect
        {...characterProps}
        label={i18n('workspace.importExport.dateDelimiter')}
        value={value.dateDelimiter}
        options={dateDelimiterOptions()}
        onChange={(dateDelimiter) => onChange({ dateDelimiter })}
      />
      <SingleCharacterSelect
        {...characterProps}
        label={i18n('workspace.importExport.yearDelimiter')}
        value={value.yearDelimiter}
        options={dateDelimiterOptions()}
        onChange={(yearDelimiter) => onChange({ yearDelimiter })}
      />
      <SingleCharacterSelect
        {...characterProps}
        label={i18n('workspace.importExport.timeDelimiter')}
        value={value.timeDelimiter}
        options={[
          { value: ':', label: i18n('workspace.importExport.delimiterColon') },
          { value: '.', label: i18n('workspace.importExport.delimiterDot') },
        ]}
        onChange={(timeDelimiter) => onChange({ timeDelimiter })}
      />
      {withDecimal && (
        <SingleCharacterSelect
          {...characterProps}
          allowCustom={false}
          label={i18n('workspace.importExport.decimalSymbol')}
          value={value.decimalSymbol}
          options={[
            { value: '.', label: i18n('workspace.importExport.delimiterDot') },
            { value: ',', label: i18n('workspace.importExport.delimiterComma') },
          ]}
          onChange={(decimalSymbol) => onChange({ decimalSymbol: decimalSymbol === ',' ? ',' : '.' })}
        />
      )}
      <div className={styles.dateExamples}>
        <span>{i18n('workspace.importExport.dateTimeExample')}</span>
        {getCsvDateTimeExamples(value).map((example) => (
          <code key={example}>{example}</code>
        ))}
      </div>
    </div>
  );
}

const dateDelimiterOptions = () => [
  { value: '-', label: i18n('workspace.importExport.delimiterDash') },
  { value: '/', label: i18n('workspace.importExport.delimiterSlash') },
  { value: '.', label: i18n('workspace.importExport.delimiterDot') },
];
