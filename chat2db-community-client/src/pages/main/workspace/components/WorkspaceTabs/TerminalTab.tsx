import { memo, useCallback, useEffect, useRef, useState } from 'react';
import Xterm, { IXtermRef } from '@/components/Xterm';
import i18n from '@/i18n';
import jcefApi from '@/jcef';
import { JavaPushActionType, JcefEventBus } from '@/jcef/eventBus';
import styles from './TerminalTab.less';
import { useGlobalStore } from '@/store/global';
import { DEFAULT_TERMINAL_SETTINGS, getTerminalTheme } from '@/constants/terminal';
import {
  getLastRenderedTerminalSequence,
  setLastRenderedTerminalSequence,
} from '@/utils/terminalBuffer';

interface TerminalTabProps {
  sessionId: string;
}

const TerminalTab = memo(({ sessionId }: TerminalTabProps) => {
  const terminalRef = useRef<IXtermRef>(null);
  const consumerIdRef = useRef(
    `${sessionId}:${Date.now()}:${Math.random()
      .toString(36)
      .slice(2)}`,
  );
  const [exited, setExited] = useState(false);
  const themeId = useGlobalStore(
    (state) => state.terminalSettings?.themeId || DEFAULT_TERMINAL_SETTINGS.themeId,
  );
  const terminalTheme = getTerminalTheme(themeId).theme;

  useEffect(() => {
    const outputEvent = `${JavaPushActionType.TERMINAL_OUTPUT}_${sessionId}`;
    const exitEvent = `${JavaPushActionType.TERMINAL_EXIT}_${sessionId}`;
    const acknowledgeOutput = (sequence: number) => {
      jcefApi.acknowledgeTerminalOutput({ sessionId, sequence }).catch(() => undefined);
    };
    const handleOutput = (message: { data?: string; sequence?: number }) => {
      const sequence = message?.sequence;
      if (typeof sequence === 'number' && sequence <= (getLastRenderedTerminalSequence(sessionId) || 0)) {
        acknowledgeOutput(sequence);
        return;
      }
      if (!message?.data || !terminalRef.current) {
        return;
      }
      terminalRef.current.xtermWrite(message.data, () => {
        if (typeof sequence === 'number') {
          setLastRenderedTerminalSequence(sessionId, sequence);
          acknowledgeOutput(sequence);
        }
      });
    };
    const handleExit = (message: { exitCode?: number }) => {
      terminalRef.current?.xtermWrite(
        `\r\n[${i18n('workspace.terminal.exited')}: ${message?.exitCode ?? '-'}]\r\n`,
      );
      setExited(true);
    };
    JcefEventBus.on(outputEvent, handleOutput);
    JcefEventBus.on(exitEvent, handleExit);
    const consumerId = consumerIdRef.current;
    const attachPromise = jcefApi.attachTerminal({ sessionId, consumerId }).catch((error) => {
      console.error('attach terminal error', error);
      setExited(true);
    });
    return () => {
      JcefEventBus.off(outputEvent, handleOutput);
      JcefEventBus.off(exitEvent, handleExit);
      attachPromise.finally(() => {
        jcefApi.detachTerminal({ sessionId, consumerId }).catch(() => undefined);
      });
    };
  }, [sessionId]);

  const handleData = useCallback(
    (data: string) => {
      if (!exited) {
        jcefApi.writeTerminal({ sessionId, data }).catch((error) => {
          console.error('write terminal error', error);
        });
      }
    },
    [exited, sessionId],
  );

  const handleResize = useCallback(
    (columns: number, rows: number) => {
      if (!exited) {
        jcefApi.resizeTerminal({ sessionId, columns, rows }).catch(() => undefined);
      }
    },
    [exited, sessionId],
  );

  return (
    <Xterm
      ref={terminalRef}
      className={styles.terminal}
      readOnly={exited}
      theme={terminalTheme}
      persistenceKey={sessionId}
      onData={handleData}
      onResize={handleResize}
    />
  );
});

export default TerminalTab;
