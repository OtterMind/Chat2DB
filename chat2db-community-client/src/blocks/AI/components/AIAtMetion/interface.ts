import React from 'react';
import type { AgentContextObject } from '@/types/agentContext';

export interface SuggestionItem {
  label: string;
  value: string;
  kind: 'table';
  tableType?: string;
  tableName?: string;
  contextObject?: AgentContextObject;
  children?: SuggestionItem[];
  extra?: React.ReactNode;
}
export type SuggestionItems = SuggestionItem[];
