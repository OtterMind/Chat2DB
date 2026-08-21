export interface SelectedMention {
  value: string;
  label: string;
  kind: 'table' | 'knowledge';
  tableName?: string;
  tableType?: string;
  knowledge?: {
    id: number;
    type: 'KNOWLEDGE_TERM' | 'BUSINESS_LOGIC' | 'SQL_TEMPLATE';
    key: string;
    value: string;
  };
}

export interface MentionTrigger {
  mode: 'explicit' | 'natural';
  query: string;
  inputText: string;
  start: number;
  end: number;
}

export interface MentionReplacement {
  value: string;
  cursor: number;
}

const escapeRegExp = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

const mentionPattern = (label: string, global = false) =>
  new RegExp(`@${escapeRegExp(label)}(?=\\s|$)`, global ? 'g' : undefined);

const selectedLabelPattern = (label: string, global = false) =>
  new RegExp(`@?${escapeRegExp(label)}`, global ? 'g' : undefined);

const EXPLICIT_TRIGGER_PATTERN = /(^|[\s，。！？,!?;；:：([{])@([^@\s，。！？,!?;；:：)\]}]*)$/;
const NATURAL_TRAILING_TEXT_PATTERN = /([^\s，。！？,!?;；:：()[\]{}]+)(?:[\s，。！？,!?;；:：()[\]{}]+)?$/;

export const detectMentionTrigger = (input: string, cursor: number): MentionTrigger | null => {
  const safeCursor = Math.max(0, Math.min(cursor, input.length));
  const inputText = input.slice(0, safeCursor);
  const explicitMatch = inputText.match(EXPLICIT_TRIGGER_PATTERN);
  if (explicitMatch) {
    const query = explicitMatch[2];
    return {
      mode: 'explicit',
      query,
      inputText,
      start: safeCursor - query.length - 1,
      end: safeCursor,
    };
  }

  if (/\s$/.test(inputText)) {
    return null;
  }

  const trailingMatch = inputText.match(NATURAL_TRAILING_TEXT_PATTERN);
  const trailingText = trailingMatch?.[1] || '';
  if (trailingText.length < 2) {
    return null;
  }
  const triggerStart = safeCursor - (trailingMatch?.[0].length || trailingText.length);
  return {
    mode: 'natural',
    query: trailingText,
    inputText,
    start: triggerStart,
    end: triggerStart + trailingText.length,
  };
};

export const findNaturalFragmentRange = (
  input: string,
  cursor: number,
  candidateKey: string,
): { start: number; end: number } | null => {
  const safeCursor = Math.max(0, Math.min(cursor, input.length));
  const inputText = input.slice(0, safeCursor).toLocaleLowerCase();
  const normalizedCandidate = candidateKey.toLocaleLowerCase();
  const maximumLength = Math.min(inputText.length, normalizedCandidate.length);
  for (let length = maximumLength; length >= 2; length -= 1) {
    if (normalizedCandidate.includes(inputText.slice(-length))) {
      return { start: safeCursor - length, end: safeCursor };
    }
  }
  return null;
};

export const replaceMentionTrigger = (
  input: string,
  cursor: number,
  trigger: MentionTrigger,
  candidateKey: string,
): MentionReplacement => {
  if (trigger.mode === 'natural') {
    const normalizedInput = input.toLocaleLowerCase();
    if (normalizedInput.includes(candidateKey.toLocaleLowerCase())) {
      return { value: input, cursor };
    }

    const range = findNaturalFragmentRange(input, trigger.end, candidateKey);
    if (!range) {
      return { value: input, cursor };
    }

    return {
      value: `${input.slice(0, range.start)}${candidateKey}${input.slice(range.end)}`,
      cursor: range.start + candidateKey.length,
    };
  }

  const replacementRange = { start: trigger.start, end: trigger.end };
  const suffix = ' ';
  const value = `${input.slice(0, replacementRange.start)}${candidateKey}${suffix}${input.slice(replacementRange.end)}`;
  return {
    value,
    cursor: replacementRange.start + candidateKey.length + suffix.length,
  };
};

export const upsertSelectedMention = (
  selected: readonly SelectedMention[],
  nextMention: SelectedMention,
): SelectedMention[] => [
  ...selected.filter((mention) => mention.value !== nextMention.value && mention.label !== nextMention.label),
  nextMention,
];

export const filterUnselectedMentionCandidates = <T extends Pick<SelectedMention, 'value' | 'label'>>(
  candidates: readonly T[],
  selected: readonly Pick<SelectedMention, 'value' | 'label'>[],
): T[] => {
  const selectedValues = new Set(selected.map((mention) => mention.value));
  const selectedLabels = new Set(selected.map((mention) => mention.label));
  return candidates.filter(
    (candidate) => !selectedValues.has(candidate.value) && !selectedLabels.has(candidate.label),
  );
};

export const reconcileSelectedMentions = (input: string, selected: readonly SelectedMention[]): SelectedMention[] => {
  const remainingByLabel = new Map<string, number>();
  selected.forEach(({ label }) => {
    if (!remainingByLabel.has(label)) {
      remainingByLabel.set(label, input.match(selectedLabelPattern(label, true))?.length || 0);
    }
  });

  return selected.filter(({ label }) => {
    const remaining = remainingByLabel.get(label) || 0;
    if (remaining <= 0) return false;
    remainingByLabel.set(label, remaining - 1);
    return true;
  });
};

export const normalizeMentionInput = (input: string, selected: readonly SelectedMention[]): string => {
  const labels = [...new Set(selected.map(({ label }) => label))].sort((left, right) => right.length - left.length);
  return labels.reduce((text, label) => text.replace(mentionPattern(label, true), label), input);
};
