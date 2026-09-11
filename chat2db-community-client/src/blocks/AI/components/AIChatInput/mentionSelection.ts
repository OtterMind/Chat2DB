import type { AgentContextObject } from '@/types/agentContext';

export interface SelectedMention {
  value: string;
  label: string;
  kind: 'table';
  tableName: string;
  tableType?: string;
  contextObject?: AgentContextObject;
}

export interface MentionTrigger {
  query: string;
  start: number;
  end: number;
}

export interface MentionReplacement {
  value: string;
  cursor: number;
}

const escapeRegExp = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const MENTION_BOUNDARY = '[\\s，。！？,!?;；:：()\\[\\]{}]';
const mentionPattern = (label: string) =>
  new RegExp(`(^|${MENTION_BOUNDARY})@?${escapeRegExp(label)}(?=$|${MENTION_BOUNDARY})`);
const EXPLICIT_TRIGGER_PATTERN = /(^|[\s，。！？,!?;；:：([{])@([^@\s，。！？,!?;；:：)\]}]*)$/;

export const detectMentionTrigger = (input: string, cursor: number): MentionTrigger | null => {
  const safeCursor = Math.max(0, Math.min(cursor, input.length));
  const inputText = input.slice(0, safeCursor);
  const match = inputText.match(EXPLICIT_TRIGGER_PATTERN);
  if (!match) return null;
  const query = match[2];
  return {
    query,
    start: safeCursor - query.length - 1,
    end: safeCursor,
  };
};

export const replaceMentionTrigger = (
  input: string,
  trigger: MentionTrigger,
  tableName: string,
): MentionReplacement => {
  const suffix = ' ';
  const value = `${input.slice(0, trigger.start)}${tableName}${suffix}${input.slice(trigger.end)}`;
  return {
    value,
    cursor: trigger.start + tableName.length + suffix.length,
  };
};

export const upsertSelectedMention = (
  selected: readonly SelectedMention[],
  nextMention: SelectedMention,
): SelectedMention[] => [
  ...selected.filter((mention) => mention.value !== nextMention.value),
  nextMention,
];

export const reconcileSelectedMentions = (input: string, selected: readonly SelectedMention[]): SelectedMention[] => {
  return selected.filter(({ label }) => mentionPattern(label).test(input));
};
