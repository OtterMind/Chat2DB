import { memo, useEffect, useState, useRef } from 'react';
import { useStyles } from './style';
import { IconfontSvg, IconButton, Empty, EmptyImage } from '@chat2db/ui';
import { Progress, Popover, Flex } from 'antd';
import i18n from '@/i18n';
import RunSqlModal from '@/blocks/ImportAndExport/components/RunSqlModal';
import ImportFileModal from '@/blocks/ImportAndExport/components/ImportFileModal';
import importExportServices from '@/service/importExport';
import { useImportExportStore } from '@/store/importExport';
import LogModal, { LogModalRef } from '@/blocks/ImportAndExport/components/LogModal';
import { ImportExportTaskStatus } from '@/constants/importExport';
import dayjs from 'dayjs';
import jcefApi from '@/jcef';

export default memo(() => {
  const { styles } = useStyles();
  const [open, setOpen] = useState(false);
  const logModalRef = useRef<LogModalRef>(null);

  const { showExportToolbar, getTaskList, currentTask, taskList, openLogModal } = useImportExportStore((state) => {
    return {
      showExportToolbar: state.showExportToolbar,
      getTaskList: state.getTaskList,
      currentTask: state.currentTask,
      taskList: state.taskList,
      openLogModal: state.openLogModal,
    };
  });

  useEffect(() => {
    getTaskList({ visible: true });
  }, []);

  const openFileLocation = (downloadUrl) => {
    jcefApi?.revealInExplorer(downloadUrl);
  };

  const handleStopTask = (id) => {
    importExportServices.stopTask({ id }).then(() => {
      getTaskList();
    });
  };

  const contentRender = () => {
    return (
      <div className={styles.wrapper}>
        <div className={styles.title}>
          <Flex align="center" gap={8}>
            <IconfontSvg code={'icon-bell'} /> <span>{i18n('workspace.title.exportProgressBar')}</span>
          </Flex>
          <IconButton code={'icon-close'} size="md" onClick={() => setOpen(false)} />
        </div>

        <div className={styles.listWrapper}>
          {taskList.length ? (
            <>
              {taskList.map((item) => {
                // A finished task with an error log is treated as failed, per the backend contract.
                const effectiveStatus =
                  item.taskStatus === ImportExportTaskStatus.FINISHED && item.errorLog
                    ? ImportExportTaskStatus.ERROR
                    : item.taskStatus;
                return (
                  <div
                    key={item.id}
                    className={styles.listItem}
                    onClick={() => {
                      openLogModal(item.id);
                    }}
                  >
                    <div className={styles.taskItemHeader}>
                      <span className={styles.taskName}>{item.taskName}</span>
                      <span className={styles.taskTime}>{dayjs(item.gmtCreate).format('MM-DD HH:mm')}</span>
                    </div>
                    {effectiveStatus === ImportExportTaskStatus.ERROR && (
                      <div className={styles.taskContent}>
                        <div className={styles.listItemLeft}>{i18n('workspace.text.taskExecutionFailure')}</div>
                      </div>
                    )}
                    {effectiveStatus === ImportExportTaskStatus.STOP && (
                      <div className={styles.taskContent}>
                        <div className={styles.listItemLeft}>{i18n('workspace.text.taskExecutionStop')}</div>
                      </div>
                    )}
                    {effectiveStatus === ImportExportTaskStatus.FINISHED &&
                      (item.downloadUrl ? (
                        <div className={styles.taskContent}>
                          <div className={styles.listItemLeft}>{item.downloadUrl}</div>
                          <div className={styles.listItemRight}>
                            <IconButton
                              code={'icon-folder'}
                              size="xs"
                              disabled={!item.downloadUrl}
                              onClick={(e) => {
                                e.stopPropagation();
                                openFileLocation(item.downloadUrl);
                              }}
                            />
                          </div>
                        </div>
                      ) : (
                        <div className={styles.taskContent}>
                          <div className={styles.listItemLeft}>{i18n('workspace.text.importSuccess')}</div>
                        </div>
                      ))}
                    {item.taskStatus === ImportExportTaskStatus.INIT && (
                      <div className={styles.taskContent}>
                        <div className={styles.listItemLeft}>{i18n('workspace.text.taskExecutionInit')}</div>
                        <div className={styles.listItemRight}>
                          <IconButton
                            code={'icon-close'}
                            size={{ boxSize: 14, iconSize: 12, borderRaduis: 14 } as any}
                            onClick={(e) => {
                              e.stopPropagation();
                              handleStopTask(item.id);
                            }}
                          />
                        </div>
                      </div>
                    )}
                    {[ImportExportTaskStatus.PROCESSING, ImportExportTaskStatus.RUNNING].includes(item.taskStatus) && (
                      <div className={styles.taskContent}>
                        <div className={styles.listItemLeft}>
                          <Progress
                            className={styles.taskItemProgress}
                            size="small"
                            percent={parseInt(item.taskProgress)}
                            showInfo={false}
                          />
                        </div>
                        <div className={styles.listItemRight}>
                          <IconButton
                            code={'icon-close'}
                            size={{ boxSize: 14, iconSize: 12, borderRaduis: 14 } as any}
                            onClick={(e) => {
                              e.stopPropagation();
                              handleStopTask(item.id);
                            }}
                          />
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}
            </>
          ) : (
            <Empty image={EmptyImage.Common} title={i18n('workspace.text.noExportTask')} />
          )}
        </div>
      </div>
    );
  };

  return (
    <>
      {showExportToolbar && (
        <div className={styles.exportProgressBar}>
          <div className={styles.left}>{currentTask?.taskStatus}</div>
          <div className={styles.right}>
            <div className={styles.rightFirst}>{currentTask?.taskName}</div>
            {currentTask && (
              <Progress
                size="small"
                className={styles.progress}
                percent={Number(currentTask?.taskProgress || '0')}
                showInfo={false}
              />
            )}
            <Popover
              overlayClassName={styles.notification}
              trigger={'click'}
              placement="topRight"
              content={contentRender()}
              open={open}
              onOpenChange={(newOpen) => setOpen(newOpen)}
            >
              <div className={styles.showAll}>{i18n('workspace.text.showAll')}</div>
            </Popover>
          </div>
        </div>
      )}
      <LogModal ref={logModalRef} />
      <RunSqlModal />
      <ImportFileModal />
    </>
  );
});
