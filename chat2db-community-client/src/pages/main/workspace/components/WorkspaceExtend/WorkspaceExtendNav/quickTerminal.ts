import { WorkspaceTabType } from '@/constants/workspace';
import type { IWorkspaceTab } from '@/typings';
import type { TerminalOpenPosition } from '@/typings/settings';

export interface QuickTerminalSession {
  sessionId: string;
  cwd: string;
  shell: string;
  shellId: string;
}

export function createQuickTerminalTab(
  terminal: QuickTerminalSession,
  title: string,
  openPosition: TerminalOpenPosition = 'tab',
): IWorkspaceTab {
  return {
    id: `${WorkspaceTabType.Terminal}:${terminal.sessionId}`,
    type: WorkspaceTabType.Terminal,
    title,
    uniqueData: {
      terminalSessionId: terminal.sessionId,
      terminalCwd: terminal.cwd,
      terminalShell: terminal.shell,
      terminalShellId: terminal.shellId,
      terminalOpenPosition: openPosition,
    },
  };
}
