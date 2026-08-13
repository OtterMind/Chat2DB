import React from 'react';

export interface SuggestionItem {
  label: string;
  value: string;
  tableType: string;
  kind?: 'TABLE' | 'AGENT';

  icon?: React.ReactNode;
  children?: SuggestionItem[];
  extra?: React.ReactNode;
}
export type SuggestionItems = SuggestionItem[];
