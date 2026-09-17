import LocalFileEncodingSelect from '@/components/LocalFileEncodingSelect';
import type { ISqlImportOptions } from '@/typings/importExport';
import i18n from '@/i18n';
import { useStyles } from './ImportMappingContent/style';

interface Props {
  value: ISqlImportOptions;
  onChange: (value: ISqlImportOptions) => void;
}

export default function SqlImportOptionsFields({ value, onChange }: Props) {
  const { styles } = useStyles();
  return (
    <div className={styles.sourceRowOptions}>
      <div className={styles.csvOptionField}>
        <span>{i18n('workspace.importExport.encoding')}</span>
        <LocalFileEncodingSelect
          className={styles.fullWidthControl}
          charset={value.encoding === 'AUTO' ? undefined : value.encoding}
          size="middle"
          variant="outlined"
          onEncodingChange={async (encoding) => onChange({ ...value, encoding: encoding || 'AUTO' })}
        />
      </div>
    </div>
  );
}
