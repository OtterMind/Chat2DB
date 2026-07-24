import React, { useEffect, useImperativeHandle, ForwardedRef, forwardRef, useCallback } from 'react';
import { useStyles } from './style';
import classnames from 'classnames';
import { Terminal } from 'xterm';
import { FitAddon } from 'xterm-addon-fit'; // Fits the terminal to its container.
// import { WebLinksAddon } from 'xterm-addon-web-links';
import 'xterm/css/xterm.css'; // Import styles.

interface IProps {
  className?: string;
  value?: string;
  xtermHeaderSlot?: React.ReactNode;
}

export interface IXtermRef {
  xtermWrite: (value: string) => void;
}

export default forwardRef((props: IProps, ref: ForwardedRef<IXtermRef>) => {
  const { styles } = useStyles();
  const terminalRef = React.useRef<HTMLDivElement>(null);
  const { className, xtermHeaderSlot } = props;
  const xtermRef = React.useRef<any>(null);
  const fitAddonRef = React.useRef<any>(null);

  const resizeFitAddon = useCallback(() => {
    fitAddonRef.current?.fit();
  }, []);

  const initXterm = () => {
    const xterm = new Terminal({
      convertEol: true, // Move the cursor to the start of the next line on EOL.
      disableStdin: true, // Disable input.
      cursorStyle: 'block', // Cursor style.
      cursorBlink: false, // Cursor blinking.
      theme: {
        foreground: '#f6f8fa', // Foreground.
        background: '#24292f', // Background.
      },
      // Set the font.
      fontSize: 14,
    });
    fitAddonRef.current = new FitAddon();
    xterm.loadAddon(fitAddonRef.current);
    xterm.open(terminalRef.current!);
    fitAddonRef.current.fit();
    xterm.onData((data) => {
      let dataWrapper = data;
      if (dataWrapper === '\r') {
        dataWrapper = '\n';
      } else if (dataWrapper === '\u0003') {
        dataWrapper += '\n';
      } else if (dataWrapper === '\u007F') {
        // ASCII DEL character
        // Delete the last character.
        xterm.write('\b \b');
        return;
      }
      xterm.write(dataWrapper);
    });
    xtermRef.current = xterm;

    window.addEventListener('resize', resizeFitAddon);
    return xterm;
  };

  useEffect(() => {
    initXterm();
    return () => {
      window.removeEventListener('resize', resizeFitAddon);
      // Dispose the Terminal (and its loaded FitAddon / onData listener)
      // so the instance, DOM, and renderer are torn down on unmount.
      xtermRef.current?.dispose();
      xtermRef.current = null;
      fitAddonRef.current = null;
    };
  }, []);

  const xtermWrite = (value: string) => {
    xtermRef.current.write(value);
  };

  useImperativeHandle(ref, () => ({
    xtermWrite,
  }));

  return (
    <div className={classnames(styles.terminalContainerBox, className)}>
      {xtermHeaderSlot}
      <div className={styles.terminalContainer} ref={terminalRef} />
    </div>
  );
});
