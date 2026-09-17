import type { CollapseProps } from 'antd';
import type { ICsvOptions } from '@/typings/importExport';
import i18n from '@/i18n';
import LocalFileEncodingSelect from '@/components/LocalFileEncodingSelect';
import SingleCharacterSelect from '@/components/SingleCharacterSelect';
import OptionsSections from './OptionsSections';
import SourceRowsFields from './SourceRowsFields';
import ValueFormatFields from './ValueFormatFields';
import { useStyles } from './style';

interface Props {
  value: ICsvOptions;
  activeKeys: string[];
  disabled?: boolean;
  dataItems: CollapseProps['items'];
  onChange: (value: ICsvOptions) => void;
  onActiveKeysChange: (keys: string[]) => void;
}

const CsvOptionsSections = ({
  value,
  activeKeys,
  disabled,
  dataItems,
  onChange,
  onActiveKeysChange,
}: Props) => {
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
    <OptionsSections
      activeKeys={activeKeys}
      onActiveKeysChange={onActiveKeysChange}
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
                onChange={(quote) =>
                  update({ quote, escape: value.escape === value.quote ? quote : value.escape })
                }
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
          children: <SourceRowsFields value={value} disabled={disabled} onChange={update} />,
        },
        {
          key: 'formats',
          label: i18n('workspace.importExport.dateTimeFormats'),
          children: <ValueFormatFields value={value} disabled={disabled} withTimeZone onChange={update} />,
        },
        ...(dataItems || []),
      ]}
    />
  );
};
export default CsvOptionsSections;
