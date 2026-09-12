import type React from 'react';
import type { AgentContextObject } from '@/types/agentContext';

interface SuggestionBase {
  label: string;
  value: string;
  children?: SuggestionItem[];
  extra?: React.ReactNode;
}

export type SuggestionItem = SuggestionBase & (
  | { kind: 'table'; tableName: string; tableType?: string; contextObject?: AgentContextObject }
  | { kind: 'skill' | 'command' }
);
export type SuggestionItems = SuggestionItem[];
