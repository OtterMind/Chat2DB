import { Button } from 'antd';
import { Download, FolderOpen, Play } from 'lucide-react';
import i18n from '@/i18n';
import { useImportExportStore } from '@/store/importExport';
import { ACTIVE_TASK_STATUSES, ImportExportTaskStatus } from '@/constants/importExport';
import type { ImportExportTaskDetails } from '@/typings/importExport';
import jcefApi from '@/jcef';
import { isDesktop } from '@/utils/env';
import { buildTaskErrorArtifactUrl, canDownloadErrorArtifact, canResumeTask } from '@/blocks/ImportAndExport/utils/taskArtifacts';
import { useTaskResume } from '@/blocks/ImportAndExport/components/useTaskResume';
import { isLatestTaskAttempt } from '@/blocks/ImportAndExport/utils/taskAttempts';

interface IProps {
  taskDetails?: ImportExportTaskDetails;
  /** Called when a resume starts, so the dialog that owns this footer can close itself first. */
  onResumeStart?: () => void;
}

/**
 * Footer actions of a task: cancel while it runs, the exported file or the rejected-rows file once
 * it stopped, and resume for an import that stopped early. Shared by the progress dialog the wizard
 * opens and by the log dialog of the task center, so both offer the same entries.
 */
export default function TaskLogActions({ taskDetails, onResumeStart }: IProps) {
  const { resumeTask, resumeContextHolder } = useTaskResume({ closeOwner: onResumeStart });
  const { cancelTask, cancellingTaskIds, taskList } = useImportExportStore((state) => ({
    cancelTask: state.cancelTask,
    cancellingTaskIds: state.cancellingTaskIds,
    taskList: state.taskList,
  }));
  if (!taskDetails) {
    return null;
  }

  const openArtifact = () => {
    if (isDesktop && taskDetails.artifactId) {
      jcefApi?.revealInExplorer(taskDetails.artifactId);
      return;
    }
    window.open(`/api/tasks/artifact?taskId=${taskDetails.id}`, '_blank');
  };

  const openErrorArtifact = () => {
    if (isDesktop && taskDetails.errorArtifactId) {
      jcefApi?.revealInExplorer(taskDetails.errorArtifactId);
      return;
    }
    window.open(buildTaskErrorArtifactUrl(taskDetails.id), '_blank');
  };

  return (
    <>
      {resumeContextHolder}
      {ACTIVE_TASK_STATUSES.includes(taskDetails.status) && (
        <Button
          danger
          loading={cancellingTaskIds.includes(taskDetails.id)}
          onClick={() => void cancelTask(taskDetails.id)}
        >
          {i18n('common.button.cancel')}
        </Button>
      )}
      {canDownloadErrorArtifact(taskDetails) && (
        <Button icon={<Download aria-hidden size={15} />} onClick={openErrorArtifact}>
          {i18n('workspace.task.errorFile.download')}
        </Button>
      )}
      {taskDetails.status === ImportExportTaskStatus.SUCCESS && taskDetails.artifactId && (
        <Button
          type="primary"
          icon={isDesktop ? <FolderOpen aria-hidden size={15} /> : <Download aria-hidden size={15} />}
          onClick={openArtifact}
        >
          {i18n(isDesktop ? 'workspace.text.openFile' : 'workspace.task.export.download')}
        </Button>
      )}
      {canResumeTask(taskDetails) && isLatestTaskAttempt(taskDetails, taskList) && (
        <Button type="primary" icon={<Play aria-hidden size={15} />} onClick={() => resumeTask(taskDetails)}>
          {i18n('workspace.task.resume.action')}
        </Button>
      )}
    </>
  );
}
