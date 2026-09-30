import { ImportExportTaskStatus } from '@/constants/importExport';
import { ImportExportTaskDetails, ITaskArtifact } from '@/typings/importExport';

const FAILURE_DIAGNOSTIC_ROLES = new Set(['IMPORT_REPORT', 'REJECT_SUMMARY', 'REJECT']);
const REJECT_ROLE_PREFIX = 'REJECT:';

type TaskArtifactVisibility = Pick<ImportExportTaskDetails, 'status' | 'artifactId' | 'artifacts'>;

export const isFailureDiagnosticArtifactRole = (role: string) =>
  FAILURE_DIAGNOSTIC_ROLES.has(role) ||
  (role.startsWith(REJECT_ROLE_PREFIX) && role.length > REJECT_ROLE_PREFIX.length);

export const getDownloadableTaskArtifacts = (task?: TaskArtifactVisibility): ITaskArtifact[] => {
  if (!task?.artifacts?.length) {
    return [];
  }
  if (task.status === ImportExportTaskStatus.SUCCESS) {
    return task.artifacts;
  }
  if (task.status === ImportExportTaskStatus.FAILED || task.status === ImportExportTaskStatus.CANCELLED) {
    return task.artifacts.filter((artifact) => isFailureDiagnosticArtifactRole(artifact.role));
  }
  return [];
};

export const shouldShowLegacyPrimaryArtifact = (task?: TaskArtifactVisibility) =>
  task?.status === ImportExportTaskStatus.SUCCESS && Boolean(task.artifactId) && !task.artifacts?.length;
