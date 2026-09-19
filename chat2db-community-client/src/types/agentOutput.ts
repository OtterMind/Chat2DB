export type AgentOutputReference = {
  mode: 'file';
  artifactId: string;
  path: string;
  format: string;
  sizeBytes: number;
  complete: boolean;
  previewTruncated: boolean;
  warning?: string;
} | {
  mode: 'unavailable';
  complete: false;
  previewTruncated: boolean;
  warning?: string;
};

export interface AgentOutputPage {
  content: string;
  nextCursor?: string | null;
  hasMore: boolean;
  warning?: string | null;
}

export interface AgentOutputItem {
  output: AgentOutputReference;
  resultIndex?: number;
}

export interface AgentOutputQuery {
  cursor?: string;
  pattern?: string;
}
