import { useEffect } from 'react';
import classnames from 'classnames';
import { isWorkspaceRecordCode, standaloneExtendConfig, workspaceRecordEntryConfig } from '../config';
import { IconButton } from '@chat2db/ui';
import { useWorkspaceStore } from '@/store/workspace';
import { useStyles } from './style';
import { canImportExport, isDesktop } from '@/utils/env';
import { useAIStore } from '@/store/ai';
import AIButton from '@/blocks/AI/components/AIButton';
import { TaskCenterModals } from '@/blocks/ImportAndExport/components/TaskCenter';
import { COMMUNITY_MAIN_ACTION_BUTTON_SIZE, COMMUNITY_TITLE_BAR_BUTTON_SIZE } from '@/constants/mainLayout';
import TaskCenterStatusBadge from '../TaskCenterStatusBadge';
import QuickTerminalButton from './QuickTerminalButton';

interface IProps {
  className?: string;
  orientation?: 'vertical' | 'horizontal';
}

export default (props: IProps) => {
  const { className, orientation = 'vertical' } = props;
  const { styles } = useStyles({ orientation });
  const tooltipPlacement = orientation === 'horizontal' ? 'bottom' : 'left';
  const { currentWorkspaceExtend, setCurrentWorkspaceExtend } = useWorkspaceStore((state) => {
    return {
      currentWorkspaceExtend: state.currentWorkspaceExtend,
      setCurrentWorkspaceExtend: state.setCurrentWorkspaceExtend,
    };
  });
  const { showPanel: showAIPanel } = useAIStore((state) => ({
    showPanel: state.showPanel,
  }));
  const recordPanelActive = isWorkspaceRecordCode(currentWorkspaceExtend);
  const buttonSize =
    orientation === 'horizontal' ? COMMUNITY_MAIN_ACTION_BUTTON_SIZE : COMMUNITY_TITLE_BAR_BUTTON_SIZE;

  const changeExtend = (code: string) => {
    if (currentWorkspaceExtend === code) {
      setCurrentWorkspaceExtend(null);
      return;
    }
    setCurrentWorkspaceExtend(code);
  };

  useEffect(() => {
    useWorkspaceStore.getState().togglePanelRight(!!currentWorkspaceExtend || showAIPanel);
  }, [currentWorkspaceExtend, showAIPanel]);

  return (
    <div className={classnames(className, styles.workspaceExtendNav)}>
      <div className={styles.topBox}>
        {standaloneExtendConfig.map((item) => (
          <IconButton
            key={item.code}
            type="primary"
            size={buttonSize}
            title={item.title}
            tooltipPlacement={tooltipPlacement}
            {...(typeof item.icon === 'string' ? { code: item.icon } : { icon: item.icon })}
            isActive={currentWorkspaceExtend === item.code}
            onClick={() => {
              changeExtend(item.code);
              useAIStore.getState().setShowPanel(false);
            }}
          />
        ))}
        <TaskCenterStatusBadge>
          <IconButton
            type="primary"
            size={buttonSize}
            title={workspaceRecordEntryConfig.title}
            tooltipPlacement={tooltipPlacement}
            {...(typeof workspaceRecordEntryConfig.icon === 'string'
              ? { code: workspaceRecordEntryConfig.icon }
              : { icon: workspaceRecordEntryConfig.icon })}
            isActive={recordPanelActive}
            onClick={() => {
              setCurrentWorkspaceExtend(recordPanelActive ? null : workspaceRecordEntryConfig.code);
              useAIStore.getState().setShowPanel(false);
            }}
          />
        </TaskCenterStatusBadge>
        {isDesktop && orientation === 'vertical' && (
          <QuickTerminalButton size={buttonSize} tooltipPlacement={tooltipPlacement} />
        )}
        <AIButton
          size={buttonSize}
          onClick={() => {
            setCurrentWorkspaceExtend(null);
            useAIStore.getState().togglePanel();
          }}
        />
      </div>
      {canImportExport && <TaskCenterModals />}
    </div>
  );
};
