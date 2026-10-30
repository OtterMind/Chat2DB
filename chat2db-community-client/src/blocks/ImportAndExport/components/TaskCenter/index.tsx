import { memo, type KeyboardEvent, type ReactNode, type UIEvent, useEffect, useMemo, useRef, useState } from 'react';
import { useSize } from 'ahooks';
import VirtualList from 'rc-virtual-list';
import { useStyles } from './style';
import { IconButton, Empty, EmptyImage, staticMessage } from '@chat2db/ui';
import { Progress, Spin, Tooltip } from 'antd';
import i18n from '@/i18n';
import RunSqlModal from '@/blocks/ImportAndExport/components/RunSqlModal';
import ImportFileModal from '@/blocks/ImportAndExport/components/ImportFileModal';
import importExportServices from '@/service/importExport';
import { useImportExportStore } from '@/store/importExport';
import { canCancelTask } from '@/store/importExport/taskCenterUtils';
import { hasSavedTaskSpec } from '@/blocks/ImportAndExport/utils/restoreTaskSpec';
import { groupTaskAttempts } from '../../utils/taskAttempts';
import { formatTaskName } from '@/blocks/ImportAndExport/utils/taskName';
import {
  buildTaskArtifactUrl,
  buildTaskErrorArtifactUrl,
  canDownloadErrorArtifact,
  canResumeTask,
  isImportTaskType,
} from '@/blocks/ImportAndExport/utils/taskArtifacts';
import { useTaskResume } from '@/blocks/ImportAndExport/components/useTaskResume';
import LogModal from '@/blocks/ImportAndExport/components/LogModal';
import { ACTIVE_TASK_STATUSES, ImportExportTaskStatus } from '@/constants/importExport';
import dayjs from 'dayjs';
import jcefApi from '@/jcef';
import { isDesktop } from '@/utils/env';
import {
  Ban,
  CircleCheck,
  CircleDashed,
  CircleX,
  Clock3,
  CopyPlus,
  Download,
  LoaderCircle,
  Play,
  RotateCw,
  Trash2,
  Upload,
} from 'lucide-react';
import { useGlobalStore } from '@/store/global';
import type { ImportExportTaskDetails } from '@/typings/importExport';
import PanelToolbar, { PANEL_TOOLBAR_BUTTON_SIZE } from '@/components/PanelToolbar';

const TASK_STATUS_I18N_KEYS = {
  [ImportExportTaskStatus.PENDING]: 'workspace.task.status.pending',
  [ImportExportTaskStatus.RUNNING]: 'workspace.task.status.running',
  [ImportExportTaskStatus.SUCCESS]: 'workspace.task.status.success',
  [ImportExportTaskStatus.FAILED]: 'workspace.task.status.failed',
  [ImportExportTaskStatus.CANCELLED]: 'workspace.task.status.cancelled',
} as const;

const TaskStatusIcon = ({ status }: { status: ImportExportTaskStatus }) => {
  if (status === ImportExportTaskStatus.SUCCESS) return <CircleCheck aria-hidden size={14} />;
  if (status === ImportExportTaskStatus.FAILED) return <CircleX aria-hidden size={14} />;
  if (status === ImportExportTaskStatus.CANCELLED) return <CircleDashed aria-hidden size={14} />;
  if (status === ImportExportTaskStatus.RUNNING) return <LoaderCircle aria-hidden size={14} />;
  return <Clock3 aria-hidden size={14} />;
};

const formatTaskTime = (time?: number | string, format = 'HH:mm:ss') => {
  if (!time) return '--';
  const value = dayjs(time);
  return value.isValid() ? value.format(format) : '--';
};

const formatTaskDuration = (
  startedAt: number | string | undefined,
  finishedAt: number | string | undefined,
  status: ImportExportTaskStatus,
  now: number,
) => {
  if (!startedAt) return '--';
  const start = dayjs(startedAt);
  const end = status === ImportExportTaskStatus.RUNNING ? dayjs(now) : finishedAt ? dayjs(finishedAt) : null;
  if (!start.isValid() || !end?.isValid()) return '--';

  const totalSeconds = Math.max(0, Math.floor(end.diff(start) / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours) return `${hours}${i18n('common.text.hour')} ${minutes}${i18n('common.text.minute')}`;
  if (minutes) return `${minutes}${i18n('common.text.minute')} ${seconds}${i18n('common.text.second')}`;
  return `${seconds}${i18n('common.text.second')}`;
};

// rc-virtual-list needs an estimate to enable virtualization; it measures the real row heights.
const TASK_CENTER_ITEM_ESTIMATED_HEIGHT = 76;

interface TaskCenterProps {
  headerLeading?: ReactNode;
}

export default memo<TaskCenterProps>(({ headerLeading }) => {
  const { styles } = useStyles();
  const listWrapperRef = useRef<HTMLDivElement>(null);
  const viewportSize = useSize(listWrapperRef);
  const [now, setNow] = useState(() => Date.now());
  const [highlightedTaskIds] = useState<Set<number>>(
    () => new Set(useImportExportStore.getState().unreadCompletedTaskIds),
  );

  const {
    getTaskList,
    loadMoreTasks,
    taskList,
    taskListHasNextPage,
    taskListLoadingMore,
    removeTask,
    cancelTask,
    cancellingTaskIds,
    reuseTaskConfiguration,
    openLogModal,
    setTaskCenterOpen,
  } = useImportExportStore((state) => ({
    getTaskList: state.getTaskList,
    loadMoreTasks: state.loadMoreTasks,
    taskList: state.taskList,
    taskListHasNextPage: state.taskListHasNextPage,
    taskListLoadingMore: state.taskListLoadingMore,
    removeTask: state.removeTask,
    cancelTask: state.cancelTask,
    cancellingTaskIds: state.cancellingTaskIds,
    reuseTaskConfiguration: state.reuseTaskConfiguration,
    openLogModal: state.openLogModal,
    setTaskCenterOpen: state.setTaskCenterOpen,
  }));
  const openUnifiedConfirmationModal = useGlobalStore((state) => state.openUnifiedConfirmationModal);
  const { resumeTask, resumeContextHolder } = useTaskResume();
  const taskGroups = useMemo(() => groupTaskAttempts(taskList), [taskList]);
  useEffect(() => {
    if (taskListHasNextPage && !taskListLoadingMore && viewportSize?.height &&
        taskGroups.length * TASK_CENTER_ITEM_ESTIMATED_HEIGHT < viewportSize.height) {
      void loadMoreTasks().catch(() => undefined);
    }
  }, [taskGroups.length, taskList.length, taskListHasNextPage,
    taskListLoadingMore, viewportSize?.height, loadMoreTasks]);
  const hasRunningTask = taskList.some((task) => task.status === ImportExportTaskStatus.RUNNING);

  useEffect(() => {
    if (!hasRunningTask) return;
    setNow(Date.now());
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [hasRunningTask]);

  useEffect(() => {
    setTaskCenterOpen(true);
    void getTaskList();
    return () => setTaskCenterOpen(false);
  }, [getTaskList, setTaskCenterOpen]);

  const openArtifact = (task) => {
    if (isDesktop && task.artifactId) {
      jcefApi?.revealInExplorer(task.artifactId);
      return;
    }
    window.open(buildTaskArtifactUrl(task.id), '_blank');
  };

  const openTaskErrorArtifact = (task: ImportExportTaskDetails) => {
    if (isDesktop && task.errorArtifactId) {
      jcefApi?.revealInExplorer(task.errorArtifactId);
      return;
    }
    window.open(buildTaskErrorArtifactUrl(task.id), '_blank');
  };

  const handleListScroll = (event: UIEvent<HTMLElement>) => {
    const list = event.currentTarget;
    const distanceToBottom = list.scrollHeight - list.scrollTop - list.clientHeight;
    if (distanceToBottom <= 40 && taskListHasNextPage && !taskListLoadingMore) {
      void loadMoreTasks().catch((error: { errorMessage?: string; message?: string }) => {
        staticMessage.error(error.errorMessage || error.message || i18n('common.text.failure'));
      });
    }
  };

  const handleDeleteTask = (task: ImportExportTaskDetails) => {
    openUnifiedConfirmationModal({
      title: i18n('common.text.deleteConfirmTitle'),
      content: i18n('workspace.task.delete.confirm', formatTaskName(task.name, i18n)),
      onOk: () =>
        importExportServices.deleteTask({ taskId: task.id }).then(() => {
          removeTask(task.id);
          return getTaskList();
        }),
    });
  };

  // "New from this configuration": restores the saved snapshot into the wizard. A task without one
  // cannot be reused, and a damaged snapshot is reported instead of opening an empty wizard.
  const handleReuseTask = (task: ImportExportTaskDetails) => {
    const result = reuseTaskConfiguration(task);
    if (!result.ok) {
      staticMessage.error(i18n(result.i18nKey));
    }
  };

  const handleReuseTaskKeyDown = (event: KeyboardEvent<HTMLDivElement>, task: ImportExportTaskDetails) => {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    event.preventDefault();
    event.stopPropagation();
    if (!hasSavedTaskSpec(task)) {
      staticMessage.info(i18n('workspace.task.reuse.unavailable'));
      return;
    }
    handleReuseTask(task);
  };

  const handleCancelTask = (task: ImportExportTaskDetails) => {
    void cancelTask(task.id).catch((error: { errorMessage?: string }) => {
      staticMessage.error(error?.errorMessage || i18n('common.text.cancelRequestFailed'));
    });
  };

  return (
    <div className={styles.wrapper}>
      {resumeContextHolder}
      <PanelToolbar
        leading={headerLeading ?? <span>{i18n('workspace.title.exportProgressBar')}</span>}
        trailing={
          <IconButton
            icon={RotateCw}
            size={PANEL_TOOLBAR_BUTTON_SIZE}
            onClick={() => void getTaskList()}
          />
        }
      />

      <div className={styles.listWrapper} ref={listWrapperRef}>
        {taskList.length ? (
          // Virtual height compensation scrolls expanding cards out of view.
          // Use normal scrolling and retain paged loading for the task history.
          <VirtualList
            className={styles.virtualList}
            data={taskGroups}
            height={Math.max(1, Math.round(viewportSize?.height || 1))}
            itemHeight={TASK_CENTER_ITEM_ESTIMATED_HEIGHT}
            virtual={false}
            itemKey="id"
            onScroll={handleListScroll}
            showScrollBar="optional"
          >
            {(group) => {
              const item = group.latest;
              const isActive = ACTIVE_TASK_STATUSES.includes(item.status);
              const isCancellable = canCancelTask(item.status);
              const isCancelling = cancellingTaskIds.includes(item.id);
              const hasSavedSpec = hasSavedTaskSpec(item);
              const reuseTitle = hasSavedSpec
                ? i18n('workspace.task.reuse.action')
                : i18n('workspace.task.reuse.unavailable');
              const statusLabel = i18n(TASK_STATUS_I18N_KEYS[item.status]);
              const isImport = isImportTaskType(item.type);
              const typeLabel = i18n(isImport ? 'workspace.menu.importData' : 'workspace.menu.exportData');
              const targetLabel = [item.target?.databaseName, item.target?.tableName].filter(Boolean).join('.');
              const canResume = canResumeTask(item);
              const hasErrorArtifact = canDownloadErrorArtifact(item);
              const startTime = formatTaskTime(item.startedAt, 'YYYY-MM-DD HH:mm:ss');
              const endTime = formatTaskTime(item.finishedAt);
              const duration = formatTaskDuration(item.startedAt, item.finishedAt, item.status, now);
              const fullStartTime = startTime;
              const fullEndTime = formatTaskTime(item.finishedAt, 'YYYY-MM-DD HH:mm:ss');
              const progress = Math.min(100, Math.max(0, Number(item.progress) || 0));
              return (
                <div
                  className={styles.listItem}
                  data-highlighted={highlightedTaskIds.has(item.id)}
                  data-status={item.status}
                  onClick={() => openLogModal(item.id)}
                >
                  <div className={styles.taskCard}>
                    <div className={styles.taskItemHeader}>
                      <span
                        aria-label={statusLabel}
                        className={styles.taskStatusIcon}
                        data-status={item.status}
                        role="img"
                      >
                        <TaskStatusIcon status={item.status} />
                      </span>
                      <span
                        aria-label={typeLabel}
                        className={styles.taskTypeIcon}
                        data-type={isImport ? 'IMPORT' : 'EXPORT'}
                        role="img"
                        title={typeLabel}
                      >
                        {isImport ? <Upload size={13} aria-hidden /> : <Download size={13} aria-hidden />}
                      </span>
                      <Tooltip
                        align={{ offset: [-48, 0] }}
                        mouseEnterDelay={0.6}
                        placement="leftTop"
                        title={
                          <div className={styles.timingTooltip}>
                            <div>
                              {i18n('workspace.text.taskName')}: {formatTaskName(group.name, i18n)}
                            </div>
                            <div>
                              {i18n('workspace.text.taskStatus')}: {statusLabel}
                            </div>
                            <div>
                              {i18n('workspace.text.startTime')}: {fullStartTime}
                            </div>
                            <div>
                              {i18n('workspace.text.endTime')}: {fullEndTime}
                            </div>
                            <div>
                              {i18n('common.text.timeConsuming')}: {duration}
                            </div>
                          </div>
                        }
                      >
                        <span className={styles.taskName}>{formatTaskName(group.name, i18n)}</span>
                      </Tooltip>
                    </div>
                    {targetLabel && <div className={styles.taskTarget}>{targetLabel}</div>}
                    {group.resumeCount > 0 && (
                      <details className={styles.attemptHistory} onClick={(event) => event.stopPropagation()}>
                        <summary>{i18n('workspace.task.resume.history', group.resumeCount)}</summary>
                        {group.attempts.map((attempt) => (
                          <button key={attempt.id} type="button" onClick={() => openLogModal(attempt.id)}>
                            {formatTaskTime(attempt.startedAt, 'YYYY-MM-DD HH:mm:ss')} · {' '}
                            {i18n(TASK_STATUS_I18N_KEYS[attempt.status])}
                          </button>
                        ))}
                        {group.attempts.length <= group.resumeCount && taskListHasNextPage && (
                          <button
                            type="button"
                            disabled={taskListLoadingMore}
                            onClick={() => void loadMoreTasks().catch(() => undefined)}
                          >
                            {i18n('workspace.task.resume.loadHistory')}
                          </button>
                        )}
                      </details>
                    )}
                    <div className={styles.listItemLeft}>
                      <time>{startTime}</time>
                      <span aria-hidden>{isActive ? '·' : '-'}</span>
                      {isActive ? (
                        <span className={styles.activeStatus} data-status={item.status}>
                          {statusLabel}
                        </span>
                      ) : (
                        <time>{endTime}</time>
                      )}
                      <span aria-hidden>·</span>
                      <span>
                        {i18n('common.text.timeConsuming')} {duration}
                      </span>
                    </div>
                    {item.status === ImportExportTaskStatus.FAILED && item.errorMessage && (
                      <button
                        type="button"
                        className={styles.taskErrorSummary}
                        title={item.errorMessage}
                        onClick={(event) => {
                          event.stopPropagation();
                          openLogModal(item.id);
                        }}
                      >
                        {item.errorMessage}
                      </button>
                    )}
                    {item.status === ImportExportTaskStatus.RUNNING && (
                      <div className={styles.taskProgress}>
                        <Progress
                          className={styles.taskProgressBar}
                          percent={progress}
                          size="small"
                          showInfo={false}
                        />
                        <span className={styles.taskProgressValue}>{progress}%</span>
                      </div>
                    )}
                    <div className={styles.taskActions}>
                      <IconButton
                        aria-disabled={!hasSavedSpec}
                        aria-label={reuseTitle}
                        disabled={!hasSavedSpec}
                        icon={CopyPlus}
                        onKeyDown={(event) => handleReuseTaskKeyDown(event, item)}
                        role="button"
                        size={{ boxSize: 18, iconSize: 13, borderRadius: 3 }}
                        tabIndex={0}
                        title={reuseTitle}
                        tooltipPlacement="left"
                        onClick={(event) => {
                          event.stopPropagation();
                          if (!hasSavedSpec) {
                            staticMessage.info(i18n('workspace.task.reuse.unavailable'));
                            return;
                          }
                          handleReuseTask(item);
                        }}
                      />
                      {isCancellable && (
                        <IconButton
                          aria-disabled={isCancelling}
                          aria-label={i18n('common.button.cancel')}
                          disabled={isCancelling}
                          icon={Ban}
                          onKeyDown={(event) => {
                            if (event.key !== 'Enter' && event.key !== ' ') return;
                            event.preventDefault();
                            event.stopPropagation();
                            if (isCancelling) return;
                            handleCancelTask(item);
                          }}
                          role="button"
                          size={{ boxSize: 18, iconSize: 13, borderRadius: 3 }}
                          spin={isCancelling}
                          tabIndex={0}
                          title={i18n('common.button.cancel')}
                          tooltipPlacement="left"
                          onClick={(event) => {
                            event.stopPropagation();
                            handleCancelTask(item);
                          }}
                        />
                      )}
                      {!isActive && (
                        <>
                          {canResume && (
                            <IconButton
                              aria-label={i18n('workspace.task.resume.action')}
                              icon={Play}
                              title={i18n('workspace.task.resume.action')}
                              tooltipPlacement="left"
                              size={{ boxSize: 18, iconSize: 13, borderRadius: 3 }}
                              onClick={(event) => {
                                event.stopPropagation();
                                resumeTask(item);
                              }}
                            />
                          )}
                          {item.status === ImportExportTaskStatus.SUCCESS && item.artifactId && (
                            <IconButton
                              code={isDesktop ? 'icon-folder' : 'icon-download'}
                              title={i18n(isDesktop ? 'workspace.text.openFile' : 'workspace.task.export.download')}
                              tooltipPlacement="left"
                              size={{ boxSize: 18, iconSize: 13, borderRadius: 3 }}
                              onClick={(e) => {
                                e.stopPropagation();
                                openArtifact(item);
                              }}
                            />
                          )}
                          {hasErrorArtifact && (
                            <IconButton
                              aria-label={i18n('workspace.task.errorFile.download')}
                              code={isDesktop ? 'icon-folder' : 'icon-download'}
                              title={i18n('workspace.task.errorFile.download')}
                              tooltipPlacement="left"
                              size={{ boxSize: 18, iconSize: 13, borderRadius: 3 }}
                              onClick={(event) => {
                                event.stopPropagation();
                                openTaskErrorArtifact(item);
                              }}
                            />
                          )}
                          <IconButton
                            className={styles.deleteAction}
                            icon={Trash2}
                            title={i18n(group.resumeCount > 0 ? 'workspace.task.resume.deleteAttempt' : 'common.button.delete')}
                            tooltipPlacement="left"
                            size={{ boxSize: 18, iconSize: 13, borderRadius: 3 }}
                            onClick={(e) => {
                              e.stopPropagation();
                              handleDeleteTask(item);
                            }}
                          />
                        </>
                      )}
                    </div>
                  </div>
                </div>
              );
            }}
          </VirtualList>
        ) : (
          <Empty image={EmptyImage.Common} title={i18n('workspace.text.noExportTask')} />
        )}
      </div>
      {taskListLoadingMore && (
        <div className={styles.loadMoreIndicator}>
          <Spin size="small" />
        </div>
      )}
    </div>
  );
});

export const TaskCenterModals = memo(() => {
  return (
    <>
      <LogModal />
      <RunSqlModal />
      <ImportFileModal />
    </>
  );
});
