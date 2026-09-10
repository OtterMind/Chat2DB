import React from 'react';

export interface SuggestionItem {
  label: string;
  value: string;
  kind: 'table';
  tableType?: string;
  tableName?: string;
  children?: SuggestionItem[];
  extra?: React.ReactNode;
}
export type SuggestionItems = SuggestionItem[];
