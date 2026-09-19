import type { AgentToolState } from '@/service/agent';

const toolNames = [
  'bash', 'read', 'edit', 'write', 'grep', 'find', 'ls', 'powershell',
  'list_all_datasources', 'list_all_databases', 'list_all_schemas',
  'list_all_tables', 'get_tables_schema', 'execute_sql',
] as const;
type KnownTool = typeof toolNames[number];
const knownTools = new Set<string>(toolNames);
const isKnownTool = (name: string): name is KnownTool => knownTools.has(name);

export const toolDescription = (
  tool: AgentToolState,
  translate: (key: `setting.agent.tool.${KnownTool}`) => string,
) => isKnownTool(tool.name) ? translate(`setting.agent.tool.${tool.name}`) : tool.description;
