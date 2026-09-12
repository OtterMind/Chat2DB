import type { SuggestionItem } from '../AIAtMetion/interface';
import { detectMentionTrigger, type MentionTrigger, type MentionReplacement } from './mentionSelection';

export type InputSuggestionTrigger = MentionTrigger & { kind: 'table' | 'slash' };

export const detectInputSuggestion = (
  input: string, cursor: number, runtime?: 'DEFAULT' | 'PI',
): InputSuggestionTrigger | null => {
  const position = Math.max(0, Math.min(cursor, input.length));
  if (runtime === 'PI') {
    const match = input.slice(0, position).match(/^(\s*)\/([a-z0-9:-]*)$/i);
    if (match) {
      const start = match[1].length;
      return { kind: 'slash', query: match[2], start,
        end: start + (input.slice(start).match(/^\S*/)?.[0].length || 0) };
    }
  }
  const mention = detectMentionTrigger(input, position);
  return mention ? { ...mention, kind: 'table' } : null;
};

export const skillSuggestions = (names: readonly string[], query: string): SuggestionItem[] => {
  const prefix = query.toLowerCase();
  return [...new Set(names)].filter((name) =>
    `skill:${name}`.toLowerCase().startsWith(prefix) || name.toLowerCase().startsWith(prefix))
    .map((name) => ({ kind: 'skill', value: `skill:${name}`, label: `/skill:${name}` }));
};

const PI_COMMANDS = ['compact', 'copy', 'export', 'hotkeys', 'model', 'reload', 'session', 'settings', 'thinking', 'tree'];

export const commandSuggestions = (query: string): SuggestionItem[] => {
  const prefix = query.toLowerCase();
  return PI_COMMANDS.filter((name) => name.startsWith(prefix))
    .map((name) => ({ kind: 'command' as const, value: name, label: `/${name}` }));
};

export const replaceSkillTrigger = (
  input: string, trigger: InputSuggestionTrigger, command: string,
): MentionReplacement => {
  const suffix = input.slice(trigger.end);
  const separator = suffix.match(/^\s+/)?.[0] || ' ';
  const prefix = input.slice(0, trigger.start) + command + separator;
  return { value: prefix + suffix.trimStart(), cursor: prefix.length };
};
