import type { AgentToolState } from '@/service/agent';

const piToolNames = ['bash', 'read', 'edit', 'write', 'grep', 'find', 'ls', 'powershell'] as const;
type PiTool = typeof piToolNames[number];
const piTools = new Set<string>(piToolNames);
const isPiTool = (name: string): name is PiTool => piTools.has(name);

export const toolDescription = (
  tool: AgentToolState,
  translate: (key: `setting.agent.tool.${PiTool}`) => string,
) => isPiTool(tool.name) ? translate(`setting.agent.tool.${tool.name}`) : tool.description;
