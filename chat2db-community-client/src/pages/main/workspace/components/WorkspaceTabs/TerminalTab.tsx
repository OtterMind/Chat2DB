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
import { createTerminalAttachmentLifecycle } from './terminalAttachmentLifecycle';

interface TerminalTabProps {
  sessionId: string;
}

const terminalAttachmentLifecycle = createTerminalAttachmentLifecycle({
  attach: (params) => jcefApi.attachTerminal(params),
  detach: (params) => jcefApi.detachTerminal(params),
});

const TerminalTab = memo(({ sessionId }: TerminalTabProps) => {
  const terminalRef = useRef<IXtermRef>(null);
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
    let active = true;
    const attachment = terminalAttachmentLifecycle.acquire(sessionId);
    attachment.attached.catch((error) => {
      console.error('attach terminal error', error);
      if (active) {
        setExited(true);
      }
    });
    return () => {
      active = false;
      JcefEventBus.off(outputEvent, handleOutput);
      JcefEventBus.off(exitEvent, handleExit);
      attachment.release();
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
