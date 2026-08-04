import { useEffect, useState } from 'react';
import classnames from 'classnames';
import i18n from '@/i18n';
import { extendConfig } from '../config';
import { IconButton, staticMessage } from '@chat2db/ui';
import { useWorkspaceStore } from '@/store/workspace';
import { useImportExportStore } from '@/store/importExport';
import { useStyles } from './style';
import { canImportExport, isDesktop } from '@/utils/env';
import { Divider } from 'antd';
import { useAIStore } from '@/store/ai';
import AIButton from '@/blocks/AI/components/AIButton';
import { Terminal } from 'lucide-react';
import { useGlobalStore } from '@/store/global';
import jcefApi from '@/jcef';
import { createQuickTerminalTab } from './quickTerminal';
import { DEFAULT_TERMINAL_SETTINGS } from '@/constants/terminal';

interface IToolbar {
  code: string;
  title: string;
  icon: any;
  components: any;
}

interface IProps {
  className?: any;
}

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
  const { showExportToolbar, setShowExportToolbar } = useImportExportStore((state) => {
    return {
      showExportToolbar: state.showExportToolbar,
      setShowExportToolbar: state.setShowExportToolbar,
    };
  });
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
      addWorkspaceTab(
        createQuickTerminalTab(terminal, i18n('workspace.terminal.title'), terminalOpenPosition),
      );
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
        {extendConfig.map((item, index) => {
          return (
            <IconButton
              size="lg"
              key={index}
              title={item.title}
              tooltipPlacement="left"
              code={item.icon}
              isActive={currentWorkspaceExtend === item.code}
              onClick={() => {
                changeExtend(item);
                useAIStore.getState().setShowPanel(false);
              }}
            />
          );
        })}
        <Divider style={{ margin: '8px 0px' }} />

        {isDesktop && (
          <IconButton
            type="primary"
            size={{ boxSize: 28, iconSize: 18, borderRadius: 6, strokeWidth: 2 }}
            title={i18n('workspace.terminal.title')}
            tooltipPlacement="left"
            icon={Terminal}
            spin={creatingTerminal}
            disabled={creatingTerminal}
            onClick={createTerminal}
          />
        )}
        <AIButton
          onClick={() => {
            setCurrentWorkspaceExtend(null);
            useAIStore.getState().togglePanel();
          }}
        />
      </div>
      <div className={styles.bottomBox}>
        {canImportExport && (
          <IconButton
            size="lg"
            title={i18n('workspace.title.exportProgressBar')}
            tooltipPlacement="left"
            code="icon-export-details"
            isActive={showExportToolbar}
            onClick={() => setShowExportToolbar(!showExportToolbar)}
          />
        )}

        {/* <Tooltip title={i18n('workspace.title.ai')} placement="left">
        </Tooltip> */}
      </div>
    </div>
  );
};
