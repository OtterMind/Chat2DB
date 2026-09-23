import type { AgentTimelineEntry, AgentTraceEntry } from '../../agentEvents';

interface ToolSection {
  kind: 'tools';
  sequence: number;
  entries: AgentTraceEntry[];
}

export type TimelineSection = AgentTimelineEntry | ToolSection;

// A result completes the call where it began, even when a question, approval,
// chart or another tool appeared while that call was waiting.
export const timelineSections = (entries: AgentTimelineEntry[]): TimelineSection[] => {
  const sections: TimelineSection[] = [];
  const owners = new Map<string, ToolSection>();
  for (const entry of entries) {
    if (entry.kind !== 'trace' || entry.trace.type === 'error') {
      sections.push(entry);
      continue;
    }
    if (entry.trace.type === 'reasoning') continue;
    const { id } = entry.trace;
    let section = id ? owners.get(id) : undefined;
    if (!section) {
      const last = sections.at(-1);
      section = last?.kind === 'tools' ? last : { kind: 'tools', sequence: entry.sequence, entries: [] };
      if (section !== last) sections.push(section);
      if (id) owners.set(id, section);
    }
    section.entries.push(entry.trace);
  }
  return sections;
};
