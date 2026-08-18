import { IconButton } from '@chat2db/ui';
import { Settings } from 'lucide-react';

import { COMMUNITY_MAIN_ACTION_BUTTON_SIZE } from '@/constants/mainLayout';
import i18n from '@/i18n';
import type { INavItem } from '@/typings/main';
import { isDesktop } from '@/utils/env';

import QuickTerminalButton from '../../workspace/components/WorkspaceExtend/WorkspaceExtendNav/QuickTerminalButton';

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
  const { styles } = useStyles();

  const handleBeforeCreateTerminal = () => {
    const workspaceItem = navItems.find((item) => item.key === 'workspace');
    if (workspaceItem && (activePage !== 'workspace' || settingsActive)) {
      onNavigate(workspaceItem);
    }
  };

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

      {(isDesktop || !hideSettings) && (
        <div className={styles.bottomActions}>
          {isDesktop && (
            <QuickTerminalButton
              size={COMMUNITY_MAIN_ACTION_BUTTON_SIZE}
              tooltipPlacement="right"
              onBeforeCreate={handleBeforeCreateTerminal}
            />
          )}
          {!hideSettings && (
            <IconButton
              type="primary"
              isActive={settingsActive}
              size={COMMUNITY_MAIN_ACTION_BUTTON_SIZE}
              title={i18n('setting.title.setting')}
              icon={Settings}
              tooltipPlacement="right"
              onClick={onOpenSettings}
            />
          )}
        </div>
      )}
    </aside>
  );
};

export default CommunityMainActionBar;
