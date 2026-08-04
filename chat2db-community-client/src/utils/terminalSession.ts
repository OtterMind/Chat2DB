import { staticModal } from '@chat2db/ui';
import { WorkspaceTabType } from '@/constants';
import { isTerminalCloseConfirmationEnabled } from '@/constants/terminal';
import i18n from '@/i18n';
import jcefApi from '@/jcef';
import { useGlobalStore } from '@/store/global';
import type { IWorkspaceTab } from '@/typings';
import { clearPersistentTerminalBuffer } from '@/utils/terminalBuffer';

const TERMINAL_BRIDGE_TIMEOUT = 2000;

function resolveWithin<T>(promise: Promise<T>, fallback: T): Promise<T> {
  return new Promise((resolve) => {
    const timer = window.setTimeout(() => resolve(fallback), TERMINAL_BRIDGE_TIMEOUT);
    promise.then(
      (value) => {
        window.clearTimeout(timer);
        resolve(value);
      },
      () => {
        window.clearTimeout(timer);
        resolve(fallback);
      },
    );
  });
}

async function confirmTerminalClose(busy: boolean) {
  return new Promise<boolean>((resolve) => {
    staticModal.confirm({
      title: i18n('workspace.terminal.closeTitle'),
      content: busy
        ? i18n('workspace.terminal.closeBusyContent')
        : i18n('workspace.terminal.closeContent'),
      okText: i18n('workspace.terminal.closeConfirm'),
      cancelText: i18n('common.button.cancel'),
      closable: false,
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    });
  });
}

export async function confirmAndKillTerminalTabs(tabs: IWorkspaceTab[], allTabs: IWorkspaceTab[] = tabs) {
  const closingTabIds = new Set(tabs.map((tab) => tab.id));
  const terminalSessionIds = [
    ...new Set(
      tabs
        .filter((tab) => tab.type === WorkspaceTabType.Terminal)
        .map((tab) => tab.uniqueData?.terminalSessionId)
        .filter(Boolean) as string[],
    ),
  ];
  if (!terminalSessionIds.length) {
    return true;
  }

  const sessionsToKill = terminalSessionIds.filter(
    (sessionId) =>
      !allTabs.some(
        (tab) =>
          !closingTabIds.has(tab.id) &&
          tab.type === WorkspaceTabType.Terminal &&
          tab.uniqueData?.terminalSessionId === sessionId,
      ),
  );
  if (!sessionsToKill.length) {
    return true;
  }

  if (isTerminalCloseConfirmationEnabled(useGlobalStore.getState().terminalSettings)) {
    const statusMap = await resolveWithin(
      jcefApi.getTerminalStatuses({ sessionIds: sessionsToKill }),
      Object.fromEntries(sessionsToKill.map((sessionId) => [sessionId, { alive: true, busy: false }])),
    );
    const statuses = sessionsToKill.map((sessionId) => statusMap[sessionId] || { alive: true, busy: false });
    const aliveStatuses = statuses.filter((status) => status.alive);
    if (aliveStatuses.length && !(await confirmTerminalClose(aliveStatuses.some((status) => status.busy)))) {
      return false;
    }
  }

  await resolveWithin(jcefApi.killTerminals({ sessionIds: sessionsToKill }), undefined);
  sessionsToKill.forEach(clearPersistentTerminalBuffer);
  return true;
}
