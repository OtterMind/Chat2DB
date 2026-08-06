import { staticModal } from '@chat2db/ui';
import { Button } from 'antd';
import { createElement, Fragment } from 'react';
import i18n from '@/i18n';
import transactionServer from '@/service/transaction';
import { useWorkspaceStore } from '@/store/workspace';
import type { IWorkspaceTab } from '@/typings';

interface TxConsole {
  consoleId: number;
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string;
}

/**
 * For each console being closed that has an open (uncommitted) transaction, prompt the user
 * to Commit, Roll back, or cancel closing. Mirrors confirmAndKillTerminalTabs: returns false
 * to abort the close, true to proceed. When the user commits or rolls back, the bound
 * connection is released on the server and the console's transaction state is cleared.
 */
export async function confirmAndReleaseTransaction(tabs: IWorkspaceTab[]): Promise<boolean> {
  const store = useWorkspaceStore.getState();
  const txConsoles: TxConsole[] = [];
  for (const tab of tabs) {
    const consoleId = tab.uniqueData?.consoleId;
    if (typeof consoleId !== 'number') {
      continue;
    }
    const state = store.getTransactionState(consoleId);
    if (state?.inTransaction) {
      txConsoles.push({
        consoleId,
        dataSourceId: tab.uniqueData?.dataSourceId as number,
        databaseName: tab.uniqueData?.databaseName,
        schemaName: tab.uniqueData?.schemaName,
      });
    }
  }
  if (!txConsoles.length) {
    return true;
  }
  return confirmTransactionClose(txConsoles);
}

function confirmTransactionClose(consoles: TxConsole[]): Promise<boolean> {
  return new Promise((resolve) => {
    let resolved = false;
    const finish = (value: boolean) => {
      if (resolved) {
        return;
      }
      resolved = true;
      resolve(value);
    };

    const releaseAll = async (action: 'commit' | 'rollback') => {
      const store = useWorkspaceStore.getState();
      await Promise.all(
        consoles.map(async (c) => {
          const request = {
            dataSourceId: c.dataSourceId,
            databaseName: c.databaseName,
            schemaName: c.schemaName,
            consoleId: c.consoleId,
          };
          try {
            const result =
              action === 'commit'
                ? await transactionServer.commitTransaction(request)
                : await transactionServer.rollbackTransaction(request);
            store.setTransactionState(c.consoleId, {
              inTransaction: false,
              lastOutcome: result?.outcome,
              lastError: result?.lastError,
            });
          } catch (error) {
            store.setTransactionState(c.consoleId, { inTransaction: false, lastError: String(error) });
          }
        }),
      );
      finish(true);
    };

    const modal = staticModal.confirm({
      title: i18n('workspace.transaction.closeTitle'),
      content: i18n('workspace.transaction.closeContent'),
      closable: false,
      okButtonProps: { style: { display: 'none' } },
      cancelButtonProps: { style: { display: 'none' } },
      footer: createElement(
        Fragment,
        null,
        createElement(Button, { key: 'cancel', onClick: () => finish(false) }, i18n('workspace.transaction.cancel')),
        createElement(
          Button,
          { key: 'rollback', danger: true, onClick: () => void releaseAll('rollback') },
          i18n('workspace.transaction.rollback'),
        ),
        createElement(
          Button,
          { key: 'commit', type: 'primary', onClick: () => void releaseAll('commit') },
          i18n('workspace.transaction.commit'),
        ),
      ),
      onCancel: () => finish(false),
    });
    void modal;
  });
}

export default confirmAndReleaseTransaction;
