import { i18n } from '@/i18n';
import { useGlobalStore } from '@/store/global';
import Logo from '@/components/Logo';
import { Tooltip } from 'antd';

interface OfflineAvatarProps {
  logoSize?: number;
  triggerSize?: number;
}

const OfflineAvatar = ({ logoSize = 36, triggerSize = logoSize }: OfflineAvatarProps) => {
  const { setSettingPageActiveTab } = useGlobalStore((state) => {
    return {
      setSettingPageActiveTab: state.setSettingPageActiveTab,
    };
  });
  const handleClick = () => {
    setSettingPageActiveTab('basic');
  };
  return (
    <Tooltip title={i18n('setting.title.setting')} placement="right">
      <div
        onClick={handleClick}
        style={{
          alignItems: 'center',
          cursor: 'pointer',
          display: 'flex',
          height: triggerSize,
          justifyContent: 'center',
          width: triggerSize,
        }}
      >
        <Logo size={logoSize} />
      </div>
    </Tooltip>
  );
};

export default OfflineAvatar;
