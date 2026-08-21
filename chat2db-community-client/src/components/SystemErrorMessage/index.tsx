import React, { useLayoutEffect, useState } from 'react';
import { Button, notification, Space, Modal } from 'antd';
import i18n from '@/i18n';
import { IconType } from 'antd/es/notification/interface';
import Iconfont from '../Iconfont';
import { copyToClipboard, getApplicationMessage } from '@/utils';
import { useGlobalStore } from '@/store/global';
import { useStyles } from './style';

interface IProps {
  type?: IconType;
  message?: React.ReactNode;
  /** Error code. */
  errorCode: string;
  /** Error message. */
  errorMessage: string;
  /** Error details. */
  errorDetail: string;
  /** Issue wiki path. */
  solutionLink: string;
  /** Requested API. */
  requestUrl: string;
  /** Request parameters. */
  requestParams?: string;
}

function MyNotification() {
  const { styles } = useStyles();
  const [notificationApi, notificationDom] = notification.useNotification({
    maxCount: 2,
  });
  const setSystemErrorMessage = useGlobalStore((s) => s.setSystemErrorMessage);
  const [open, setOpen] = useState(false);
  const [props, setProps] = useState<IProps>();

  useLayoutEffect(() => {
    const systemErrorMessageApi = (myProps: IProps) => {
      const { errorCode, errorMessage, solutionLink } = myProps;
      setProps(myProps);
      const actions = (
        <Space>
          <Button
            type="link"
            size="small"
            onClick={() => {
              setOpen(true);
            }}
          >
            {i18n('common.notification.detail')}
          </Button>
          {solutionLink && (
            <Button type="link" size="small" target="_blank" href={solutionLink}>
              {i18n('common.notification.solution')}
            </Button>
          )}
        </Space>
      );

      const renderDescription = () => {
        return (
          <div className={styles.description}>
            {errorCode}
          </div>
        );
      };

      const renderMessage = () => {
        return (
          <div className={styles.message}>
            <Iconfont className={styles.messageIcon} code="&#xe60c;" />
            <div className={styles.messageText}>{errorMessage || errorCode}</div>
          </div>
        );
      };

      notificationApi.open({
        className: styles.notification,
        message: renderMessage(),
        description: renderDescription(),
        placement: 'bottomRight',
        actions,
      });
    };
    setSystemErrorMessage(systemErrorMessageApi);
  }, []);

  function renderModalTitle() {
    return <div className={styles.modalTitle}>{props?.errorMessage || props?.errorCode}</div>;
  }

  function copyError() {
    const errorMessage = {
      getApplicationMessage: getApplicationMessage(),
      ...props,
    };
    copyToClipboard(JSON.stringify(errorMessage));
  }

  function renderModalFooter() {
    if (props?.requestParams) {
      return (
        <div className={styles.modalFooter} onClick={copyError}>
          <Iconfont code="&#xeb4e;" />
          {i18n('common.button.copyError')}
          <span className={styles.copyErrorTips}>{i18n('common.button.copyErrorTips')}</span>
        </div>
      );
    }
    return false;
  }

  return (
    <>
      {notificationDom}
      <Modal
        className={styles.modal}
        title={renderModalTitle()}
        open={open}
        width="70vw"
        footer={renderModalFooter()}
        onCancel={() => {
          setOpen(false);
        }}
        maskClosable={false}
        zIndex={99999}
      >
        <div className={styles.errorDetail}>{props?.errorDetail}</div>
      </Modal>
    </>
  );
}

export default MyNotification;
