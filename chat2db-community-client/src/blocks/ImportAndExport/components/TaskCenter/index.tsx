import { memo, useEffect, useState } from 'react';
import { useStyles } from './style';
import { IconButton, Empty, EmptyImage } from '@chat2db/ui';
import { Progress, Spin, Tooltip } from 'antd';
import i18n from '@/i18n';
import RunSqlModal from '@/blocks/ImportAndExport/components/RunSqlModal';
import ImportFileModal from '@/blocks/ImportAndExport/components/ImportFileModal';
import importExportServices from '@/service/importExport';
import { useImportExportStore } from '@/store/importExport';
import LogModal from '@/blocks/ImportAndExport/components/LogModal';
import { ImportExportTaskStatus } from '@/constants/importExport';
import dayjs from 'dayjs';
import jcefApi from '@/jcef';
import { isDesktop } from '@/utils/env';
import { CircleCheck, CircleDashed, CircleX, Clock3, LoaderCircle, Trash2 } from 'lucide-react';
import { useGlobalStore } from '@/store/global';
import type { ImportExportTaskDetails } from '@/typings/importExport';

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

export default memo(() => {
  const { styles } = useStyles();
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
    openLogModal,
    setTaskCenterOpen,
  } = useImportExportStore((state) => ({
    getTaskList: state.getTaskList,
    loadMoreTasks: state.loadMoreTasks,
    taskList: state.taskList,
    taskListHasNextPage: state.taskListHasNextPage,
    taskListLoadingMore: state.taskListLoadingMore,
    removeTask: state.removeTask,
    openLogModal: state.openLogModal,
    setTaskCenterOpen: state.setTaskCenterOpen,
  }));
  const openUnifiedConfirmationModal = useGlobalStore((state) => state.openUnifiedConfirmationModal);
  const hasRunningTask = taskList.some((task) => task.status === ImportExportTaskStatus.RUNNING);

  useEffect(() => {
    if (!hasRunningTask) return;
    setNow(Date.now());
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [hasRunningTask]);

  useEffect(() => {
    setTaskCenterOpen(true);
    return () => setTaskCenterOpen(false);
  }, [setTaskCenterOpen]);

  const openArtifact = (task) => {
    if (isDesktop && task.artifactId) {
      jcefApi?.revealInExplorer(task.artifactId);
      return;
    }
    window.open(`/api/tasks/artifact?taskId=${task.id}`, '_blank');
  };

  const handleStopTask = (id) => {
    importExportServices.cancelTask({ taskId: id }).then(() => {
      getTaskList();
    });
  };

  const handleDeleteTask = (task: ImportExportTaskDetails) => {
    openUnifiedConfirmationModal({
      title: i18n('common.text.deleteConfirmTitle'),
      content: i18n('workspace.task.delete.confirm', task.name),
      onOk: () =>
        importExportServices.deleteTask({ taskId: task.id }).then(() => {
          removeTask(task.id);
          return getTaskList();
        }),
    });
  };

  return (
    <div className={styles.wrapper}>
      <div className={styles.title}>
        <span>{i18n('workspace.title.exportProgressBar')}</span>
        <IconButton code="icon-refresh" size={18} onClick={() => void getTaskList()} />
      </div>

      <div
        className={styles.listWrapper}
        onScroll={(event) => {
          const list = event.currentTarget;
          const distanceToBottom = list.scrollHeight - list.scrollTop - list.clientHeight;
          if (distanceToBottom <= 40 && taskListHasNextPage && !taskListLoadingMore) {
            void loadMoreTasks();
          }
        }}
      >
        {taskList.length ? (
          <>
            {taskList.map((item) => {
              const canCancel =
                item.status === ImportExportTaskStatus.PENDING || item.status === ImportExportTaskStatus.RUNNING;
              const statusLabel = i18n(TASK_STATUS_I18N_KEYS[item.status]);
              const startTime = formatTaskTime(item.startedAt, 'YYYY-MM-DD HH:mm:ss');
              const endTime = formatTaskTime(item.finishedAt);
              const duration = formatTaskDuration(item.startedAt, item.finishedAt, item.status, now);
              const fullStartTime = startTime;
              const fullEndTime = formatTaskTime(item.finishedAt, 'YYYY-MM-DD HH:mm:ss');
              const progress = Math.min(100, Math.max(0, Number(item.progress) || 0));
              return (
                <div
                  key={item.id}
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
                      <Tooltip
                        align={{ offset: [-48, 0] }}
                        mouseEnterDelay={0.6}
                        placement="leftTop"
                        title={
                          <div className={styles.timingTooltip}>
                            <div>
                              {i18n('workspace.text.taskName')}: {item.name}
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
                        <span className={styles.taskName}>{item.name}</span>
                      </Tooltip>
                    </div>
                    {canCancel && (
                      <div className={styles.listItemRight}>
                        <IconButton
                          code={'icon-close'}
                          size={{ boxSize: 14, iconSize: 12, borderRadius: 14 }}
                          onClick={(e) => {
                            e.stopPropagation();
                            handleStopTask(item.id);
                          }}
                        />
                      </div>
                    )}
                    <div className={styles.listItemLeft}>
                      <time>{startTime}</time>
                      <span aria-hidden>-</span>
                      <time>{endTime}</time>
                      <span aria-hidden>·</span>
                      <span>
                        {i18n('common.text.timeConsuming')} {duration}
                      </span>
                    </div>
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
                    {!canCancel && (
                      <div className={styles.taskActions}>
                        {item.status === ImportExportTaskStatus.SUCCESS && item.artifactId && (
                          <IconButton
                            code={isDesktop ? 'icon-folder' : 'icon-download'}
                            title={i18n('workspace.text.openFile')}
                            tooltipPlacement="left"
                            size={{ boxSize: 18, iconSize: 13, borderRadius: 3 }}
                            onClick={(e) => {
                              e.stopPropagation();
                              openArtifact(item);
                            }}
                          />
                        )}
                        <IconButton
                          className={styles.deleteAction}
                          icon={Trash2}
                          title={i18n('common.button.delete')}
                          tooltipPlacement="left"
                          size={{ boxSize: 18, iconSize: 13, borderRadius: 3 }}
                          onClick={(e) => {
                            e.stopPropagation();
                            handleDeleteTask(item);
                          }}
                        />
                      </div>
                    )}
                  </div>
                </div>
              );
            })}
            {taskListLoadingMore && (
              <div className={styles.loadMoreIndicator}>
                <Spin size="small" />
              </div>
            )}
          </>
        ) : (
          <Empty image={EmptyImage.Common} title={i18n('workspace.text.noExportTask')} />
        )}
      </div>
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
