import { useCallback } from 'react';
import { Modal } from 'antd';
import i18n from '@/i18n';
import importExportServices from '@/service/importExport';
import { useImportExportStore } from '@/store/importExport';
import type { ImportExportTaskDetails } from '@/typings/importExport';

/**
 * Resume confirmation shared by the task center row and the progress panel.
 *
 * Continuing replays the last batch that reached the database after the last checkpoint, so the
 * confirmation has to say that out loud instead of silently writing those rows again.
 */
export const useTaskResume = (options: { closeOwner?: () => void } = {}) => {
  const [modal, contextHolder] = Modal.useModal();
  const { getTaskList, openLogModal } = useImportExportStore((state) => ({
    getTaskList: state.getTaskList,
    openLogModal: state.openLogModal,
  }));

  const resumeTask = useCallback(
    (task: ImportExportTaskDetails) => {
      modal.confirm({
        title: i18n('workspace.task.resume.confirmTitle'),
        width: 520,
        okText: i18n('workspace.task.resume.action'),
        cancelText: i18n('common.button.cancel'),
        content: (
          <div>
            <div>{i18n('workspace.task.resume.confirmSource')}</div>
            <div>{i18n('workspace.task.resume.confirmReplay')}</div>
          </div>
        ),
        onOk: async () => {
          const response = await importExportServices.resumeTask({ taskId: task.id });
          // The dialog that asked for the resume steps aside before the new task's log opens, so the
          // user sees one dialog instead of two stacked ones.
          options.closeOwner?.();
          await getTaskList();
          openLogModal(response.taskId);
        },
      });
    },
    [getTaskList, modal, openLogModal, options],
  );

  return { resumeTask, resumeContextHolder: contextHolder };
};
