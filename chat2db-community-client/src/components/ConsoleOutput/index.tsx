import { forwardRef, memo, useCallback, useEffect, useRef, type HTMLAttributes, type ReactNode } from 'react';
import classnames from 'classnames';
import { useStyles } from './style';

export type ConsoleOutputLevel = 'INFO' | 'WARN' | 'ERROR';
export type ConsoleOutputTimestamp = number | string | Date;

interface ConsoleOutputViewportProps extends HTMLAttributes<HTMLDivElement> {
  children: ReactNode;
  contentClassName?: string;
  contentVersion?: unknown;
  followLatest?: boolean;
  latestAtStart?: boolean;
}

export const ConsoleOutputViewport = memo(
  forwardRef<HTMLDivElement, ConsoleOutputViewportProps>(
    (
      { children, className, contentClassName, contentVersion, followLatest = true, latestAtStart = false, ...rest },
      forwardedRef,
    ) => {
      const { styles } = useStyles();
      const viewportRef = useRef<HTMLDivElement | null>(null);
      const contentRef = useRef<HTMLDivElement>(null);

      const setViewportRef = useCallback(
        (element: HTMLDivElement | null) => {
          viewportRef.current = element;
          if (typeof forwardedRef === 'function') {
            forwardedRef(element);
          } else if (forwardedRef) {
            forwardedRef.current = element;
          }
        },
        [forwardedRef],
      );

      const alignToLatest = useCallback(() => {
        const viewport = viewportRef.current;
        if (viewport) {
          viewport.scrollTop = latestAtStart ? 0 : viewport.scrollHeight;
        }
      }, [latestAtStart]);

      useEffect(() => {
        if (!followLatest) return;
        const frame = window.requestAnimationFrame(alignToLatest);
        return () => window.cancelAnimationFrame(frame);
      }, [alignToLatest, contentVersion, followLatest]);

      useEffect(() => {
        const viewport = viewportRef.current;
        const content = contentRef.current;
        if (!viewport || !content || typeof ResizeObserver === 'undefined') return;

        let frame: number | undefined;
        const observer = new ResizeObserver(() => {
          if (!followLatest) return;
          if (frame !== undefined) window.cancelAnimationFrame(frame);
          frame = window.requestAnimationFrame(alignToLatest);
        });
        observer.observe(viewport);
        observer.observe(content);

        return () => {
          observer.disconnect();
          if (frame !== undefined) window.cancelAnimationFrame(frame);
        };
      }, [alignToLatest, followLatest]);

      return (
        <div {...rest} className={classnames(styles.viewport, className)} ref={setViewportRef}>
          <div className={classnames(styles.content, contentClassName)} ref={contentRef}>
            {children}
          </div>
        </div>
      );
    },
  ),
);

interface ConsoleOutputLineProps {
  children: ReactNode;
  className?: string;
  contentClassName?: string;
  timestamp: ConsoleOutputTimestamp;
  timestampProminent?: boolean;
}

export const ConsoleOutputLine = memo(
  ({ children, className, contentClassName, timestamp, timestampProminent = false }: ConsoleOutputLineProps) => {
    const { styles } = useStyles();
    return (
      <div className={classnames(styles.line, className)}>
        <ConsoleOutputTime value={timestamp} prominent={timestampProminent} />
        <div className={contentClassName}>{children}</div>
      </div>
    );
  },
);

interface ConsoleOutputMessageLineProps {
  action?: ReactNode;
  className?: string;
  level: ConsoleOutputLevel;
  message: ReactNode;
  timestamp: ConsoleOutputTimestamp;
}

export const ConsoleOutputMessageLine = memo(
  ({ action, className, level, message, timestamp }: ConsoleOutputMessageLineProps) => {
    const { styles } = useStyles();
    return (
      <ConsoleOutputLine className={className} timestamp={timestamp} timestampProminent>
        <div className={classnames(styles.message, styles[`message${level}`])}>
          <span className={classnames(styles.level, level === 'INFO' && styles.infoLevel)}>{level}</span>
          <span className={classnames(styles.messageText, level === 'INFO' && styles.messageINFOText)}>{message}</span>
          {action}
        </div>
      </ConsoleOutputLine>
    );
  },
);

export const ConsoleOutputEmpty = memo(({ children }: { children: ReactNode }) => {
  const { styles } = useStyles();
  return <div className={styles.empty}>{children}</div>;
});

function ConsoleOutputTime({ value, prominent }: { value: ConsoleOutputTimestamp; prominent: boolean }) {
  const { styles } = useStyles();
  return (
    <time className={classnames(styles.timestamp, prominent && styles.prominentTimestamp)}>
      [{formatConsoleOutputTimestamp(value)}]
    </time>
  );
}

export function formatConsoleOutputTimestamp(value: ConsoleOutputTimestamp) {
  const normalizedValue = typeof value === 'string' && /^\d+$/.test(value) ? Number(value) : value;
  const date = normalizedValue instanceof Date ? normalizedValue : new Date(normalizedValue);
  if (Number.isNaN(date.getTime())) return '-';
  const pad = (part: number) => String(part).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(
    date.getMinutes(),
  )}:${pad(date.getSeconds())}`;
}
