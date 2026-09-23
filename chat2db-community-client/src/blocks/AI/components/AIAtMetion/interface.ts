import type React from 'react';
import type { AgentContextObject } from '@/types/agentContext';

export type SuggestionSelectionIntent = 'execute' | 'complete';

interface SuggestionBase {
  label: string;
  value: string;
  children?: SuggestionItem[];
  extra?: React.ReactNode;
}

export type SuggestionItem = SuggestionBase & (
  | { kind: 'table'; tableName: string; tableType?: string; contextObject?: AgentContextObject }
  | { kind: 'skill' }
  | { kind: 'command' }
);
export type SuggestionItems = SuggestionItem[];
