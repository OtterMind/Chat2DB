import { IconButton } from '@chat2db/ui';
import { SlidersVertical } from 'lucide-react';
import type { MouseEvent } from 'react';

import CustomLayout from '@/components/CustomLayout';
import { COMMUNITY_TITLE_BAR_BUTTON_SIZE } from '@/constants/mainLayout';
import i18n from '@/i18n';
import type { INavItem } from '@/typings/main';

import WorkspaceExtendNav from '../../workspace/components/WorkspaceExtend/WorkspaceExtendNav';
import { useStyles } from './style';

interface CommunityTitleBarActionsProps {
  navItems: INavItem[];
  activePage: string;
  settingsActive: boolean;
  hideAccountActions: boolean;
  onNavigate: (item: INavItem) => void;
  onOpenSettings: () => void;
}

const stopDoubleClickPropagation = (event: MouseEvent<HTMLDivElement>) => {
  event.stopPropagation();
};

const CommunityTitleBarActions = ({
  navItems,
  activePage,
  settingsActive,
  hideAccountActions,
  onNavigate,
  onOpenSettings,
}: CommunityTitleBarActionsProps) => {
  const { styles } = useStyles();
  const showWorkspaceActions = activePage === 'workspace' && !settingsActive;

  return (
    <div className={styles.toolbar}>
      <div className={styles.navigationActions} onDoubleClick={stopDoubleClickPropagation}>
        {navItems.map((item) => (
          <IconButton
            type="primary"
            isActive={item.key === activePage && !settingsActive}
            key={item.key}
            size={COMMUNITY_TITLE_BAR_BUTTON_SIZE}
            title={item.name}
            icon={item.icon}
            tooltipPlacement="bottom"
            onClick={() => onNavigate(item)}
          />
        ))}
      </div>

      <div className={styles.dragRegion} />

      <div className={styles.rightActions} onDoubleClick={stopDoubleClickPropagation}>
        {showWorkspaceActions && (
          <div className={styles.workspaceActions}>
            <WorkspaceExtendNav orientation="horizontal" />
            <CustomLayout className={styles.layoutActions} />
          </div>
        )}

        {!hideAccountActions && (
          <div className={styles.accountActions}>
            <IconButton
              type="primary"
              isActive={settingsActive}
              size={COMMUNITY_TITLE_BAR_BUTTON_SIZE}
              title={i18n('setting.title.setting')}
              icon={SlidersVertical}
              tooltipPlacement="bottom"
              onClick={onOpenSettings}
            />
          </div>
        )}
      </div>
    </div>
  );
};

export default CommunityTitleBarActions;
