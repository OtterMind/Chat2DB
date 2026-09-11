export interface AgentContextScope {
  dataSourceId: string;
  dataSourceName?: string;
  databaseType?: string;
  database: string | null;
  schema: string | null;
}

export interface AgentContextObject extends AgentContextScope {
  type: 'TABLE' | 'VIEW';
  name: string;
  source: 'CURRENT_TABLE' | 'MENTION';
}

export interface AgentRunContextRequest {
  timeZone: string;
  selection: AgentContextScope | null;
  objects: AgentContextObject[];
}
