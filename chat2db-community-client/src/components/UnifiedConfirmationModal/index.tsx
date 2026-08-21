import { memo, useEffect, useState } from 'react';
import { useStyles } from './style';
import { Modal } from '@chat2db/ui';
import { useGlobalStore } from '@/store/global';
import i18n from '@/i18n';
import { Button, Checkbox, Input } from 'antd';
import ModalFooterButton from '@/components/Modal/ModalFooterButton';

interface IProps {
  className?: string;
}

export default memo<IProps>(() => {
  const { styles } = useStyles();
  const [loading, setLoading] = useState(false);
  const [userChecked, setUserChecked] = useState<boolean>(false);
  const [inputConfirmText, setInputConfirmText] = useState('');
  const { unifiedConfirmationModalInfo, openUnifiedConfirmationModal } = useGlobalStore((state) => {
    return {
      unifiedConfirmationModalInfo: state.unifiedConfirmationModalInfo,
      openUnifiedConfirmationModal: state.openUnifiedConfirmationModal,
    };
  });

  useEffect(() => {
    if (unifiedConfirmationModalInfo === null) {
      setUserChecked(false);
      setInputConfirmText('');
      setLoading(false);
    }
  }, [unifiedConfirmationModalInfo]);

  const needInputConfirmText = unifiedConfirmationModalInfo?.needInputConfirmText;
  const inputConfirmed = !needInputConfirmText || inputConfirmText === needInputConfirmText;

  const handleCancel = () => {
    unifiedConfirmationModalInfo?.onCancel?.();
    openUnifiedConfirmationModal(null);
  };

  const renderFooter = () => {
    return (
      <ModalFooterButton
        footerRight={
          <>
            <Button
              onClick={() => {
                handleCancel();
              }}
            >
              {i18n('common.button.cancel')}
            </Button>
            <Button
              danger
              loading={loading}
              disabled={(!userChecked && !!unifiedConfirmationModalInfo?.needDoubleConfirmText) || !inputConfirmed}
              onClick={() => {
                setLoading(true);
                unifiedConfirmationModalInfo
                  ?.onOk(inputConfirmText)
                  .then(() => {
                    openUnifiedConfirmationModal(null);
                  })
                  .finally(() => {
                    setLoading(false);
                  });
              }}
            >
              {i18n('common.button.confirm')}
            </Button>
          </>
        }
      />
    );
  };

  return (
    <Modal
      headerIconCode={unifiedConfirmationModalInfo?.headerIconCode || 'icon-trash'}
      title={unifiedConfirmationModalInfo?.title || ''}
      open={!!unifiedConfirmationModalInfo}
      maskClosable={false}
      closable={false}
      footer={renderFooter()}
      onCancel={() => {
        handleCancel();
      }}
      width={unifiedConfirmationModalInfo?.width || 400}
    >
      <div>{unifiedConfirmationModalInfo?.content}</div>
      {unifiedConfirmationModalInfo?.needDoubleConfirmText && (
        <div className={styles.checkContainer}>
          <Checkbox
            checked={userChecked}
            onChange={(e) => {
              setUserChecked(e.target.checked);
            }}
          >
            {unifiedConfirmationModalInfo?.needDoubleConfirmText}
          </Checkbox>
        </div>
      )}
      {needInputConfirmText && (
        <div className={styles.inputConfirmContainer}>
          <div className={styles.inputConfirmLabel}>
            {unifiedConfirmationModalInfo?.inputConfirmLabel || i18n('common.button.confirm')}
          </div>
          <Input
            value={inputConfirmText}
            placeholder={unifiedConfirmationModalInfo?.inputConfirmPlaceholder}
            onChange={(event) => {
              setInputConfirmText(event.target.value);
            }}
          />
          {inputConfirmText && !inputConfirmed && (
            <div className={styles.inputConfirmMismatch}>
              {unifiedConfirmationModalInfo?.inputConfirmMismatchTip || ''}
            </div>
          )}
        </div>
      )}
    </Modal>
  );
});
