import { LoaderCircle } from 'lucide-react';
import type { ReactNode } from 'react';

import { useImportExportStore } from '@/store/importExport';

import { useStyles } from './style';

interface TaskCenterStatusBadgeProps {
  children: ReactNode;
}

const TaskCenterStatusBadge = ({ children }: TaskCenterStatusBadgeProps) => {
  const { styles } = useStyles();
  const { activeTaskCount, unreadCompletedTaskCount } = useImportExportStore((state) => ({
    activeTaskCount: state.activeTaskCount,
    unreadCompletedTaskCount: state.unreadCompletedTaskCount,
  }));

  return (
    <span className={styles.buttonWithStatus}>
      {children}
      {unreadCompletedTaskCount > 0 && (
        <span aria-hidden className={styles.notificationCount}>
          {unreadCompletedTaskCount}
        </span>
      )}
      {activeTaskCount > 0 && <LoaderCircle aria-hidden className={styles.runningIndicator} size={12} />}
    </span>
  );
};

export default TaskCenterStatusBadge;
