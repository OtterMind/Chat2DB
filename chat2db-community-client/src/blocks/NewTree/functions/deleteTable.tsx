// Pinned form
import { useState } from 'react';
import mysqlService from '@/service/sql';
import { Button, Checkbox } from 'antd';
import { staticMessage } from '@chat2db/ui';
import { openModal } from '@/store/common/components';
import styles from './deleteTable.less';
import { getDeleteTableErrorMessage } from './deleteTableError';
import i18n from '@/i18n';

export const deleteTable = (treeNodeData, loadData) => {
  openModal({
    width: '450px',
    content: <DeleteModalContent treeNodeData={treeNodeData} loadData={loadData} openModal={openModal} />,
  });
};

export const DeleteModalContent = (params: { treeNodeData: any; openModal: any; loadData: any }) => {
  const { treeNodeData, loadData } = params;
  // Disable OK button
  const [userChecked, setUserChecked] = useState<boolean>(false);

  const onOk = () => {
    const p: any = {
      dataSourceId: treeNodeData.extraParams.dataSourceId,
      databaseName: treeNodeData.extraParams.databaseName,
      schemaName: treeNodeData.extraParams.schemaName,
      tableName: treeNodeData.originalTitle,
    };
    mysqlService
      .deleteTable(p)
      .then(() => {
        loadData();
        openModal(false);
      })
      .catch((error) => {
        staticMessage.error(getDeleteTableErrorMessage(error, i18n('common.text.failure')));
      });
  };

  return (
    <div className={styles.deleteModalContent}>
      <div className={styles.title}>{i18n('workspace.tree.delete.table.tip', `"${treeNodeData.originalTitle}"`)}</div>
      <div className={styles.checkContainer}>
        <Checkbox
          value={userChecked}
          onChange={(e) => {
            setUserChecked(e.target.checked);
          }}
        >
          {i18n('workspace.tree.delete.tip')}
        </Checkbox>
      </div>
      <div className={styles.deleteTableFooter}>
        <Button
          type="primary"
          onClick={() => {
            openModal(false);
          }}
        >
          {i18n('common.button.cancel')}
        </Button>
        <Button disabled={!userChecked} danger onClick={onOk}>
          {i18n('common.button.affirm')}
        </Button>
      </div>
    </div>
  );
};
