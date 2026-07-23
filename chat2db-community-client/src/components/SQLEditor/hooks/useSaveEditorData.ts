import { useState, useEffect, useRef } from 'react';
import { ConsoleOpenedStatus, ConsoleStatus, WorkspaceTabType } from '@/constants';
import historyServer from '@/service/history';
import i18n from '@/i18n';
import { useWorkspaceStore } from '@/store/workspace';
import { staticMessage } from '@chat2db/ui';
import { useUserStore } from '@/store/user';
import { useIndexDBStore } from '@/store/indexDB';
import { SQLEditorRef } from '@/components/SQLEditor/editor/SQLEditor';
import { isTemporaryId } from '@/utils';
import { emitSavedConsoleUpdated } from '@/utils/savedConsoleEvents';
import { savedConsoleMutationCoordinator } from '@/store/workspace/utils/savedConsoleMutationCoordinator';
import { persistSavedConsoleRecord } from '@/store/workspace/utils/savedConsolePersistence';

interface IProps {
  isActive?: boolean;
  source?: 'workspace';
  editorRef: React.RefObject<SQLEditorRef>;
  boundInfo: any;
  defaultValue?: string;
  name?: string;
  type:
    | WorkspaceTabType.CONSOLE
    | WorkspaceTabType.FUNCTION
    | WorkspaceTabType.PROCEDURE
    | WorkspaceTabType.TRIGGER
    | WorkspaceTabType.VIEW
    | WorkspaceTabType.LocalSQLFile;
}

export const useSaveEditorData = (props: IProps) => {
  const { isActive, source, editorRef, boundInfo, defaultValue, name, type } = props;

  const timerRef = useRef<any>();
  // Console data from the previous synchronization.
  const lastSyncConsole = useRef<any>(defaultValue);
  const storageId = boundInfo?.workspaceTabId ?? boundInfo?.consoleId;
  const isTemporaryConsole = isTemporaryId(storageId);
  const isPersistedConsole =
    type === WorkspaceTabType.CONSOLE && typeof boundInfo?.consoleId === 'number' && !isTemporaryConsole;
  const isReadOnly = !!boundInfo?.readOnly;
  const [saveStatus, setSaveStatus] = useState<ConsoleStatus>(boundInfo?.status || ConsoleStatus.DRAFT);
  const saveStatusRef = useRef<ConsoleStatus>(boundInfo?.status || ConsoleStatus.DRAFT);
  const { getSavedConsoleList, savedConsoleList, updateWorkspaceTabBoundInfo } = useWorkspaceStore((s) => ({
    getSavedConsoleList: s.getSavedConsoleList,
    savedConsoleList: s.savedConsoleList,
    updateWorkspaceTabBoundInfo: s.updateWorkspaceTabBoundInfo,
  }));
  const hasSavedSqlRecord = Boolean(
    type === WorkspaceTabType.CONSOLE &&
      (boundInfo?.status === ConsoleStatus.RELEASE ||
        saveStatus === ConsoleStatus.RELEASE ||
        savedConsoleList?.some((item) => item.id === boundInfo?.consoleId)),
  );

  const indexDB = useIndexDBStore((s) => ({
    getValue: s.getValue,
    setValue: s.setValue,
    deleteValue: s.deleteValue,
  }));

  const { curUser } = useUserStore((s) => {
    return {
      curUser: s.curUser,
    };
  });

  const saveConsole = (value?: string, noPrompting?: boolean) => {
    const p: any = {
      id: boundInfo.consoleId,
      status: ConsoleStatus.RELEASE,
      ddl: value,
    };

    if (!storageId) {
      return;
    }

    if (isReadOnly) {
      lastSyncConsole.current = value;
      return;
    }

    if (!isPersistedConsole) {
      indexDB
        .setValue(storageId, {
          ddl: value,
          userId: curUser?.id,
        })
        .then(() => {
          lastSyncConsole.current = value;
        });
      return;
    }

    const consoleId = boundInfo.consoleId as number;
    const mode = noPrompting ? 'automatic' : 'manual';
    savedConsoleMutationCoordinator
      .save(consoleId, mode, () =>
        persistSavedConsoleRecord(historyServer, {
          manual: !noPrompting,
          createParams: {
            id: consoleId,
            name: name || boundInfo.databaseName || boundInfo.schemaName || '',
            ddl: value || '',
            dataSourceId: boundInfo.dataSourceId,
            dataSourceName: boundInfo.dataSourceName,
            type: boundInfo.databaseType,
            databaseName: boundInfo.databaseName,
            schemaName: boundInfo.schemaName,
            status: ConsoleStatus.RELEASE,
            tabOpened: ConsoleOpenedStatus.IS_OPEN,
            operationType: WorkspaceTabType.CONSOLE,
          },
          updateParams: p,
        }),
      )
      .then((result) => {
        if (!result.executed) {
          indexDB
            .setValue(storageId, {
              ddl: value,
              userId: curUser?.id,
            })
            .then(() => {
              lastSyncConsole.current = value;
            });
          return;
        }
        if (!result.current) {
          return;
        }
        getSavedConsoleList();
        emitSavedConsoleUpdated(boundInfo);
        indexDB.deleteValue(storageId);
        lastSyncConsole.current = value;
        saveStatusRef.current = ConsoleStatus.RELEASE;
        setSaveStatus(ConsoleStatus.RELEASE);
        updateWorkspaceTabBoundInfo({
          workspaceTabId: boundInfo.workspaceTabId,
          consoleId: boundInfo.consoleId,
          status: ConsoleStatus.RELEASE,
        });
        if (noPrompting) {
          return;
        }
        staticMessage.success(i18n('common.tips.saveSuccessfully'));
        timingAutoSave();
      });
  };

  function timingAutoSave() {
    if (timerRef.current) {
      clearInterval(timerRef.current);
    }
    timerRef.current = setInterval(() => {
      const curValue = editorRef.current?.getValue();
      if (curValue === lastSyncConsole.current) {
        return;
      }
      if (saveStatusRef.current === ConsoleStatus.RELEASE) {
        saveConsole(curValue, true);
      } else {
        if (isReadOnly || !storageId) {
          lastSyncConsole.current = curValue;
          return;
        }
        indexDB
          .setValue(storageId, {
            ddl: curValue,
            userId: curUser?.id,
          })
          .then(() => {
            lastSyncConsole.current = curValue;
          });
      }
    }, 5000);
  }

  useEffect(() => {
    if (source !== 'workspace') {
      return;
    }
    // Save on exit.
    if (!isActive) {
      // Clear the timer on exit.
      if (timerRef.current) {
        clearInterval(timerRef.current);
      }
      const curValue = editorRef?.current?.getValue();
      if (curValue === lastSyncConsole.current) {
        return;
      }
      if (saveStatusRef.current === ConsoleStatus.RELEASE) {
        saveConsole(curValue, true);
      } else {
        if (isReadOnly || !storageId) {
          lastSyncConsole.current = curValue;
          return;
        }
        indexDB
          .setValue(storageId, {
            ddl: curValue,
            userId: curUser?.id,
          })
          .then(() => {
            lastSyncConsole.current = curValue;
          });
      }
    } else {
      timingAutoSave();
    }
    return () => {
      lastSyncConsole.current = null;
      if (timerRef.current) {
        clearInterval(timerRef.current);
      }
    };
  }, [isActive]);

  useEffect(() => {
    const nextStatus = boundInfo?.status || ConsoleStatus.DRAFT;
    saveStatusRef.current = nextStatus;
    setSaveStatus(nextStatus);
  }, [boundInfo?.consoleId, boundInfo?.status]);

  useEffect(() => {
    if (saveStatus === ConsoleStatus.RELEASE) {
      editorRef?.current?.setValue(defaultValue || '', 'reset');
    } else {
      if (isReadOnly || !storageId) {
        editorRef?.current?.setValue(defaultValue || '', 'reset');
        return;
      }
      indexDB.getValue(storageId).then((res: any) => {
        // oldValue handles functions and views that already carry values and do not need a database lookup.
        const oldValue = editorRef?.current?.getValue();
        if (!oldValue) {
          editorRef?.current?.setValue(res?.ddl || '', 'reset');
        }
      });
    }
  }, []);

  return { saveConsole, saveStatus, hasSavedSqlRecord };
};
