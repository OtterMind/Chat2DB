import { useEffect } from 'react';
import { useImportExportStore } from '@/store/importExport';

const useTaskCenter = (enabled: boolean) => {
  const { getTaskList, stopTaskListPolling } = useImportExportStore((state) => ({
    getTaskList: state.getTaskList,
    stopTaskListPolling: state.stopTaskListPolling,
  }));

  useEffect(() => {
    if (!enabled) return;
    getTaskList();
    return stopTaskListPolling;
  }, [enabled, getTaskList, stopTaskListPolling]);
};

export default useTaskCenter;
