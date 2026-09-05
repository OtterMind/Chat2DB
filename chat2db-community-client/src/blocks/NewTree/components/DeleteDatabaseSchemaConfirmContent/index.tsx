import { memo, type ReactNode } from 'react';
import i18n from '@/i18n';
import SQLPreview from '@/components/SQLPreview';
import { useStyles } from './style';

interface IProps {
  sqlPreview: string;
  objectType: 'database' | 'schema' | 'tablespace';
  warningDesc?: ReactNode;
  occupyingTables?: string[];
}

export default memo<IProps>((props) => {
  const { sqlPreview, objectType, warningDesc, occupyingTables = [] } = props;
  const { styles } = useStyles();

  return (
    <div className={styles.confirmContent}>
      <div className={styles.warningDesc}>
        {warningDesc || i18n(`workspace.deleteDatabaseSchema.${objectType}.warningDesc`)}
      </div>
      {occupyingTables.length > 0 && (
        <>
          <div className={styles.sectionTitle}>{i18n('workspace.tablespace.occupiedObjects')}</div>
          <ul className={styles.occupyingTables}>
            {occupyingTables.map((table) => (
              <li key={table}>{table}</li>
            ))}
          </ul>
        </>
      )}
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
