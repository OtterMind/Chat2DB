import type { AgentOutputItem, AgentOutputReference } from '@/types/agentOutput';

export const parseOutputReference = (value: unknown): AgentOutputReference | undefined => {
  if (!value || typeof value !== 'object') return undefined;
  const output = value as Record<string, unknown>;
  const warning = typeof output.warning === 'string' ? { warning: output.warning } : {};
  if (output.mode === 'unavailable') return {
    mode: 'unavailable', complete: false, previewTruncated: true, ...warning,
  };
  if (output.mode !== 'file' || typeof output.artifactId !== 'string' || !output.artifactId
    || typeof output.path !== 'string' || typeof output.format !== 'string'
    || typeof output.sizeBytes !== 'number' || !Number.isFinite(output.sizeBytes) || output.sizeBytes < 0) return undefined;
  return {
    mode: 'file', artifactId: output.artifactId, path: output.path, format: output.format,
    sizeBytes: output.sizeBytes, complete: output.complete === true,
    previewTruncated: output.previewTruncated === true, ...warning,
  };
};

const envelopeOutputs = (value: unknown): AgentOutputItem[] => {
  if (!value || typeof value !== 'object') return [];
  const envelope = value as { output?: unknown; data?: { results?: { output?: unknown }[] } };
  const output = parseOutputReference(envelope.output);
  if (output) return [{ output }];
  if (!Array.isArray(envelope.data?.results)) return [];
  return envelope.data.results.flatMap((result, index) => {
    const reference = parseOutputReference(result?.output);
    return reference ? [{ output: reference, resultIndex: index + 1 }] : [];
  });
};

export const toolOutputItems = (content: string, details?: unknown): AgentOutputItem[] => {
  const outputs = envelopeOutputs(details);
  if (outputs.length) return outputs;
  try { return envelopeOutputs(JSON.parse(content)); }
  catch { return []; }
};

export const formatToolResult = (content: string): string => {
  try { return JSON.stringify(JSON.parse(content), null, 2); }
  catch { return content; }
};
