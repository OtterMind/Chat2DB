import { useMemo } from 'react';
import { Alert } from 'antd';
import { IconfontSvg } from '@chat2db/ui';
import { cx } from 'antd-style';
import { databaseMap, normalizeDatabaseType } from '@/constants';
import type { IChatAttachment } from '@/service/aiAttachment';
import type { AgentTimelineEntry, AgentTraceEntry } from '../../agentEvents';
import { useStyles } from '../../style';
import AgentTimeline, { AgentTimelineProps } from './AgentTimeline';

export interface AgentV2Message {
  id: string;
  runId?: string;
  role: 'user' | 'assistant';
  content: string;
  attachments?: IChatAttachment[];
  contextSummary?: string;
  contextDatabaseType?: string;
  traceEntries?: AgentTraceEntry[];
  timeline?: AgentTimelineEntry[];
  error?: string;
}

interface AgentV2SessionProps extends Omit<AgentTimelineProps, 'entries' | 'runId'> {
  messages: AgentV2Message[];
  currentRoundUserMessageId: string | null;
  highlightedUserMessageId: string | null;
  streamingText: string;
  streamTimelineEntries: AgentTimelineEntry[];
  activeRunId?: string;
  running: boolean;
  onUserMessageRef: (id: string, node: HTMLDivElement | null) => void;
  onLastRoundRef: (node: HTMLDivElement | null) => void;
}

export default function AgentV2Session(props: AgentV2SessionProps) {
  const { styles } = useStyles();
  const rounds = useMemo(() => {
    const result: { key: string; user?: AgentV2Message; assistant?: AgentV2Message }[] = [];
    props.messages.forEach((message) => {
      const last = result[result.length - 1];
      if (message.role === 'assistant' && last?.user && !last.assistant) last.assistant = message;
      else result.push({ key: message.id, [message.role]: message });
    });
    return result;
  }, [props.messages]);

  const renderReply = (
    content: string, timeline: AgentTimelineEntry[] = [], runId?: string, active = false, error?: string,
  ) => (
    <div className={styles.assistantRow}>
      <div className={cx(styles.assistantBadge, active && styles.assistantBadgeLoading)} aria-hidden="true">
        <div className={styles.aiIconWrap}><span className={styles.aiSpark}>✦</span></div>
      </div>
      <div className={styles.assistantContent} aria-busy={active}>
        {timeline.length
          ? <AgentTimeline {...props} entries={timeline} runId={runId} />
          : props.renderMarkdown(content)}
        {error && <Alert type="error" showIcon message={error} />}
      </div>
    </div>
  );

  return <div data-agent-v2-session>{rounds.map((round, index) => {
    const user = round.user;
    const assistant = round.assistant;
    const current = user?.id === props.currentRoundUserMessageId;
    const icon = databaseMap[normalizeDatabaseType(user?.contextDatabaseType || '') || ''];
    return (
      <div key={round.key} className={styles.roundBlock}
        ref={index === rounds.length - 1 ? props.onLastRoundRef : undefined}
      >
        {user && <div
          className={cx(styles.userRow, user.id === props.highlightedUserMessageId && styles.userRowHighlighted)}
          ref={(node) => props.onUserMessageRef(user.id, node)}
                 >
          <div className={styles.userBubbleWrap}>
            {!!user.attachments?.length && <div className={styles.userAttachmentList}>
              {user.attachments.map((attachment, attachmentIndex) => (
                <div key={attachmentIndex} className={styles.userAttachmentItem}>{attachment.fileName}</div>
              ))}
            </div>}
            {user.contextSummary && <div className={styles.userContext}>
              <IconfontSvg size="sm" code={icon?.icon || 'icon-database'} existDark={icon?.iconExistDark} />
              <span>{user.contextSummary}</span>
            </div>}
            <div className={styles.userBubble}>{user.content}</div>
          </div>
        </div>}
        {assistant && renderReply(assistant.content, assistant.timeline, assistant.runId, false, assistant.error)}
        {current && renderReply(props.streamingText, props.streamTimelineEntries, props.activeRunId, props.running)}
      </div>
    );
  })}</div>;
}
