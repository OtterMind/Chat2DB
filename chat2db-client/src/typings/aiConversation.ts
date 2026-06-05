export interface IAiConversation {
  conversationId: string;
  title?: string | null;
  dataSourceId?: number | null;
  dataSourceName?: string | null;
  databaseName?: string | null;
  schemaName?: string | null;
  messageCount: number;
  lastMessagePreview?: string | null;
  status: 'ACTIVE' | 'ARCHIVED' | 'DELETED';
  gmtCreate?: string;
  gmtModified?: string;
}

export interface IAiMessage {
  messageId: string;
  role: 'user' | 'assistant';
  content: string;
  thinking?: string | null;
  sequenceNo: number;
  gmtCreate?: string;
}

export interface IAiConversationDetail {
  conversation: IAiConversation;
  messages: IAiMessage[];
}

export interface IAiConversationCreateRequest {
  dataSourceId?: number | null;
  databaseName?: string | null;
  schemaName?: string | null;
  initialMessage?: string | null;
}

export interface IAiConversationQueryRequest {
  pageNo: number;
  pageSize: number;
  searchKey?: string;
}

export interface IAiConversationRenameRequest {
  title: string;
}

export interface IPageResponse<T> {
  data: T[];
  pageNo: number;
  pageSize: number;
  total: number;
  hasNextPage?: boolean;
}
