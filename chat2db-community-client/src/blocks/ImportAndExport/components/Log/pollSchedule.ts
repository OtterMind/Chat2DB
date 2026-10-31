import { ACTIVE_TASK_STATUSES, ImportExportTaskStatus } from '@/constants/importExport';
import { ACTIVE_TASK_POLL_INTERVAL, FAILED_TASK_POLL_INTERVAL } from '@/store/importExport/taskCenterUtils';

/**
 * Delay before the next task event poll, or null when polling can stop.
 * A full event page means there is more to fetch, but it must wait for the normal interval:
 * busy-polling with a zero delay floods the backend while the task is still writing events.
 */
export const getTaskEventPollDelay = (
  status: ImportExportTaskStatus,
  eventsLoaded: boolean,
  hasMoreEvents: boolean,
): number | null => {
  if (!eventsLoaded) return FAILED_TASK_POLL_INTERVAL;
  if (hasMoreEvents || ACTIVE_TASK_STATUSES.includes(status)) return ACTIVE_TASK_POLL_INTERVAL;
  return null;
};
