import { useEffect, useRef, useState } from 'react';
import ERModal, { ERModalRef } from './index';
import { IBoundInfo } from '@/typings';
import { IERTableDetail } from '@/typings/er';
import { useStyles } from './style';
import { ToolbarBtn, Loading } from '@chat2db/ui';
import { getTableErInfo, saveTableErPosition } from '@/service/er';
import { openWebPage } from '@/utils/url';
import { useGlobalStore } from '@/store/global';
import i18n from '@/i18n';

interface IProps {
  uniqueData: IBoundInfo;
}

const ConsoleERModal = (props: IProps) => {
  const { uniqueData } = props;
  const { styles } = useStyles();
  const { dataSourceId, databaseName, schemaName } = uniqueData;
  const appUrlConfig = useGlobalStore((s) => s.appUrlConfig);

  const [erModalData, setErModalData] = useState<IERTableDetail[]>();
  const [storedLayout, setStoredLayout] = useState<string>();
  const [loading, setLoading] = useState(true);

  const erModalRef = useRef<ERModalRef>(null);

  useEffect(() => {
    getTableErInfo({ dataSourceId, databaseName, schemaName })
      .then((res) => {
        setErModalData(res.tables);
        setStoredLayout(res.position);
      })
      .finally(() => {
        setLoading(false);
      });
  }, []);

  const handleResetLayout = () => {
    erModalRef.current?.handleResetLayout();
  };

  const handleSaveLayout = (e) => {
    saveTableErPosition({ dataSourceId, databaseName, schemaName, position: JSON.stringify(e) });
  };

  return (
    <div className={styles.consoleERModal}>
      <div className={styles.toolBarList}>
        <div className={styles.toolBarLeft}>
          <div className={styles.toolBarItem}>
            <ToolbarBtn
              onClick={handleResetLayout}
              prefixIcon="icon-reset-layout"
              text={i18n('workspace.menu.resetLayout')}
            />
          </div>
        </div>
        <div className={styles.toolBarRight}>
          <div className={styles.toolBarItem}>
            <ToolbarBtn onClick={() => openWebPage(appUrlConfig.DOCS_URL)} prefixIcon="icon-question-mark-circle" text={i18n('workspace.menu.help')} />
          </div>
        </div>
      </div>
      <div className={styles.erModal}>
        {loading ? (
          <Loading />
        ) : (
          <ERModal
            ref={erModalRef}
            erModalData={erModalData}
            storedLayout={storedLayout}
            onSaveLayout={handleSaveLayout}
          />
        )}
      </div>
    </div>
  );
};

export default ConsoleERModal;
