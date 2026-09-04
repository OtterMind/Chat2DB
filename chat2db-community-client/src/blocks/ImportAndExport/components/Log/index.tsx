import {
  memo,
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  UIEvent,
  WheelEvent,
} from 'react';
import { useSize } from 'ahooks';
import VirtualList, { type ListRef } from 'rc-virtual-list';
import { useStyles } from './style';
import { Progress, Spin } from 'antd';
import importExportServices from '@/service/importExport';
import { ImportExportTaskDetails, ImportExportTaskEvent } from '@/typings/importExport';
import { ACTIVE_TASK_STATUSES, ImportExportTaskStatus } from '@/constants/importExport';
import i18n from '@/i18n';
import { useImportExportStore } from '@/store/importExport';
import {
  mergeTaskEvents,
  TASK_EVENT_INITIAL_PAGE_SIZE,
  TASK_EVENT_PAGE_SIZE,
} from '@/store/importExport/taskCenterUtils';
import { ConsoleOutputEmpty, ConsoleOutputMessageLine } from '@/components/ConsoleOutput';

interface IProps {
  className?: string;
  taskId: number;
  onTaskChange?: (taskDetails: ImportExportTaskDetails) => void;
}

const LEGACY_EVENT_TIMESTAMP = /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}:?\s*/;

const formatEventMessage = (event: ImportExportTaskEvent) => {
  const message = event.message?.replace(LEGACY_EVENT_TIMESTAMP, '').trim() || '-';
  const details = event.details || {};
  if (event.code === 'IMPORT_SUMMARY') {
    return i18n(
      'workspace.importExport.importSummary',
      String(details.totalRows ?? 0),
      String(details.successCount ?? 0),
      String(details.failedCount ?? 0),
    );
  }
  if (event.code === 'IMPORT_ROW_FAILED') {
    return i18n(
      'workspace.importExport.rowImportFailed',
      String(details.sourceRow ?? '-'),
      String(details.message || message),
    );
  }
  if (event.code === 'IMPORT_BATCH_RETRYING_ROWS') {
    return i18n(
      'workspace.importExport.batchRetryingRows',
      String(details.statementCount ?? 0),
      String(details.message || message),
    );
  }
  return message;
};

interface ScrollRestore {
  anchorSequence: number;
  scrollTop: number;
}

const Log = (props: IProps) => {
  const { taskId } = props;
  const { styles } = useStyles();
  const [taskDetails, setTaskDetails] = useState<ImportExportTaskDetails>();
  const [events, setEvents] = useState<ImportExportTaskEvent[]>([]);
  const [initialLoading, setInitialLoading] = useState(true);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [followLatest, setFollowLatest] = useState(true);
  const [eventsLoadFailed, setEventsLoadFailed] = useState(false);
  const onTaskChangeRef = useRef(props.onTaskChange);
  const viewportContainerRef = useRef<HTMLDivElement>(null);
  const virtualListRef = useRef<ListRef>(null);
  const eventsRef = useRef<ImportExportTaskEvent[]>([]);
  const hasOlderEventsRef = useRef(false);
  const loadingOlderRef = useRef(false);
  const taskGenerationRef = useRef(0);
  const scrollRestoreRef = useRef<ScrollRestore>();
  const viewportSize = useSize(viewportContainerRef);
  const { getTaskList } = useImportExportStore((state) => {
    return {
      getTaskList: state.getTaskList,
    };
  });

  useEffect(() => {
    onTaskChangeRef.current = props.onTaskChange;
  }, [props.onTaskChange]);

  useEffect(() => {
    let active = true;
    const generation = ++taskGenerationRef.current;
    let latestSequence = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;
    setTaskDetails(undefined);
    setEvents([]);
    eventsRef.current = [];
    hasOlderEventsRef.current = false;
    loadingOlderRef.current = false;
    scrollRestoreRef.current = undefined;
    setInitialLoading(true);
    setLoadingOlder(false);
    setFollowLatest(true);
    setEventsLoadFailed(false);

    const updateEvents = (incomingEvents: ImportExportTaskEvent[]) => {
      if (!incomingEvents.length) return;
      setEvents((currentEvents) => {
        const mergedEvents = mergeTaskEvents(currentEvents, incomingEvents);
        eventsRef.current = mergedEvents;
        return mergedEvents;
      });
    };

    const poll = async () => {
      let details: ImportExportTaskDetails;
      try {
        details = await importExportServices.getTaskDetails({ taskId });
      } catch {
        if (active) timer = setTimeout(poll, 1500);
        return;
      }
      if (!active) return;

      setTaskDetails(details);
      onTaskChangeRef.current?.(details);

      let newEvents: ImportExportTaskEvent[] = [];
      let eventsLoaded = true;
      try {
        newEvents = await importExportServices.getTaskEvents({
          taskId,
          afterSequence: latestSequence,
          limit: TASK_EVENT_PAGE_SIZE,
        });
      } catch {
        eventsLoaded = false;
      }
      if (!active) return;

      setEventsLoadFailed(!eventsLoaded);
      if (eventsLoaded && newEvents.length) {
        latestSequence = Math.max(latestSequence, ...newEvents.map((event) => event.sequence));
        updateEvents(newEvents);
      }

      const hasMoreNewEvents = eventsLoaded && newEvents.length === TASK_EVENT_PAGE_SIZE;
      if (hasMoreNewEvents || ACTIVE_TASK_STATUSES.includes(details.status) || !eventsLoaded) {
        timer = setTimeout(poll, hasMoreNewEvents ? 0 : eventsLoaded ? 1000 : 1500);
        return;
      }
      getTaskList();
    };

    const initialize = async () => {
      try {
        const [details, latestEvents] = await Promise.all([
          importExportServices.getTaskDetails({ taskId }),
          importExportServices.getTaskEvents({ taskId, limit: TASK_EVENT_INITIAL_PAGE_SIZE }),
        ]);
        if (!active) return;

        const initialEvents = mergeTaskEvents([], latestEvents);
        latestSequence = initialEvents.at(-1)?.sequence || 0;
        eventsRef.current = initialEvents;
        hasOlderEventsRef.current = (initialEvents[0]?.sequence || 0) > 1;
        setTaskDetails(details);
        setEvents(initialEvents);
        setEventsLoadFailed(false);
        setInitialLoading(false);
        onTaskChangeRef.current?.(details);

        timer = setTimeout(poll, ACTIVE_TASK_STATUSES.includes(details.status) ? 1000 : 0);
      } catch {
        if (!active) return;
        setEventsLoadFailed(true);
        timer = setTimeout(initialize, 1500);
      }
    };

    initialize();
    return () => {
      active = false;
      if (taskGenerationRef.current === generation) taskGenerationRef.current += 1;
      if (timer) clearTimeout(timer);
    };
  }, [getTaskList, taskId]);

  const loadOlderEvents = useCallback(async () => {
    const currentEvents = eventsRef.current;
    const earliestSequence = currentEvents[0]?.sequence;
    if (!earliestSequence || !hasOlderEventsRef.current || loadingOlderRef.current) return;

    const generation = taskGenerationRef.current;
    loadingOlderRef.current = true;
    setLoadingOlder(true);
    setFollowLatest(false);
    try {
      const olderEvents = await importExportServices.getTaskEvents({
        taskId,
        beforeSequence: earliestSequence,
        limit: TASK_EVENT_PAGE_SIZE,
      });
      if (generation !== taskGenerationRef.current) return;

      setEventsLoadFailed(false);
      hasOlderEventsRef.current = (olderEvents[0]?.sequence || 0) > 1;
      if (!olderEvents.length) {
        loadingOlderRef.current = false;
        setLoadingOlder(false);
        return;
      }

      const scrollInfo = virtualListRef.current?.getScrollInfo();
      if (scrollInfo) {
        scrollRestoreRef.current = {
          anchorSequence: earliestSequence,
          scrollTop: scrollInfo.y,
        };
      }
      setEvents((existingEvents) => {
        const mergedEvents = mergeTaskEvents(existingEvents, olderEvents);
        eventsRef.current = mergedEvents;
        return mergedEvents;
      });
    } catch {
      if (generation !== taskGenerationRef.current) return;
      setEventsLoadFailed(true);
      loadingOlderRef.current = false;
      setLoadingOlder(false);
    }
  }, [taskId]);

  useLayoutEffect(() => {
    const scrollRestore = scrollRestoreRef.current;
    const virtualList = virtualListRef.current;
    if (!virtualList) return;

    if (scrollRestore) {
      virtualList.scrollTo({
        key: scrollRestore.anchorSequence,
        align: 'top',
        offset: scrollRestore.scrollTop,
      });
      scrollRestoreRef.current = undefined;
      loadingOlderRef.current = false;
      setLoadingOlder(false);
      return;
    }

    if (followLatest && events.length) {
      virtualList.scrollTo({ index: events.length - 1, align: 'bottom' });
    }
  }, [events, followLatest, viewportSize?.height]);

  const handleScroll = useCallback(
    (event: UIEvent<HTMLDivElement>) => {
      const viewport = event.currentTarget;
      setFollowLatest(viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight <= 24);
      if (viewport.scrollTop <= 32) loadOlderEvents();
    },
    [loadOlderEvents],
  );

  const handleWheel = useCallback(
    (event: WheelEvent<HTMLDivElement>) => {
      const scrollTop = virtualListRef.current?.getScrollInfo().y || 0;
      if (event.deltaY < 0 && scrollTop <= 32) loadOlderEvents();
    },
    [loadOlderEvents],
  );

  if (initialLoading || !taskDetails) {
    return (
      <div className={styles.loading}>
        <Spin size="large" />
        <span>{eventsLoadFailed ? i18n('workspace.task.events.loadFailed') : i18n('common.text.loading')}</span>
      </div>
    );
  }

  const fallbackErrorEvent: ImportExportTaskEvent[] =
    !events.length && taskDetails.errorMessage
      ? [
          {
            eventId: -1,
            taskId,
            sequence: -1,
            level: 'ERROR',
            code: taskDetails.errorCode || ImportExportTaskStatus.FAILED,
            message: taskDetails.errorMessage,
            createdAt: taskDetails.finishedAt || taskDetails.updatedAt || taskDetails.createdAt,
          },
        ]
      : [];
  const visibleEvents = events.length ? events : fallbackErrorEvent;
  const progress =
    taskDetails.status === ImportExportTaskStatus.SUCCESS
      ? 100
      : Math.min(100, Math.max(0, Number(taskDetails.progress) || 0));
  const progressStatus =
    taskDetails.status === ImportExportTaskStatus.SUCCESS
      ? 'success'
      : taskDetails.status === ImportExportTaskStatus.FAILED
      ? 'exception'
      : ACTIVE_TASK_STATUSES.includes(taskDetails.status)
      ? 'active'
      : 'normal';
  return (
    <div className={styles.log}>
      {loadingOlder && (
        <div className={styles.olderLoading}>
          <Spin size="small" />
          <span>{i18n('common.text.loading')}</span>
        </div>
      )}
      <div className={styles.eventConsole} onWheel={handleWheel}>
        <div className={styles.virtualListContainer} ref={viewportContainerRef}>
          {!!visibleEvents.length && (
            <VirtualList
              ref={virtualListRef}
              className={styles.virtualList}
              data={visibleEvents}
              height={Math.max(1, Math.round(viewportSize?.height || 1))}
              itemHeight={20}
              itemKey="sequence"
              onScroll={handleScroll}
              showScrollBar="optional"
            >
              {(event) => (
                <ConsoleOutputMessageLine
                  className={styles.virtualListItem}
                  timestamp={event.createdAt}
                  level={event.level}
                  message={formatEventMessage(event)}
                />
              )}
            </VirtualList>
          )}
          {!visibleEvents.length && eventsLoadFailed && (
            <ConsoleOutputEmpty>{i18n('workspace.task.events.loadFailed')}</ConsoleOutputEmpty>
          )}
          {!visibleEvents.length && !eventsLoadFailed && (
            <ConsoleOutputEmpty>{i18n('workspace.task.events.empty')}</ConsoleOutputEmpty>
          )}
        </div>
        {!!visibleEvents.length && eventsLoadFailed && (
          <div className={styles.loadFailed}>{i18n('workspace.task.events.loadFailed')}</div>
        )}
      </div>
      <div className={styles.progressPanel}>
        <div className={styles.progressHeader}>
          <Progress
            className={styles.progressBar}
            percent={progress}
            showInfo={false}
            size="small"
            status={progressStatus}
          />
          <span className={styles.progressValue} data-status={taskDetails.status}>
            {progress}%
          </span>
        </div>
      </div>
    </div>
  );
};

export default memo(Log);
