import { useEffect, useState } from 'react';
import classnames from 'classnames';
import i18n from '@/i18n';
import { extendConfig, GlobalComponents, type IToolbar } from '../config';
import { IconButton, staticMessage } from '@chat2db/ui';
import { useWorkspaceStore } from '@/store/workspace';
import { useImportExportStore } from '@/store/importExport';
import { useStyles } from './style';
import { canImportExport, isDesktop } from '@/utils/env';
import { Badge } from 'antd';
import { useAIStore } from '@/store/ai';
import AIButton from '@/blocks/AI/components/AIButton';
import { LoaderCircle, Terminal } from 'lucide-react';
import { useGlobalStore } from '@/store/global';
import jcefApi from '@/jcef';
import { createQuickTerminalTab } from './quickTerminal';
import { DEFAULT_TERMINAL_SETTINGS } from '@/constants/terminal';
import { TaskCenterModals } from '@/blocks/ImportAndExport/components/TaskCenter';
import { COMMUNITY_TITLE_BAR_BUTTON_SIZE } from '@/constants/mainLayout';

interface IProps {
  className?: string;
  orientation?: 'vertical' | 'horizontal';
}

export default (props: IProps) => {
  const { className, orientation = 'vertical' } = props;
  const { styles } = useStyles({ orientation });
  const tooltipPlacement = orientation === 'horizontal' ? 'bottom' : 'left';
  const [creatingTerminal, setCreatingTerminal] = useState(false);
  const terminalShellId = useGlobalStore((state) => state.terminalSettings.shellId);
  const terminalOpenPosition = useGlobalStore(
    (state) => state.terminalSettings.openPosition || DEFAULT_TERMINAL_SETTINGS.openPosition,
  );
  const { addWorkspaceTab, currentWorkspaceExtend, setCurrentWorkspaceExtend } = useWorkspaceStore((state) => {
    return {
      addWorkspaceTab: state.addWorkspaceTab,
      currentWorkspaceExtend: state.currentWorkspaceExtend,
      setCurrentWorkspaceExtend: state.setCurrentWorkspaceExtend,
    };
  });
  const { activeTaskCount, unreadCompletedTaskCount } = useImportExportStore((state) => ({
    activeTaskCount: state.activeTaskCount,
    unreadCompletedTaskCount: state.unreadCompletedTaskCount,
  }));
  const { showPanel: showAIPanel } = useAIStore((state) => ({
    showPanel: state.showPanel,
  }));

  const changeExtend = (item: IToolbar) => {
    if (currentWorkspaceExtend === item.code) {
      setCurrentWorkspaceExtend(null);
      return;
    }
    setCurrentWorkspaceExtend(item.code);
  };

  const createTerminal = async () => {
    if (creatingTerminal) {
      return;
    }
    setCreatingTerminal(true);
    try {
      const terminal = await jcefApi.createTerminal({
        columns: 100,
        rows: 30,
        shellId: terminalShellId,
      });
      addWorkspaceTab(createQuickTerminalTab(terminal, i18n('workspace.terminal.title'), terminalOpenPosition), {
        activate: terminalOpenPosition === 'tab',
      });
    } catch (error) {
      console.error('create terminal error', error);
      staticMessage.error(i18n('workspace.localSqlFileTree.openTerminalFailed'));
    } finally {
      setCreatingTerminal(false);
    }
  };

  useEffect(() => {
    useWorkspaceStore.getState().togglePanelRight(!!currentWorkspaceExtend || showAIPanel);
  }, [currentWorkspaceExtend, showAIPanel]);

  return (
    <div className={classnames(className, styles.workspaceExtendNav)}>
      <div className={styles.topBox}>
        {extendConfig.map((item) => {
          const button = (
            <IconButton
              key={item.code}
              type="primary"
              size={COMMUNITY_TITLE_BAR_BUTTON_SIZE}
              title={item.title}
              tooltipPlacement={tooltipPlacement}
              {...(typeof item.icon === 'string' ? { code: item.icon } : { icon: item.icon })}
              isActive={currentWorkspaceExtend === item.code}
              onClick={() => {
                changeExtend(item);
                useAIStore.getState().setShowPanel(false);
              }}
            />
          );
          if (item.code !== GlobalComponents.task_center) {
            return button;
          }
          return (
            <Badge
              key={item.code}
              className={styles.taskNotificationBadge}
              count={unreadCompletedTaskCount}
              offset={[0, 3]}
              overflowCount={Number.MAX_SAFE_INTEGER}
            >
              <span className={styles.taskCenterButton}>
                {button}
                {activeTaskCount > 0 && <LoaderCircle aria-hidden className={styles.taskRunningIndicator} size={12} />}
              </span>
            </Badge>
          );
        })}
        {isDesktop && (
          <IconButton
            type="primary"
            size={COMMUNITY_TITLE_BAR_BUTTON_SIZE}
            title={i18n('workspace.terminal.title')}
            tooltipPlacement={tooltipPlacement}
            icon={Terminal}
            spin={creatingTerminal}
            disabled={creatingTerminal}
            onClick={createTerminal}
          />
        )}
        <AIButton
          size={COMMUNITY_TITLE_BAR_BUTTON_SIZE}
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
