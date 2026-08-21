import React from 'react';

export interface SuggestionItem {
  label: string;
  value: string;
  kind: 'table' | 'knowledge';
  tableType?: string;
  tableName?: string;
  knowledge?: {
    id: number;
    type: 'KNOWLEDGE_TERM' | 'BUSINESS_LOGIC' | 'SQL_TEMPLATE';
    key: string;
    value: string;
  };

  icon?: React.ReactNode;
  children?: SuggestionItem[];
  extra?: React.ReactNode;
}
export type SuggestionItems = SuggestionItem[];
