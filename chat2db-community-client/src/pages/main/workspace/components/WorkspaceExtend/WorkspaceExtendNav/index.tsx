import { useEffect, useState } from 'react';
import classnames from 'classnames';
import i18n from '@/i18n';
import { extendConfig, GlobalComponents, type IToolbar } from '../config';
import { IconButton, staticMessage } from '@chat2db/ui';
import { useWorkspaceStore } from '@/store/workspace';
import { useImportExportStore } from '@/store/importExport';
import { useStyles } from './style';
import { canImportExport, isDesktop } from '@/utils/env';
import { Badge, Divider } from 'antd';
import { useAIStore } from '@/store/ai';
import AIButton from '@/blocks/AI/components/AIButton';
import { LoaderCircle, Terminal } from 'lucide-react';
import { useGlobalStore } from '@/store/global';
import jcefApi from '@/jcef';
import { createQuickTerminalTab } from './quickTerminal';
import { DEFAULT_TERMINAL_SETTINGS } from '@/constants/terminal';
import { TaskCenterModals } from '@/blocks/ImportAndExport/components/TaskCenter';

interface IProps {
  className?: any;
}

const WORKSPACE_SIDEBAR_BUTTON_SIZE = {
  boxSize: 34,
  iconSize: 18,
  borderRadius: 6,
  strokeWidth: 2,
} as const;

export default (props: IProps) => {
  const { className } = props;
  const { styles } = useStyles();
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
              size={WORKSPACE_SIDEBAR_BUTTON_SIZE}
              title={item.title}
              tooltipPlacement="left"
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
              offset={[-3, 3]}
              overflowCount={Number.MAX_SAFE_INTEGER}
            >
              <span className={styles.taskCenterButton}>
                {button}
                {activeTaskCount > 0 && <LoaderCircle aria-hidden className={styles.taskRunningIndicator} size={12} />}
              </span>
            </Badge>
          );
        })}
        <Divider style={{ margin: '8px 0px' }} />

        {isDesktop && (
          <IconButton
            type="primary"
            size={WORKSPACE_SIDEBAR_BUTTON_SIZE}
            title={i18n('workspace.terminal.title')}
            tooltipPlacement="left"
            icon={Terminal}
            spin={creatingTerminal}
            disabled={creatingTerminal}
            onClick={createTerminal}
          />
        )}
        <AIButton
          size={WORKSPACE_SIDEBAR_BUTTON_SIZE}
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
