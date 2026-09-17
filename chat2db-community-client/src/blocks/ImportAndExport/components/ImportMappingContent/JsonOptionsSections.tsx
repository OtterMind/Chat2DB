import { useEffect, useState } from 'react';
import { Input, Select, type CollapseProps } from 'antd';
import type { IJsonOptions } from '@/typings/importExport';
import LocalFileEncodingSelect from '@/components/LocalFileEncodingSelect';
import i18n from '@/i18n';
import OptionsSections from './OptionsSections';
import ValueFormatFields from './ValueFormatFields';
import { useStyles } from './style';

interface Props {
  value: IJsonOptions;
  disabled: boolean;
  activeKeys: string[];
  onActiveKeysChange: (keys: string[]) => void;
  dataItems: CollapseProps['items'];
  onChange: (value: IJsonOptions) => void;
}

export default function JsonOptionsSections({
  value,
  disabled,
  activeKeys,
  onActiveKeysChange,
  dataItems,
  onChange,
}: Props) {
  const { styles } = useStyles();
  const update = (patch: Partial<IJsonOptions>) => onChange({ ...value, ...patch });
  const [dataPath, setDataPath] = useState(value.dataPath);
  useEffect(() => {
    setDataPath(value.dataPath);
  }, [value.dataPath]);
  const commitDataPath = () => {
    const next = dataPath.trim();
    if (next !== value.dataPath) {
      update({ dataPath: next });
    }
  };
  return (
    <OptionsSections
      activeKeys={activeKeys}
      onActiveKeysChange={onActiveKeysChange}
      items={[
        {
          key: 'jsonFormat',
          label: i18n('workspace.importExport.jsonFormat'),
          children: (
            <div className={styles.csvFormatOptions}>
              <div className={styles.csvOptionField}>
                <span>{i18n('workspace.importExport.encoding')}</span>
                <LocalFileEncodingSelect
                  charset={value.encoding}
                  disabled={disabled}
                  size="middle"
                  variant="outlined"
                  allowAutoDetect={false}
                  onEncodingChange={async (encoding) => update({ encoding: encoding || 'UTF-8' })}
                />
              </div>
              <label className={styles.csvOptionField}>
                <span>{i18n('workspace.importExport.jsonStructure')}</span>
                <Select
                  aria-label={i18n('workspace.importExport.jsonStructure')}
                  disabled={disabled}
                  value={value.structure}
                  options={(['ARRAY', 'OBJECT', 'LINES'] as const).map((structure) => ({
                    value: structure,
                    label: i18n(`workspace.importExport.json${structure}`),
                  }))}
                  onChange={(structure) =>
                    update({ structure, dataPath: structure === 'LINES' ? '$' : value.dataPath })
                  }
                />
              </label>
              <label className={styles.csvOptionField}>
                <span>{i18n('workspace.importExport.jsonDataPath')}</span>
                <Input
                  disabled={disabled || value.structure === 'LINES'}
                  value={dataPath}
                  placeholder="$.data.items"
                  onChange={(event) => setDataPath(event.target.value)}
                  onBlur={commitDataPath}
                  onPressEnter={commitDataPath}
                />
              </label>
            </div>
          ),
        },
        {
          key: 'formats',
          label: i18n('workspace.importExport.dateTimeFormats'),
          children: (
            <ValueFormatFields value={value} disabled={disabled} withDecimal={false} onChange={update} />
          ),
        },
        ...(dataItems || []),
      ]}
    />
  );
}
