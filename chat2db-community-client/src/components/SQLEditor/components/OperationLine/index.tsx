import { useMemo } from 'react';
import { EditorType, SQLOptType } from '../../type';
import { useStyles } from './style';
import { isRoutineOperationSupportedDatabaseType, WorkspaceTabType } from '@/constants';
import SelectBoundInfo from '@/components/SelectBoundInfo';
import { IBoundInfo as IDBInfo } from '@/typings/workspace';
import historyService from '@/service/history';
import i18n from '@/i18n';
import { IconButton, staticMessage, staticModal } from '@chat2db/ui';
import { keyboardKey } from '../../helper/utils';
import { useZoerStore } from '@/store/zoer';
import { useWorkspaceStore } from '@/store/workspace';
import { isTemporaryId } from '@/utils';
import { buildConsoleDefaultTabName } from '@/store/workspace/utils/consoleTabName';
import transactionServer from '@/service/transaction';
import type { TransactionState } from '@/store/workspace/slices/console/initialState';

interface OperationLineProps {
  active: boolean;
  type: EditorType;
  dbInfo: IDBInfo;
  hasEditorContent: boolean;
  setDBInfo: (dbInfo: IDBInfo) => void;
  action: (type: SQLOptType, params?: any) => void;
  isConsole?: boolean;
  contentDiffEnabled?: boolean;
  transactionState?: TransactionState;
  showTransactionControls?: boolean;
}

const OperationLine = ({
  active,
  type,
  dbInfo,
  hasEditorContent,
  setDBInfo,
  action,
  isConsole = true,
  contentDiffEnabled = false,
  transactionState,
  showTransactionControls = false,
}: OperationLineProps) => {
  const { styles, cx } = useStyles();

  const { zoerBoundInfo } = useZoerStore((state) => ({
    zoerBoundInfo: state.zoerBoundInfo,
  }));

  const showRunButton = useMemo(() => {
    return [WorkspaceTabType.CONSOLE, WorkspaceTabType.LocalSQLFile].includes(type);
  }, [type]);

  const showRoutineButtons = useMemo(() => {
    return isRoutineOperationSupportedDatabaseType(dbInfo.databaseType)
      && [WorkspaceTabType.FUNCTION, WorkspaceTabType.PROCEDURE].includes(type);
  }, [dbInfo.databaseType, type]);

  const showSettingButton = useMemo(() => {
    if (showRoutineButtons) {
      return false;
    }
    if (zoerBoundInfo) {
      return false;
    }
    return true;
  }, [showRoutineButtons, zoerBoundInfo]);

  const showOptimizeButton = useMemo(() => {
    return showRunButton && !zoerBoundInfo;
  }, [showRunButton]);

  const showFormatButton = useMemo(() => {
    return !showRoutineButtons;
  }, [showRoutineButtons]);

  const showSaveFileButton = useMemo(() => {
    return [WorkspaceTabType.LocalSQLFile].includes(type);
  }, [type]);

  const showSaveButton = useMemo(() => {
    return [WorkspaceTabType.CONSOLE].includes(type) && dbInfo.consoleId && !isTemporaryId(dbInfo.consoleId);
  }, [type, dbInfo]);

  const showRunViewButton = useMemo(() => {
    return [WorkspaceTabType.VIEW].includes(type);
  }, [type, dbInfo]);

  const showSaveFunctionButton = useMemo(() => {
    return [
      WorkspaceTabType.VIEW,
      WorkspaceTabType.TRIGGER,
    ].includes(type);
  }, [type, dbInfo]);

  const showSaveFileToDesktopButton = useMemo(() => {
    return !showRoutineButtons;
  }, [showRoutineButtons]);

  const showRunSigleSQLButton = useMemo(() => {
    return [WorkspaceTabType.CONSOLE, WorkspaceTabType.LocalSQLFile].includes(type);
  }, [type, dbInfo]);

  const showSelectDBInfoComponent = useMemo(() => {
    return [WorkspaceTabType.CONSOLE, WorkspaceTabType.LocalSQLFile].includes(type);
  }, [type]);

  const handleChangeDBInfo = async (_dbInfo: IDBInfo) => {
    // Switching the connection changes the catalog; an open transaction cannot survive it, so
    // confirm and roll back first when a transaction is in progress.
    if (transactionState?.inTransaction && typeof dbInfo.consoleId === 'number') {
      const confirmed = await new Promise<boolean>((resolve) => {
        staticModal.confirm({
          title: i18n('workspace.transaction.switchConnectionTitle'),
          content: i18n('workspace.transaction.switchConnectionContent'),
          okText: i18n('workspace.transaction.rollback'),
          cancelText: i18n('common.button.cancel'),
          onOk: () => resolve(true),
          onCancel: () => resolve(false),
        });
      });
      if (!confirmed) {
        return;
      }
      try {
        await transactionServer.rollbackTransaction({
          dataSourceId: dbInfo.dataSourceId as number,
          databaseName: dbInfo.databaseName,
          schemaName: dbInfo.schemaName,
          consoleId: dbInfo.consoleId,
        });
        useWorkspaceStore.getState().setTransactionState(dbInfo.consoleId, { inTransaction: false });
      } catch (error) {
        // The rollback failed, so the server still holds an open transaction; abort the
        // switch instead of silently abandoning it.
        staticMessage.error(String(error));
        return;
      }
    }
    const nameCustomized = _dbInfo.nameCustomized ?? dbInfo.nameCustomized ?? false;
    const nextDBInfo = {
      ..._dbInfo,
      nameCustomized,
    };
    setDBInfo(nextDBInfo);
    if (!nextDBInfo.consoleId || isTemporaryId(nextDBInfo.consoleId)) {
      return;
    }
    historyService.updateSavedConsole({
      id: nextDBInfo.consoleId,
      dataSourceId: nextDBInfo.dataSourceId,
      dataSourceName: nextDBInfo.dataSourceName,
      databaseName: nextDBInfo.databaseName,
      schemaName: nextDBInfo.schemaName,
      type: nextDBInfo.databaseType,
      name: nameCustomized ? undefined : buildConsoleDefaultTabName(nextDBInfo),
      nameCustomized,
    });
  };

  const shouldDisableActionButton = !hasEditorContent;

  return (
    <div className={styles.consoleOptionsWrapper}>
      <div className={styles.consoleOptionsLeft}>
        {showRunButton && (
          <>
            <IconButton
              code="icon-play"
              size="sm"
              title={i18n('common.button.execute') + ' ' + [keyboardKey.command, 'R'].join('+')}
              className={cx(styles.operatingButtonIcon, styles.iconButtonPlay)}
              onClick={() => {
                action(SQLOptType.EXECUTE_SQL);
              }}
            />
          </>
        )}

        {showRunSigleSQLButton && (
          <IconButton
            className={styles.operatingButtonIcon}
            code="icon-play1"
            size="sm"
            disabled={shouldDisableActionButton}
            title={i18n('common.button.executeSingleSQL')}
            onClick={() => {
              action(SQLOptType.EXECUTE_SINGLE_SQL);
            }}
          />
        )}
        {showRunButton && (
          <IconButton
            className={styles.operatingButtonIcon}
            code="icon-sort-ascending1"
            size="sm"
            disabled={shouldDisableActionButton}
            title={i18n('common.button.explain')}
            onClick={() => {
              action(SQLOptType.EXPLAIN_SQL);
            }}
          />
        )}

        {showTransactionControls && (
          <>
            <IconButton
              className={styles.operatingButtonIcon}
              code="icon-switch-horizontal"
              size="sm"
              title={i18n('common.button.autoCommit')}
              onClick={() => {
                action(SQLOptType.TOGGLE_AUTO_COMMIT);
              }}
            />
            {transactionState?.mode === 'manual' && (
              <>
                <IconButton
                  className={styles.operatingButtonIcon}
                  code="icon-check"
                  size="sm"
                  disabled={!transactionState?.inTransaction}
                  title={i18n('common.button.commit')}
                  onClick={() => {
                    action(SQLOptType.COMMIT_TRANSACTION);
                  }}
                />
                <IconButton
                  className={styles.operatingButtonIcon}
                  code="icon-huigun"
                  size="sm"
                  disabled={!transactionState?.inTransaction}
                  title={i18n('common.button.rollback')}
                  onClick={() => {
                    action(SQLOptType.ROLLBACK_TRANSACTION);
                  }}
                />
              </>
            )}
          </>
        )}

        {(showRunSigleSQLButton || showRunButton) && <div className={styles.partingLine} />}

        {showRoutineButtons && (
          <>
            <IconButton
              code="icon-play"
              size="sm"
              title={i18n('workspace.routine.button.invoke')}
              className={cx(styles.operatingButtonIcon, styles.iconButtonPlay)}
              onClick={() => {
                action(SQLOptType.EXECUTE_ROUTINE);
              }}
            />
            <IconButton
              className={styles.operatingButtonIcon}
              code="icon-upload"
              size="sm"
              disabled={shouldDisableActionButton}
              title={i18n('workspace.routine.button.apply')}
              onClick={() => {
                action(SQLOptType.APPLY_ROUTINE_DDL);
              }}
            />
            <IconButton
              className={styles.operatingButtonIcon}
              code="icon-refresh"
              size="sm"
              title={i18n('workspace.routine.button.refresh')}
              onClick={() => {
                action(SQLOptType.REFRESH_ROUTINE_DDL);
              }}
            />
            <IconButton
              className={styles.operatingButtonIcon}
              code="icon-huigun"
              size="sm"
              disabled={shouldDisableActionButton}
              title={i18n('workspace.routine.button.revert')}
              onClick={() => {
                action(SQLOptType.REVERT_ROUTINE_DDL);
              }}
            />
          </>
        )}

        {showRoutineButtons && contentDiffEnabled && (
          <IconButton
            className={styles.operatingButtonIcon}
            code="icon-switch-horizontal"
            size="sm"
            title={i18n('monaco.text.showDiff')}
            onClick={() => {
              action(SQLOptType.OPEN_CONTENT_DIFF);
            }}
          />
        )}

        {showSaveFileButton && isConsole && (
          <IconButton
            className={styles.operatingButtonIcon}
            code="icon-Vector"
            size="sm"
            disabled={shouldDisableActionButton}
            title={i18n('common.button.save') + ' ' + [keyboardKey.command, 'S'].join('+')}
            onClick={() => {
              action(SQLOptType.SAVE_FILE);
            }}
          />
        )}

        {showSaveButton && isConsole && (
          <IconButton
            className={styles.operatingButtonIcon}
            code="icon-Vector"
            size="sm"
            disabled={shouldDisableActionButton}
            title={i18n('common.button.save') + ' ' + [keyboardKey.command, 'S'].join('+')}
            onClick={() => {
              action(SQLOptType.SAVE_SQL);
            }}
          />
        )}

        {showRunViewButton && (
          <IconButton
            className={styles.operatingButtonIcon}
            code="icon-yulan1"
            size="sm"
            disabled={shouldDisableActionButton}
            title={i18n('common.title.preview') + ' ' + [keyboardKey.command, 'R'].join('+')}
            onClick={() => {
              action(SQLOptType.EXECUTE_TABLE, { tableName: dbInfo.viewName! });
            }}
          />
        )}

        {showSaveFunctionButton && (
          <IconButton
            className={styles.operatingButtonIcon}
            code="icon-Vector"
            size="sm"
            disabled={shouldDisableActionButton}
            title={i18n('common.button.save')}
            onClick={() => {
              action(SQLOptType.EXECUTE_SQL);
            }}
          />
        )}

        {showSaveFileToDesktopButton && isConsole && (
          <IconButton
            className={styles.operatingButtonIcon}
            code="icon-lingcunwei"
            size="sm"
            disabled={shouldDisableActionButton}
            title={i18n('common.text.saveAsSQLFile')}
            onClick={() => {
              action(SQLOptType.SAVE_FILE_TO_DESKTOP);
            }}
          />
        )}

        {showFormatButton && (
          <IconButton
            className={styles.operatingButtonIcon}
            code="icon-geshihua"
            size="sm"
            disabled={shouldDisableActionButton}
            title={i18n('common.button.format')}
            onClick={() => {
              action(SQLOptType.FORMAT_SQL);
            }}
          />
        )}

        {!showRoutineButtons && contentDiffEnabled && (
          <IconButton
            className={styles.operatingButtonIcon}
            code="icon-switch-horizontal"
            size="sm"
            title={i18n('monaco.text.showDiff')}
            onClick={() => {
              action(SQLOptType.OPEN_CONTENT_DIFF);
            }}
          />
        )}

        {showOptimizeButton && (
          <>
            <div className={styles.partingLine} />
            <IconButton
              className={styles.operatingButtonIcon}
              code="icon-inbox-in"
              size="sm"
              disabled={shouldDisableActionButton}
              title={i18n('common.button.optimize')}
              onClick={() => {
                action(SQLOptType.SQL_OPTIMIZER);
              }}
            />
          </>
        )}

        {showSettingButton && (
          <>
            <div className={styles.partingLine} />
            <IconButton
              className={styles.operatingButtonIcon}
              code="icon-setting"
              size="sm"
              // title={i18n('common.button.setting')}
              onClick={() => {
                action(SQLOptType.OPEN_SETTINGS);
              }}
            />
          </>
        )}
      </div>

      {showSelectDBInfoComponent && !zoerBoundInfo && active && (
        <SelectBoundInfo boundInfo={dbInfo} onChangeDBInfo={handleChangeDBInfo} allowEmpty />
      )}
    </div>
  );
};

export default OperationLine;
