import { Collapse, Input, Select, type CollapseProps } from 'antd';
import type { IExcelOptions, IJsonOptions } from '@/typings/importExport';
import LocalFileEncodingSelect from '@/components/LocalFileEncodingSelect';
import i18n from '@/i18n';
import SourceRowsFields from './SourceRowsFields';
import ValueFormatFields from './ValueFormatFields';
import { useStyles } from './style';

type FormatProps =
  | { format: 'excel'; value: IExcelOptions; sheets: string[]; onChange: (value: IExcelOptions) => void }
  | { format: 'json'; value: IJsonOptions; onChange: (value: IJsonOptions) => void };
type Props = FormatProps & {
  disabled: boolean;
  activeKeys: string[];
  onActiveKeysChange: (keys: string[]) => void;
  dataItems: CollapseProps['items'];
};

export default function DataFileOptionsSections(props: Props) {
  const { styles } = useStyles();
  const { disabled, activeKeys, onActiveKeysChange, dataItems } = props;
  const sourceItem =
    props.format === 'excel'
      ? {
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
                    value={props.value.sheetIndex}
                    options={props.sheets.map((label, value) => ({ label, value }))}
                    onChange={(sheetIndex) => props.onChange({ ...props.value, sheetIndex })}
                  />
                </label>
                <label className={styles.csvOptionField}>
                  <span>{i18n('workspace.importExport.columnRange')}</span>
                  <Input
                    disabled={disabled}
                    placeholder="A:H"
                    value={props.value.columnRange}
                    onChange={(event) => props.onChange({ ...props.value, columnRange: event.target.value })}
                  />
                </label>
              </div>
              <SourceRowsFields
                value={props.value}
                disabled={disabled}
                onChange={(patch) => props.onChange({ ...props.value, ...patch })}
              />
            </>
          ),
        }
      : {
          key: 'jsonFormat',
          label: i18n('workspace.importExport.jsonFormat'),
          children: (
            <div className={styles.csvFormatOptions}>
              <div className={styles.csvOptionField}>
                <span>{i18n('workspace.importExport.encoding')}</span>
                <LocalFileEncodingSelect
                  charset={props.value.encoding}
                  disabled={disabled}
                  size="middle"
                  variant="outlined"
                  allowAutoDetect={false}
                  onEncodingChange={async (encoding) =>
                    props.onChange({ ...props.value, encoding: encoding || 'UTF-8' })
                  }
                />
              </div>
              <label className={styles.csvOptionField}>
                <span>{i18n('workspace.importExport.jsonStructure')}</span>
                <Select
                  aria-label={i18n('workspace.importExport.jsonStructure')}
                  disabled={disabled}
                  value={props.value.structure}
                  options={(['ARRAY', 'OBJECT', 'LINES'] as const).map((value) => ({
                    value,
                    label: i18n(`workspace.importExport.json${value}`),
                  }))}
                  onChange={(structure) =>
                    props.onChange({
                      ...props.value,
                      structure,
                      dataPath: structure === 'LINES' ? '$' : props.value.dataPath,
                    })
                  }
                />
              </label>
              <label className={styles.csvOptionField}>
                <span>{i18n('workspace.importExport.jsonDataPath')}</span>
                <Input
                  disabled={disabled || props.value.structure === 'LINES'}
                  value={props.value.dataPath}
                  placeholder="$.data.items"
                  onChange={(event) => props.onChange({ ...props.value, dataPath: event.target.value })}
                />
              </label>
            </div>
          ),
        };
  return (
    <Collapse
      className={styles.sections}
      ghost
      size="small"
      activeKey={activeKeys}
      onChange={(keys) => onActiveKeysChange(Array.isArray(keys) ? keys : [keys])}
      items={[
        sourceItem,
        {
          key: 'formats',
          label: i18n('workspace.importExport.dateTimeFormats'),
          children: (
            <ValueFormatFields
              value={props.value}
              disabled={disabled}
              withDecimal={props.format === 'excel'}
              onChange={(patch) =>
                props.format === 'excel'
                  ? props.onChange({ ...props.value, ...patch })
                  : props.onChange({ ...props.value, ...patch })
              }
            />
          ),
        },
        ...(dataItems || []),
      ]}
    />
  );
}
