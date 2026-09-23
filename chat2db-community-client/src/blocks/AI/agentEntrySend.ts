import { useAIStore } from '@/store/ai';
import { useGlobalStore } from '@/store/global';
import { useWorkspaceStore } from '@/store/workspace';
import { agentEntryCascaderData, buildAgentEntryDetail, type AgentEntry, type AgentEntryMode } from './agentEntry';

/**
 * Hands an entry point's request to the AI panel.
 *
 * `send` starts the run immediately, which is what an explicit menu action means; `prefill` fills
 * the input and leaves the decision to the user, which is what a diagnose affordance means. Only
 * the event name differs, so both modes share the slot builders in `agentEntry.ts`.
 */
export const sendAgentEntry = (entry: AgentEntry, mode: AgentEntryMode = 'send'): void => {
  const detail = buildAgentEntryDetail(entry);
  const cascader = agentEntryCascaderData(entry.scope);
  const page = useGlobalStore.getState().mainPageActiveTab as
    | 'workspace'
    | 'dashboard'
    | 'chat'
    | 'stream';

  useWorkspaceStore.getState().setCurrentWorkspaceExtend(null);
  if (cascader) {
    useAIStore.getState().setCascaderData(page, cascader);
  }
  useAIStore.getState().setShowPanel(true);

  const event = mode === 'send' ? 'stream:sendMessage' : 'stream:prefillMessage';
  window.setTimeout(() => {
    window.dispatchEvent(new CustomEvent(event, { detail }));
  }, 100);
};
