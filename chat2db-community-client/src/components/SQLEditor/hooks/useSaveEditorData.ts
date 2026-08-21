import { useState, useEffect, useRef, useCallback } from 'react';
import { ConsoleOpenedStatus, ConsoleStatus, WorkspaceTabType } from '@/constants';
import historyServer from '@/service/history';
import i18n from '@/i18n';
import { useWorkspaceStore } from '@/store/workspace';
import { staticMessage } from '@chat2db/ui';
import { useUserStore } from '@/store/session';
import { useIndexDBStore } from '@/store/indexDB';
import { SQLEditorRef } from '@/components/SQLEditor/editor/SQLEditor';
import type { IBoundInfo } from '@/typings';
import { isTemporaryId } from '@/utils';
import { emitSavedConsoleUpdated } from '@/utils/savedConsoleEvents';
import {
  savedConsoleMutationCoordinator,
  type SavedConsoleSaveMode,
} from '@/store/workspace/utils/savedConsoleMutationCoordinator';
import { persistSavedConsoleRecord } from '@/store/workspace/utils/savedConsolePersistence';

interface IProps {
  isActive?: boolean;
  source?: 'workspace';
  editorRef: React.RefObject<SQLEditorRef>;
  boundInfo: IBoundInfo;
  defaultValue?: string;
  name?: string;
  onBoundInfoChange?: (boundInfo: IBoundInfo) => void;
  type:
    | WorkspaceTabType.CONSOLE
    | WorkspaceTabType.FUNCTION
    | WorkspaceTabType.PROCEDURE
    | WorkspaceTabType.TRIGGER
    | WorkspaceTabType.VIEW
    | WorkspaceTabType.LocalSQLFile;
}

interface SaveConsoleOptions {
  mode?: SavedConsoleSaveMode;
  initialName?: string;
}

export const useSaveEditorData = (props: IProps) => {
  const { isActive, source, editorRef, boundInfo, defaultValue, name, onBoundInfoChange, type } = props;

  const timerRef = useRef<any>();
  const effectiveConsoleIdRef = useRef<number | undefined>(boundInfo?.consoleId);
  // Console data from the previous synchronization.
  const lastSyncConsole = useRef<any>(defaultValue);
  const storageId = boundInfo?.workspaceTabId ?? boundInfo?.consoleId;
  const isReadOnly = !!boundInfo?.readOnly;
  const [saveStatus, setSaveStatus] = useState<ConsoleStatus>(boundInfo?.status || ConsoleStatus.DRAFT);
  const saveStatusRef = useRef<ConsoleStatus>(boundInfo?.status || ConsoleStatus.DRAFT);
  const { getSavedConsoleList, savedConsoleList, markWorkspaceTabConsoleSaved } = useWorkspaceStore((s) => ({
    getSavedConsoleList: s.getSavedConsoleList,
    savedConsoleList: s.savedConsoleList,
    markWorkspaceTabConsoleSaved: s.markWorkspaceTabConsoleSaved,
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

  const saveConsole = (value?: string, options: SaveConsoleOptions = {}) => {
    const mode = options.mode || 'manual';
    const initialName = options.initialName?.trim();
    const nameCustomized = boundInfo.nameCustomized === true || Boolean(initialName && initialName !== name);
    const consoleId = effectiveConsoleIdRef.current;
    const p: any = {
      id: consoleId,
      status: ConsoleStatus.RELEASE,
      ddl: value,
    };
    if (initialName) {
      p.name = initialName;
      p.nameCustomized = nameCustomized;
    }

    if (!storageId) {
      return Promise.resolve();
    }

    if (isReadOnly) {
      lastSyncConsole.current = value;
      return Promise.resolve();
    }

    const isPersistedConsole =
      type === WorkspaceTabType.CONSOLE && typeof consoleId === 'number' && !isTemporaryId(consoleId);
    if (!isPersistedConsole) {
      return indexDB
        .setValue(storageId, {
          ddl: value,
          userId: curUser?.id,
        })
        .then(() => {
          lastSyncConsole.current = value;
        });
    }

    return savedConsoleMutationCoordinator
      .save(consoleId, mode, () =>
        persistSavedConsoleRecord(historyServer, {
          manual: mode === 'manual',
          createParams: {
            id: consoleId,
            name: initialName || name || boundInfo.databaseName || boundInfo.schemaName || '',
            ddl: value || '',
            dataSourceId: boundInfo.dataSourceId,
            dataSourceName: boundInfo.dataSourceName,
            type: boundInfo.databaseType,
            databaseName: boundInfo.databaseName,
            schemaName: boundInfo.schemaName,
            nameCustomized,
            status: ConsoleStatus.RELEASE,
            tabOpened: ConsoleOpenedStatus.IS_OPEN,
            operationType: WorkspaceTabType.CONSOLE,
          },
          updateParams: p,
        }),
      )
      .then(async (result) => {
        if (!result.executed) {
          await indexDB
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
        const persistedConsoleId = result.value?.consoleId;
        if (persistedConsoleId === undefined) {
          return;
        }
        effectiveConsoleIdRef.current = persistedConsoleId;
        const savedBoundInfo = {
          ...boundInfo,
          consoleId: persistedConsoleId,
          status: ConsoleStatus.RELEASE,
          nameCustomized: initialName ? nameCustomized : boundInfo.nameCustomized,
        };
        if (persistedConsoleId !== consoleId || saveStatusRef.current !== ConsoleStatus.RELEASE) {
          onBoundInfoChange?.(savedBoundInfo);
        }
        getSavedConsoleList();
        emitSavedConsoleUpdated(savedBoundInfo);
        void indexDB.deleteValue(storageId);
        lastSyncConsole.current = value;
        saveStatusRef.current = ConsoleStatus.RELEASE;
        setSaveStatus(ConsoleStatus.RELEASE);
        markWorkspaceTabConsoleSaved({
          workspaceTabId: boundInfo.workspaceTabId,
          consoleId: persistedConsoleId,
          name: initialName,
          nameCustomized: initialName ? nameCustomized : undefined,
        });
        if (mode === 'automatic') {
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
        void saveConsole(curValue, { mode: 'automatic' });
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
        void saveConsole(curValue, { mode: 'automatic' });
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
    effectiveConsoleIdRef.current = boundInfo?.consoleId;
  }, [boundInfo?.consoleId]);

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

  const hasUnsavedChanges = useCallback(
    (value: string) => {
      if (!value.trim()) {
        return false;
      }
      return !hasSavedSqlRecord || value !== lastSyncConsole.current;
    },
    [hasSavedSqlRecord],
  );

  return { saveConsole, saveStatus, hasSavedSqlRecord, hasUnsavedChanges };
};
