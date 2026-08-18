export const agentArtifactTypes = ['REPORT', 'METRIC', 'CHART', 'DATA_TABLE', 'FILE'] as const;

export type AgentOutputArtifactType = typeof agentArtifactTypes[number];

export interface AgentOutputRequirement {
  type: AgentOutputArtifactType;
  min: number;
}

export interface AgentOutputContractForm {
  outputRequirements: AgentOutputRequirement[];
  outputRequiredSections: string[];
  extras: Record<string, unknown>;
}

const defaultRequirement: AgentOutputRequirement = { type: 'REPORT', min: 1 };

export function parseAgentOutputContract(value?: string): AgentOutputContractForm {
  if (!value?.trim()) {
    return { outputRequirements: [defaultRequirement], outputRequiredSections: [], extras: {} };
  }
  try {
    const parsed = JSON.parse(value) as Record<string, unknown>;
    const outputRequirements = Array.isArray(parsed.requiredArtifacts)
      ? parsed.requiredArtifacts.flatMap((item) => {
        if (!item || typeof item !== 'object') return [];
        const requirement = item as Record<string, unknown>;
        const type = String(requirement.type || '').toUpperCase() as AgentOutputArtifactType;
        if (!agentArtifactTypes.includes(type)) return [];
        const minimum = Number(requirement.min);
        return [{ type, min: Number.isInteger(minimum) && minimum > 0 ? minimum : 1 }];
      })
      : [defaultRequirement];
    const outputRequiredSections = Array.isArray(parsed.requiredSections)
      ? parsed.requiredSections.filter((item): item is string => typeof item === 'string' && !!item.trim())
      : [];
    const extras = Object.fromEntries(
      Object.entries(parsed).filter(([key]) => !['requiredArtifacts', 'requiredSections'].includes(key)),
    );
    return {
      outputRequirements: outputRequirements.length ? outputRequirements : [defaultRequirement],
      outputRequiredSections,
      extras,
    };
  } catch {
    return { outputRequirements: [defaultRequirement], outputRequiredSections: [], extras: {} };
  }
}

export function serializeAgentOutputContract(
  requirements: AgentOutputRequirement[] | undefined,
  sections: string[] | undefined,
  extras: Record<string, unknown> = {},
) {
  const requiredArtifacts = Array.from(
    new Map((requirements || []).map((requirement) => [
      requirement.type,
      { type: requirement.type, min: Number(requirement.min) > 0 ? Number(requirement.min) : 1 },
    ])).values(),
  );
  const requiredSections = Array.from(new Set(
    (sections || []).map((section) => section.trim()).filter(Boolean),
  ));
  return JSON.stringify({
    ...extras,
    requiredArtifacts: requiredArtifacts.length ? requiredArtifacts : [defaultRequirement],
    ...(requiredSections.length ? { requiredSections } : {}),
  });
}
