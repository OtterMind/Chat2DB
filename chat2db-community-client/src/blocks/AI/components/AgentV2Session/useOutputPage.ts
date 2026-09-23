import { useEffect, useRef, useState } from 'react';
import type { AgentOutputPage, AgentOutputQuery } from '@/types/agentOutput';

type OutputPageState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; page: AgentOutputPage }
  | { status: 'failed'; error: unknown };

export type LoadOutputPage = (
  sessionId: string, artifactId: string, query: AgentOutputQuery, signal: AbortSignal,
) => Promise<AgentOutputPage>;

export default function useOutputPage(
  sessionId: string, artifactId: string, query: AgentOutputQuery, load: LoadOutputPage,
) {
  const [state, setState] = useState<OutputPageState>({ status: 'loading' });
  const pending = useRef<AbortController>();
  useEffect(() => {
    const controller = new AbortController();
    pending.current = controller;
    setState({ status: 'loading' });
    void load(sessionId, artifactId, query, controller.signal).then((page) => {
      if (!controller.signal.aborted) setState({ status: 'ready', page });
    })
      .catch((error: unknown) => {
      if (!controller.signal.aborted) setState({ status: 'failed', error });
    });
    return () => controller.abort();
  }, [sessionId, artifactId, query, load]);
  return { state, cancel: () => {
    pending.current?.abort();
    setState({ status: 'idle' });
  } };
}
