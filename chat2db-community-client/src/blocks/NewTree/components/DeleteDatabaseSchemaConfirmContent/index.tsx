import { memo } from 'react';
import i18n from '@/i18n';
import SQLPreview from '@/components/SQLPreview';
import { useStyles } from './style';

interface IProps {
  sqlPreview: string;
  objectType: 'database' | 'schema' | 'tablespace';
}

export default memo<IProps>((props) => {
  const { sqlPreview, objectType } = props;
  const { styles } = useStyles();

  return (
    <div className={styles.confirmContent}>
      <div className={styles.warningDesc}>{i18n(`workspace.deleteDatabaseSchema.${objectType}.warningDesc`)}</div>
      <div className={styles.sectionTitle}>{i18n('workspace.deleteDatabaseSchema.sqlPreview')}</div>
      <div className={styles.sqlPreview}>
        <SQLPreview
          style={{ height: '100%' }}
          sql={sqlPreview}
          source="delete-database-schema-confirm"
          copyable={false}
          foldable={false}
          surface="transparent"
        />
      </div>
    </div>
  );
});
