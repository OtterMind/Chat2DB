import React, { useEffect, useImperativeHandle, ForwardedRef, forwardRef, useCallback } from 'react';
import { useStyles } from './style';
import classnames from 'classnames';
import { ITheme, Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { SerializeAddon } from '@xterm/addon-serialize';
import '@xterm/xterm/css/xterm.css';
import { getPersistentTerminalBuffer, setPersistentTerminalBuffer } from '@/utils/terminalBuffer';

interface IProps {
  className?: string;
  value?: string;
  xtermHeaderSlot?: React.ReactNode;
  onData?: (data: string) => void;
  onResize?: (columns: number, rows: number) => void;
  readOnly?: boolean;
  theme?: ITheme;
  persistenceKey?: string;
}

export interface IXtermRef {
  xtermWrite: (value: string, callback?: () => void) => void;
}

export default forwardRef((props: IProps, ref: ForwardedRef<IXtermRef>) => {
  const { styles } = useStyles();
  const terminalRef = React.useRef<HTMLDivElement>(null);
  const { className, xtermHeaderSlot, onData, onResize, readOnly = true, theme, persistenceKey } = props;
  const xtermRef = React.useRef<Terminal | null>(null);
  const fitAddonRef = React.useRef<FitAddon | null>(null);
  const serializeAddonRef = React.useRef<SerializeAddon | null>(null);
  const resizeObserverRef = React.useRef<ResizeObserver | null>(null);
  const resizeFrameRef = React.useRef<number>();
  const lastSizeRef = React.useRef<{ columns: number; rows: number }>();
  const onDataRef = React.useRef(onData);
  const onResizeRef = React.useRef(onResize);

  onDataRef.current = onData;
  onResizeRef.current = onResize;

  const resizeFitAddon = useCallback(() => {
    if (resizeFrameRef.current !== undefined) {
      return;
    }
    resizeFrameRef.current = window.requestAnimationFrame(() => {
      resizeFrameRef.current = undefined;
      fitAddonRef.current?.fit();
      const terminal = xtermRef.current;
      if (!terminal) {
        return;
      }
      const nextSize = { columns: terminal.cols, rows: terminal.rows };
      const lastSize = lastSizeRef.current;
      if (lastSize?.columns === nextSize.columns && lastSize.rows === nextSize.rows) {
        return;
      }
      lastSizeRef.current = nextSize;
      onResizeRef.current?.(nextSize.columns, nextSize.rows);
    });
  }, []);

  const initXterm = () => {
    const xterm = new Terminal({
      convertEol: true, // Move the cursor to the start of the next line on EOL.
      disableStdin: readOnly,
      cursorStyle: 'block', // Cursor style.
      cursorBlink: !readOnly,
      theme,
      // Set the font.
      fontSize: 14,
    });
    fitAddonRef.current = new FitAddon();
    serializeAddonRef.current = new SerializeAddon();
    xterm.loadAddon(fitAddonRef.current);
    xterm.loadAddon(serializeAddonRef.current);
    xterm.open(terminalRef.current!);
    xterm.onData((data) => onDataRef.current?.(data));
    xtermRef.current = xterm;
    if (persistenceKey) {
      const persistedBuffer = getPersistentTerminalBuffer(persistenceKey);
      if (persistedBuffer) {
        xterm.write(persistedBuffer);
      }
    }
    resizeFitAddon();

    const resizeObserver = new ResizeObserver(resizeFitAddon);
    resizeObserver.observe(terminalRef.current!);
    resizeObserverRef.current = resizeObserver;
    return xterm;
  };

  useEffect(() => {
    initXterm();
    return () => {
      resizeObserverRef.current?.disconnect();
      resizeObserverRef.current = null;
      if (resizeFrameRef.current !== undefined) {
        window.cancelAnimationFrame(resizeFrameRef.current);
        resizeFrameRef.current = undefined;
      }
      if (persistenceKey && serializeAddonRef.current) {
        setPersistentTerminalBuffer(persistenceKey, serializeAddonRef.current.serialize());
      }
      // Dispose the Terminal (and its loaded FitAddon / onData listener)
      // so the instance, DOM, and renderer are torn down on unmount.
      xtermRef.current?.dispose();
      xtermRef.current = null;
      fitAddonRef.current = null;
      serializeAddonRef.current = null;
      lastSizeRef.current = undefined;
    };
  }, [persistenceKey, resizeFitAddon]);

  useEffect(() => {
    if (xtermRef.current) {
      xtermRef.current.options.disableStdin = readOnly;
      xtermRef.current.options.cursorBlink = !readOnly;
    }
  }, [readOnly]);

  useEffect(() => {
    if (xtermRef.current) {
      xtermRef.current.options.theme = theme;
    }
  }, [theme]);

  const xtermWrite = (value: string, callback?: () => void) => {
    if (xtermRef.current) {
      xtermRef.current.write(value, callback);
    } else {
      callback?.();
    }
  };

  useImperativeHandle(ref, () => ({
    xtermWrite,
  }));

  return (
    <div
      className={classnames(styles.terminalContainerBox, className)}
      style={{ backgroundColor: theme?.background, color: theme?.foreground }}
    >
      {xtermHeaderSlot}
      <div className={styles.terminalContainer} ref={terminalRef} />
    </div>
  );
});
