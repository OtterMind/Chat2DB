import UploadLocalFile from '@/components/UploadLocalFile';
import { ImportConnectionType } from '@/constants/database';
import i18n from '@/i18n';
import ConnectionServer from '@/service/connection';
import { isDesktop } from '@/utils/env';
import { Modal, staticMessage } from '@chat2db/ui';
import { Input } from 'antd';
import React, { useMemo, useState } from 'react';
import { useStyles } from './style';

interface IImportConnectionProps {
  open: boolean;
// Import type: Navicat, DataGrip, Chat2DB, or DBeaver.
  type?: string;
  onClose: () => void;
  onConfirm?: () => void;
}

export const importConfigList: any = [
  {
    type: ImportConnectionType.CHAT2DB,
    title: i18n('connection.title.importChat2DB'),
    accept: '.json',
    api: ConnectionServer.importChat2DBConnections,
  },
  {
    type: ImportConnectionType.DBEAVER,
    title: i18n('connection.title.importDBeaver'),
    accept: '.dbp',
    api: ConnectionServer.importDBeaverConnections,
    tips: i18n('connection.import.unable.password.tips'),
  },
  {
    type: ImportConnectionType.DATAGRIP,
    title: i18n('connection.title.importDataGrip'),
    accept: '',
    api: ConnectionServer.importDatagripConnections,
    tips: i18n('connection.import.unable.password.tips'),
  },
  {
    type: ImportConnectionType.NAVICAT,
    title: i18n('connection.title.importNavicat'),
    accept: '.ncx',
    api: ConnectionServer.importNavicatConnections,
    tips: i18n('connection.import.unable.password.tips'),
  },
];

if (isDesktop) {
  importConfigList.push({
    type: ImportConnectionType.CHAT2DB_COMMUNITY,
    title: i18n('connection.title.importChat2DBCommunity'),
  });
}

export const importConfigMap = importConfigList.reduce((acc, cur) => {
  acc[cur.type] = cur;
  return acc;
}, {});

const ImportConnection: React.FC<IImportConnectionProps> = ({ open, type, onClose, onConfirm }) => {
  const [desktopLoading, setDesktopLoading] = useState(false);
  const [importContent, setImportContent] = useState<any>();
  const [masterPassword, setMasterPassword] = useState<string>('');
  const { styles } = useStyles();

  const currentConfig = useMemo(() => {
    if (!type) {
      return;
    }
    return importConfigMap[type];
  }, [type]);

  const handleConfirmUpload = () => {
    if (!type) {
      return;
    }

    setDesktopLoading(true);

    let params: any = {
      file: importContent,
    };

    if (type === ImportConnectionType.DBEAVER && masterPassword) {
      // DBeaver encrypts saved credentials with the master password when one is set.
      params = {
        ...params,
        masterPassword,
      };
    }

    if (type === ImportConnectionType.DATAGRIP) {
      params = {
        text: importContent,
      };
    }

    importConfigMap[type]
      .api(params)
      .then((res) => {
        onConfirm && onConfirm();
        setDesktopLoading(false);
        if (res.result) {
          staticMessage.success(res.result);
          return;
        }
        staticMessage.success(i18n('connection.import.success', res.count));
      })
      .catch(() => {
        setDesktopLoading(false);
      });
  };

  const handleFileUrlListChange = (fileUrlList: any) => {
    if (fileUrlList.length === 0) {
      return;
    }
    setImportContent(fileUrlList[0].filePath ? [fileUrlList[0].filePath] : fileUrlList[0].file);
  };

  return (
    <Modal
      headerBorder
      destroyOnClose
      title={currentConfig?.title}
      open={open}
      onCancel={onClose}
      onOk={handleConfirmUpload}
      confirmLoading={desktopLoading}
      maskClosable={false}
      width={600}
    >
      <div className={styles.modalContent}>
        {currentConfig?.type === ImportConnectionType.DATAGRIP ? (
          <>
            <Input.TextArea
              rows={12}
              onChange={(event) => {
                setImportContent(event.target.value);
              }}
              spellCheck="false"
              style={{ resize: 'none' }}
            />
          </>
        ) : (
          <>
            <UploadLocalFile fileUrlListChange={handleFileUrlListChange} accept={currentConfig?.accept} />
            {currentConfig?.type === ImportConnectionType.DBEAVER && (
              <Input.Password
                allowClear
                className={styles.masterPasswordInput}
                placeholder={i18n('connection.import.dbeaver.masterPassword.placeholder')}
                onChange={(event) => {
                  setMasterPassword(event.target.value);
                }}
              />
            )}
            <div className={styles.tips}>{currentConfig?.tips}</div>
          </>
        )}
      </div>
    </Modal>
  );
};

export default ImportConnection;
