import { IconButton } from '@chat2db/ui';
import { PANEL_TOOLBAR_BUTTON_SIZE } from '@/components/PanelToolbar';

import { useWorkspaceStore } from '@/store/workspace';

import { GlobalComponents, workspaceRecordConfig } from '../config';
import TaskCenterStatusBadge from '../TaskCenterStatusBadge';
import { useStyles } from './style';

const WorkspaceRecordSwitcher = () => {
  const { styles } = useStyles();
  const { currentWorkspaceExtend, setCurrentWorkspaceExtend } = useWorkspaceStore((state) => ({
    currentWorkspaceExtend: state.currentWorkspaceExtend,
    setCurrentWorkspaceExtend: state.setCurrentWorkspaceExtend,
  }));

  return (
    <div className={styles.switcher} role="tablist">
      {workspaceRecordConfig.map((item) => {
        const button = (
          <IconButton
            type="primary"
            size={PANEL_TOOLBAR_BUTTON_SIZE}
            title={item.title}
            tooltipPlacement="bottom"
            {...(typeof item.icon === 'string' ? { code: item.icon } : { icon: item.icon })}
            isActive={currentWorkspaceExtend === item.code}
            onClick={() => setCurrentWorkspaceExtend(item.code)}
          />
        );

        return item.code === GlobalComponents.task_center ? (
          <TaskCenterStatusBadge key={item.code}>{button}</TaskCenterStatusBadge>
        ) : (
          <span key={item.code} className={styles.buttonSlot}>
            {button}
          </span>
        );
      })}
    </div>
  );
};

export default WorkspaceRecordSwitcher;
