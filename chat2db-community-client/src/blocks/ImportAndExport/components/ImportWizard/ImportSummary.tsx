import { Button } from 'antd';
import i18n from '@/i18n';
import { useStyles } from './style';

export interface ImportSummaryItem {
  key: string;
  label: string;
  value: string;
}

interface Props {
  fileName: string;
  fileFormat: string;
  target: string;
  targetLabel: string;
  items: ImportSummaryItem[];
  disabled: boolean;
  onEditSource: () => void;
}

export default function ImportSummary({
  fileName, fileFormat, target, targetLabel, items, disabled, onEditSource,
}: Props) {
  const { styles } = useStyles();
  return (
    <section className={styles.summary}>
      <div className={styles.summaryHeading}>
        <h3 className={styles.sectionTitle}>{i18n('workspace.importExport.wizard.summaryTitle')}</h3>
        <Button type="link" size="small" disabled={disabled} onClick={onEditSource}>
          {i18n('workspace.importExport.wizard.editSource')}
        </Button>
      </div>
      <dl className={styles.summaryIdentity}>
        <div>
          <dt>{i18n('workspace.importExport.wizard.summaryFile')}</dt>
          <dd>{fileName}</dd>
          <span>{fileFormat}</span>
        </div>
        <div>
          <dt>{targetLabel}</dt>
          <dd>{target}</dd>
        </div>
      </dl>
      <dl className={styles.summaryGrid}>
        {items.map(({ key, label, value }) => (
          <div key={key}><dt>{label}</dt><dd>{value}</dd></div>
        ))}
      </dl>
    </section>
  );
}
