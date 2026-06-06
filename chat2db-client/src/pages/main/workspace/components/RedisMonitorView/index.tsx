import React, { memo, useEffect, useMemo, useRef, useState } from 'react';
import { Button, Checkbox, Input, message } from 'antd';
import { v4 as uuid } from 'uuid';

import Iconfont from '@/components/Iconfont';
import redisService from '@/service/redis';

import styles from './index.less';

interface IProps {
  uniqueData: {
    dataSourceId: number;
    databaseName?: string;
  };
}

const MAX_LINES = 5000;

const RedisMonitorView = memo((props: IProps) => {
  const { uniqueData } = props;
  const [running, setRunning] = useState(false);
  const [autoWrap, setAutoWrap] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [lines, setLines] = useState<string[]>([]);
  const [startTime, setStartTime] = useState('');
  const closeStreamRef = useRef<(() => void) | null>(null);
  const sessionIdRef = useRef('');
  const contentRef = useRef<HTMLDivElement>(null);

  const visibleLines = useMemo(() => {
    const value = keyword.trim().toLowerCase();
    if (!value) {
      return lines;
    }
    return lines.filter((line) => line.toLowerCase().includes(value));
  }, [keyword, lines]);

  useEffect(() => {
    if (contentRef.current) {
      contentRef.current.scrollTop = contentRef.current.scrollHeight;
    }
  }, [visibleLines]);

  useEffect(() => {
    return () => {
      stopMonitor();
    };
  }, []);

  const startMonitor = () => {
    if (!uniqueData?.dataSourceId || running) {
      return;
    }
    const sessionId = uuid();
    sessionIdRef.current = sessionId;
    setLines((current) => current.concat(`Begin Monitoring (${formatTime(new Date())})`));
    setStartTime(formatTime(new Date()));
    setRunning(true);
    closeStreamRef.current = redisService.streamMonitor({
      uid: sessionId,
      dataSourceId: uniqueData.dataSourceId,
      databaseName: uniqueData.databaseName,
      onCommand: (line) => {
        if (sessionIdRef.current !== sessionId || !line) {
          return;
        }
        setLines((current) => current.concat(line).slice(-MAX_LINES));
      },
      onDone: () => {
        if (sessionIdRef.current !== sessionId) {
          return;
        }
        setRunning(false);
        setLines((current) => current.concat(`End Monitoring (${formatTime(new Date())})`));
        closeStreamRef.current = null;
      },
      onError: (errorMessage) => {
        if (sessionIdRef.current !== sessionId) {
          return;
        }
        setRunning(false);
        message.error(errorMessage || 'Redis monitor 连接失败');
        setLines((current) => current.concat(`End Monitoring (${formatTime(new Date())})`));
        closeStreamRef.current = null;
      },
    });
  };

  const stopMonitor = () => {
    closeStreamRef.current?.();
    closeStreamRef.current = null;
    if (running) {
      setLines((current) => current.concat(`End Monitoring (${formatTime(new Date())})`));
    }
    sessionIdRef.current = '';
    setRunning(false);
  };

  const clearLines = () => {
    setLines([]);
  };

  const exportLines = () => {
    const blob = new Blob([lines.join('\n')], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `redis-monitor-${uniqueData.databaseName || '0'}-${Date.now()}.log`;
    link.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className={styles.redisMonitorView}>
      <div className={styles.toolbar}>
        <Button disabled={running} icon={<Iconfont code="&#xe624;" />} type="text" onClick={startMonitor}>
          开始
        </Button>
        <Button disabled={!running} icon={<Iconfont code="&#xe625;" />} type="text" onClick={stopMonitor}>
          结束
        </Button>
        <Checkbox checked={autoWrap} onChange={(event) => setAutoWrap(event.target.checked)}>
          自动换行
        </Checkbox>
        <Button icon={<Iconfont code="&#xe613;" />} type="text" onClick={exportLines}>
          导出
        </Button>
        <Button icon={<Iconfont code="&#xe6a7;" />} type="text" onClick={clearLines}>
          清除
        </Button>
        <div className={styles.spacer} />
        <Input
          allowClear
          className={styles.searchInput}
          prefix={<Iconfont code="&#xe62d;" />}
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
        />
        <Button icon={<Iconfont code="&#xe62b;" />} type="text" />
      </div>
      <div className={styles.status}>
        <Iconfont code="&#xe611;" />
        <span>{running ? `Monitoring since ${startTime}` : 'Monitor stopped'}</span>
        <span>{visibleLines.length} lines</span>
      </div>
      <div ref={contentRef} className={autoWrap ? styles.content : `${styles.content} ${styles.noWrap}`}>
        {visibleLines.map((line, index) => (
          <div className={styles.line} key={`${index}-${line}`}>
            <span className={styles.lineNo}>{index + 1}</span>
            <span className={styles.lineText}>{line}</span>
          </div>
        ))}
      </div>
    </div>
  );
});

function formatTime(date: Date) {
  return date.toLocaleString();
}

export default RedisMonitorView;
