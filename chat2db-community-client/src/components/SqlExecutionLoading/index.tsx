import { Spin } from 'antd';
import i18n from '@/i18n';
import { useStyles } from './style';

interface SqlExecutionLoadingProps {
  onCancel?: () => void;
}

export default function SqlExecutionLoading({ onCancel }: SqlExecutionLoadingProps) {
  const { styles } = useStyles();

  return (
    <div className={styles.loading}>
      <Spin />
      {onCancel && (
        <div className={styles.cancel} onClick={onCancel}>
          {i18n('common.button.cancelRequest')}
        </div>
      )}
    </div>
  );
}
