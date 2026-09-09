import { Button, Input, Select, Tooltip } from 'antd';
import { Send, Square } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

import i18n from '@/i18n';
import agentService, { AgentEvent, AgentRun, toAgentModelSnapshot } from '@/service/agent';
import { IModelOptionItem } from '@/service/aiStream';
import { listAvailableModelOptions } from '@/service/aiModelConfig';
import feedback from '@/utils/feedback';
import { buildAgentTranscript, isTerminalAgentEvent, mergeAgentEvents } from './model';
import { useStyles } from './style';

interface AgentChatProps {
  initialSessionId?: string;
  initialTitle?: string;
  initialModelConfigId?: string;
}

const requestId = () =>
  globalThis.crypto?.randomUUID?.() ||
  `${Date.now()}-${Math.random()
    .toString(36)
    .slice(2)}`;

export default function AgentChat({ initialSessionId, initialTitle, initialModelConfigId }: AgentChatProps) {
  const { styles } = useStyles();
  const [sessionId, setSessionId] = useState(initialSessionId || '');
  const [title, setTitle] = useState(initialTitle || '');
  const [events, setEvents] = useState<AgentEvent[]>([]);
  const [models, setModels] = useState<IModelOptionItem[]>([]);
  const [modelValue, setModelValue] = useState('');
  const [input, setInput] = useState('');
  const [activeRun, setActiveRun] = useState<AgentRun | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const lastSequenceRef = useRef(0);

  const refreshEvents = useCallback(async (targetSessionId: string) => {
    const incoming =
      (await agentService.listEvents({
        sessionId: targetSessionId,
        afterSequence: lastSequenceRef.current,
        limit: 200,
      })) || [];
    if (!incoming.length) return;
    lastSequenceRef.current = Math.max(lastSequenceRef.current, ...incoming.map((event) => event.sequence));
    setEvents((current) => mergeAgentEvents(current, incoming));
    if (incoming.some(isTerminalAgentEvent)) setActiveRun(null);
  }, []);

  useEffect(() => {
    setSessionId(initialSessionId || '');
    setTitle(initialTitle || '');
    setEvents([]);
    setActiveRun(null);
    lastSequenceRef.current = 0;
    if (initialSessionId) refreshEvents(initialSessionId).catch(() => feedback.error(i18n('stream.error.loadSessionMessages')));
  }, [initialSessionId, initialTitle, refreshEvents]);

  useEffect(() => {
    listAvailableModelOptions()
      .then((items) => {
        const available = items || [];
        setModels(available);
        const selected =
          available.find((item) => item.modelConfigId === initialModelConfigId) ||
          available.find((item) => item.defaultOption) ||
          available[0];
        setModelValue(selected?.value || '');
      })
      .catch(() => feedback.error(i18n('stream.error.loadModelList')));
  }, [initialModelConfigId]);

  useEffect(() => {
    if (!activeRun || !sessionId) return;
    const timer = window.setInterval(() => {
      refreshEvents(sessionId).catch(() => undefined);
    }, 400);
    return () => window.clearInterval(timer);
  }, [activeRun, refreshEvents, sessionId]);

  const selectedModel = useMemo(() => models.find((item) => item.value === modelValue), [modelValue, models]);
  const transcript = useMemo(() => buildAgentTranscript(events), [events]);

  const send = useCallback(async () => {
    const text = input.trim();
    if (!text || !selectedModel || submitting || activeRun) return;
    setSubmitting(true);
    try {
      let targetSessionId = sessionId;
      if (!targetSessionId) {
        const created = await agentService.createSession({
          sessionVersion: 2,
          title: text.slice(0, 100),
          definition: {
            id: 'DEFAULT',
            name: 'Chat2DB Agent',
            systemPrompt: 'You are a database assistant in Chat2DB.',
            runtimeType: 'PI',
            modelConfigId: selectedModel.modelConfigId || selectedModel.value,
            revision: 1,
          },
        });
        targetSessionId = created.id;
        setSessionId(created.id);
        setTitle(created.title);
        window.dispatchEvent(
          new CustomEvent('stream:agentSessionCreated', {
            detail: { sessionId: created.id, title: created.title, sessionVersion: 2 },
          }),
        );
      }
      const run = await agentService.startRun({
        sessionId: targetSessionId,
        model: toAgentModelSnapshot(selectedModel),
        input: { text, artifactIds: [] },
        idempotencyKey: requestId(),
      });
      setInput('');
      setActiveRun(run);
      await refreshEvents(targetSessionId);
      window.dispatchEvent(new CustomEvent('stream:sessionsChanged'));
    } catch (error) {
      feedback.error((error as { errorMessage?: string })?.errorMessage || i18n('stream.agent.sendFailed'));
    } finally {
      setSubmitting(false);
    }
  }, [activeRun, input, refreshEvents, selectedModel, sessionId, submitting]);

  const cancel = useCallback(async () => {
    if (!activeRun || !sessionId) return;
    try {
      await agentService.cancelRun({ runId: activeRun.id, sessionId });
      await refreshEvents(sessionId);
    } catch (error) {
      feedback.error((error as { errorMessage?: string })?.errorMessage || i18n('stream.agent.cancelFailed'));
    }
  }, [activeRun, refreshEvents, sessionId]);

  return (
    <div className={styles.root}>
      <div className={styles.header}>
        <span className={styles.title}>{title || i18n('stream.agent.title')}</span>
        <span>{i18n('stream.agent.runtimePi')}</span>
      </div>
      <div className={styles.transcript}>
        {transcript.length === 0 ? <div className={styles.empty}>{i18n('stream.agent.empty')}</div> : null}
        {transcript.map((message) => (
          <div
            key={message.id}
            className={`${styles.message} ${message.role === 'user' ? styles.userMessage : styles.assistantMessage}`}
          >
            {message.role === 'assistant' ? (
              <ReactMarkdown remarkPlugins={[remarkGfm]}>{message.content}</ReactMarkdown>
            ) : (
              message.content
            )}
            {message.status ? (
              <div className={styles.status}>{i18n(`stream.agent.status.${message.status}`)}</div>
            ) : null}
          </div>
        ))}
      </div>
      <div className={styles.composer}>
        <Select
          className={styles.model}
          value={modelValue || undefined}
          options={models.map((model) => ({ value: model.value, label: model.label }))}
          onChange={setModelValue}
          disabled={Boolean(activeRun || sessionId)}
        />
        <Input.TextArea
          className={styles.input}
          value={input}
          autoSize={{ minRows: 1, maxRows: 6 }}
          placeholder={i18n('stream.agent.placeholder')}
          disabled={Boolean(activeRun)}
          onChange={(event) => setInput(event.target.value)}
          onPressEnter={(event) => {
            if (!event.shiftKey) {
              event.preventDefault();
              send();
            }
          }}
        />
        {activeRun ? (
          <Tooltip title={i18n('common.button.cancel')}>
            <Button className={styles.iconButton} icon={<Square size={15} />} onClick={cancel} />
          </Tooltip>
        ) : (
          <Tooltip title={i18n('stream.agent.send')}>
            <Button
              type="primary"
              className={styles.iconButton}
              icon={<Send size={16} />}
              loading={submitting}
              disabled={!input.trim() || !selectedModel}
              onClick={send}
            />
          </Tooltip>
        )}
      </div>
    </div>
  );
}
