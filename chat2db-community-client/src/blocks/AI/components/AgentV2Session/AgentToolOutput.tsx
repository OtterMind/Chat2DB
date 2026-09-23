import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Input, Spin } from 'antd';
import { createStyles } from 'antd-style';
import type { AgentOutputQuery, AgentOutputReference } from '@/types/agentOutput';
import { readAgentOutput } from '@/service/agentOutput';
import pi from '@/service/pi';
import { formatFileSize } from '@/utils/file';
import i18n from '@/i18n';
import { useGlobalStore } from '@/store/global';
import { agentErrorText } from '../../agentEvents';
import useOutputPage from './useOutputPage';

const useStyles = createStyles(({ css, token }) => ({
  bar: css`display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin: 6px 0; font-size: 12px;`,
  viewer: css`margin-top: 8px; display: grid; gap: 8px; min-width: 0;`,
  page: css`
    height: 220px;
    overflow: auto;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: 6px;
    background: ${token.colorFillTertiary};
    padding: 10px;
    pre { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font: 12px/1.6 monospace; }
  `,
}));

function OutputViewer({ sessionId, artifactId }: { sessionId: string; artifactId: string }) {
  const { styles } = useStyles();
  const [pattern, setPattern] = useState('');
  const [query, setQuery] = useState<AgentOutputQuery>({});
  const [previousCursors, setPreviousCursors] = useState<Array<string | undefined>>([]);
  const { state, cancel } = useOutputPage(sessionId, artifactId, query, readAgentOutput);
  const search = (value: string) => {
    setPreviousCursors([]);
    setQuery(value.trim() ? { pattern: value.trim() } : {});
  };
  return <div className={styles.viewer} data-agent-output-viewer>
    <Input.Search size="small" value={pattern} allowClear
      placeholder={i18n('stream.output.search')} aria-label={i18n('stream.output.search')}
      onChange={(event) => setPattern(event.target.value)}
      onSearch={search}
    />
    <div key={`${query.pattern}:${query.cursor}`} className={styles.page}
      aria-busy={state.status === 'loading'} tabIndex={0}
    >
      {state.status === 'loading' ? <Spin size="small" />
        : state.status === 'failed' ? <Alert type="error" showIcon
            message={agentErrorText(state.error) || i18n('stream.output.loadFailed')}
                                      />
          : state.status === 'idle' ? <span>{i18n('stream.output.cancelled')}</span>
            : <pre>{state.page.content || i18n(query.pattern ? 'stream.output.noMatches' : 'stream.output.empty')}</pre>}
    </div>
    <div className={styles.bar}>
      <Button size="small" disabled={!previousCursors.length || state.status === 'loading'} onClick={() => {
        setQuery({ ...query, cursor: previousCursors.at(-1) });
        setPreviousCursors((current) => current.slice(0, -1));
      }}
      >{i18n('stream.output.previous')}</Button>
      <Button size="small" disabled={state.status !== 'ready' || !state.page.hasMore || !state.page.nextCursor}
        onClick={() => {
          if (state.status !== 'ready') return;
          setPreviousCursors((current) => [...current, query.cursor]);
          setQuery({ ...query, cursor: state.page.nextCursor || undefined });
        }}
      >{i18n('stream.output.next')}</Button>
      {state.status === 'loading'
        ? <Button size="small" onClick={cancel}>{i18n('common.button.cancel')}</Button>
        : (state.status === 'failed' || state.status === 'idle')
          && <Button size="small" onClick={() => setQuery({ ...query })}>{i18n('stream.output.retry')}</Button>}
    </div>
    {state.status === 'ready' && state.page.warning && <Alert type="warning" showIcon message={state.page.warning} />}
  </div>;
}

export default function AgentToolOutput({ output, sessionId, resultIndex }: {
  output: AgentOutputReference; sessionId?: string; resultIndex?: number;
}) {
  const { styles } = useStyles();
  useGlobalStore((state) => state.baseSetting.language);
  const [viewing, setViewing] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState('');
  const pendingDownload = useRef<AbortController>();
  useEffect(() => () => pendingDownload.current?.abort(), []);
  const download = async () => {
    if (!sessionId || output.mode !== 'file' || pendingDownload.current) return;
    const controller = new AbortController();
    pendingDownload.current = controller;
    setDownloading(true);
    setDownloadError('');
    try {
      await pi.host.downloadOutput({ sessionId, artifactId: output.artifactId }, { signal: controller.signal });
    } catch (error) {
      if (!controller.signal.aborted) setDownloadError(agentErrorText(error) || i18n('stream.output.downloadFailed'));
    } finally {
      pendingDownload.current = undefined;
      if (!controller.signal.aborted) setDownloading(false);
    }
  };
  const resultLabel = resultIndex === undefined ? '' : i18n('stream.output.resultIndex', resultIndex);
  if (output.mode === 'unavailable') return <Alert type="warning" showIcon
    message={[resultLabel, output.warning || i18n('stream.output.unavailable')].filter(Boolean).join(' · ')}
                                            />;
  return <div data-agent-output={output.artifactId}>
    <div className={styles.bar}>
      {resultLabel && <span>{resultLabel}</span>}
      <span>{i18n(output.complete ? 'stream.output.saved' : 'stream.output.partial', formatFileSize(output.sizeBytes))}</span>
      <Button size="small" type="link" disabled={!sessionId} aria-expanded={viewing}
        onClick={() => setViewing(!viewing)}
      >{i18n(viewing ? 'stream.output.hide' : 'stream.output.view')}</Button>
      <Button size="small" type="link" loading={downloading} disabled={!sessionId} onClick={() => void download()}>
        {i18n('stream.output.download')}
      </Button>
    </div>
    {output.warning && <Alert type="warning" showIcon message={output.warning} />}
    {downloadError && <Alert type="error" showIcon message={downloadError} />}
    {viewing && sessionId && <OutputViewer key={`${sessionId}:${output.artifactId}`}
      sessionId={sessionId} artifactId={output.artifactId}
                             />}
  </div>;
}
