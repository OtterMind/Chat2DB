import { IconButton, staticMessage } from '@chat2db/ui';
import { Terminal } from 'lucide-react';
import { useState, type ComponentProps } from 'react';

import { DEFAULT_TERMINAL_SETTINGS } from '@/constants/terminal';
import i18n from '@/i18n';
import jcefApi from '@/jcef';
import { useGlobalStore } from '@/store/global';
import { useWorkspaceStore } from '@/store/workspace';

import { createQuickTerminalTab } from './quickTerminal';

interface QuickTerminalButtonProps {
  size: ComponentProps<typeof IconButton>['size'];
  tooltipPlacement: ComponentProps<typeof IconButton>['tooltipPlacement'];
  onBeforeCreate?: () => void;
}

const QuickTerminalButton = ({ size, tooltipPlacement, onBeforeCreate }: QuickTerminalButtonProps) => {
  const [creatingTerminal, setCreatingTerminal] = useState(false);
  const terminalShellId = useGlobalStore((state) => state.terminalSettings.shellId);
  const terminalOpenPosition = useGlobalStore(
    (state) => state.terminalSettings.openPosition || DEFAULT_TERMINAL_SETTINGS.openPosition,
  );
  const addWorkspaceTab = useWorkspaceStore((state) => state.addWorkspaceTab);

  const createTerminal = async () => {
    if (creatingTerminal) {
      return;
    }
    onBeforeCreate?.();
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

  return (
    <IconButton
      type="primary"
      size={size}
      title={i18n('workspace.terminal.title')}
      tooltipPlacement={tooltipPlacement}
      icon={Terminal}
      spin={creatingTerminal}
      disabled={creatingTerminal}
      onClick={createTerminal}
    />
  );
};

export default QuickTerminalButton;
