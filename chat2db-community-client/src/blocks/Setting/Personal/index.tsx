import { useStyles } from './style';
import { useCommonStyle } from '../commonStyle';
import SettingSubsection from '../SettingSubsection';
import ChangeEmail from './ChangeEmail';
import ChangePassword from './ChangePassword';
import ChangePersonal from './ChangePersonal';
import { useUserStore } from '@/store/user';
import i18n from '@/i18n';
import PlanBox from '@/components/PlanBox';

// personal settings
export default function Personal() {
  const { styles } = useStyles();
  const { styles: commonStyles } = useCommonStyle();
  const { curUser, updateUser } = useUserStore((s) => {
    return {
      curUser: s.curUser,
      updateUser: s.updateUser,
    };
  });

  const urlParams = new URLSearchParams(window.location.search);
  const modal = urlParams.get('modal');
  const activationCode = urlParams.get('activationCode') || '';
  return (
    <div className={styles.personalBox}>
      {/* personal information */}
      <PlanBox openActivationCodeModal={modal === 'activationCode'} activationCode={activationCode} />
      <div className={commonStyles.containerBlock}>
        <SettingSubsection
          title={i18n('setting.nav.personalInformation')}
          describe={i18n('setting.nav.personalInformationDescribe')}
        />
        <ChangePersonal curUser={curUser} updateUser={updateUser} />
      </div>
      <div className={commonStyles.containerBlock}>
        <SettingSubsection title={i18n('setting.nav.resetEmail')} describe={i18n('setting.nav.resetEmailDescribe')} />
        <ChangeEmail curUser={curUser} updateUser={updateUser} />
      </div>
      {curUser?.email && (
        <div className={commonStyles.containerBlock}>
          <SettingSubsection
            title={i18n('setting.nav.resetPassword')}
            describe={i18n('setting.nav.resetPasswordDescribe')}
          />
          <ChangePassword updateUser={updateUser} />
        </div>
      )}
    </div>
  );
}
