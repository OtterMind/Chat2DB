import { IconButton } from '@chat2db/ui';
import { Tooltip } from 'antd';

import Logo from '@/components/Logo';
import { COMMUNITY_MAIN_ACTION_BUTTON_SIZE } from '@/constants/mainLayout';
import i18n from '@/i18n';
import type { INavItem } from '@/typings/main';

import { useStyles } from './style';

interface CommunityMainActionBarProps {
  navItems: INavItem[];
  activePage: string;
  settingsActive: boolean;
  hideSettings: boolean;
  onNavigate: (item: INavItem) => void;
  onOpenSettings: () => void;
}

const CommunityMainActionBar = ({
  navItems,
  activePage,
  settingsActive,
  hideSettings,
  onNavigate,
  onOpenSettings,
}: CommunityMainActionBarProps) => {
  const { styles, cx } = useStyles();

  return (
    <aside className={styles.actionBar}>
      <nav className={styles.navigationActions}>
        {navItems.map((item) => (
          <IconButton
            type="primary"
            isActive={item.key === activePage && !settingsActive}
            key={item.key}
            size={COMMUNITY_MAIN_ACTION_BUTTON_SIZE}
            title={item.name}
            icon={item.icon}
            tooltipPlacement="right"
            onClick={() => onNavigate(item)}
          />
        ))}
      </nav>

      {!hideSettings && (
        <Tooltip title={i18n('setting.title.setting')} placement="right" mouseEnterDelay={0.3}>
          <button
            type="button"
            aria-pressed={settingsActive}
            className={cx(styles.settingsAction, settingsActive && styles.settingsActionActive)}
            onClick={onOpenSettings}
          >
            <Logo size={22} />
          </button>
        </Tooltip>
      )}
    </aside>
  );
};

export default CommunityMainActionBar;
