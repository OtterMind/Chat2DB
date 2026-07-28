import { useMemo } from 'react';
import { useStyles } from './style';
import i18n from '@/i18n';
import { Flex, Button } from 'antd';
import { useUserStore } from '@/store/user';
import { useGlobalStore } from '@/store/global';
import { GuideDialogStatus, LicenseDialogType } from '@/components/GuideDialog/type';

// defines the activation state of the enumeration
enum LicenseStatus {
  UNUSED,
  TRIAL,
  EXPIRED,
  ACTIVATED,
}

const TWO_WEEK = 14 * 24 * 60 * 60 * 1000;

const License = () => {
  const { styles } = useStyles();
  const { curUser } = useUserStore((state) => ({
    curUser: state.curUser,
  }));
  const { setOpenLinenseDialog, setOpenGuideDialog, setGuideDialogStatus, setLicenseDialogType } = useGlobalStore(
    (state) => ({
      setOpenLinenseDialog: state.setOpenLinenseDialog,
      setOpenGuideDialog: state.setOpenGuideDialog,
      setGuideDialogStatus: state.setGuideDialogStatus,
      setLicenseDialogType: state.setLicenseDialogType,
    }),
  );

  const licenseStatus = useMemo(() => {
    if (curUser?.activated) {
      return LicenseStatus.ACTIVATED;
    }

    if (!curUser?.trialStartTime) {
      return LicenseStatus.UNUSED;
    }

    const trialStartTime = curUser?.trialStartTime;
    if (trialStartTime) {
      const trialEndTime = new Date(trialStartTime).getTime() + TWO_WEEK;
      return Date.now() > trialEndTime ? LicenseStatus.EXPIRED : LicenseStatus.TRIAL;
    }

    return LicenseStatus.UNUSED;
  }, [curUser]);

  // Returns the corresponding button text according to the status
  const getButtonText = (status: LicenseStatus) => {
    switch (status) {
      case LicenseStatus.UNUSED:
        return i18n('setting.license.button.learnMore');
      case LicenseStatus.TRIAL:
      case LicenseStatus.EXPIRED:
        return i18n('setting.license.button.activate');
      case LicenseStatus.ACTIVATED:
        return i18n('setting.license.button.unbind');
      default:
        return '';
    }
  };

  // returns the corresponding status text according to the status
  const getStatusText = (status: LicenseStatus) => {
    switch (status) {
      case LicenseStatus.UNUSED:
        return i18n('setting.license.status.unused');
      case LicenseStatus.TRIAL:
        return i18n('setting.license.status.trial');
      case LicenseStatus.EXPIRED:
        return i18n('setting.license.status.expired');
      case LicenseStatus.ACTIVATED:
        return i18n('setting.license.status.activated');
      default:
        return '';
    }
  };

  const renderButton = () => {
    if (licenseStatus === LicenseStatus.UNUSED) {
      return (
        <Button
          type="primary"
          onClick={() => {
            setOpenGuideDialog(true);
            setGuideDialogStatus(GuideDialogStatus.OfflineTrial);
          }}
        >
          {i18n('setting.license.button.learnMore')}
        </Button>
      );
    }
    if (licenseStatus === LicenseStatus.TRIAL) {
      return (
        <Button
          type="primary"
          onClick={() => {
            setOpenLinenseDialog(true);
            setLicenseDialogType(LicenseDialogType.Activation);
          }}
        >
          {i18n('setting.license.button.activate')}
        </Button>
      );
    }
    if (licenseStatus === LicenseStatus.EXPIRED) {
      return (
        <Flex align="center" gap={8}>
          <Button
            onClick={() => {
              setOpenGuideDialog(true);
              setGuideDialogStatus(GuideDialogStatus.OfflineTrialExpired);
            }}
          >
            {i18n('setting.license.button.learnMore')}
          </Button>
          <Button
            type="primary"
            onClick={() => {
              setOpenLinenseDialog(true);
              setLicenseDialogType(LicenseDialogType.Activation);
            }}
          >
            {i18n('setting.license.button.activate')}
          </Button>
        </Flex>
      );
    }
    if (licenseStatus === LicenseStatus.ACTIVATED) {
      return (
        <Button
          type="primary"
          danger
          onClick={() => {
            setOpenLinenseDialog(true);
            setLicenseDialogType(LicenseDialogType.Unbind);
          }}
        >
          {i18n('setting.license.button.unbind')}
        </Button>
      );
    }
    return <Button type="primary">{getButtonText(licenseStatus)}</Button>;
  };

  return (
    <div className={styles.wrapper}>
      <Flex vertical gap={8}>
        <div className={styles.colWrapper}>
          <Flex align="center" gap={12}>
            <div className={styles.colTitle}>{i18n('setting.license.title')}</div>
            <div className={styles.colStatus}>{getStatusText(licenseStatus)}</div>
          </Flex>
          {renderButton()}
        </div>
      </Flex>
    </div>
  );
};

export default License;
